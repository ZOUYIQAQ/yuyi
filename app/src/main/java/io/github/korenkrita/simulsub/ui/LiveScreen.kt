package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity

/** 同传页把会话主操作、语言配置与字幕分开编排，所有动作仍交回控制器。 */
@Composable
internal fun LiveScreen(state: LiveUiState, actions: SimulSubActions, wide: Boolean) {
    val editable = !state.busy
    var customLanguageOpen by rememberSaveable { mutableStateOf(false) }
    var showGrantedPermissions by rememberSaveable { mutableStateOf(false) }
    val missingPermission = state.permissions.firstOrNull { it.required && !it.granted }
    val primaryLabel = when {
        state.running -> "停止同传"
        state.modelLabel == "未启用模型" -> "选择识别模型"
        state.translationLabel == "未配置" -> "配置翻译服务"
        missingPermission != null -> "开启${missingPermission.title}"
        else -> "开始同传"
    }
    val primaryAction: () -> Unit = when {
        state.running -> actions.onStop
        state.modelLabel == "未启用模型" -> ({ actions.onNavigate(UiDestination.MODELS) })
        state.translationLabel == "未配置" -> ({ actions.onNavigate(UiDestination.SETTINGS) })
        missingPermission != null -> ({ actions.onPermission(missingPermission.id) })
        else -> actions.onStart
    }
    PageColumns(wide, first = {
        item("session") {
            Card(
                modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(state.statusTitle, style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    SupportingText(state.statusDetail)
                    if (state.elapsed.isNotEmpty()) Text("本次时长：${state.elapsed}", style = MaterialTheme.typography.labelLarge)
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Button(
                        onClick = primaryAction, enabled = !state.busy, shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Icon(if (state.running) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.busy) "正在处理…" else primaryLabel)
                    }
                }
            }
        }
        item("configuration") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeading("声音与译文", if (state.running) "语言与场景调整将用于后续句子。" else "选择原声、字幕语言与内容场景。")
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth >= 340.dp && LocalDensity.current.fontScale < 1.5f) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.weight(1f)) { OptionPicker("原声语言", state.sourceId, state.sourceOptions, editable, actions.onSourceLanguage) }
                            Box(Modifier.weight(1f)) { OptionPicker("字幕语言", state.targetId, state.targetOptions, editable, actions.onTargetLanguage) }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OptionPicker("原声语言", state.sourceId, state.sourceOptions, editable, actions.onSourceLanguage)
                            OptionPicker("字幕语言", state.targetId, state.targetOptions, editable, actions.onTargetLanguage)
                        }
                    }
                }
                TextButton(onClick = { customLanguageOpen = !customLanguageOpen }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (customLanguageOpen) "收起其他语言" else "使用其他字幕语言")
                }
                if (customLanguageOpen) {
                    OutlinedTextField(
                        value = state.customTarget, onValueChange = actions.onCustomTargetChange, enabled = editable,
                        label = { Text("自定义字幕语言") }, supportingText = { Text("输入语言名称后应用。") }, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = actions.onApplyCustomTarget, enabled = editable && state.customTarget.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("应用自定义语言") }
                }
                OptionPicker("内容场景", state.sceneId, state.sceneOptions, editable, actions.onSelectScene)
                TextButton(onClick = { actions.onNavigate(UiDestination.SCENES) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("管理场景与术语") }
                ToggleSetting("仅显示定稿", "关闭后显示实时修正的草稿。", state.finalOnly, !state.busy, actions.onFinalOnly)
            }
        }
        item("services") {
            Card(
                modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionHeading("当前服务")
                    LiveServiceRow("识别模型", state.modelLabel, "模型库") { actions.onNavigate(UiDestination.MODELS) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    LiveServiceRow("翻译服务", state.translationLabel, "翻译设置") { actions.onNavigate(UiDestination.SETTINGS) }
                }
            }
        }
        if (state.permissions.isNotEmpty()) item("permissions") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeading("系统权限")
                SupportingText(if (missingPermission == null) "必要权限已就绪" else "${state.permissions.count { it.required && !it.granted }} 项必要权限待允许")
                TextButton(onClick = { showGrantedPermissions = !showGrantedPermissions }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (showGrantedPermissions) "收起已允许的权限" else "查看全部权限")
                }
                state.permissions.filter { !it.granted || showGrantedPermissions }.forEach { permission ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(permission.title, style = MaterialTheme.typography.titleSmall)
                        SupportingText(permission.description)
                        if (permission.granted) {
                            SupportingText("已允许${if (permission.required) " · 必需" else " · 可选"}")
                        } else {
                            OutlinedButton(onClick = { actions.onPermission(permission.id) }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(if (permission.required) "去允许" else "允许可选权限")
                            }
                        }
                    }
                }
            }
        }
    }, second = {
        item("captions-title") { SectionHeading("本次字幕", "本次会话的原文与译文") }
        if (state.captions.isEmpty()) {
            item("captions-empty") {
                Surface(
                    modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (state.running) "等待声音" else "字幕将在这里显示", style = MaterialTheme.typography.titleMedium)
                        SupportingText(if (state.running) "部分应用可能限制内部音频采集。" else "开始同传后，可在这里查看和选取字幕。")
                    }
                }
            }
        } else {
            items(state.captions, key = { "caption:${it.id}" }) { caption ->
                SelectionContainer {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (caption.isDraft) "实时草稿" else "定稿", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        if (caption.original.isNotEmpty()) SupportingText(caption.original)
                        if (caption.translated.isNotEmpty()) Text(caption.translated, style = MaterialTheme.typography.titleMedium)
                        if (caption.detail.isNotEmpty()) SupportingText(caption.detail)
                        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    })
}

/** 服务名称可完整换行，次要入口单独保持至少 48dp 的触控高度。 */
@Composable
private fun LiveServiceRow(title: String, value: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = onClick, modifier = Modifier.widthIn(max = 120.dp).heightIn(min = 48.dp)) { Text(action) }
    }
}
