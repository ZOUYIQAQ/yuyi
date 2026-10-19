package io.github.korenkrita.simulsub.online

import okhttp3.OkHttpClient
import io.github.korenkrita.simulsub.parseMeteredUsage
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import io.github.korenkrita.simulsub.online.LiveTranslateError.Code

/**
 * Android 可用的千问 3.5 云端音频听译客户端，仅请求文本输出，不重连、不记录音频或凭据。
 * start 异步连接；awaitReady 只能在后台调用；READY 之前音频直接拒收，不缓存。
 * finish 保留最后一段并等待 session.finished；close 立即取消，返回后不再通知任何字幕。
 * 单实例只支持一次会话。回调串行执行，可在回调中 close，但禁止阻塞等待或等待其他回调线程。
 */
class QwenLiveTranslateClient internal constructor(
    private val config: LiveTranslateConfig,
    private val listener: LiveTranslateListener,
    private val options: LiveTranslateOptions,
    private val transport: LiveTranslateTransport
) : AutoCloseable {
    /** 创建独立且关闭后释放资源的传输，避免共享日志拦截器接触音频与密钥。 */
    constructor(
        config: LiveTranslateConfig,
        listener: LiveTranslateListener,
        options: LiveTranslateOptions = LiveTranslateOptions()
    ) : this(config, listener, options, OwnedLiveTranslateTransport())

    private val lock = Any()
    private val ready = CountDownLatch(1)
    private val closed = CountDownLatch(1)
    private val timers = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "千问听译超时").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private var deadline: ScheduledFuture<*>? = null
    private var socket: WebSocket? = null
    private var serverFinished = false
    private var sequence = 0L
    private val events = LiveTranslateEvents()
    private val meteredResponses = HashSet<String>()

    @Volatile var state: LiveTranslateState = LiveTranslateState.NEW
        private set

    /** 发起一次异步握手；重复启动、已取消或配置错误时返回 false。 */
    fun start(): Boolean = synchronized(lock) {
        if (state != LiveTranslateState.NEW) return false
        val invalid = config.validationError() ?: if (!config.isRealtime) "所选模型需使用分段听译接口" else null
        if (invalid != null) {
            fail(Code.INVALID_CONFIG, invalid)
            return false
        }
        transition(LiveTranslateState.CONNECTING)
        if (terminal()) return false
        armTimeout(options.handshakeTimeoutMs, Code.HANDSHAKE_TIMEOUT, "听译会话握手超时")
        try {
            val request = Request.Builder().url(config.requestUrl())
                .header("Authorization", "Bearer ${config.apiKey}").build()
            val created = transport.newWebSocket(request, socketListener)
            if (terminal()) created.cancel() else socket = created
            !terminal()
        } catch (_: Exception) {
            fail(Code.NETWORK, "听译连接无法建立")
            false
        }
    }

    /** 是否收到服务端结束确认，用于标记中断时尚未回报的尾段费用。 */
    val completed: Boolean get() = synchronized(lock) { serverFinished }

    /** 后台等待确认后才允许发送播放音频。 */
    fun awaitReady(timeoutMs: Long = 15_000): Boolean {
        require(timeoutMs in 0..120_000) { "听译等待时间无效" }
        if (state == LiveTranslateState.NEW) return false
        try {
            val signalled = ready.await(timeoutMs, TimeUnit.MILLISECONDS)
            return synchronized(lock) {
                if (!signalled && !terminal() && state != LiveTranslateState.READY) {
                    fail(Code.HANDSHAKE_TIMEOUT, "听译会话握手超时")
                }
                state == LiveTranslateState.READY
            }
        } catch (_: InterruptedException) {
            close()
            Thread.currentThread().interrupt()
            return false
        }
    }

    /** 接收 16kHz 单声道 PCM16LE；不重采样、不存储，未就绪返回 false，格式或缓冲错误终止会话。 */
    fun sendPcm16(audio: ByteArray): Boolean = synchronized(lock) {
        if (state != LiveTranslateState.READY) return false
        if (audio.isEmpty() || audio.size % 2 != 0 || audio.size > options.maxAudioChunkBytes) {
            fail(Code.INVALID_AUDIO, "听译音频必须是大小受限的完整 PCM16LE 样本")
            return false
        }
        sendFrame(event("input_audio_buffer.append").put("audio", audio.toByteString().base64()))
    }

    /** 停止接收音频并发送 session.finish；异步保留尾段，超时会可靠取消网络请求。 */
    fun finish(): Boolean = synchronized(lock) {
        if (state == LiveTranslateState.FINISHING) return true
        if (state != LiveTranslateState.READY) {
            if (!terminal()) terminate(LiveTranslateState.CLOSED)
            return false
        }
        transition(LiveTranslateState.FINISHING)
        if (terminal()) return false
        armTimeout(options.finishTimeoutMs, Code.FINISH_TIMEOUT, "听译会话结束超时")
        sendFrame(event("session.finish"))
    }

    /** 后台等待网络和超时资源释放；等待超时立即关闭，中断保留线程中断标记。 */
    fun awaitClosed(timeoutMs: Long = 15_000): Boolean {
        require(timeoutMs in 0..120_000) { "听译等待时间无效" }
        try {
            if (closed.await(timeoutMs, TimeUnit.MILLISECONDS)) return true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        close()
        return false
    }

    /** 立即取消连接、计时器与待发送数据；幂等且不会触发任何付费重连。 */
    override fun close() = synchronized(lock) {
        if (!terminal()) terminate(LiveTranslateState.CLOSED)
    }

    private val socketListener = object : WebSocketListener() {
        /** 仅保存连接，收到 session.created 后才发会话配置。 */
        override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(lock) {
            if (terminal()) webSocket.cancel() else socket = webSocket
        }

        /** 串行归并事件，忽略关闭后事件，解析异常不传播服务端正文。 */
        override fun onMessage(webSocket: WebSocket, text: String) = synchronized(lock) {
            if (terminal() || serverFinished) return
            if (text.length > 1_048_576) {
                fail(Code.PROTOCOL, "听译事件超过大小限制")
                return
            }
            try {
                receive(JSONObject(text))
            } catch (_: Exception) {
                fail(Code.PROTOCOL, "听译服务返回无效事件")
            }
        }

        /** 仅文本协议不接受二进制帧，避免把音频输出误当成字幕。 */
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = synchronized(lock) {
            if (!terminal()) fail(Code.PROTOCOL, "听译服务返回非文本事件")
        }

        /** 完成会话后回应关闭握手；提前断开视为失败，不尝试重连。 */
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String): Unit = synchronized(lock) {
            if (terminal()) return
            if (serverFinished && code == 1000) webSocket.close(1000, "听译完成")
            else fail(Code.NETWORK, "听译连接提前关闭")
        }

        /** 关闭握手完成后释放全部客户端自有资源。 */
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) {
            if (terminal()) return
            if (serverFinished && code == 1000) terminate(LiveTranslateState.CLOSED)
            else fail(Code.NETWORK, "听译连接提前关闭")
        }

        /** 丢弃异常正文与响应体，避免 URL、认证和原始音频进入错误回调。 */
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(lock) {
            val status = response?.code
            response?.close()
            if (!terminal()) fail(Code.NETWORK, when (status) {
                401, 403 -> "听译鉴权失败，请检查 API 密钥、业务空间、地域和模型权限"
                429 -> "听译服务额度不足或请求过于频繁，请检查配额后重试"
                else -> "听译网络连接失败，请检查地址和网络"
            })
        }
    }

    /** 按协议完成握手和结束控制，只有就绪后才接受字幕事件。 */
    private fun receive(message: JSONObject) {
        when (message.getString("type")) {
            "error" -> fail(Code.SERVER, "听译服务拒绝请求，请检查密钥、模型、地域和配额")
            "session.created" -> if (state == LiveTranslateState.CONNECTING) {
                transition(LiveTranslateState.CONFIGURING)
                if (!terminal()) sendFrame(sessionUpdate())
            }
            "session.updated" -> if (state == LiveTranslateState.CONFIGURING) {
                deadline?.cancel(false)
                deadline = null
                transition(LiveTranslateState.READY)
                ready.countDown()
            }
            "session.finished" -> if (state == LiveTranslateState.FINISHING) {
                serverFinished = true
                if (socket?.close(1000, "听译完成") != true) terminate(LiveTranslateState.CLOSED)
            }
            else -> if (state == LiveTranslateState.READY || state == LiveTranslateState.FINISHING) {
                if (message.optString("type") == "response.done") {
                    val response = message.optJSONObject("response")
                    val responseId = response?.optString("id").orEmpty()
                    if (responseId.isNotEmpty() && meteredResponses.add(responseId)) {
                        // 在字幕解析之前记录已返回的实际用量；字幕协议出错也不丢失该响应。
                        val usage = parseMeteredUsage(response?.opt("usage"))
                        // 实测3.5每个响应携带的是连接累计值，不能把响应号当作独立费用相加。
                        notifyListener { listener.onCumulativeUsage(responseId, usage) }
                    }
                    if (terminal()) return
                }
                val result = events.accept(message)
                val error = result.error
                if (error != null) fail(error.code, error.message)
                else for (text in result.texts) {
                    if (terminal()) break
                    notifyListener { listener.onText(text) }
                }
            }
        }
    }

    /** 明确仅输出文本并启用原文 ASR，自动源语种时完全省略 language。 */
    private fun sessionUpdate(): JSONObject {
        val transcription = JSONObject().put("model", "qwen3-asr-flash-realtime")
        config.sourceLang?.let { transcription.put("language", it) }
        return event("session.update").put("session", JSONObject()
            .put("modalities", JSONArray().put("text"))
            .put("sample_rate", 16000)
            .put("input_audio_format", "pcm")
            .put("input_audio_transcription", transcription)
            .put("turn_detection", JSONObject().put("type", "server_vad"))
            .put("translation", JSONObject().put("language", config.targetLang)))
    }

    /** 客户端事件号只在内存中递增，不包含用户数据。 */
    private fun event(type: String): JSONObject = JSONObject().put("type", type).put("event_id", "client_${++sequence}")

    /** 发送前检查真实 OkHttp 待发字节数；溢出或发送拒绝均终止，绝不静默丢帧。 */
    private fun sendFrame(message: JSONObject): Boolean {
        if (terminal()) return false
        val current = socket ?: return false
        val text = message.toString()
        val bytes = text.toByteArray(Charsets.UTF_8).size
        if (current.queueSize() + bytes > options.maxQueuedBytes) {
            fail(Code.BUFFER_OVERFLOW, "听译发送缓冲已满，会话已停止")
            return false
        }
        if (!current.send(text)) {
            fail(Code.NETWORK, "听译数据无法发送")
            return false
        }
        return true
    }

    /** 计时器使用同一把锁，确保关闭和超时不会重复通知或遗留连接。 */
    private fun armTimeout(timeoutMs: Long, code: Code, message: String) {
        deadline?.cancel(false)
        deadline = timers.schedule({ synchronized(lock) {
            // 取消定时任务时它可能已在等待锁，必须再次确认仍处于对应的等待阶段。
            val stillWaiting = when (code) {
                Code.HANDSHAKE_TIMEOUT -> state == LiveTranslateState.CONNECTING || state == LiveTranslateState.CONFIGURING
                Code.FINISH_TIMEOUT -> state == LiveTranslateState.FINISHING
                else -> false
            }
            if (stillWaiting) fail(code, message)
        } }, timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** 更新状态并串行通知，允许监听器同步取消当前会话。 */
    private fun transition(next: LiveTranslateState) {
        state = next
        notifyListener { listener.onState(next) }
    }

    /** 终止错误统一使用固定中文信息，不携带原始异常。 */
    private fun fail(code: Code, message: String) {
        if (!terminal()) terminate(LiveTranslateState.FAILED, LiveTranslateError(code, message))
    }

    /** 先取消全部资源再通知终止状态，唤醒所有等待者，避免关闭后的迟到回调。 */
    private fun terminate(next: LiveTranslateState, error: LiveTranslateError? = null) {
        state = next
        deadline?.cancel(false)
        deadline = null
        // 超时可能正在本线程触发；不能中断自身，否则调用方回调也会继承中断状态。
        timers.shutdown()
        socket?.cancel()
        socket = null
        events.clear()
        transport.close()
        try {
            if (error != null) notifyListener { listener.onError(error) }
            notifyListener { listener.onState(next) }
        } finally {
            ready.countDown()
            closed.countDown()
        }
    }

    /** 判断会话是否已不可恢复地终止。 */
    private fun terminal(): Boolean = state == LiveTranslateState.CLOSED || state == LiveTranslateState.FAILED

    /** 隔离调用方异常，避免异常正文经由 OkHttp 的未捕获异常路径输出。 */
    private fun notifyListener(action: () -> Unit) {
        try { action() } catch (_: Exception) { /* 调用方异常不写日志，也不改变网络协议。 */ }
    }
}

/** 仅供本包真实本地 WebSocket 测试替换握手目标，生产端点校验始终生效。 */
internal interface LiveTranslateTransport : AutoCloseable {
    /** 建立一个可取消的 WebSocket，不进行重试。 */
    fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket
}

/** 生产传输独占网络资源，不跟随重定向，不自动重试，不安装日志拦截器。 */
private class OwnedLiveTranslateTransport : LiveTranslateTransport {
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build()

    /** 建立唯一连接，认证与音频仅交给 TLS 传输。 */
    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket = client.newWebSocket(request, listener)

    /** 仅清理当前客户端自有资源，不影响应用其他网络请求。 */
    override fun close() {
        client.dispatcher.cancelAll()
        closeCloudHttpClient(client)
    }
}
