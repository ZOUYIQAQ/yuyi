package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.korenkrita.simulsub.ListeningMode
import io.github.korenkrita.simulsub.PlatformCredentials
import io.github.korenkrita.simulsub.online.LiveTranslateModels
import io.github.korenkrita.simulsub.online.CloudAsrConfig

/** 三条路径使用不同名称，避免将在线听写与直接听译混为一谈。 */
internal fun listeningModeLabel(mode: ListeningMode): String = when (mode) {
    ListeningMode.LOCAL -> "本地识别"
    ListeningMode.ONLINE -> "千问语音直译"
    ListeningMode.CLOUD_ASR -> "千问在线听写"
}

/** 简述每条路径的识别位置和翻译依赖。 */
private fun listeningModeDescription(mode: ListeningMode): String = when (mode) {
    ListeningMode.LOCAL -> "第一步在设备上识别声音；第二步将文字交给翻译服务。需下载识别模型。"
    ListeningMode.ONLINE -> "使用千问 LiveTranslate，由语音直接得到译文，无需下载模型或另配翻译服务。"
    ListeningMode.CLOUD_ASR -> "第一步由千问在线听写；第二步将文字交给翻译服务（推荐 DeepSeek）。无需下载模型。"
}

/** 听写表单复用在线表单视图，但所有读写和帮助动作均指向听写自己的控制器入口。 */
internal fun cloudAsrFormActions(actions: SimulSubActions) = actions.copy(
    onOnlineDraftChange = actions.onCloudAsrDraftChange,
    onSaveOnline = actions.onSaveCloudAsr,
    onTestOnline = actions.onTestCloudAsr,
    onCancelOnlineTest = actions.onCancelCloudAsrTest,
    onDiscardOnlineDraft = actions.onDiscardCloudAsrDraft,
    onOpenOnlineHelp = actions.onOpenCloudAsrHelp,
)

/** 旧版模式选择仅保留给兼容测试，生产页面统一使用服务下拉。 */
@Composable
internal fun ListeningModePicker(mode: ListeningMode, enabled: Boolean, onSelect: (ListeningMode) -> Unit,
    compact: Boolean = false) {
    if (compact) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ListeningMode.LOCAL, ListeningMode.CLOUD_ASR, ListeningMode.ONLINE).forEach { option ->
                FilterChip(
                    selected = mode == option, onClick = { if (mode != option) onSelect(option) },
                    enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("listening-mode-${option.name}"),
                    label = { Text(listeningModeLabel(option)) },
                    leadingIcon = { Icon(if (option == ListeningMode.LOCAL) Icons.Outlined.RecordVoiceOver else Icons.Outlined.Cloud,
                        null, Modifier.size(18.dp)) },
                )
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(ListeningMode.LOCAL, ListeningMode.CLOUD_ASR, ListeningMode.ONLINE).forEach { option ->
                val selected = mode == option
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth().testTag("listening-mode-${option.name}")
                        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton,
                            onClick = { if (mode != option) onSelect(option) }),
                    border = BorderStroke(if (selected) 2.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.outlinedCardColors(containerColor = if (selected)
                        MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
                ) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (option == ListeningMode.LOCAL) Icons.Outlined.RecordVoiceOver else Icons.Outlined.Cloud, null)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(listeningModeLabel(option), style = MaterialTheme.typography.titleMedium)
                            Text(listeningModeDescription(option),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        RadioButton(selected = selected, onClick = null, enabled = enabled)
                    }
                }
            }
        }
    }
}

/** 兼容旧控制器时也只保留真实服务，管理和模型资源使用独立入口。 */
internal fun List<UiOption>.serviceOptions(): List<UiOption> = filterNot { it.id == "catalog" || it.id == "manage" }

/** 两步服务合为一张平整卡片，说明收进行内，复用选择器菜单并保留空状态与原动作。 */
@Composable
internal fun ServiceSelectionCard(listeningServiceId: String, listeningServices: List<UiOption>,
    translationServiceId: String, translationServices: List<UiOption>, mode: ListeningMode,
    enabled: Boolean, actions: SimulSubActions, shortcuts: Boolean = false) {
    val listeningOptions = listeningServices.serviceOptions()
    val translationOptions = translationServices.serviceOptions()
    val selectedListening = listeningOptions.firstOrNull { it.id == listeningServiceId }
    val selectedTranslation = translationOptions.firstOrNull { it.id == translationServiceId }
    Card(Modifier.fillMaxWidth().testTag("service-selection"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Column(Modifier.fillMaxWidth()) {
            if (listeningOptions.isEmpty()) {
                Column(Modifier.fillMaxWidth().testTag("listening-services-empty").padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("听写服务尚未就绪", style = MaterialTheme.typography.titleSmall)
                    SupportingText("软件已提供本地模型与千问接口，请检查模型下载或平台密钥。")
                    FilledTonalButton(onClick = actions.onOpenListeningSettings, enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("查看听写服务") }
                }
            } else Box(Modifier.fillMaxWidth().testTag("listening-service-picker")) {
                OptionPicker("① 听写服务", selectedListening?.id ?: "请选择听写服务", listeningOptions, enabled,
                    embedded = true,
                    supportingText = if (selectedListening == null) "当前听写服务不可用，请重新选择。"
                        else selectedListening.description,
                    onSelect = actions.onSelectListeningService)
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            if (mode == ListeningMode.ONLINE) {
                ListItem(
                    headlineContent = { Text("② 已包含翻译", color = MaterialTheme.colorScheme.primary) },
                    supportingContent = { Text("当前听译服务直接生成译文，无需另配翻译服务。", style = MaterialTheme.typography.bodySmall) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("translation-included"),
                )
            } else if (translationOptions.isEmpty()) {
                Column(Modifier.fillMaxWidth().testTag("translation-services-empty").padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("翻译服务尚未就绪", style = MaterialTheme.typography.titleSmall)
                    SupportingText("软件已提供 DeepSeek 与千问接口，请填写对应平台密钥。")
                    FilledTonalButton(onClick = actions.onOpenApiSettings, enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("查看翻译服务") }
                }
            } else Box(Modifier.fillMaxWidth().testTag("translation-service-picker")) {
                OptionPicker("② 翻译服务", selectedTranslation?.id ?: "请选择翻译服务", translationOptions, enabled,
                    embedded = true,
                    supportingText = if (selectedTranslation == null) "当前翻译服务不可用，请重新选择。"
                        else selectedTranslation.description,
                    onSelect = actions.onSelectTranslationService)
            }
            // 管理区只保留可用入口；外侧 4dp 加文字按钮内缩 12dp，与行内文字统一为 16dp。
            if (shortcuts && (listeningOptions.isNotEmpty() ||
                    (mode != ListeningMode.ONLINE && translationOptions.isNotEmpty()))) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
                    ActionRow {
                        if (listeningOptions.isNotEmpty()) TextButton(onClick = actions.onOpenListeningSettings,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("管理听写") }
                        if (mode != ListeningMode.ONLINE && translationOptions.isNotEmpty()) TextButton(onClick = actions.onOpenApiSettings,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("管理翻译") }
                    }
                }
            }
        }
    }
}

/** 听写根页只显示调用服务列表，进入编辑后展示技术类型、模型和连接草稿。 */
@Composable
internal fun ListeningSettings(state: SettingsUiState, sessionBusy: Boolean, actions: SimulSubActions, wide: Boolean,
    models: ModelsUiState = ModelsUiState()) {
    val editor = state.listeningEditor
    if (editor == null) {
        ListeningServiceManagement(state, sessionBusy, actions, wide, models)
        return
    }
    val asr = editor.mode == ListeningMode.CLOUD_ASR
    val configuration = if (asr) state.cloudAsr else state.online
    val editorActions = (if (asr) cloudAsrFormActions(actions) else actions).copy(onSaveOnline = actions.onSaveListeningService)
    val locked = sessionBusy || state.api.testing || state.api.saving ||
        (if (asr) state.online.testing else state.cloudAsr.testing)
    PageColumns(wide, first = {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeading(if (asr) "千问在线听写接口" else
                    if (LiveTranslateModels.find(configuration.model)?.realtime == false) "千问分段听译接口" else "千问实时听译接口",
                    configuration.model)
                OnlineConfigurationIntro(editorActions, editor.mode)
            }
        }
    }, second = {
        item("listening-editor-${editor.id}") {
            OnlineConfigurationForm(configuration, locked, editorActions, editor.mode,
                state.platformKeys.firstOrNull { it.id == PlatformCredentials.forUrl(configuration.endpoint)?.id },
                saveLabel = "保存听写服务")
        }
    })
}

/** 引导复用设置表单，调用方传入各自独立的草稿与动作。 */
@Composable
internal fun OnlineListeningSettings(online: OnlineListeningUiState, sessionBusy: Boolean, actions: SimulSubActions, wide: Boolean,
    mode: ListeningMode = ListeningMode.ONLINE, platformKeys: List<PlatformKeyUiState> = emptyList()) {
    PageColumns(wide, first = { item { OnlineConfigurationIntro(actions, mode) } },
        second = { item { OnlineConfigurationForm(online, sessionBusy, actions, mode, platformKeys.firstOrNull { it.id == PlatformCredentials.forUrl(online.endpoint)?.id }) } })
}

/** 说明音频与文本去向、接入能力和配置来源。 */
@Composable
private fun OnlineConfigurationIntro(actions: SimulSubActions, mode: ListeningMode) {
    val asr = mode == ListeningMode.CLOUD_ASR
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeading(if (asr) "千问 3.1 Streaming 在线听写" else "千问 LiveTranslate",
            if (asr) "音频交给千问听写，文本交给第二步的文字翻译服务。" else "将播放音频直接翻为字幕，无需下载模型或另配翻译服务。")
        SupportingText(if (asr) "连接设置用于已有在线听写服务。" else "连接设置用于已有千问听译服务。")
        TextButton(onClick = actions.onOpenOnlineHelp, modifier = Modifier.heightIn(min = 48.dp)
            .testTag(if (asr) "cloud-asr-help" else "online-help")) { Text("查看官方配置说明") }
    }
}

/** 测试跟随编辑路由，滚动与懒加载不会取消连接测试。 */
@Composable
internal fun OnlineProbeRouteLifetime(active: Boolean, onCancel: () -> Unit) {
    val cancelTest by rememberUpdatedState(onCancel)
    DisposableEffect(active) {
        onDispose { if (active) cancelTest() }
    }
}

/** 两套连接分别派发各自动作；千问密钥只读取中央平台状态，不再重复输入。 */
@Composable
private fun OnlineConfigurationForm(online: OnlineListeningUiState, sessionBusy: Boolean, actions: SimulSubActions,
    mode: ListeningMode, platform: PlatformKeyUiState?, saveLabel: String? = null, canSave: Boolean = true) {
    val asr = mode == ListeningMode.CLOUD_ASR
    val editable = !sessionBusy && !online.testing
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (asr) "在线听写连接信息" else "直接在线听译连接信息", style = MaterialTheme.typography.titleMedium)
            TextField(value = online.endpoint,
                onValueChange = { actions.onOnlineDraftChange(online.copy(endpoint = it)) },
                label = { Text("连接地址") }, enabled = editable, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().testTag(if (asr) "cloud-asr-endpoint" else "online-endpoint"))
            Column(Modifier.fillMaxWidth().testTag(if (asr) "cloud-asr-model" else "online-model"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionPicker(title = if (asr) "听写模型" else "听译模型", selectedId = online.model, enabled = editable,
                    options = if (asr) listOf(UiOption(CloudAsrConfig.DEFAULT_MODEL, "千问 3.1 · Streaming 听写"))
                        else LiveTranslateModels.choices(online.model).map { UiOption(it.id, it.label, it.description) }) { model ->
                    focus.clearFocus(); keyboard?.hide()
                    actions.onOnlineDraftChange(online.copy(model = model, endpoint = if (asr) online.endpoint else
                        LiveTranslateModels.endpointAfterSelection(online.endpoint, online.model, model)))
                }
                SupportingText(if (asr) "仅建立在线听写会话，翻译由独立文字 API 完成。" else
                    LiveTranslateModels.find(online.model)?.description ?: "当前模型未接入，请从下拉列表重新选择。")
                if (!asr) SupportingText("费用为北京地域公开原价估算，不含译文输出；实际按服务商用量计费。")
            }
            ServiceCredentialPicker(
                platformId = PlatformCredentials.forUrl(online.endpoint)?.id.orEmpty(),
                serviceId = "audio:${mode.name}",
                options = online.credentialOptions,
                selectedId = online.credentialId,
                selectedLabel = online.credentialLabel,
                enabled = editable,
                actions = actions,
            )
            if (online.testing) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(when {
                online.hasUnsavedChanges -> "保存状态：有未保存的修改"
                online.hasSavedConfiguration -> "保存状态：已保存"
                else -> "保存状态：未保存"
            }, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(if (asr) "cloud-asr-save-status" else "online-save-status"))
            online.message?.let {
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(if (asr) "cloud-asr-result" else "online-result")) { MessagePanel(it) }
            }
            ActionRow {
                Button(onClick = { focus.clearFocus(); keyboard?.hide(); actions.onSaveOnline() }, enabled = editable && canSave,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(saveLabel ?: if (asr) "保存听写配置" else "保存在线配置") }
                FilledTonalButton(onClick = { focus.clearFocus(); keyboard?.hide(); actions.onTestOnline() }, enabled = editable,
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (online.testing) "正在测试…" else if (asr) "测试听写连接" else "测试在线连接")
                }
                if (online.testing) TextButton(onClick = actions.onCancelOnlineTest,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("取消连接测试") }
            }
            SupportingText("测试只验证当前连接与配置，不会录音或发送播放音频，也不会自动保存。仅点击开始同传后才发送音频。")
            if (sessionBusy) SupportingText("同传或另一连接测试进行中，配置暂时锁定。停止后可修改。")
        }
    }
}

/** 引导和根导航共用离开确认，继续编辑不会触发保存或恢复动作。 */
@Composable
internal fun UnsavedConfigurationDialog(onDiscard: () -> Unit, onContinueEditing: () -> Unit) {
    AlertDialog(onDismissRequest = onContinueEditing,
        title = { Text("配置尚未保存") },
        text = { Text("离开将放弃本次修改，是否继续编辑？") },
        confirmButton = { TextButton(onClick = onDiscard) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = onContinueEditing) { Text("继续编辑") } })
}
