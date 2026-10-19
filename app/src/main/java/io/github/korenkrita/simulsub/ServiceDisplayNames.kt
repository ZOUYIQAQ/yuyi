package io.github.korenkrita.simulsub

import io.github.korenkrita.simulsub.online.LiveTranslateModels

/** 按实际听写协议与模型生成标题，不读取用户保存的名称或场景。 */
fun listeningServiceTitle(mode: ListeningMode, model: String): String {
    val actualModel = model.trim()
    return when (mode) {
        ListeningMode.LOCAL -> if (actualModel.isEmpty()) "本地模型" else "本地 · $actualModel"
        ListeningMode.CLOUD_ASR -> when (actualModel) {
            "" -> "千问在线听写"
            "qwen-audio-3.1-asr-flash-streaming" -> "千问 3.1 Streaming · 听写"
            else -> "千问在线听写 · $actualModel"
        }
        ListeningMode.ONLINE -> if (LiveTranslateModels.isRetired(actualModel)) "已停用的分段听译"
            else LiveTranslateModels.find(actualModel)?.label
                ?: if (actualModel.isEmpty()) "千问语音直译" else "千问语音直译 · $actualModel"
    }
}

/** 按连接来源与实际模型生成翻译标题；未知来源的地址留给详情展示。 */
fun translationServiceTitle(url: String, model: String): String {
    val platform = PlatformCredentials.forUrl(url)
    val provider = when (platform?.id) {
        PlatformCredentials.QWEN.id -> "千问"
        PlatformCredentials.DEEPSEEK.id -> "DeepSeek"
        PlatformCredentials.SILICONFLOW.id -> "硅基流动"
        PlatformCredentials.OPENROUTER.id -> "OpenRouter"
        PlatformCredentials.TYPESAFE.id -> "TypeSafe"
        else -> TextServiceProviders.entries.firstOrNull { it.platformId == platform?.id }?.title ?: "自定义接口"
    }
    val actualModel = model.trim()
    return if (actualModel.isEmpty()) provider else "$provider · $actualModel"
}

/** 保持原有顺序与标识；同名标题依次编号，避免覆盖已有的独立标题。 */
fun distinctServiceTitles(items: List<Pair<String, String>>): Map<String, String> {
    require(items.map { it.first }.distinct().size == items.size) { "服务标识不能重复" }
    val counts = items.groupingBy { it.second }.eachCount()
    val reserved = counts.filterValues { it == 1 }.keys
    val used = mutableSetOf<String>()
    val nextNumber = mutableMapOf<String, Int>()
    return buildMap {
        items.forEach { (id, title) ->
            if (counts.getValue(title) == 1) {
                put(id, title)
            } else {
                var number = nextNumber[title] ?: 1
                var candidate = "$title（$number）"
                while (candidate in used || candidate in reserved) {
                    number++
                    candidate = "$title（$number）"
                }
                put(id, candidate)
                used += candidate
                nextNumber[title] = number + 1
            }
        }
    }
}
