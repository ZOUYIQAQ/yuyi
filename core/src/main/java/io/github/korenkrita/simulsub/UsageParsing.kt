package io.github.korenkrita.simulsub

import org.json.JSONObject

/** 只读取服务端明确给出的用量字段；缺失与非法字段保持 null，显式零保持零。 */
fun parseMeteredUsage(raw: Any?): MeteredUsage {
    val usage = raw as? JSONObject ?: return MeteredUsage()
    val inputDetails = usage.optJSONObject("input_tokens_details") ?: usage.optJSONObject("input_token_details")
        ?: usage.optJSONObject("prompt_tokens_details")
    val seconds = usage.opt("seconds") ?: inputDetails?.opt("seconds")
    return MeteredUsage(
        inputTokens = usage.usageLong("input_tokens") ?: usage.usageLong("prompt_tokens"),
        outputTokens = usage.usageLong("output_tokens") ?: usage.usageLong("completion_tokens"),
        cachedInputTokens = inputDetails?.usageLong("cached_tokens")
            ?: usage.usageLong("prompt_cache_hit_tokens") ?: usage.usageLong("cached_input_tokens")
            ?: usage.usageLong("cached_tokens"),
        audioInputTokens = inputDetails?.usageLong("audio_tokens"),
        audioSeconds = (seconds as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 },
    )
}

/** 拒绝负数、非整数和溢出值，避免把畸形协议解释成可计价的零。 */
private fun JSONObject.usageLong(key: String): Long? {
    val value = opt(key) as? Number ?: return null
    return value.toString().toLongOrNull()?.takeIf { it >= 0 }
}
