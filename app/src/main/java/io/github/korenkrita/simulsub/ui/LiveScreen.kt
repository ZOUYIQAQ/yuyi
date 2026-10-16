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
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading

/** 手机把控制舱放在语言之后的首屏中部，平板拆为左侧配置与右侧控制，全部内容可滚动。 */
@Composable
internal fun LiveScreen(state: LiveUiState, actions: SimulSubActions, wide: Boolean) {
    val missingPermissions = state.permissions.filter { it.required && !it.granted }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("live-control-viewport")) {
        val twoColumns = wide && maxWidth >= 720.dp
        val controlsFirst = !twoColumns && (maxHeight < 520.dp || LocalDensity.current.fontScale >= 1.5f)
        val shortWindow = maxHeight < 400.dp
        PageColumns(twoColumns, first = {
            if (missingPermissions.isNotEmpty()) item("permissions") {
                LivePermissionWarning(missingPermissions, !state.busy, actions)
            }
            if (controlsFirst) item("session") { LiveControlPanel(state, actions, shortWindow) }
            item("languages") { LiveLanguagePickers(state, actions) }
            if (!twoColumns && !controlsFirst) item("session") { LiveControlPanel(state, actions, shortWindow) }
            item("configuration") { LiveSceneOptions(state, actions) }
        }, second = {
            if (twoColumns) item("session") { LiveControlPanel(state, actions, shortWindow) }
            item("services") { LiveServices(state, actions) }
        })
    }
}

/** 语言选择优先展示，常规手机并排节省首屏高度，窄屏或大字号改为逐行排列。 */
@Composable
private fun LiveLanguagePickers(state: LiveUiState, actions: SimulSubActions) {
    Column(Modifier.testTag("live-language-controls"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeading("声音与译文")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 280.dp && LocalDensity.current.fontScale < 1.5f) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) { OptionPicker("原声语言", state.sourceId, state.sourceOptions, !state.busy, onSelect = actions.onSourceLanguage) }
                    Box(Modifier.weight(1f)) { OptionPicker("字幕语言", state.targetId, state.targetOptions, !state.busy, onSelect = actions.onTargetLanguage) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OptionPicker("原声语言", state.sourceId, state.sourceOptions, !state.busy, onSelect = actions.onSourceLanguage)
                    OptionPicker("字幕语言", state.targetId, state.targetOptions, !state.busy, onSelect = actions.onTargetLanguage)
                }
            }
        }
    }
}

/** 暖色控制卡承载状态和横向主按钮，内容决定高度，手机和平板沿用一致的视觉层级。 */
@Composable
private fun LiveControlPanel(state: LiveUiState, actions: SimulSubActions, shortWindow: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("live-control-panel"), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(20.dp)) {
            if (shortWindow && maxWidth >= 480.dp && LocalDensity.current.fontScale < 1.5f) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiveSessionStatus(state, Modifier.weight(1f))
                    Box(Modifier.width(220.dp)) { LivePrimaryAction(state, actions) }
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    LiveSessionStatus(state, Modifier.fillMaxWidth())
                    LivePrimaryAction(state, actions)
                }
            }
        }
    }
}

/** 会话状态只呈现一次，计时与忙碌进度直接来自业务状态，窄栏允许完整换行。 */
@Composable
private fun LiveSessionStatus(state: LiveUiState, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(state.statusTitle, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
        if (state.statusDetail.isNotEmpty()) SupportingText(state.statusDetail)
        if (state.elapsed.isNotEmpty()) Text("本次时长：${state.elapsed}", style = MaterialTheme.typography.labelLarge)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/** 场景与进阶语言入口位于主操作之后，平板集中到左栏，所有配置继续交由原动作处理。 */
@Composable
private fun LiveSceneOptions(state: LiveUiState, actions: SimulSubActions) {
    val editable = !state.busy
    var customLanguageOpen by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.running) SupportingText("语言与场景调整将用于后续句子。")
        OptionPicker("内容场景", state.sceneId, state.sceneOptions, editable, onSelect = actions.onSelectScene)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { actions.onNavigate(UiDestination.SCENES) }, enabled = editable,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("场景与术语")
            }
            OutlinedButton(onClick = { customLanguageOpen = !customLanguageOpen }, enabled = editable,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Translate, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (customLanguageOpen) "收起其他语言" else "其他字幕语言")
            }
        }
        AnimatedVisibility(customLanguageOpen) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(
                    shape = TextFieldDefaults.shape,
                    colors = TextFieldDefaults.colors(),
                    value = state.customTarget, onValueChange = actions.onCustomTargetChange, enabled = editable,
                    label = { Text("自定义字幕语言") }, supportingText = { Text("输入语言名称后应用。") }, modifier = Modifier.fillMaxWidth(),
                )
                FilledTonalButton(onClick = actions.onApplyCustomTarget, enabled = editable && state.customTarget.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("应用自定义语言") }
            }
        }
        ToggleSetting("仅显示定稿", "关闭后显示实时修正的草稿。", state.finalOnly, editable, actions.onFinalOnly)
    }
}

/** 服务摘要仅出现一次，平板归入控制面板下方，保留模型与翻译设置直达入口。 */
@Composable
private fun LiveServices(state: LiveUiState, actions: SimulSubActions) {
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

/** 必需授权在滚动内容最前使用主题强调底色，明暗主题均沿用配对内容色。 */
@Composable
private fun LivePermissionWarning(permissions: List<PermissionUiState>, enabled: Boolean, actions: SimulSubActions) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("live-permission-warning"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary),
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
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onPrimary,
                            contentColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("去允许") }
                }
            }
            TextButton(onClick = { actions.onPermission("all") }, enabled = enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
                modifier = Modifier.heightIn(min = 48.dp)) { Text("查看全部权限") }
        }
    }
}

/** 服务名称可完整换行，入口为大字号保留足够宽度与至少48dp触控高度。 */
@Composable
private fun LiveServiceRow(title: String, value: String, action: String, onClick: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        if (maxWidth < 320.dp && LocalDensity.current.fontScale >= 1.5f) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveServiceSummary(title, value, Modifier.fillMaxWidth())
                TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LiveServiceSummary(title, value, Modifier.weight(1f))
                TextButton(onClick = onClick, modifier = Modifier.widthIn(max = 160.dp).heightIn(min = 48.dp)) { Text(action) }
            }
        }
    }
}

/** 窄栏大字号为服务名称保留整行，避免英文模型名被右侧入口挤成碎片。 */
@Composable
private fun LiveServiceSummary(title: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
    }
}
