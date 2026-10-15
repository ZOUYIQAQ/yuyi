package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.korenkrita.simulsub.models.ModelComparison
import io.github.korenkrita.simulsub.models.ModelComparisons
import kotlin.math.roundToInt

/** 模型目录居中限宽，宽屏把同一行模型按内容区逐段对齐，手机保持完整单列。 */
@Composable
internal fun ModelsScreen(state: ModelsUiState, actions: SimulSubActions, wide: Boolean) {
    val confirmation = state.items.firstOrNull { it.id == state.deleteConfirmationId }
    if (confirmation != null) {
        ModelDeleteConfirmation(confirmation, state.deletingId == confirmation.id, actions)
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val columns = if (wide && maxWidth >= 760.dp) 2 else 1
        val states = LocalPageScrollStates.current
        val listState = (if (columns == 2) states?.first else states?.single) ?: rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier.widthIn(max = if (columns == 2) 1120.dp else 640.dp).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = if (columns == 2) 24.dp else 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("models-heading") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            SectionHeading("离线识别模型", "选择适合语言的模型，下载后在本机识别。")
                        }
                        IconButton(onClick = actions.onRefreshModels, enabled = !state.loading) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "刷新模型状态")
                        }
                    }
                    if (state.storageLabel.isNotEmpty()) SupportingText(state.storageLabel)
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.message?.let { MessagePanel(it) }
                }
            }
            if (state.items.isEmpty()) item("models-empty") {
                SupportingText(if (state.loading) "正在加载模型目录。" else "暂无模型可显示。请刷新目录；若仍为空，请检查上方提示。")
            }
            items(state.items.chunked(columns), key = { row -> row.first().id }) { row ->
                if (columns == 1) ModelItem(row.first(), actions) else ModelCardRow(row, actions)
            }
        }
    }
}

/** 单列沿用相同内容结构，避免切换屏幕宽度时改变操作与详情顺序。 */
@Composable
private fun ModelItem(model: ModelUiState, actions: SimulSubActions) {
    ModelCardRow(listOf(model), actions)
}

/**
 * 每段先以实际列宽测量，再使用两卡中的最大段高共同排版。
 * 标题、状态、简介、属性、参考数据、进度、操作和详情均不依赖固定高度或省略号；
 * 背景最后按总高度测量，确保下载失败、长文本和大字号下卡片仍等高。
 */
@Composable
private fun ModelCardRow(models: List<ModelUiState>, actions: SimulSubActions) {
    Layout(
        modifier = Modifier.fillMaxWidth().semantics { isTraversalGroup = true },
        content = {
            models.forEachIndexed { column, model ->
                key(model.id) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {}
                    ModelSections(model, actions, column)
                }
            }
        },
    ) { measurables, constraints ->
        val gap = 16.dp.roundToPx()
        val padding = 16.dp.roundToPx()
        val sectionGap = 8.dp.roundToPx()
        val columnCount = models.size
        val cardWidth = (constraints.maxWidth - gap * (columnCount - 1)) / columnCount
        val sectionConstraints = Constraints.fixedWidth((cardWidth - padding * 2).coerceAtLeast(0))
        val groups = measurables.chunked(measurables.size / columnCount)
        val sections = groups.map { group -> group.drop(1).map { it.measure(sectionConstraints) } }
        val heights = sections.first().indices.map { index -> sections.maxOf { it[index].height } }
        val totalHeight = padding * 2 + heights.sum() + sectionGap * (heights.size - 1)
        val backgrounds = groups.map { it.first().measure(Constraints.fixed(cardWidth, totalHeight)) }
        layout(constraints.maxWidth, totalHeight) {
            sections.forEachIndexed { column, cells ->
                val left = column * (cardWidth + gap)
                backgrounds[column].placeRelative(left, 0)
                var top = padding
                cells.forEachIndexed { index, cell ->
                    cell.placeRelative(left + padding, top)
                    top += heights[index] + sectionGap
                }
            }
        }
    }
}

/** 每张卡固定输出相同数量的内容段，读屏按模型逐个阅读而不是跨列交错。 */
@Composable
private fun ModelSections(model: ModelUiState, actions: SimulSubActions, column: Int) {
    var details by rememberSaveable(model.id) { mutableStateOf(false) }
    val comparison = ModelComparisons[model.id]
    ModelSection(column, 0) {
        Text(model.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth().semantics { heading() })
    }
    ModelSection(column, 1) { ModelStatus(model) }
    ModelSection(column, 2) {
        Text(comparison?.introduction ?: model.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
    }
    ModelSection(column, 3) {
        ModelMetadata("识别语言", model.languages)
    }
    ModelSection(column, 4) {
        ModelMetadata("模型大小", model.sizeLabel)
    }
    ModelSection(column, 5) { comparison?.let { ModelBenchmark(it) } }
    ModelSection(column, 6) { ModelProgress(model) }
    ModelSection(column, 7) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ActionRow {
                if (model.canDownload) Button(
                    onClick = { actions.onDownloadModel(model.id) }, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(if (model.status == ModelUiStatus.FAILED || model.status == ModelUiStatus.CANCELLED) "重试下载" else "下载模型") }
                if (model.canCancel) FilledTonalButton(
                    onClick = { actions.onCancelDownload(model.id) }, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("取消下载") }
                if (model.canActivate) Button(
                    onClick = { actions.onActivateModel(model.id) }, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("启用模型") }
                if (!model.canDownload && !model.canCancel && !model.canActivate) {
                    Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                        SupportingText(if (model.status == ModelUiStatus.ACTIVE) "当前选中模型" else model.status.title)
                    }
                }
                if (model.deletionScope.isNotBlank()) TextButton(
                    onClick = { actions.onRequestDeleteModel(model.id) },
                    enabled = model.canDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "删除${model.name}" },
                ) { Text("删除模型") }
            }
            model.deleteBlockedReason?.takeIf { model.deletionScope.isNotBlank() && it.isNotBlank() }?.let { SupportingText(it) }
        }
    }
    ModelSection(column, 8) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { details = !details }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (details) "收起模型详情" else "来源、许可与完整性")
            }
            if (details) {
                SupportingText("来源：${model.sourceLabel}\n许可：${model.license}")
                if (model.verificationDetail.isNotEmpty()) SupportingText(model.verificationDetail)
                if (comparison != null) {
                    SupportingText("评测条件：FLEURS（日语650句、英语647句），Mac M4 Pro，2线程，int8，sherpa-onnx 1.13.8。整句离线解码，音频首尾各补0.3秒静音。")
                    SupportingText("日语为字错误率，英语为词错误率；参考耗时为单句解码中位数。")
                }
                ActionRow {
                    if (model.sourceUrl.isNotEmpty()) TextButton(onClick = { actions.onModelSource(model.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("查看下载来源") }
                    if (model.license.isNotBlank() || model.licenseUrl.isNotEmpty()) TextButton(onClick = { actions.onModelLicense(model.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("查看许可") }
                    TextButton(onClick = actions.onOpenModelBenchmark, modifier = Modifier.heightIn(min = 48.dp)) { Text("评测来源与条件") }
                }
            }
        }
    }
}

/** 主卡仅保留易懂的错误率和参考耗时，参考设备紧跟耗时以避免误认为手机延迟。 */
@Composable
private fun ModelBenchmark(comparison: ModelComparison) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ModelMetadata("日语识别错误率", comparison.japaneseErrorRate)
        ModelMetadata("英语识别错误率", comparison.englishErrorRate)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ModelMetadata("参考识别耗时（日/英）", "${comparison.japaneseDecodeMs} / ${comparison.englishDecodeMs} ms")
            Text("参考设备：Mac M4 Pro", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 页面内的正式确认必须展示模型、固定文件范围和停用后果，取消不派发删除。 */
@Composable
private fun ModelDeleteConfirmation(model: ModelUiState, deleting: Boolean, actions: SimulSubActions) {
    AlertDialog(
        onDismissRequest = { if (!deleting) actions.onDismissDeleteModel() },
        title = { Text("删除${model.name}？") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("将删除 ${model.name} 的以下文件范围：")
                Text(model.deletionScope, style = MaterialTheme.typography.bodySmall)
                Text("删除会停用此模型；如果它是当前选中模型，将清空选择，不会自动切换到其他模型。再次使用需要重新下载。")
                Text("下载、校验或同传运行期间不能删除。其他模型、应用设置和使用记录会保留。")
                model.deleteBlockedReason?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                model.error?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (deleting) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { actions.onConfirmDeleteModel(model.id) }, enabled = model.canDelete && !deleting,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(if (deleting) "正在删除…" else "确认删除")
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismissDeleteModel, enabled = !deleting) { Text("取消") }
        },
    )
}

/** 属性使用固定标签列与可换行值列，缩短手机卡片且保留跨卡对齐。 */
@Composable
private fun ModelMetadata(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.58f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.42f))
    }
}

/** 每个内容段只产生一个测量节点，并为跨列读屏指定稳定阅读次序。 */
@Composable
private fun ModelSection(column: Int, section: Int, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().semantics { isTraversalGroup = true; traversalIndex = (column * 9 + section).toFloat() }) { content() }
}

/** 状态使用文字和主题色共同表达，下载状态变化以礼貌方式播报。 */
@Composable
private fun ModelStatus(model: ModelUiState) {
    val failed = model.status == ModelUiStatus.FAILED
    val active = model.status == ModelUiStatus.ACTIVE
    Surface(
        shape = MaterialTheme.shapes.small,
        color = when {
            failed -> MaterialTheme.colorScheme.errorContainer
            active -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = when {
            failed -> MaterialTheme.colorScheme.onErrorContainer
            active -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Text(model.status.title, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/** 下载与校验分别标明阶段，未知进度不捏造百分比，错误保留完整原文。 */
@Composable
private fun ModelProgress(model: ModelUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (model.status == ModelUiStatus.DOWNLOADING || model.status == ModelUiStatus.VERIFYING) {
            val progress = model.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
            if (model.status == ModelUiStatus.VERIFYING || progress == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                SupportingText(if (model.status == ModelUiStatus.VERIFYING) "正在校验模型完整性…" else "正在下载，等待进度…")
            } else {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text("已下载 ${(progress * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (model.canCancel && model.downloadedLabel.isNotEmpty()) SupportingText(model.downloadedLabel)
        model.error?.takeIf { it.isNotEmpty() }?.let { MessagePanel(UiMessage(it, true)) }
    }
}
