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
    const val ECONOMY_ID = "qwen3-livetranslate-flash"
    const val REALTIME_ENDPOINT = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime"
    const val ECONOMY_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"

    val realtime = LiveTranslateModel(REALTIME_ID, "千问 3.5 · 实时听译",
        "连续听译，支持原文与译文；音频输入约 1.01 元/小时，译文另计。", true, REALTIME_ENDPOINT)
    val economy = LiveTranslateModel(ECONOMY_ID, "千问 3 · 省钱分段",
        "收集短段后翻译，仅返回译文；音频输入约 0.45 元/小时，译文另计。", false, ECONOMY_ENDPOINT)
    val selectable = listOf(realtime, economy)
    val all = selectable + listOf(
        realtime.copy(id = "$REALTIME_ID-2026-05-19", label = "千问 3.5 · 2026-05-19 快照"),
        economy.copy(id = "$ECONOMY_ID-2025-12-01", label = "千问 3 · 2025-12-01 快照"),
    )

    /** 仅目录中的完整模型名可用于请求，不能把任意名字当作兼容协议。 */
    fun find(id: String): LiveTranslateModel? = all.firstOrNull { it.id == id }

    /** 保留用户已有的快照入口，常规新配置只需在两种工作方式之间选择。 */
    fun choices(currentId: String): List<LiveTranslateModel> =
        selectable + listOfNotNull(find(currentId)?.takeUnless { it in selectable })

    /** 切换协议时同步标准路由，业务空间域名保留；非标准自定义路由交给用户继续编辑。 */
    fun endpointAfterSelection(endpoint: String, oldId: String, newId: String): String {
        val selected = find(newId) ?: return endpoint
        val old = find(oldId)
        if (endpoint.isBlank() || endpoint == old?.defaultEndpoint) return selected.defaultEndpoint
        if (old?.realtime == selected.realtime) return endpoint
        val url = endpoint.replaceFirst(Regex("^wss://", RegexOption.IGNORE_CASE), "https://").toHttpUrlOrNull()
            ?: return endpoint
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return endpoint
        if (url.encodedPath !in setOf("/api-ws/v1/realtime", "/compatible-mode/v1/chat/completions")) return endpoint
        val path = if (selected.realtime) "/api-ws/v1/realtime" else "/compatible-mode/v1/chat/completions"
        val switched = url.newBuilder().encodedPath(path).build().toString()
        return if (selected.realtime) switched.replaceFirst("https://", "wss://") else switched
    }
}
