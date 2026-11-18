package io.github.korenkrita.simulsub

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId

/** 检验精度、缺失字段和官方路由边界。 */
class CostAccountingTest {
    /** 北京千问纯音频输入仅按音频和文本输出计费。 */
    @Test fun realtimeAudioAndTextAreSplitPrecisely() {
        val rate = CostAccounting.officialRate("qwen", "wss://dashscope.aliyuncs.com/api-ws/v1/realtime",
            "qwen3.5-livetranslate-flash-realtime", 0)!!
        assertNull(rate.input)
        val estimate = CostAccounting.estimate(MeteredUsage(inputTokens = 2, outputTokens = 2,
            audioInputTokens = 2), rate)
        assertEquals(0, estimate.amountYuan!!.compareTo(BigDecimal("0.00028")))
        assertFalse(estimate.unpriced)
    }

    /** 音频之外的输入没有已知价格，只保留已知费用小计。 */
    @Test fun realtimeUnpricedRemainderIsNotAssignedImagePrice() {
        val realtime = CostAccounting.officialRate("qwen", "wss://dashscope.aliyuncs.com/api-ws/v1/realtime",
            "qwen3.5-livetranslate-flash-realtime", 0)!!
        val remainder = CostAccounting.estimate(MeteredUsage(
            inputTokens = 3, outputTokens = 2, audioInputTokens = 2), realtime)
        assertEquals(0, remainder.amountYuan!!.compareTo(BigDecimal("0.00028")))
        assertTrue(remainder.unpriced)
        assertTrue(CostAccounting.estimate(MeteredUsage(
            outputTokens = 2, audioInputTokens = 2), realtime).unpriced)
        val flash = CostAccounting.officialRate("qwen", "https://dashscope.aliyuncs.com/v1",
            "qwen3-livetranslate-flash", 0)!!
        assertNull(flash.input)
        val audioOnly = CostAccounting.estimate(MeteredUsage(
            inputTokens = 2, outputTokens = 1, audioInputTokens = 2), flash)
        assertEquals(0, audioOnly.amountYuan!!.compareTo(BigDecimal("0.00003")))
        assertFalse(audioOnly.unpriced)
    }

    /** 未报告的缓存命中数不能假定为零，已知输出仍保留估算小计。 */
    @Test fun missingCacheIsUnpricedButKnownOutputRemains() {
        val rate = ManualCostRate(input = BigDecimal("2"), output = BigDecimal("8"), cache = BigDecimal("0.04"))
        val estimate = CostAccounting.estimate(MeteredUsage(inputTokens = 100, outputTokens = 10), rate)
        assertTrue(estimate.unpriced)
        assertEquals(0, estimate.amountYuan!!.compareTo(BigDecimal("0.00008")))
        assertTrue(CostAccounting.estimate(MeteredUsage(), rate).unpriced)
    }

    /** DeepSeek 北京时间工作日峰谷采用不同价格，周末始终按谷价估算。 */
    @Test fun deepSeekPeakUsesBeijingClock() {
        val zone = ZoneId.of("Asia/Shanghai")
        val mondayPeak = LocalDateTime.of(2026, 10, 19, 9, 0).atZone(zone).toInstant().toEpochMilli()
        val mondayOffPeak = LocalDateTime.of(2026, 10, 19, 12, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(ManualCostRate(BigDecimal("2"), BigDecimal("8"), BigDecimal("0.04")),
            CostAccounting.officialRate("deepseek", "https://api.deepseek.com/v1", "deepseek-flash", mondayPeak))
        assertEquals(ManualCostRate(BigDecimal("1"), BigDecimal("4"), BigDecimal("0.02")),
            CostAccounting.officialRate("deepseek", "https://api.deepseek.com/v1",
                "deepseek-v4-flash", mondayOffPeak))
        assertEquals(BigDecimal("0.04"), CostAccounting.officialRate("deepseek", "https://api.deepseek.com/v1",
            "deepseek-v4-flash-vision-exp", mondayPeak)?.cache)
        val proPeak = CostAccounting.officialRate("deepseek", "https://api.deepseek.com/v1",
            "deepseek-v4-pro", mondayPeak)!!
        assertEquals(ManualCostRate(BigDecimal("9"), BigDecimal("27"), BigDecimal("0.30")), proPeak)
        val proOffPeak = CostAccounting.officialRate("deepseek", "https://api.deepseek.com/v1",
            "deepseek-v4-pro", mondayOffPeak)!!
        assertEquals(ManualCostRate(BigDecimal("4.5"), BigDecimal("13.5"), BigDecimal("0.15")), proOffPeak)
        assertTrue(UsageCostSummary(UsageCostTotal(), null, null).priceNote.contains("未识别中国法定节假日"))
    }

    /** 未知模型、定制来源与非北京千问不可套用官方价格。 */
    @Test fun unknownRoutesStayUnpriced() {
        assertNull(CostAccounting.officialRate("deepseek", "https://api.deepseek.com/v1", "deepseek-chat", 0))
        assertNull(CostAccounting.officialRate("deepseek", "https://api.deepseek.com/v1", "deepseek-v4flash", 0))
        assertNull(CostAccounting.officialRate("origin:https://proxy.example", "https://proxy.example/v1",
            "deepseek-flash", 0))
        assertNull(CostAccounting.officialRate("qwen", "https://dashscope-intl.aliyuncs.com/v1",
            "qwen-audio-3.1-asr-flash-streaming", 0))
        assertNull(CostAccounting.officialRate("qwen", "https://llm.cn-shanghai.maas.aliyuncs.com/v1",
            "qwen-audio-3.1-asr-flash-streaming", 0))
    }

    /** 手填每秒单价使用十进制金额，不依赖浮点金额累计。 */
    @Test fun manualSecondsPreserveDecimalPrecision() {
        val estimate = CostAccounting.estimate(MeteredUsage(audioSeconds = 0.25),
            ManualCostRate(perSecond = BigDecimal("0.00003")))
        assertEquals(0, estimate.amountYuan!!.compareTo(BigDecimal("0.0000075")))
        assertFalse(estimate.unpriced)
    }

    /** Token 费率下的服务端音频秒数只留作元数据，不造成未计价或重复收费。 */
    @Test fun tokenPricingIgnoresReportedAudioSeconds() {
        val rate = ManualCostRate(input = BigDecimal("6"), output = BigDecimal("4.5"))
        val withDuration = CostAccounting.estimate(MeteredUsage(
            inputTokens = 100, outputTokens = 10, audioSeconds = 7.25), rate)
        val withoutDuration = CostAccounting.estimate(MeteredUsage(
            inputTokens = 100, outputTokens = 10), rate)
        assertEquals(0, withDuration.amountYuan!!.compareTo(BigDecimal("0.000645")))
        assertEquals(withoutDuration.amountYuan, withDuration.amountYuan)
        assertFalse(withDuration.unpriced)
    }

    /** 官方 ASR 返回累计秒数时仍只按完整输入、输出 Token 报价。 */
    @Test fun officialAsrDurationIsMetadataForTokenPrice() {
        val rate = CostAccounting.officialRate("qwen",
            "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
            "qwen-audio-3.1-asr-flash-streaming", 0)!!
        val estimate = CostAccounting.estimate(MeteredUsage(
            inputTokens = 85, outputTokens = 4, audioSeconds = 95.0), rate)
        assertEquals(0, estimate.amountYuan!!.compareTo(BigDecimal("0.000528")))
        assertFalse(estimate.unpriced)
    }

    /** 只有秒费率时，服务返回的非零或零 Token 均不影响报价状态。 */
    @Test fun perSecondPricingIgnoresTokenMetadata() {
        val rate = ManualCostRate(perSecond = BigDecimal("0.01"))
        for (usage in listOf(
            MeteredUsage(100, 20, 5, 30, 2.5),
            MeteredUsage(0, 0, 0, 0, 2.5),
        )) {
            val estimate = CostAccounting.estimate(usage, rate)
            assertEquals(0, estimate.amountYuan!!.compareTo(BigDecimal("0.025")))
            assertFalse(estimate.unpriced)
        }
        assertTrue(CostAccounting.estimate(MeteredUsage(inputTokens = 0, outputTokens = 0), rate).unpriced)
    }
}
