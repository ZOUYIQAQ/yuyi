package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 首页展示两项金额；嵌入控制卡时继承配对内容色，独立使用时保留原来的表面颜色。 */
@Composable
internal fun UsageCostSummary(costs: UsageCostUiState, running: Boolean, onOpen: () -> Unit, embedded: Boolean = false) {
    val partial = costs.todayPartial || if (running) costs.currentPartial else costs.lastPartial
    val contentColor = if (embedded) LocalContentColor.current else MaterialTheme.colorScheme.onSurface
    Surface(onClick = onOpen,
        color = if (embedded) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = contentColor,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("live-cost-summary").semantics { role = Role.Button }) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stacked = maxWidth < 280.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f
                FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = if (stacked) 1 else 2,
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("今日预计" to costs.todayAmount,
                        (if (running) "本次同传" else "上次同传") to
                            (if (running) costs.currentAmount.ifBlank { "待计量" } else costs.lastAmount)).forEach { (title, amount) ->
                        val amountModifier = if (stacked) Modifier.fillMaxWidth() else Modifier.weight(1f)
                        if (!embedded) CostSummaryAmount(title, amount, amountModifier)
                        else Column(amountModifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(title, style = MaterialTheme.typography.labelMedium, color = contentColor.copy(alpha = 0.85f))
                            Text(amount, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
            Text(if (partial) "含未计价服务 · 金额可能不完整 · 查看明细 ›" else "查看费用明细 ›",
                style = MaterialTheme.typography.labelMedium, color = if (embedded) contentColor else MaterialTheme.colorScheme.primary)
        }
    }
}

/** 金额完整换行，避免大字号或较大金额在手机窄栏被裁切。 */
@Composable
private fun CostSummaryAmount(title: String, amount: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(amount, style = MaterialTheme.typography.titleSmall)
    }
}

/** 费用页只渲染用量状态，未知服务明确列为未计价，不把部分估算当成整单总额。 */
@Composable
internal fun UsageCostSettings(costs: UsageCostUiState, wide: Boolean) {
    PageColumns(wide, first = {
        item("cost-today") { CostPeriodCard("今日预计", costs.todayAmount, costs.todayDetail, costs.todayPartial, "cost-today") }
        item("cost-last") { CostPeriodCard("上次同传", costs.lastAmount, costs.lastDetail, costs.lastPartial, "cost-last") }
        if (costs.currentAmount.isNotBlank()) item("cost-current") {
            CostPeriodCard("本次同传", costs.currentAmount, costs.currentDetail, costs.currentPartial, "cost-current")
        }
    }, second = {
        item("cost-breakdown-heading") { SectionHeading("分类成本") }
        if (costs.breakdown.isEmpty()) item("cost-empty") { SupportingText("暂无分类用量明细。") }
        itemsIndexed(costs.breakdown, key = { index, option -> "cost-category-$index-${option.id}" }) { _, option ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(option.label, style = MaterialTheme.typography.titleSmall)
                    if (option.description.isNotBlank()) SupportingText(option.description)
                }
            }
        }
        if (costs.unpricedModels.isNotEmpty()) {
            item("unpriced-heading") { SectionHeading("未计价服务") }
            itemsIndexed(costs.unpricedModels, key = { index, _ -> "unpriced-$index" }) { _, model ->
                ListItem(headlineContent = { Text(model) }, supportingContent = { Text("未计价") },
                    modifier = Modifier.fillMaxWidth().testTag("unpriced-service"))
            }
        }
        item("cost-note") { SupportingText("${costs.priceNote} · 单价日期：${costs.ratesUpdated}") }
        item("cost-storage-note") { SupportingText("仅保存用量与费用统计，不保存字幕内容。") }
    })
}

/** 各时间范围的金额和用量明细分开展示，未计价提示紧邻金额，避免误读为零费用。 */
@Composable
private fun CostPeriodCard(title: String, amount: String, detail: String, partial: Boolean, tag: String) {
    Card(Modifier.fillMaxWidth().testTag(tag),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(amount, style = MaterialTheme.typography.headlineSmall)
            if (partial) Text("存在未计价服务，金额可能不完整", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            if (detail.isNotBlank()) SupportingText(detail)
        }
    }
}
