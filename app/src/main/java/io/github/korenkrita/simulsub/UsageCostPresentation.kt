package io.github.korenkrita.simulsub

import io.github.korenkrita.simulsub.ui.UiOption
import io.github.korenkrita.simulsub.ui.UsageCostUiState
import java.math.BigDecimal
import java.math.RoundingMode

/** 金额使用十进制，极小的正费用也不显示成零。 */
internal fun costAmount(amount: BigDecimal): String = if (amount.signum() > 0 && amount < BigDecimal("0.000001"))
    "<¥0.000001" else "¥${amount.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()}"

/** 请求和时长说明紧邻金额；用量缺失时明确标示未计价。 */
private fun costDetail(total: UsageCostTotal): String {
    val minutes = total.durationSeconds / 60
    val seconds = total.durationSeconds % 60
    val time = if (minutes > 0) "$minutes 分 $seconds 秒" else "$seconds 秒"
    return "$time · ${total.requests} 次请求" +
        if (total.unpricedRequests > 0) " · ${total.unpricedRequests} 次未完整计价" else ""
}

/** 全部用量未知时显示待计价，避免醒目的零金额误导使用者。 */
private fun UsageCostTotal.displayAmount(): String =
    if (requests > 0 && unpricedRequests == requests && amountYuan.signum() == 0) "待计价" else costAmount(amountYuan)

/** 为今天、上次和当前会话保留各自结果，不把未知费用当成零。 */
internal fun costUiState(summary: UsageCostSummary): UsageCostUiState {
    val scopes = listOfNotNull(summary.today, summary.lastSession, summary.currentSession)
    val focus = summary.currentSession ?: summary.lastSession ?: summary.today
    val focusLabel = when {
        summary.currentSession != null -> "本次同传"
        summary.lastSession != null -> "上次同传"
        else -> "今天"
    }
    return UsageCostUiState(
        todayAmount = summary.today.displayAmount(),
        lastAmount = summary.lastSession?.displayAmount() ?: "暂无会话",
        currentAmount = summary.currentSession?.displayAmount().orEmpty(),
        todayDetail = costDetail(summary.today),
        lastDetail = summary.lastSession?.let(::costDetail).orEmpty(),
        currentDetail = summary.currentSession?.let(::costDetail).orEmpty(),
        breakdown = focus.byCategory.map { (category, amount) ->
            UiOption(category.name, category.costLabel(), "$focusLabel · ${costAmount(amount)}")
        },
        unpricedModels = scopes.flatMap { it.unknownModels }.distinct(),
        ratesUpdated = summary.ratesUpdated,
        priceNote = summary.priceNote,
        todayPartial = summary.today.unpricedRequests > 0,
        lastPartial = (summary.lastSession?.unpricedRequests ?: 0) > 0,
        currentPartial = (summary.currentSession?.unpricedRequests ?: 0) > 0,
    )
}

/** 将内部用途转换成面向使用者的分类名称。 */
private fun CostCategory.costLabel(): String = when (this) {
    CostCategory.ASR -> "在线听写"
    CostCategory.TRANSLATION -> "文字翻译"
    CostCategory.DIRECT -> "语音直译"
    CostCategory.JEV -> "在线辅助"
    CostCategory.TEST -> "连接测试"
}
