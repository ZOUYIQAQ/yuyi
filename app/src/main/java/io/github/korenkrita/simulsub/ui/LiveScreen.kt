package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.layout.*
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Security
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading

/** 同传页把会话主操作、语言配置与字幕分开编排，所有动作仍交回控制器。 */
@Composable
internal fun LiveScreen(state: LiveUiState, actions: SimulSubActions, wide: Boolean) {
    val editable = !state.busy
    var customLanguageOpen by rememberSaveable { mutableStateOf(false) }
    val missingPermission = state.permissions.firstOrNull { it.required && !it.granted }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
    PageColumns(wide, first = {
        if (missingPermission != null) item("permissions") {
            LivePermissionWarning(state.permissions.filter { it.required && !it.granted }, !state.busy, actions)
        }
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
                    TextField(
                        shape = TextFieldDefaults.shape,
                        colors = TextFieldDefaults.colors(),
                        value = state.customTarget, onValueChange = actions.onCustomTargetChange, enabled = editable,
                        label = { Text("自定义字幕语言") }, supportingText = { Text("输入语言名称后应用。") }, modifier = Modifier.fillMaxWidth(),
                    )
                    FilledTonalButton(onClick = actions.onApplyCustomTarget, enabled = editable && state.customTarget.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("应用自定义语言") }
                }
                OptionPicker("内容场景", state.sceneId, state.sceneOptions, editable, actions.onSelectScene)
                TextButton(onClick = { actions.onNavigate(UiDestination.SCENES) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("管理场景与术语") }
                ToggleSetting("仅显示定稿", "关闭后显示实时修正的草稿。", state.finalOnly, !state.busy, actions.onFinalOnly)
            }
        }
    }, second = {
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
    })
        }
        LivePrimaryAction(state, actions)
    }
}

/** 必需授权在滚动内容最前使用主题强调底色，明暗主题均沿用配对内容色。 */
@Composable
private fun LivePermissionWarning(permissions: List<PermissionUiState>, enabled: Boolean, actions: SimulSubActions) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("live-permission-warning"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Security, contentDescription = null)
                Text("需要系统权限", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
            }
            Text("允许以下必需权限后即可开始。", style = MaterialTheme.typography.bodyMedium)
            permissions.forEach { permission ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(permission.title, style = MaterialTheme.typography.titleSmall)
                    Text(permission.description, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { actions.onPermission(permission.id) }, enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("去允许") }
                }
            }
            TextButton(onClick = { actions.onPermission("all") }, enabled = enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                modifier = Modifier.heightIn(min = 48.dp)) { Text("查看全部权限") }
        }
    }
}

/** 服务名称可完整换行，入口为大字号保留足够宽度与至少48dp触控高度。 */
@Composable
private fun LiveServiceRow(title: String, value: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = onClick, modifier = Modifier.widthIn(max = 160.dp).heightIn(min = 48.dp)) { Text(action) }
    }
}
