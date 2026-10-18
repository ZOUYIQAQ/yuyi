package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.*
import io.github.korenkrita.simulsub.ListeningMode

/** 设置入口与详情使用独立页面，返回路径由顶栏和系统返回统一处理。 */
internal enum class SettingsSection(val title: String, val detail: String) {
    LISTENING("听写服务", "本地识别模型与云端听写、听译接口"),
    API("翻译服务", "文字翻译平台、模型与接口连接"),
    KEYS("平台密钥", "保存密钥与设置默认项"),
    SUBTITLE("字幕样式", "显示句数、文字和浮窗尺寸"),
    APPEARANCE("外观", "主题模式、配色与动态取色"),
    PERMISSIONS("系统权限", "录音、悬浮字幕与通知授权"),
    ADVANCED("高级设置", "上下文与在线辅助"),
    RECORDS("使用记录", "保存、分享与清理文字记录"),
    COSTS("用量与费用", "今日与会话估算、分类成本和未计价服务"),
    UPDATES("应用更新", "启动检测、版本提醒与手动检查"),
    ABOUT("关于", "Fork来源、原作者与许可")
}

/** 设置路由只包含页面与稳定服务标识；列表、各编辑器及密钥页不共享滚动位置。 */
private data class SettingsRoute(val section: SettingsSection?, val editorId: String? = null) {
    val scrollKey: String get() = "settings/${section?.name ?: "home"}/${editorId?.let { "editor/$it" } ?: "list"}"
    val depth: Int get() = if (section == null) 0 else if (editorId == null) 1 else 2
}

/** 设置首页以官方列表项和填充卡片分组，详情不再堆叠横向标签。 */
@Composable
internal fun SettingsScreen(state: SettingsUiState, sessionBusy: Boolean, actions: SimulSubActions, wide: Boolean,
    selected: SettingsSection? = null, finalOnly: Boolean = false, onSelect: (SettingsSection) -> Unit = {},
    translationEditorOpen: Boolean = false, onOpenTranslationEditor: () -> Unit = {},
    models: ModelsUiState = ModelsUiState()) {
    val fallbackScrollHolder = rememberSaveableStateHolder()
    val scrollHolder = LocalRouteScrollStateHolder.current ?: fallbackScrollHolder
    val route = SettingsRoute(selected, when (selected) {
        SettingsSection.LISTENING -> state.listeningEditor?.id
        SettingsSection.API -> if (translationEditorOpen) state.translationEditorId ?: state.api.profileId else null
        else -> null
    })
    androidx.compose.animation.AnimatedContent(
        targetState = route,
        transitionSpec = { routeTransition(forward = targetState.depth >= initialState.depth) },
        label = "设置页面切换",
    ) { page ->
        val rendered = rememberRouteSnapshot(page == route, state)
        val renderedModels = rememberRouteSnapshot(page == route, models)
        CompositionLocalProvider(LocalRouteScrollStateHolder provides scrollHolder) {
            RouteScrollStateProvider(page.scrollKey) {
                when (page.section) {
                    null -> SettingsHome(rendered.listeningMode, wide, actions, onSelect)
                    SettingsSection.LISTENING -> ListeningSettings(rendered, sessionBusy, actions, wide, renderedModels)
                    SettingsSection.API -> if (page.editorId != null) TranslationApiSettings(rendered.api, sessionBusy, actions, wide, null,
                        mode = rendered.listeningMode, platformKeys = rendered.platformKeys, showProfiles = false)
                    else TranslationServiceManagement(rendered, sessionBusy, actions, wide, onOpenTranslationEditor)
                    SettingsSection.KEYS -> PlatformKeysSettings(rendered.platformKeys,
                        sessionBusy || rendered.api.testing || rendered.api.saving || rendered.online.testing || rendered.cloudAsr.testing, actions, wide)
                    SettingsSection.SUBTITLE -> SubtitleSettings(rendered.subtitle, actions, wide, null, finalOnly)
                    SettingsSection.APPEARANCE -> AppearanceSettings(rendered, actions, wide, null)
                    SettingsSection.PERMISSIONS -> PermissionsSettings(rendered.permissions, actions, wide)
                    SettingsSection.ADVANCED -> AdvancedSettings(rendered.advanced, sessionBusy, actions, wide, null,
                        mode = rendered.listeningMode, platformKeys = rendered.platformKeys)
                    SettingsSection.RECORDS -> UsageRecords(rendered, actions, wide, null)
                    SettingsSection.COSTS -> UsageCostSettings(rendered.costs, wide)
                    SettingsSection.UPDATES -> UpdateSettings(rendered, actions, wide)
                    SettingsSection.ABOUT -> AboutSettings(rendered, actions, wide, null)
                }
            }
        }
    }
}

/** 设置入口保留简短说明，整行触控和官方水波纹可直接进入对应详情。 */
@Composable
private fun SettingsHome(mode: ListeningMode, wide: Boolean, actions: SimulSubActions, onSelect: (SettingsSection) -> Unit) {
    PageColumns(wide, first = {
        item("settings-services-heading") { SectionHeading("听写与翻译") }
        item("settings-services") {
            Box(Modifier.testTag("settings-subtitle-translation-group")) {
                SettingsCard(padding = 4.dp, spacing = 0.dp) {
                    SettingsEntry(SettingsSection.LISTENING, if (mode != ListeningMode.LOCAL) Icons.Outlined.Cloud else Icons.Outlined.RecordVoiceOver, onSelect)
                    SettingsEntry(SettingsSection.API, Icons.Outlined.Translate, onSelect)
                    SettingsEntry(SettingsSection.KEYS, Icons.Outlined.Key, onSelect)
                    SettingsLink("场景术语", "内容背景和专有名词译法", Icons.Outlined.Tune) { actions.onNavigate(UiDestination.SCENES) }
                    SettingsEntry(SettingsSection.ADVANCED, Icons.Outlined.AutoAwesome, onSelect)
                    SettingsEntry(SettingsSection.COSTS, Icons.Outlined.Payments, onSelect)
                }
            }
        }
        item("settings-personalization-heading") { SectionHeading("个性化") }
        item("settings-personalization") {
            SettingsCard(padding = 4.dp, spacing = 0.dp) {
                SettingsEntry(SettingsSection.SUBTITLE, Icons.Outlined.Subtitles, onSelect)
                SettingsEntry(SettingsSection.APPEARANCE, Icons.Outlined.Palette, onSelect)
            }
        }
    }, second = {
        item("settings-application-heading") { SectionHeading("应用") }
        item("settings-application") {
            Box(Modifier.testTag("settings-application-group")) {
                SettingsCard(padding = 4.dp, spacing = 0.dp) {
                    SettingsEntry(SettingsSection.PERMISSIONS, Icons.Outlined.Security, onSelect)
                    SettingsLink("首次配置引导", "重新检查使用前的必要配置", Icons.Outlined.AutoFixHigh, actions.onRestartOnboarding)
                    SettingsEntry(SettingsSection.RECORDS, Icons.Outlined.History, onSelect)
                    SettingsEntry(SettingsSection.UPDATES, Icons.Outlined.SystemUpdate, onSelect)
                    SettingsEntry(SettingsSection.ABOUT, Icons.Outlined.Info, onSelect)
                }
            }
        }
    })
}

/** 官方列表组件提供清晰的标题、图标与点击反馈，说明允许完整换行。 */
@Composable
private fun SettingsEntry(section: SettingsSection, icon: androidx.compose.ui.graphics.vector.ImageVector, onSelect: (SettingsSection) -> Unit) {
    ListItem(
        headlineContent = { Text(section.title) }, supportingContent = { Text(section.detail) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable { onSelect(section) }.heightIn(min = 76.dp),
    )
}

/** 先选服务再编辑连接信息，附加请求参数默认收起，保存与测试始终明确。 */
@Composable
internal fun TranslationApiSettings(api: TranslationApiUiState, sessionBusy: Boolean, actions: SimulSubActions, wide: Boolean, navigation: (@Composable () -> Unit)?, onboarding: Boolean = false,
    mode: ListeningMode = ListeningMode.LOCAL, platformKeys: List<PlatformKeyUiState> = emptyList(), showProfiles: Boolean = true) {
    val locked = sessionBusy && mode == ListeningMode.CLOUD_ASR
    val editable = !locked && !api.testing && !api.saving
    var advancedOpen by rememberSaveable { mutableStateOf(api.headers.isNotEmpty() || api.userAgent.isNotEmpty()) }
    PageColumns(wide, header = navigation, first = {
        if (!onboarding && showProfiles) item("api-profiles") {
            // 配置目录复用原有保存期间禁用机制；不改变真实保存状态或其他分区提示。
            TranslationProfiles(api.copy(saving = api.saving || locked), actions, sessionBusy)
        }
        item("api-intro") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionHeading("第二步 · 翻译", when (mode) {
                    ListeningMode.CLOUD_ASR -> "声音由千问在线听写，识别出的文字交给你选择的服务翻译。"
                    ListeningMode.LOCAL -> "声音在本机识别，识别出的文字交给你选择的服务翻译。"
                    ListeningMode.ONLINE -> "千问语音直译已包含翻译，无需另配翻译服务。这里的文字翻译设置保留，供本地识别或在线听写使用。"
                })
                OptionPicker("服务预设", api.presetId,
                    listOf(UiOption("custom", "自定义 / 手动填写", "保留当前表单，所有字段自行填写。")) + TranslationPresets.entries.map { UiOption(it.id, it.title, it.detail) },
                    enabled = editable, onSelect = actions.onApplyApiPreset)
                SupportingText("预设只帮助填写。地址和模型都可继续修改，保存后才生效。")
                if (locked) SupportingText("同传进行中，请先停止，再修改或测试翻译配置。")
                api.message?.let { MessagePanel(it) }
            }
        }
    }, second = {
        item("api-form") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("连接信息", style = MaterialTheme.typography.titleMedium)
                    TextField(
                        shape = TextFieldDefaults.shape,
                        colors = TextFieldDefaults.colors(),value = api.url, onValueChange = { actions.onApiDraftChange(api.copy(url = it, presetId = TranslationPresets.match(it))) }, label = { Text("服务地址") }, supportingText = { Text("HTTP 或 HTTPS 基础地址，也可填写完整聊天接口地址。") }, enabled = editable, modifier = Modifier.fillMaxWidth())
                    EditableModelPicker(
                        value = api.model,
                        options = TranslationPresets.modelsFor(api.url, api.model),
                        enabled = editable,
                        onValueChange = { actions.onApiDraftChange(api.copy(model = it)) },
                    )
                    io.github.korenkrita.simulsub.ByokTranslator.cleartextWarning(api.url)?.let { SupportingText(it) }
                    ServiceCredentialPicker(api.platformId, "translation:${api.profileId}",
                        api.credentialOptions, api.credentialId, api.credentialLabel, editable, actions)
                    if (api.testing || api.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(when {
                        api.hasUnsavedChanges -> "保存状态：有未保存的修改"
                        api.hasSavedConfiguration -> "保存状态：已保存"
                        else -> "保存状态：未保存"
                    }, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    ActionRow {
                        Button(onClick = actions.onSaveApi, enabled = editable, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (api.saving) "正在保存…" else "保存翻译配置") }
                        FilledTonalButton(onClick = actions.onTestApi, enabled = editable && api.canTest && !sessionBusy, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (api.testing) "正在测试…" else "测试连接") }
                    }
                    SupportingText("测试只发送当前草稿，不自动保存。服务商可能计费；不点击替换就保持原配置。")
                    if (api.suggestedUrl.isNotBlank()) {
                        HorizontalDivider()
                        Text("发现可用地址，是否替换？", style = MaterialTheme.typography.titleMedium)
                        SupportingText(api.suggestedUrl)
                        SupportingText("仅点击替换才保存；不操作就保留原地址。已测试的模型、密钥与请求参数会保留。")
                        ActionRow {
                            Button(onClick = actions.onApplyTestedApiUrl, enabled = editable && !sessionBusy,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("替换并保存") }
                            TextButton(onClick = actions.onKeepApiUrl, enabled = editable,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("保持原地址") }
                        }
                    }
                }
            }
        }
        if (!onboarding) item("api-advanced-toggle") {
            TextButton(onClick = { advancedOpen = !advancedOpen }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (advancedOpen) "收起附加请求参数" else "附加请求参数（可选）")
            }
        }
        if (advancedOpen && !onboarding) {
            item("api-advanced") {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SectionHeading("附加请求参数", "一般无需填写。修改后需再次保存翻译配置。")
                    if (api.resolvedUrl.isNotEmpty()) SupportingText("实际请求地址：${api.resolvedUrl}")
                    TextField(
                        shape = TextFieldDefaults.shape,
                        colors = TextFieldDefaults.colors(),value = api.userAgent, onValueChange = { actions.onApiDraftChange(api.copy(userAgent = it)) }, label = { Text("User-Agent（可选）") }, enabled = editable, modifier = Modifier.fillMaxWidth())
                }
            }
            items(api.headers, key = { "header:${it.id}" }) { header ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        shape = TextFieldDefaults.shape,
                        colors = TextFieldDefaults.colors(),value = header.name, onValueChange = { value -> actions.onApiDraftChange(api.copy(headers = api.headers.map { if (it.id == header.id) it.copy(name = value) else it })) }, label = { Text("请求头名称") }, enabled = editable, modifier = Modifier.fillMaxWidth())
                    TextField(
                        shape = TextFieldDefaults.shape,
                        colors = TextFieldDefaults.colors(),value = header.value, onValueChange = { value -> actions.onApiDraftChange(api.copy(headers = api.headers.map { if (it.id == header.id) it.copy(value = value) else it })) }, label = { Text("请求头值") }, enabled = editable, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { actions.onRemoveHeader(header.id) }, enabled = editable, modifier = Modifier.heightIn(min = 48.dp)) { Text("移除此请求头") }
                }
            }
            item("api-add-header") {
                FilledTonalButton(onClick = actions.onAddHeader, enabled = editable, modifier = Modifier.heightIn(min = 48.dp)) { Text("添加请求头") }
            }
        }
    })
}

/** 展示真实使用记录，删除必须先确认，正在写入的记录不允许分享或删除。 */
@Composable
private fun UsageRecords(state: SettingsUiState, actions: SimulSubActions, wide: Boolean, navigation: (@Composable () -> Unit)?) {
    var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingTitle by rememberSaveable { mutableStateOf("") }
    var clearAll by rememberSaveable { mutableStateOf(false) }
    PageColumns(wide, header = navigation, first = {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeading("使用记录", "记录保存在本机，可通过系统分享给其他应用，也可删除不再需要的记录。")
                ToggleSetting("保存会话记录", "默认关闭。只有主动开启后才保存后续会话的原文、译文与诊断信息。", state.keepLogs, onChange = actions.onKeepLogsChange)
                FilledTonalButton(onClick = actions.onRefreshRecords, enabled = !state.recordsLoading, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.recordsLoading) "正在读取…" else "刷新记录") }
                TextButton(onClick = { clearAll = true }, enabled = !state.recordsLoading && state.records.any { !it.active }, modifier = Modifier.heightIn(min = 48.dp)) { Text("清空使用记录") }
                if (state.recordsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.recordsMessage?.let { MessagePanel(it) }
            }
        }
    }, second = {
        if (state.records.isEmpty()) item { SupportingText(if (state.recordsLoading) "正在读取本地记录。" else "暂无记录。开启保存并完成一次会话后，可在这里分享。") }
        items(state.records, key = { it.id }) { record ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(record.title, style = MaterialTheme.typography.titleMedium)
                SupportingText(record.timeLabel)
                SupportingText(record.detail)
                ActionRow {
                    FilledTonalButton(onClick = { actions.onShareRecord(record.id) }, enabled = !record.sharing && !record.active, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (record.sharing) "正在准备分享…" else "分享记录") }
                    TextButton(onClick = { pendingId = record.id; pendingTitle = record.title }, enabled = !record.active && !record.sharing && !state.recordsLoading, modifier = Modifier.heightIn(min = 48.dp)) { Text("删除记录") }
                }
                HorizontalDivider()
            }
        }
    })
    if (pendingId != null || clearAll) {
        val canDelete = !state.recordsLoading && if (clearAll) state.records.any { !it.active }
            else state.records.any { it.id == pendingId && !it.active }
        AlertDialog(
            onDismissRequest = { pendingId = null; clearAll = false },
            title = { Text(if (clearAll) "清空使用记录？" else "删除这条记录？") },
            text = {
                Text(if (clearAll) "将删除本机所有已结束的会话记录，删除后无法恢复。正在写入的记录会保留，不会中断当前同传。"
                    else "将删除「$pendingTitle」的本机会话记录（$pendingId），删除后无法恢复。正在写入的记录会保留。")
            },
            confirmButton = {
                TextButton(onClick = {
                    if (clearAll) actions.onDeleteAllRecords() else pendingId?.let(actions.onDeleteRecord)
                    pendingId = null; clearAll = false
                }, enabled = canDelete) { Text(if (clearAll) "确认清空" else "确认删除") }
            },
            dismissButton = { TextButton(onClick = { pendingId = null; clearAll = false }) { Text("取消") } },
        )
    }
}

/** 管理入口与设置详情共用列表样式，点击反馈和触控高度保持一致。 */
@Composable
private fun SettingsLink(title: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(detail) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 76.dp))
}

/** 权限设置始终可查看，首页只保留尚未完成的必需授权。 */
@Composable
private fun PermissionsSettings(permissions: List<PermissionUiState>, actions: SimulSubActions, wide: Boolean) {
    PageColumns(false, first = {
        item { SectionHeading("系统权限", "屏幕共享会在每次开始同传时由系统询问。") }
        items(permissions, key = { it.id }) { permission ->
            SettingsCard {
                Text(permission.title, style = MaterialTheme.typography.titleMedium)
                SupportingText(permission.description)
                Text(if (permission.granted) "已允许" else if (permission.required) "使用同传前需要允许" else "可选权限",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                FilledTonalButton(onClick = { actions.onPermission(permission.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (permission.granted) "查看系统设置" else "去允许")
                }
            }
        }
    }, second = {})
}
