package io.github.korenkrita.simulsub.online

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 同一句的中间和最终结果共用 segmentId；调用方应按此 ID 覆盖文本。 */
data class CloudAsrText(val segmentId: String, val text: String, val isFinal: Boolean)

/** 服务端累计用量；duration 单位为秒，缺席字段保持 null。 */
data class CloudAsrUsage(val duration: Long?, val inputTokens: Long?, val outputTokens: Long?)

/** 单次会话的在线听写客户端；回调串行执行，awaitReady 应在后台线程调用。 */
class QwenStreamingAsrClient internal constructor(
    private val config: CloudAsrConfig,
    private val onText: (CloudAsrText) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onFailure: (String) -> Unit,
    private val transport: CloudAsrTransport,
    private val onUsage: (CloudAsrUsage) -> Unit = {}
) : AutoCloseable {
    /** 创建独占网络传输，不安装日志拦截器。 */
    constructor(
        config: CloudAsrConfig,
        onText: (CloudAsrText) -> Unit,
        onStatus: (String) -> Unit,
        onFailure: (String) -> Unit,
        onUsage: (CloudAsrUsage) -> Unit = {}
    ) : this(config, onText, onStatus, onFailure, OwnedCloudAsrTransport(), onUsage)

    private enum class State { NEW, CONNECTING, READY, FINISHING, CLOSED }

    private val lock = Any()
    private val ready = CountDownLatch(1)
    private val terminal = CountDownLatch(1)
    private val timers = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "千问听写超时").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private var timeout: ScheduledFuture<*>? = null
    private var socket: WebSocket? = null
    private var state = State.NEW
    private var taskFinished = false
    private var lastUsage: CloudAsrUsage? = null
    private val taskId = UUID.randomUUID().toString()

    /** 仅收到当前任务的 task-finished 后为 true；取消和失败均为 false。 */
    val finished: Boolean get() = synchronized(lock) { taskFinished }

    /** 异步建立连接；配置错误或重复启动返回 false。 */
    fun start(): Boolean = synchronized(lock) {
        if (state != State.NEW) return false
        val errors = config.validationErrors()
        if (errors.isNotEmpty()) {
            fail(errors.joinToString("；"))
            return false
        }
        state = State.CONNECTING
        notify { onStatus("听写正在连接") }
        if (state == State.CLOSED) return false
        armTimeout(15_000, State.CONNECTING, "听写连接或任务启动超时")
        try {
            val request = Request.Builder().url(requireNotNull(config.requestUrl()))
                .header("Authorization", "Bearer ${config.apiKey}").build()
            val created = transport.newWebSocket(request, listener)
            if (state == State.CLOSED) created.cancel() else socket = created
            state != State.CLOSED
        } catch (_: Exception) {
            fail("听写连接无法建立，请检查地址和网络")
            false
        }
    }

    /** 等待 task-started；等待超时会终止会话，不能在网络回调中调用。 */
    fun awaitReady(timeoutMs: Long): Boolean {
        require(timeoutMs in 0..120_000) { "听写等待时间无效" }
        if (synchronized(lock) { state == State.NEW }) return false
        return try {
            val signalled = ready.await(timeoutMs, TimeUnit.MILLISECONDS)
            synchronized(lock) {
                if (!signalled && state == State.CONNECTING) fail("听写任务启动超时")
                state == State.READY
            }
        } catch (_: InterruptedException) {
            close()
            Thread.currentThread().interrupt()
            false
        }
    }

    /** 发送完整的 16 kHz 单声道 PCM16LE 样本；缓冲过满时终止会话，避免丢音。 */
    fun sendPcm16(bytes: ByteArray): Boolean = synchronized(lock) {
        if (state != State.READY) return false
        if (bytes.isEmpty() || bytes.size % 2 != 0 || bytes.size > MAX_AUDIO_CHUNK_BYTES) {
            fail("听写音频必须是大小不超过一秒的完整 PCM16LE 样本")
            return false
        }
        val current = socket ?: return false
        if (current.queueSize() + bytes.size > MAX_QUEUED_BYTES) {
            fail("听写发送缓冲已满，会话已停止")
            return false
        }
        if (!current.send(bytes.toByteString())) {
            fail("听写音频发送失败")
            return false
        }
        true
    }

    /** 发送 finish-task 并等待最终句和 task-finished；重复调用只发送一次。 */
    fun finishAudio(): Boolean = synchronized(lock) {
        if (state == State.FINISHING) return true
        if (state != State.READY) return false
        state = State.FINISHING
        notify { onStatus("听写正在结束") }
        if (state == State.CLOSED) return false
        armTimeout(15_000, State.FINISHING, "听写任务结束超时")
        sendControl(JSONObject().put("header", header("finish-task"))
            .put("payload", JSONObject().put("input", JSONObject())))
    }

    /** 后台等待最终事件和回调完成；不得在回调中调用，超时会终止会话。 */
    fun awaitFinished(timeoutMs: Long): Boolean {
        require(timeoutMs in 0..120_000) { "听写等待时间无效" }
        if (synchronized(lock) { state != State.FINISHING && !taskFinished }) return false
        return try {
            val signalled = terminal.await(timeoutMs, TimeUnit.MILLISECONDS)
            synchronized(lock) {
                if (!signalled && state == State.FINISHING) fail("听写任务结束超时")
                taskFinished
            }
        } catch (_: InterruptedException) {
            close()
            Thread.currentThread().interrupt()
            false
        }
    }

    /** 立即取消网络、计时器和排队音频；返回后不会再触发回调。 */
    override fun close() = synchronized(lock) { release() }

    private val listener = object : WebSocketListener() {
        /** 连接建立后只发送 run-task，等待服务端确认再接收音频。 */
        override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(lock) {
            if (state != State.CONNECTING) { webSocket.cancel(); return }
            socket = webSocket
            sendControl(runTask())
            Unit
        }

        /** 仅解析官方事件字段，不把 words 的末片当作整句。 */
        override fun onMessage(webSocket: WebSocket, text: String) = synchronized(lock) {
            if (state == State.CLOSED) return
            if (text.length > MAX_EVENT_CHARS) { fail("听写服务事件超过大小限制"); return }
            try { receive(JSONObject(text)) } catch (_: Exception) { fail("听写服务返回无效事件") }
        }

        /** 协议仅允许服务端文本事件。 */
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = synchronized(lock) {
            if (state != State.CLOSED) fail("听写服务返回了非文本事件")
        }

        /** 服务端提前关闭连接时报告失败。 */
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) {
            if (state != State.CLOSED) fail("听写连接提前关闭")
        }

        /** 服务端提前断开时报告失败。 */
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) {
            if (state != State.CLOSED) fail("听写连接提前关闭")
        }

        /** 握手状态码区分鉴权、额度和普通网络错误。 */
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(lock) {
            val status = response?.code
            response?.close()
            if (state != State.CLOSED) fail(when (status) {
                401, 403 -> "听写鉴权失败，请检查 API 密钥、业务空间、地域和模型权限"
                429 -> "听写请求过于频繁或额度不足，请检查配额"
                else -> "听写网络连接失败，请检查地址和网络"
            })
        }
    }

    /** 根据官方 run-task 结构建立任务；使用 400 ms 静音断句和多阈值模式，自动语种不发送提示。 */
    private fun runTask(): JSONObject {
        val parameters = JSONObject().put("format", "pcm").put("sample_rate", 16000)
            .put("max_sentence_silence", 400)
            .put("multi_threshold_mode_enabled", true)
            .put("heartbeat", true)
        if (config.sourceLanguage != "auto") {
            parameters.put("language_hints", JSONArray().put(config.sourceLanguage))
        }
        if (config.vocabulary.isNotEmpty()) parameters.put("vocabulary", JSONObject(config.vocabulary))
        val input = JSONObject()
        if (config.context.isNotBlank()) {
            input.put("context", JSONArray().put(JSONObject().put("role", "user")
                .put("content", JSONArray().put(JSONObject().put("type", "input_text")
                    .put("text", config.context)))))
        }
        return JSONObject().put("header", header("run-task"))
            .put("payload", JSONObject().put("task_group", "audio").put("task", "asr")
                .put("function", "recognition").put("model", config.model)
                .put("parameters", parameters).put("input", input))
    }

    /** 所有控制帧复用当前任务 UUID 和双工模式。 */
    private fun header(action: String): JSONObject = JSONObject().put("action", action)
        .put("task_id", taskId).put("streaming", "duplex")

    /** 严格关联任务 ID，忽略心跳与空句，并以 sentence_id 构造稳定段 ID。 */
    private fun receive(event: JSONObject) {
        val header = event.getJSONObject("header")
        if (header.getString("task_id") != taskId) { fail("听写服务返回了不匹配的任务 ID"); return }
        when (header.getString("event")) {
            "task-started" -> if (state == State.CONNECTING) {
                timeout?.cancel(false)
                state = State.READY
                // 任务已启动但尚无服务端计量；预建未知记录供取消或超时场景保留。
                lastUsage = CloudAsrUsage(null, null, null)
                notify { onUsage(requireNotNull(lastUsage)) }
                if (state == State.CLOSED) return
                ready.countDown()
                notify { onStatus("听写已就绪") }
            }
            "result-generated" -> if (state == State.READY || state == State.FINISHING) {
                val payload = event.optJSONObject("payload") ?: return
                reportUsage(payload)
                if (state == State.CLOSED) return
                val sentence = payload.optJSONObject("output")?.optJSONObject("sentence") ?: return
                if (sentence.optBoolean("heartbeat", false)) return
                if (!sentence.has("sentence_id") || sentence.isNull("sentence_id")) return
                val id = sentence.optInt("sentence_id", -1)
                val text = sentence.optString("text", "")
                if (id >= 0 && text.isNotBlank()) {
                    notify { onText(CloudAsrText("$taskId:$id", text, sentence.optBoolean("sentence_end", false))) }
                }
            }
            "task-finished" -> if (state == State.FINISHING) {
                reportUsage(event.optJSONObject("payload"))
                if (state == State.CLOSED) return
                taskFinished = true
                release { onStatus("听写已完成") }
            }
            "task-failed" -> fail("听写任务失败，请检查模型、音频格式、额度和网络")
        }
    }

    /** 官方用量为任务累计值；只发布更完整的快照，缺席字段不清除已知数。 */
    private fun reportUsage(payload: JSONObject?) {
        val usage = payload?.optJSONObject("usage") ?: return
        val previous = lastUsage ?: CloudAsrUsage(null, null, null)
        val snapshot = CloudAsrUsage(
            cumulative(previous.duration, usageNumber(usage, "duration")),
            cumulative(previous.inputTokens, usageNumber(usage, "input_tokens")),
            cumulative(previous.outputTokens, usageNumber(usage, "output_tokens"))
        )
        if (snapshot != lastUsage) {
            lastUsage = snapshot
            notify { onUsage(snapshot) }
        }
    }

    /** 对累计字段保留已知较大值，防止迟到快照让账本倒退。 */
    private fun cumulative(previous: Long?, reported: Long?): Long? = when {
        previous == null -> reported
        reported == null -> previous
        else -> maxOf(previous, reported)
    }

    /** 仅接受服务端确实报告的非负整数，非法数值保持未知。 */
    private fun usageNumber(usage: JSONObject, field: String): Long? =
        (usage.opt(field) as? Number)?.toString()?.toLongOrNull()?.takeIf { it >= 0 }

    /** OkHttp 的实际待发送字节数受限；失败立即取消，绝不静默跳帧。 */
    private fun sendControl(message: JSONObject): Boolean {
        val current = socket ?: return false
        val frame = message.toString()
        if (current.queueSize() + frame.toByteArray(Charsets.UTF_8).size > MAX_QUEUED_BYTES) {
            fail("听写发送缓冲已满，会话已停止")
            return false
        }
        if (!current.send(frame)) { fail("听写控制指令发送失败"); return false }
        return true
    }

    /** 超时任务与网络回调共用状态锁，避免关闭后重复报错。 */
    private fun armTimeout(delayMs: Long, expected: State, message: String) {
        timeout?.cancel(false)
        timeout = timers.schedule({ synchronized(lock) {
            if (state == expected) fail(message)
        } }, delayMs, TimeUnit.MILLISECONDS)
    }

    /** 发出单次中文失败通知并释放当前会话资源。 */
    private fun fail(message: String) {
        if (state == State.CLOSED) return
        release { onFailure(message) }
    }

    /** 幂等释放连接；终态回调完成后才唤醒等待者，外部 close 无回调。 */
    private fun release(afterRelease: (() -> Unit)? = null) {
        if (state == State.CLOSED) return
        state = State.CLOSED
        timeout?.cancel(false)
        timers.shutdown()
        val current = socket
        socket = null
        try {
            try { current?.cancel() } catch (_: Exception) { /* 继续释放独占传输。 */ }
            try { transport.close() } catch (_: Exception) { /* 仍需通知等待者。 */ }
            if (afterRelease != null) notify(afterRelease)
        } finally {
            ready.countDown()
            terminal.countDown()
        }
    }

    /** 调用方回调异常不传播到 OkHttp，也不暴露音频与密钥。 */
    private fun notify(action: () -> Unit) {
        try { action() } catch (_: Exception) { /* 回调异常不改变网络协议。 */ }
    }

    private companion object {
        const val MAX_AUDIO_CHUNK_BYTES = 32_000
        const val MAX_QUEUED_BYTES = 256L * 1024
        const val MAX_EVENT_CHARS = 1_048_576
    }
}

/** 测试可替换握手传输，生产配置校验始终生效。 */
internal interface CloudAsrTransport : AutoCloseable {
    /** 建立一次可取消的 WebSocket。 */
    fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket
}

/** 独占 OkHttp 资源，禁用重试和重定向。 */
private class OwnedCloudAsrTransport : CloudAsrTransport {
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build()

    /** 使用 TLS 与请求头建立 WebSocket。 */
    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
        client.newWebSocket(request, listener)

    /** 立即取消请求，连接池与线程资源交由后台清理，避免主线程执行 TLS 关闭写出。 */
    override fun close() {
        closeCloudHttpClient(client)
    }
}
