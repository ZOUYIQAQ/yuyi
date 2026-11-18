package io.github.korenkrita.simulsub.online

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 在线模型只展示已接入的协议；稳定版供常规选择，已有快照配置仍可回填。 */
data class LiveTranslateModel(
    val id: String,
    val label: String,
    val description: String,
    val realtime: Boolean,
    val defaultEndpoint: String,
)

/** 模型、地址、协议和展示名称共用同一目录，避免界面可选但实际不能使用。 */
object LiveTranslateModels {
    const val REALTIME_ID = "qwen3.5-livetranslate-flash-realtime"
    /** 旧值仅用于识别已有配置和范围外引用过渡，不属于支持目录。 */
    const val ECONOMY_ID = "qwen3-livetranslate-flash"
    const val REALTIME_ENDPOINT = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime"
    const val ECONOMY_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"

    val realtime = LiveTranslateModel(REALTIME_ID, "千问 3.5 · 实时听译",
        "连续听译，支持原文与译文；音频输入约 1.01 元/小时，译文另计。", true, REALTIME_ENDPOINT)
    /** 仅供旧配置识别及范围外旧引用过渡，不属于支持或可选模型。 */
    @Deprecated("分段听译已停用，仅保留旧配置迁移兼容")
    val economy = LiveTranslateModel(ECONOMY_ID, "已停用的千问分段听译",
        "请改用千问实时听译。", false, ECONOMY_ENDPOINT)
    val selectable = listOf(realtime)
    val all = selectable + listOf(
        realtime.copy(id = "$REALTIME_ID-2026-05-19", label = "千问 3.5 · 2026-05-19 快照"),
    )

    /** 仅目录中的完整模型名可用于请求，不能把任意名字当作兼容协议。 */
    fun find(id: String): LiveTranslateModel? = all.firstOrNull { it.id == id }

    /** 只保留受支持的实时快照，已停用的分段模型不能重新进入选项。 */
    fun choices(currentId: String): List<LiveTranslateModel> =
        selectable + listOfNotNull(find(currentId)?.takeUnless { it in selectable })

    /** 切换协议时同步标准路由，业务空间域名保留；非标准自定义路由交给用户继续编辑。 */
    fun endpointAfterSelection(endpoint: String, oldId: String, newId: String): String {
        val selected = find(newId) ?: return endpoint
        val old = find(oldId)
        if (endpoint.isBlank() || endpoint == old?.defaultEndpoint) return selected.defaultEndpoint
        if (!isRetired(oldId) && old?.realtime == selected.realtime) return endpoint
        val url = endpoint.replaceFirst(Regex("^wss://", RegexOption.IGNORE_CASE), "https://").toHttpUrlOrNull()
            ?: return endpoint
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return endpoint
        if (url.encodedPath !in setOf("/api-ws/v1/realtime", "/compatible-mode/v1/chat/completions")) return endpoint
        val path = if (selected.realtime) "/api-ws/v1/realtime" else "/compatible-mode/v1/chat/completions"
        val switched = url.newBuilder().encodedPath(path).build().toString()
        return if (selected.realtime) switched.replaceFirst("https://", "wss://") else switched
    }

    /** 仅识别过去确实支持过的分段版本，不把任意未知模型静默替换。 */
    fun isRetired(id: String): Boolean = id == ECONOMY_ID || id == "$ECONOMY_ID-2025-12-01"

    /** 旧标准路由原来源迁到实时协议；私有非标准路由或无效地址保留原存储等待人工选择。 */
    fun migratedConnection(endpoint: String, model: String): Pair<String, String>? {
        if (!isRetired(model) || endpoint != endpoint.trim() || endpoint.any { it.isWhitespace() || it == '\\' }) return null
        if (!endpoint.startsWith("https://", ignoreCase = true)) return null
        val url = endpoint.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null ||
            url.encodedPath != "/compatible-mode/v1/chat/completions") return null
        return endpointAfterSelection(endpoint, model, REALTIME_ID) to REALTIME_ID
    }
}
