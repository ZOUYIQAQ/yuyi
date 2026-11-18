package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
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
import io.github.korenkrita.simulsub.ListeningMode

/** 手机连续排列；平板左侧配置、右侧就绪与启动，各列保留独立滚动状态。 */
@Composable
internal fun LiveScreen(state: LiveUiState, actions: SimulSubActions, wide: Boolean) {
    val missingPermissions = state.permissions.filter { it.required && !it.granted }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("live-control-viewport")) {
        val horizontalControls = wide || maxHeight < 400.dp
        // 两种窗口复用同一份配置条目，平板放左列，手机接在就绪卡之后。
        val configuration: LazyListScope.() -> Unit = {
            item("services") {
                ServiceSelectionCard(state.listeningServiceId, state.listeningServices.serviceOptions(),
                    state.translationServiceId, state.translationServices.serviceOptions(), state.listeningMode,
                    !state.running && !state.busy, actions, shortcuts = true)
            }
            if (state.listeningMode != ListeningMode.ONLINE) item("configuration") { LiveSceneOptions(state, actions) }
            item("final-only") { LiveFinalOnlyControl(state, actions) }
        }
        PageColumns(wide, first = {
            if (wide) {
                configuration()
            } else {
                if (missingPermissions.isNotEmpty()) item("permissions") {
                    LivePermissionWarning(missingPermissions, !state.busy, actions)
                }
                item("languages") { LiveLanguagePickers(state, actions) }
                item("session") { LiveControlPanel(state, actions, horizontalControls) }
            }
        }, second = {
            if (wide) {
                if (missingPermissions.isNotEmpty()) item("permissions") {
                    LivePermissionWarning(missingPermissions, !state.busy, actions)
                }
                item("languages") { LiveLanguagePickers(state, actions) }
                item("session") { LiveControlPanel(state, actions, horizontalControls) }
            } else configuration()
        })
    }
}

/** 语言功能沿用原有回调，常规手机并排展示，窄屏或大字号改为逐行排列。 */
@Composable
private fun LiveLanguagePickers(state: LiveUiState, actions: SimulSubActions) {
    val editable = !state.busy && (state.listeningMode == ListeningMode.LOCAL || !state.running)
    Column(Modifier.testTag("live-language-controls"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 280.dp && LocalDensity.current.fontScale < 1.5f) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) { OptionPicker("原声语言", state.sourceId, state.sourceOptions, editable, onSelect = actions.onSourceLanguage) }
                    Box(Modifier.weight(1f)) { OptionPicker("字幕语言", state.targetId, state.targetOptions, editable, onSelect = actions.onTargetLanguage) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OptionPicker("原声语言", state.sourceId, state.sourceOptions, editable, onSelect = actions.onSourceLanguage)
                    OptionPicker("字幕语言", state.targetId, state.targetOptions, editable, onSelect = actions.onTargetLanguage)
                }
            }
        }
    }
}

/** 状态、主按钮和计价共享暖色卡片，宽窗口并排呈现，窄屏和大字号由内容决定高度。 */
@Composable
private fun LiveControlPanel(state: LiveUiState, actions: SimulSubActions, horizontalControls: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("live-control-panel"), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
            if (horizontalControls && maxWidth >= 480.dp && LocalDensity.current.fontScale < 1.5f) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiveSessionStatus(state, Modifier.weight(1f))
                    Box(Modifier.width(220.dp)) { LivePrimaryAction(state, actions) }
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    if (horizontalControls || LocalDensity.current.fontScale >= 1.5f) {
                        LivePrimaryAction(state, actions)
                        LiveSessionStatus(state, Modifier.fillMaxWidth())
                    } else {
                        LiveSessionStatus(state, Modifier.fillMaxWidth())
                        LivePrimaryAction(state, actions)
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f))
        UsageCostSummary(state.costs, state.running, actions.onOpenCosts, embedded = true)
    }
}

/** 会话状态只呈现一次，计时与忙碌进度直接来自业务状态，窄栏允许完整换行。 */
@Composable
private fun LiveSessionStatus(state: LiveUiState, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(state.statusTitle, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
        if (state.statusDetail.isNotEmpty()) Text(state.statusDetail, style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current.copy(alpha = 0.85f))
        if (state.elapsed.isNotEmpty()) Text("本次时长：${state.elapsed}", style = MaterialTheme.typography.labelLarge)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/** 场景和进阶语言位于服务选择之后、定稿开关之前，配置锁定条件与原动作保持一致。 */
@Composable
private fun LiveSceneOptions(state: LiveUiState, actions: SimulSubActions) {
    val editable = !state.busy && !(state.running && state.listeningMode == ListeningMode.CLOUD_ASR)
    var customLanguageOpen by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.testTag("live-scene-controls"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.running) SupportingText(if (state.listeningMode == ListeningMode.CLOUD_ASR)
            "在线听写进行中，模式、听写配置、源语言、目标语言与场景已锁定。" else "语言与场景调整将用于后续句子。")
        if (state.listeningMode == ListeningMode.CLOUD_ASR) SupportingText("内容场景同时用于在线听写背景与文字翻译。")
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
    }
}

/** 定稿开关始终位于首页末尾，直译隐藏场景后仍保留原来的可编辑条件。 */
@Composable
private fun LiveFinalOnlyControl(state: LiveUiState, actions: SimulSubActions) {
    val editable = !state.busy && !(state.running && state.listeningMode == ListeningMode.CLOUD_ASR)
    Column(Modifier.testTag("live-final-only"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.running && state.listeningMode == ListeningMode.ONLINE) {
            SupportingText("在线听译进行中，模式、连接配置与语言已锁定。")
        }
        ToggleSetting("仅显示定稿", "开启后隐藏临时字幕，只显示定稿内容。", state.finalOnly, editable, actions.onFinalOnly)
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
