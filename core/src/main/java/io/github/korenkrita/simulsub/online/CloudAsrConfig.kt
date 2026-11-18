package io.github.korenkrita.simulsub.online

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 千问 3.1 Streaming 听写配置；音频固定为 16 kHz 单声道 PCM16LE。 */
data class CloudAsrConfig(
    val endpoint: String = DEFAULT_ENDPOINT,
    val model: String = DEFAULT_MODEL,
    val apiKey: String,
    val sourceLanguage: String = "ja",
    val context: String = "",
    val vocabulary: Map<String, Int> = emptyMap()
) {
    companion object {
        const val DEFAULT_ENDPOINT = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
        const val DEFAULT_MODEL = "qwen-audio-3.1-asr-flash-streaming"
        val SUPPORTED_MODELS: List<String> = listOf(DEFAULT_MODEL)
        private val LANGUAGES = setOf("zh", "en", "ja", "ko", "vi", "th", "id", "ms", "tl", "hi",
            "ar", "fr", "de", "es", "pt", "ru", "it", "nl", "sv", "da", "fi", "no", "el",
            "pl", "cs", "hu", "ro", "bg", "hr", "sk")
    }

    /** 返回全部中文配置错误；空列表表示可以建立连接。 */
    fun validationErrors(): List<String> = buildList {
        if (requestUrl() == null) add("听写地址必须是无账号、查询参数和片段的 WSS /api-ws/v1/inference 地址")
        if (model !in SUPPORTED_MODELS) add("听写模型仅支持千问 3.1 ASR Flash Streaming")
        if (apiKey.isEmpty()) add("请先配置阿里百炼平台密钥")
        if (sourceLanguage != "auto" && sourceLanguage !in LANGUAGES) add("听写源语种不受支持")
        if (context.codePointCount(0, context.length) > 400) add("听写上下文不能超过 400 字")
        if (vocabulary.size > 2000 || vocabulary.any { (word, weight) ->
                word.isBlank() || weight !in 1..5 && weight != 50
            } || vocabulary.values.count { it == 50 } > 50) {
            add("即时热词不能超过 2000 个，权重需为 1 至 5 或 50，超级热词最多 50 个")
        }
    }

    /** 校验并取得不含用户凭据的 WSS 地址。 */
    internal fun requestUrl(): HttpUrl? {
        if (!endpoint.startsWith("wss://", ignoreCase = true) ||
            endpoint != endpoint.trim() || endpoint.any { it.isWhitespace() || it == '\\' }) return null
        val url = ("https://" + endpoint.substring(6)).toHttpUrlOrNull() ?: return null
        return url.takeIf {
            it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null &&
                it.encodedPath == "/api-ws/v1/inference"
        }
    }

    /** 配置输出不包含密钥和上下文正文。 */
    override fun toString(): String = "CloudAsrConfig(在线听写配置)"
}
