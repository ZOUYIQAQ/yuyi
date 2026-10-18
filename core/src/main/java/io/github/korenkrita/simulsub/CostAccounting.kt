package io.github.korenkrita.simulsub

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/** 服务端实际报告的用量；null 表示未报告。inputTokens 为含音频的总输入，缓存属于文字输入。 */
data class MeteredUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val audioInputTokens: Long? = null,
    val audioSeconds: Double? = null,
)

/** 发起请求时的业务用途。 */
enum class CostCategory { ASR, TRANSLATION, DIRECT, JEV, TEST }

/** 手填费率：token 字段单位为人民币元/百万 token，perSecond 为人民币元/秒。 */
data class ManualCostRate(
    val input: BigDecimal? = null,
    val output: BigDecimal? = null,
    val cache: BigDecimal? = null,
    val audio: BigDecimal? = null,
    val perSecond: BigDecimal? = null,
)

/** 已知项目费用小计；unpricedRequests 非零时金额不是整单费用。 */
data class UsageCostTotal(
    val amountYuan: BigDecimal = BigDecimal.ZERO,
    val unpricedRequests: Int = 0,
    val requests: Int = 0,
    val durationSeconds: Long = 0,
    val label: String = "",
    val byCategory: Map<CostCategory, BigDecimal> = emptyMap(),
    val unknownModels: List<String> = emptyList(),
)

/** 首页费用状态，金额始终是估算。 */
data class UsageCostSummary(
    val today: UsageCostTotal,
    val lastSession: UsageCostTotal?,
    val currentSession: UsageCostTotal?,
    val ratesUpdated: String = "2026-10-17",
    val priceNote: String = "费用按官方标价估算；DeepSeek 峰谷判断未识别中国法定节假日，节假日工作时段可能高估。",
)

/** 单次用量观测采用的费率和结果；rate 为计价当时的快照。 */
data class CostEstimate(val amountYuan: BigDecimal?, val rate: ManualCostRate?, val unpriced: Boolean)

/** 只对精确匹配的官方平台、地域及模型应用内置参考费率。 */
object CostAccounting {
    private val million = BigDecimal("1000000")
    private val beijing = ZoneId.of("Asia/Shanghai")

    /** 按事件时刻解析官方参考费率，未知路由返回 null。 */
    fun officialRate(platformId: String, endpoint: String, model: String, atMs: Long): ManualCostRate? {
        if (platformId == PlatformCredentials.QWEN.id && isBeijingQwen(endpoint)) {
            return when (model) {
                "qwen-audio-3.1-asr-flash-streaming" -> ManualCostRate(input = BigDecimal("6"), output = BigDecimal("4.5"))
                "qwen3.5-livetranslate-flash-realtime" -> ManualCostRate(output = BigDecimal("100"), audio = BigDecimal("40"))
                "qwen3-livetranslate-flash" -> ManualCostRate(output = BigDecimal("10"), audio = BigDecimal("10"))
                else -> null
            }
        }
        if (platformId == PlatformCredentials.DEEPSEEK.id && isOfficialDeepSeek(endpoint) &&
            model in setOf("deepseek-flash", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp",
                "deepseek-v4-pro")) {
            val local = Instant.ofEpochMilli(atMs).atZone(beijing)
            val peak = local.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) &&
                (local.hour in 9..11 || local.hour in 14..17)
            return when (model) {
                "deepseek-v4-pro" -> if (peak) ManualCostRate(BigDecimal("9"), BigDecimal("27"), BigDecimal("0.30"))
                    else ManualCostRate(BigDecimal("4.5"), BigDecimal("13.5"), BigDecimal("0.15"))
                else -> if (peak) ManualCostRate(BigDecimal("2"), BigDecimal("8"), BigDecimal("0.04"))
                    else ManualCostRate(BigDecimal("1"), BigDecimal("4"), BigDecimal("0.02"))
            }
        }
        return null
    }

    /** 用正式来源与精确北京域名约束千问费率。 */
    private fun isBeijingQwen(endpoint: String): Boolean {
        val uri = runCatching { java.net.URI(endpoint) }.getOrNull() ?: return false
        val host = uri.host?.lowercase()?.removeSuffix(".") ?: return false
        return uri.scheme?.lowercase() in setOf("https", "wss") && uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            officialPlatform(uri) == PlatformCredentials.QWEN.id &&
            (host == "dashscope.aliyuncs.com" ||
                (host.endsWith(".cn-beijing.maas.aliyuncs.com") &&
                    host.removeSuffix(".cn-beijing.maas.aliyuncs.com").let { name ->
                        name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '-' }
                    }))
    }

    /** 限制 DeepSeek 参考费率仅用于官方 HTTPS/WSS 端点。 */
    private fun isOfficialDeepSeek(endpoint: String): Boolean {
        val uri = runCatching { java.net.URI(endpoint) }.getOrNull() ?: return false
        return uri.scheme?.lowercase() in setOf("https", "wss") &&
            uri.host?.lowercase()?.removeSuffix(".") == "api.deepseek.com" &&
            uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443) &&
            officialPlatform(uri) == PlatformCredentials.DEEPSEEK.id
    }

    /** 去掉路径和查询后委托平台凭据规则核实官方来源。 */
    private fun officialPlatform(uri: java.net.URI): String? {
        val origin = runCatching { java.net.URI(uri.scheme, null, uri.host, uri.port, "/", null, null).toString() }
            .getOrNull() ?: return null
        return PlatformCredentials.forUrl(origin)?.id
    }

    /** 精确计算已知用量；缺少所需费率或所有用量未知时不报价。 */
    fun estimate(usage: MeteredUsage, rate: ManualCostRate?): CostEstimate {
        if (rate == null) return CostEstimate(null, null, true)
        val parts = mutableListOf<BigDecimal>()
        var unknown = false
        val hasTokenRates = listOf(rate.input, rate.output, rate.cache, rate.audio).any { it != null }
        if (hasTokenRates) {
            val textInput = when {
                usage.inputTokens == null -> null
                rate.audio != null && usage.audioInputTokens == null -> null
                rate.audio == null || usage.audioInputTokens == null -> usage.inputTokens
                usage.inputTokens >= usage.audioInputTokens -> usage.inputTokens - usage.audioInputTokens
                else -> null
            }
            if (rate.input != null && usage.inputTokens == null) unknown = true
            if (rate.audio != null && usage.inputTokens == null) unknown = true
            if (usage.inputTokens != null && textInput == null) unknown = true
            if (textInput != null) {
                val uncached = when {
                    rate.cache != null && usage.cachedInputTokens == null -> null
                    usage.cachedInputTokens == null -> textInput
                    usage.cachedInputTokens <= textInput -> textInput - usage.cachedInputTokens
                    else -> null
                }
                if (uncached == null) unknown = true
                else if (rate.input == null) {
                    if (uncached > 0) unknown = true
                } else parts += BigDecimal.valueOf(uncached).multiply(rate.input).divide(million)
            }
            if (usage.cachedInputTokens != null) {
                if (rate.cache == null) {
                    if (usage.cachedInputTokens > 0) unknown = true
                } else parts += BigDecimal.valueOf(usage.cachedInputTokens).multiply(rate.cache).divide(million)
            }
            if (rate.cache != null && usage.cachedInputTokens == null) unknown = true
            if (rate.output != null && usage.outputTokens == null) unknown = true
            if (rate.audio != null && usage.audioInputTokens == null) unknown = true
            if (usage.outputTokens != null) {
                if (rate.output == null) {
                    if (usage.outputTokens > 0) unknown = true
                } else parts += BigDecimal.valueOf(usage.outputTokens).multiply(rate.output).divide(million)
            }
            if (usage.audioInputTokens != null && rate.audio != null) {
                parts += BigDecimal.valueOf(usage.audioInputTokens).multiply(rate.audio).divide(million)
            }
            if (usage.audioInputTokens != null && rate.audio == null && usage.audioInputTokens > 0 &&
                usage.inputTokens == null) unknown = true
        }
        if (rate.perSecond != null) {
            if (usage.audioSeconds == null || !usage.audioSeconds.isFinite() || usage.audioSeconds < 0) unknown = true
            else parts += BigDecimal.valueOf(usage.audioSeconds).multiply(rate.perSecond)
        }
        return CostEstimate(if (parts.isEmpty()) null else parts.fold(BigDecimal.ZERO, BigDecimal::add), rate,
            unknown || parts.isEmpty())
    }
}
