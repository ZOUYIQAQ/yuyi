package io.github.korenkrita.simulsub.online

import io.github.korenkrita.simulsub.MeteredUsage
import io.github.korenkrita.simulsub.parseMeteredUsage
import io.github.korenkrita.simulsub.online.LiveTranslateError.Code
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 千问文件分段听译同步客户端；每个片段独立计费请求，不重试、不重连。 */
class QwenFileTranslateClient internal constructor(
    private val config: LiveTranslateConfig,
    private val listener: LiveTranslateListener,
    injectedClient: OkHttpClient?
) : AutoCloseable {
    /** 生产环境使用独立 HTTP 客户端，避免其他调用方拦截器接触音频或密钥。 */
    constructor(config: LiveTranslateConfig, listener: LiveTranslateListener) : this(config, listener, null)

    private val client = (injectedClient ?: OkHttpClient()).newBuilder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).build()
    private val ownsClient = injectedClient == null
    private val lock = Any()
    private val operationLock = Any()
    private var closed = false
    private var activeCall: Call? = null

    /** 免费 GET 模型目录以核实鉴权与所选模型，绝不提交音频。 */
    fun probe(): Boolean = synchronized(operationLock) {
        val url = checkedUrl() ?: return false
        val parts = url.pathSegments
        val modelsUrl = url.newBuilder().removePathSegment(parts.lastIndex)
            .setPathSegment(parts.lastIndex - 1, "models").build()
        val request = credentialRequest(modelsUrl)?.get()?.build() ?: return false
        execute(request) { response ->
            val bytes = response.peekBody(MAX_PROBE_BYTES + 1L).bytes()
            if (bytes.size > MAX_PROBE_BYTES) throw ProtocolFailure()
            val directory = JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("data")
            val found = (0 until directory.length()).any { directory.optJSONObject(it)?.optString("id") == config.model }
            if (!found) report(Code.RESPONSE_FAILED, "听译模型目录不包含所选模型")
            found
        }
    }

    /** 同步发送单个 16kHz 单声道 PCM16LE 片段，完整结束后才确认最终字幕。 */
    fun translatePcm16(audio: ByteArray, segmentId: String): Boolean = synchronized(operationLock) {
        val url = checkedUrl() ?: return false
        if (audio.size !in MIN_PCM_BYTES..MAX_PCM_BYTES || audio.size % 2 != 0 || segmentId.isBlank() || segmentId.length > 512) {
            report(Code.INVALID_AUDIO, "听译音频片段必须为 100 毫秒至 12 秒的 16kHz 单声道 PCM16")
            return false
        }
        val wav = wav(audio)
        val input = JSONObject().put("type", "input_audio").put("input_audio", JSONObject()
            .put("data", "data:audio/wav;base64,${Base64.getEncoder().encodeToString(wav)}")
            .put("format", "wav"))
        val languages = JSONObject().put("target_lang", config.targetLang)
        config.sourceLang?.let { languages.put("source_lang", it) }
        val payload = JSONObject().put("model", config.model).put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))
            .put("modalities", JSONArray().put("text"))
            .put("messages", JSONArray().put(JSONObject().put("role", "user")
                .put("content", JSONArray().put(input))))
            .put("translation_options", languages)
        val request = (credentialRequest(url) ?: return false)
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val usage = AtomicReference<MeteredUsage?>()
        execute(request, segmentId, usage) { response -> readStream(response, segmentId, usage) }
    }

    /** 输入可以原样保存；HTTP不能承载的凭据在发起请求时报告错误，不崩溃或发送非法头。 */
    private fun credentialRequest(url: HttpUrl): Request.Builder? = try {
        Request.Builder().url(url).header("Authorization", "Bearer ${config.apiKey}")
    } catch (_: IllegalArgumentException) {
        report(Code.INVALID_CONFIG, "已保存的密钥无法用于 HTTP 鉴权，请核对平台提供的原文")
        null
    }

    /** 取消正在执行的请求，并与回调互斥；返回后不会再通知调用方。 */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            activeCall?.cancel()
        }
        if (ownsClient) {
            closeCloudHttpClient(client)
        }
    }

    /** 校验模型与 HTTPS 完整端点；无效配置不会产生请求。 */
    private fun checkedUrl(): HttpUrl? {
        synchronized(lock) { if (closed) return null }
        val validModel = config.model == "qwen3-livetranslate-flash" ||
            config.model == "qwen3-livetranslate-flash-2025-12-01"
        val invalid = config.validationError()
        if (!validModel || invalid != null) {
            report(Code.INVALID_CONFIG, "千问文件听译配置无效")
            return null
        }
        val url = try { config.requestUrl() } catch (_: Exception) { null }
        if (url?.scheme != "https" || url.query != null || url.fragment != null ||
            url.pathSegments.takeLast(4) != listOf("compatible-mode", "v1", "chat", "completions")) {
            report(Code.INVALID_CONFIG, "千问文件听译端点必须是 HTTPS 完整地址")
            return null
        }
        return url
    }

    /** 单次执行 HTTP 请求；所有异常只转成本地固定中文错误，且每次释放响应体。 */
    private fun execute(request: Request, usageEventId: String? = null,
                        usage: AtomicReference<MeteredUsage?> = AtomicReference(),
                        process: (Response) -> Boolean): Boolean {
        val sent = AtomicBoolean(false)
        val body = request.body
        val meteredRequest = if (usageEventId != null && body != null) request.newBuilder().method(request.method,
            object : RequestBody() {
                /** 沿用原请求的媒体类型。 */
                override fun contentType() = body.contentType()
                /** 沿用原请求的精确长度。 */
                override fun contentLength() = body.contentLength()
                /** 禁止写出后自动重发已计费片段。 */
                override fun isOneShot() = true
                /** 在正文实际开始写入时标记本片段已发送。 */
                override fun writeTo(sink: okio.BufferedSink) { sent.set(true); body.writeTo(sink) }
            }).build() else request
        val call = client.newCall(meteredRequest)
        synchronized(lock) {
            if (closed) return false
            activeCall = call
        }
        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    report(Code.SERVER, when (response.code) {
                        401, 403 -> "分段听译鉴权失败，请检查 API 密钥、业务空间、地域和模型权限"
                        429 -> "分段听译额度不足或请求过于频繁，请检查配额后重试"
                        else -> "千问分段听译服务请求失败，请检查接口地址与模型权限"
                    })
                    false
                } else if (synchronized(lock) { closed }) false else process(response)
            }
        } catch (_: ProtocolFailure) {
            report(Code.PROTOCOL, "千问文件听译响应格式或长度无效")
            false
        } catch (_: JSONException) {
            report(Code.PROTOCOL, "千问文件听译响应格式或长度无效")
            false
        } catch (_: Exception) {
            report(Code.NETWORK, "千问文件听译网络请求失败或超时")
            false
        } finally {
            synchronized(lock) { if (activeCall === call) activeCall = null }
            // 即使超时或 close 已取消，已发出的请求仍须留下未知或已取得的真实计量。
            if (usageEventId != null && sent.get()) runCatching {
                listener.onUsage(usageEventId, usage.get() ?: MeteredUsage())
            }
        }
    }

    /** 有界读取 SSE，delta.content 逐块累加，仅 stop 加 DONE 构成最终结果。 */
    private fun readStream(response: Response, segmentId: String,
                           usage: AtomicReference<MeteredUsage?>): Boolean {
        val source = response.body?.source() ?: throw ProtocolFailure()
        val text = StringBuilder()
        var totalBytes = 0
        var stopped = false
        var done = false
        while (!done) {
            if (synchronized(lock) { closed }) return false
            val line = try { source.readUtf8LineStrict(MAX_LINE_BYTES.toLong()) }
                catch (_: EOFException) { break }
            totalBytes += line.toByteArray(Charsets.UTF_8).size + 1
            if (totalBytes > MAX_RESPONSE_BYTES) throw ProtocolFailure()
            if (!line.startsWith("data:")) continue
            val data = line.substring(5).trimStart()
            if (data == "[DONE]") {
                done = true
                break
            }
            if (data.isEmpty()) continue
            val event = JSONObject(data)
            if (event.has("usage") && !event.isNull("usage")) {
                // 官方尾帧 choices 为空；须先取 usage，再决定是否处理字幕 delta。
                val current = parseMeteredUsage(event.opt("usage"))
                usage.updateAndGet { previous -> mergeUsage(previous, current) }
            }
            if (event.has("error")) {
                report(Code.SERVER, "千问文件听译服务返回错误")
                return false
            }
            val choices = event.optJSONArray("choices") ?: continue
            if (choices.length() == 0) continue
            val choice = choices.getJSONObject(0)
            val delta = choice.optJSONObject("delta")
            val chunk = delta?.opt("content") as? String
            if (chunk != null && chunk.isNotEmpty()) {
                if (stopped || text.length + chunk.length > MAX_TEXT_CHARS) throw ProtocolFailure()
                text.append(chunk)
                emit(LiveTranslationText(segmentId, "", text.toString(), false, text.toString()))
            }
            val reason = choice.opt("finish_reason") as? String
            if (reason != null) {
                if (reason != "stop") {
                    report(Code.RESPONSE_FAILED, "听译响应未完整完成")
                    return false
                }
                stopped = true
            }
        }
        if (!stopped || !done) {
            report(Code.RESPONSE_FAILED, "听译响应未完整完成")
            return false
        }
        emit(LiveTranslationText(segmentId, "", text.toString(), true, text.toString()))
        return synchronized(lock) { !closed }
    }

    /** 多帧 usage 是同一请求的累计快照，逐字段保留最大已知值，绝不相加或把缺失当零。 */
    private fun mergeUsage(previous: MeteredUsage?, current: MeteredUsage): MeteredUsage = MeteredUsage(
        inputTokens = maxOfNotNull(previous?.inputTokens, current.inputTokens),
        outputTokens = maxOfNotNull(previous?.outputTokens, current.outputTokens),
        cachedInputTokens = maxOfNotNull(previous?.cachedInputTokens, current.cachedInputTokens),
        audioInputTokens = maxOfNotNull(previous?.audioInputTokens, current.audioInputTokens),
        audioSeconds = maxOfNotNull(previous?.audioSeconds, current.audioSeconds),
    )

    /** 保留两个可选计量值中的最大已知值。 */
    private fun <T : Comparable<T>> maxOfNotNull(first: T?, second: T?): T? = when {
        first == null -> second
        second == null -> first
        first >= second -> first
        else -> second
    }

    /** 写入 44 字节 RIFF/WAVE 头，保留原始 PCM 字节顺序。 */
    private fun wav(audio: ByteArray): ByteArray {
        val result = ByteArray(44 + audio.size)
        val header = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + audio.size)
        header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        header.putShort(1).putShort(1).putInt(16_000).putInt(32_000)
        header.putShort(2).putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII)).putInt(audio.size)
        audio.copyInto(result, 44)
        return result
    }

    /** 在关闭锁内通知字幕，消除 close 返回后的迟到回调。 */
    private fun emit(value: LiveTranslationText) {
        synchronized(lock) { if (!closed) listener.onText(value) }
    }

    /** 在关闭锁内通知固定错误，避免暴露音频、密钥和服务端原文。 */
    private fun report(code: Code, message: String) {
        synchronized(lock) { if (!closed) listener.onError(LiveTranslateError(code, message)) }
    }

    /** 表示服务端数据超过限制或结构无效，不携带原始内容。 */
    private class ProtocolFailure : RuntimeException()

    private companion object {
        const val MIN_PCM_BYTES = 3_200
        const val MAX_PCM_BYTES = 384_000
        const val MAX_PROBE_BYTES = 64 * 1024
        const val MAX_LINE_BYTES = 64 * 1024
        const val MAX_RESPONSE_BYTES = 512 * 1024
        const val MAX_TEXT_CHARS = 65_536
    }
}
