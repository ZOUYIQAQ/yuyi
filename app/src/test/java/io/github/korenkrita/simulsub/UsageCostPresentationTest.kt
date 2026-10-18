package io.github.korenkrita.simulsub

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

/** 展示不能将未知费用或极小正费用伪装成零，也不能混淆各个统计范围。 */
class UsageCostPresentationTest {
    /** 全未知请求显示待计价；没有请求则正常显示零。 */
    @Test fun entirelyUnknownCostsDoNotLookFree() {
        val result = costUiState(UsageCostSummary(
            UsageCostTotal(requests = 1, unpricedRequests = 1, unknownModels = listOf("custom")), null, null))
        assertEquals("待计价", result.todayAmount)
        assertTrue(result.todayPartial)
        assertEquals("¥0", costUiState(UsageCostSummary(UsageCostTotal(), null, null)).todayAmount)
        assertEquals("<¥0.000001", costAmount(BigDecimal("0.00000002")))
    }

    /** 上次会话未计价不能误标今天已完整计价的费用。 */
    @Test fun incompleteScopeIsKeptSeparate() {
        val result = costUiState(UsageCostSummary(
            UsageCostTotal(BigDecimal("0.01234"), requests = 3),
            UsageCostTotal(BigDecimal.ZERO, 1, 1, unknownModels = listOf("custom")), null))
        assertFalse(result.todayPartial)
        assertTrue(result.lastPartial)
        assertEquals("¥0.01234", result.todayAmount)
        assertEquals("待计价", result.lastAmount)
    }
}
