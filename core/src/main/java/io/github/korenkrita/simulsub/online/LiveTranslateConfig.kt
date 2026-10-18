package io.github.korenkrita.simulsub.online

import io.github.korenkrita.simulsub.MeteredUsage
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 在线听译配置；由已接入模型决定协议，密钥仅放在请求头。 */
data class LiveTranslateConfig(
    val endpoint: String = DEFAULT_ENDPOINT,
    val model: String = DEFAULT_MODEL,
    val apiKey: String,
    val sourceLang: String? = null,
    val targetLang: String = "zh"
) {
    companion object {
        const val DEFAULT_ENDPOINT = LiveTranslateModels.REALTIME_ENDPOINT
        const val DEFAULT_MODEL = LiveTranslateModels.REALTIME_ID
        val SUPPORTED_TARGET_LANGUAGES: Set<String> = setOf("zh", "en", "ja", "ko")
    }

    val isRealtime: Boolean get() = LiveTranslateModels.find(model)?.realtime == true

    /** 返回不包含配置原文的校验说明；空值表示通过，源语种为空时由云端识别。 */
    fun validationError(): String? = when {
        baseUrl() == null -> "听译端点必须是无账号、查询参数和片段的 HTTPS 或 WSS 地址"
        LiveTranslateModels.find(model) == null -> "请选择已接入的千问实时听译或省钱分段模型"
        !isRealtime && !endpoint.startsWith("https://", ignoreCase = true) -> "省钱分段模型需要 HTTPS 接口地址"
        !isRealtime && baseUrl()?.pathSegments?.takeLast(4) != listOf("compatible-mode", "v1", "chat", "completions") ->
            "省钱分段地址需以 /compatible-mode/v1/chat/completions 结束"
        apiKey.isEmpty() -> "请先配置阿里百炼平台密钥"
        sourceLang != null && sourceLang !in SUPPORTED_TARGET_LANGUAGES -> "听译源语种暂不支持"
        targetLang !in SUPPORTED_TARGET_LANGUAGES -> "听译目标语种暂不支持"
        else -> null
    }

    /** 生成安全握手地址；OkHttp 用 HTTPS 表示 WSS 握手，不改变传输安全性。 */
    internal fun requestUrl(): HttpUrl {
        val url = requireNotNull(baseUrl()) { "听译端点格式无效" }
        return if (isRealtime) url.newBuilder().addQueryParameter("model", model).build() else url
    }

    /** 严格拒绝明文协议及 URL 凭据，避免解析异常泄露用户输入。 */
    private fun baseUrl(): HttpUrl? {
        if (endpoint != endpoint.trim() || endpoint.any { it.isWhitespace() || it == '\\' }) return null
        val normalized = when {
            endpoint.startsWith("wss://", ignoreCase = true) -> "https://" + endpoint.substring(6)
            endpoint.startsWith("https://", ignoreCase = true) -> endpoint
            else -> return null
        }
        val url = normalized.toHttpUrlOrNull() ?: return null
        return url.takeIf {
            it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
        }
    }

    /** 禁止配置对象被日志隐式展开为密钥或包含凭据的地址。 */
    override fun toString(): String = "LiveTranslateConfig(实时听译配置)"
}

/** 所有缓冲按编码后的 WebSocket 文本字节数计算，单次音频最多一秒。 */
data class LiveTranslateOptions(
    val handshakeTimeoutMs: Long = 15_000,
    val finishTimeoutMs: Long = 15_000,
    val maxQueuedBytes: Long = 256 * 1024,
    val maxAudioChunkBytes: Int = 32_000
) {
    init {
        require(handshakeTimeoutMs in 1..120_000 && finishTimeoutMs in 1..120_000) { "听译超时设置无效" }
        require(maxQueuedBytes in 1024..(8L * 1024 * 1024)) { "听译发送缓冲大小无效" }
        require(maxAudioChunkBytes in 2..32_000 && maxAudioChunkBytes % 2 == 0) { "听译音频块大小无效" }
    }
}

/** 客户端一次性使用，终止后不可重启；重开会话必须由调用方明确创建新实例。 */
enum class LiveTranslateState { NEW, CONNECTING, CONFIGURING, READY, FINISHING, CLOSED, FAILED }

/** 同一译文消息始终使用相同段号；最终译文确认后仍允许迟到原文补全同一段。 */
data class LiveTranslationText(
    val segmentId: String,
    val sourceText: String,
    val translatedText: String,
    val isFinal: Boolean,
    /** 已确认前缀与预测尾部分开，只有前者可以提前断句。 */
    val confirmedText: String = if (isFinal) translatedText else ""
)

/** 错误码与中文说明均为本地常量，不包含服务端原始错误、异常、请求地址或音频。 */
data class LiveTranslateError(val code: Code, val message: String) {
    enum class Code {
        INVALID_CONFIG, HANDSHAKE_TIMEOUT, FINISH_TIMEOUT, BUFFER_OVERFLOW,
        INVALID_AUDIO, NETWORK, SERVER, PROTOCOL, RESPONSE_FAILED, TRANSCRIPTION_FAILED
    }
}

/** 回调串行执行但线程不固定；应快速返回，界面调用方自行切换主线程，禁止在回调内等待。 */
interface LiveTranslateListener {
    /** 每个已完成响应或文件片段通知一次服务端报告的用量；未知字段保持 null。 */
    fun onUsage(eventId: String, usage: MeteredUsage) {}
    /** 通知握手、可发送音频及终止状态。 */
    fun onState(state: LiveTranslateState) {}
    /** 以完整字幕快照通知新增或更新，同段快照相同则不重复通知。 */
    fun onText(text: LiveTranslationText) {}
    /** 通知终止原因；同一客户端最多通知一次错误。 */
    fun onError(error: LiveTranslateError) {}
}
