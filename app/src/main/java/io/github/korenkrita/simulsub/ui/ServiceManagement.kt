package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.korenkrita.simulsub.LlmProfile
import io.github.korenkrita.simulsub.TextServiceProviders

/** 听写目录按服务逐卡管理；本地条目直接展示模型操作，云端配置继续使用原弹窗。 */
@Composable
internal fun ListeningServiceManagement(state: SettingsUiState, sessionBusy: Boolean,
    actions: SimulSubActions, wide: Boolean, models: ModelsUiState = ModelsUiState()) {
    val enabled = !sessionBusy && !state.online.testing && !state.cloudAsr.testing && !state.api.testing && !state.api.saving
    var selectedDialogId by rememberSaveable { mutableStateOf<String?>(null) }
    val modelsById = models.items.associateBy { it.id }
    ServiceProfilesList(state.listeningProfiles, wide,
        emptyText = "正在读取内置听写服务；本地模型与千问接口会在此列出。",
        header = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ServiceManagementHeader("选择已接入的本地模型或千问服务。", sessionBusy)
                if (models.storageLabel.isNotBlank()) SupportingText(models.storageLabel)
                if (models.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                models.message?.let { MessagePanel(it) }
            }
        }) { profile ->
            if (profile.kind == "local" || profile.id.startsWith("local:")) {
                LocalModelServiceCard(profile, modelsById[profile.id.removePrefix("local:")],
                    enabled, models.loading, actions)
            } else {
                ServiceProfileCard(profile, enabled, onEdit = {
                    if (profile.builtIn && profile.platformId.isNotBlank() &&
                        profile.credentialOptions.none { it.description == "已保存" })
                        actions.onOpenQuickPlatformKey(profile.platformId, profile.id)
                    else if (profile.builtIn) selectedDialogId = profile.id
                    else actions.onEditListeningService(profile.id)
                }, onUse = { actions.onSelectListeningService("profile:${profile.id}") })
            }
    }
    models.items.firstOrNull { it.id == models.deleteConfirmationId }?.let { model ->
        ModelDeleteConfirmation(model, models.deletingId == model.id, actions)
    }
    state.listeningProfiles.firstOrNull { it.id == selectedDialogId }?.let { profile ->
        ServiceCredentialDialog(profile, sessionBusy, actions, onDismiss = { selectedDialogId = null })
    }
}

/** 翻译目录使用真实配置标识；选择草稿后再打开表单，列表中不嵌入连接编辑。 */
@Composable
internal fun TranslationServiceManagement(state: SettingsUiState, sessionBusy: Boolean,
    actions: SimulSubActions, wide: Boolean, onOpenEditor: () -> Unit) {
    val enabled = !sessionBusy && !state.api.testing && !state.api.saving && !state.online.testing && !state.cloudAsr.testing
    var selectedDialogId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingDialogId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val canDelete = enabled && state.translationProfiles.size > 1
    ServiceProfilesList(state.translationProfiles, wide,
        emptyText = "正在读取内置翻译接口；DeepSeek 与千问会在此列出。",
        header = {
            ServiceManagementHeader("选择已接入的翻译服务。", sessionBusy,
                actionLabel = "添加翻译服务", actionTag = "create-translation-service",
                actionEnabled = enabled && !state.api.hasUnsavedChanges && state.api.profiles.size < LlmProfile.MAX_PROFILES,
                onAction = { creating = true })
        }) { profile ->
            ServiceProfileCard(profile, enabled, onEdit = {
                if (profile.builtIn && profile.platformId.isNotBlank() &&
                    profile.credentialOptions.none { it.description == "已保存" })
                    actions.onOpenQuickPlatformKey(profile.platformId, "translation:${profile.id}")
                else if (profile.builtIn) selectedDialogId = profile.id else {
                    actions.onEditTranslationService(profile.id)
                    onOpenEditor()
                }
            }, onUse = { actions.onSelectTranslationService(profile.id) },
                onDelete = { if (canDelete) deletingDialogId = profile.id }, deleteEnabled = canDelete)
    }
    state.translationProfiles.firstOrNull { it.id == deletingDialogId }?.let { profile ->
        TranslationServiceDeleteDialog(profile, canDelete, sessionBusy, state.translationProfiles.size <= 1,
            onDismiss = { deletingDialogId = null }, onConfirm = {
                // 确认时再次校验最新状态，运行或仅剩一项时不派发删除。
                if (canDelete) {
                    deletingDialogId = null
                    actions.onDeleteTranslationService(profile.id)
                }
            })
    }
    state.translationProfiles.firstOrNull { it.id == selectedDialogId }?.let { profile ->
        ServiceCredentialDialog(profile.copy(id = "translation:${profile.id}"), sessionBusy,
            actions, onDismiss = { selectedDialogId = null }, onEditConnection = {
                selectedDialogId = null
                actions.onEditTranslationService(profile.id)
                onOpenEditor()
            })
    }
    if (creating) NewTranslationServiceDialog(onDismiss = { creating = false }, onCreate = { presetId ->
        creating = false
        actions.onCreateTranslationProvider(presetId)
        onOpenEditor()
    })
}

/** 删除任意已保存翻译条目前二次确认，切换当前服务与保留平台密钥由控制器完成。 */
@Composable
private fun TranslationServiceDeleteDialog(profile: ServiceProfileUiState, enabled: Boolean,
    sessionBusy: Boolean, lastService: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("删除翻译服务？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                if (profile.selected) Text("这是当前使用的服务，删除后将切换到其他服务。")
                Text("平台密钥会保留，其他服务仍可继续使用。")
                if (!enabled) SupportingText(when {
                    sessionBusy -> "请先停止同传，再删除服务。"
                    lastService -> "至少需要保留一项翻译服务。"
                    else -> "正在保存或测试连接，请稍后再删除服务。"
                })
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag("confirm-delete-translation-service"),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text("删除")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
}

/** 服务目录显示简短说明；只有翻译目录提供模板创建入口。 */
@Composable
private fun ServiceManagementHeader(description: String, sessionBusy: Boolean,
    actionLabel: String? = null, actionTag: String = "", actionEnabled: Boolean = false,
    onAction: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SupportingText(description)
        if (actionLabel != null && onAction != null) FilledTonalButton(onClick = onAction,
            enabled = actionEnabled, modifier = Modifier.heightIn(min = 48.dp).testTag(actionTag)) {
            Text(actionLabel)
        }
        if (sessionBusy) SupportingText("请先停止同传，再编辑或切换服务。")
    }
}

/** 按供应商目录筛选模板，手动兼容接口始终位于首项，确认后进入已有编辑器。 */
@Composable
private fun NewTranslationServiceDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var selectedGroup by rememberSaveable { mutableStateOf("全部") }
    val providers = TextServiceProviders.entries.associateBy { it.id }
    val groups = listOf("全部") + TextServiceProviders.entries.map { it.group }.distinct()
    val options = listOf(UiOption("custom", "自定义兼容接口", "手动填写服务地址与模型 ID")) +
        TranslationPresets.entries.filter { preset ->
            (selectedGroup == "全部" || providers[preset.id]?.group == selectedGroup) &&
                (search.isBlank() || preset.title.contains(search.trim(), ignoreCase = true) ||
                    preset.id.contains(search.trim(), ignoreCase = true) ||
                    preset.model.contains(search.trim(), ignoreCase = true))
        }.map { UiOption(it.id, it.title, it.detail) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("添加翻译服务") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(value = search, onValueChange = { search = it; selectedId = null },
                    label = { Text("搜索供应商") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("translation-provider-search"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    groups.forEach { group ->
                        FilterChip(selected = selectedGroup == group,
                            onClick = { selectedGroup = group; selectedId = null },
                            label = { Text(group) })
                    }
                }
                Box(Modifier.testTag("new-translation-provider")) {
                    OptionPicker("供应商模板", selectedId ?: "请选择供应商", options,
                        onSelect = { selectedId = it })
                }
                selectedId?.let { id ->
                    options.firstOrNull { it.id == id }?.description?.let { SupportingText(it) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { selectedId?.let(onCreate) }, enabled = selectedId != null) {
            Text("继续配置")
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

/** 服务交错分配到两栏并同时从首行显示；手机保留原列表顺序，复用页面原有三组滚动状态。 */
@Composable
private fun ServiceProfilesList(profiles: List<ServiceProfileUiState>, wide: Boolean, emptyText: String,
    header: @Composable () -> Unit, card: @Composable (ServiceProfileUiState) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val minimumWidth = maxOf(720.dp, 640.dp * LocalDensity.current.fontScale.coerceAtLeast(1f))
        val twoColumns = wide && maxWidth >= minimumWidth
        val first = if (twoColumns) profiles.filterIndexed { index, _ -> index % 2 == 0 } else profiles
        val second = if (twoColumns) profiles.filterIndexed { index, _ -> index % 2 != 0 } else emptyList()
        Box(Modifier.fillMaxSize().testTag(if (twoColumns) "service-profiles-two-columns" else "service-profiles-single-column")) {
            PageColumns(twoColumns, header = header, first = {
                if (profiles.isEmpty()) item("services-empty") { SupportingText(emptyText) }
                items(first, key = { it.id }) { card(it) }
            }, second = {
                items(second, key = { it.id }) { card(it) }
            })
        }
    }
}

/** 正文与配置文字左对齐，使用按钮保持主层级；删除图标独立显示，按钮组支持自然换行。 */
@Composable
private fun ServiceProfileCard(profile: ServiceProfileUiState, enabled: Boolean,
    onEdit: () -> Unit, onUse: () -> Unit, onDelete: (() -> Unit)? = null, deleteEnabled: Boolean = false) {
    val local = profile.kind == "local" || profile.id.startsWith("local:")
    val needsDownload = local && !profile.configured
    Box(Modifier.fillMaxWidth().testTag("service-profile-${profile.id}")) {
      SettingsCard(padding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(profile.name, style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(top = if (onDelete != null) 8.dp else 0.dp))
                if (onDelete != null) IconButton(onClick = onDelete, enabled = deleteEnabled,
                    modifier = Modifier.testTag("delete-translation-service-${profile.id}")) {
                    Icon(Icons.Outlined.DeleteOutline, "删除翻译服务：${profile.name}", Modifier.size(20.dp))
                }
            }
            if (profile.detail.isNotBlank()) SupportingText(profile.detail)
            if (!local && profile.credentialLabel.isNotBlank()) SupportingText("密钥：${profile.credentialLabel}")
            Text(when { needsDownload -> "模型文件尚未下载"; local -> "已安装"; profile.configured -> "已保存"; else -> "待填写平台密钥" },
                style = MaterialTheme.typography.labelLarge)
            if (profile.selected) Text("使用中", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            ActionRow {
                if (!local) TextButton(onClick = onEdit, enabled = enabled,
                    // 保留最小触控宽度但让内容靠左，避免短文字被按钮默认内边距或居中布局推偏。
                    modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 48.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)) {
                    Box(Modifier.widthIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                        Text(if (profile.builtIn) "选择密钥" else "编辑")
                    }
                }
                Button(onClick = onUse, enabled = enabled && (needsDownload || (profile.configured && !profile.selected)),
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(when { needsDownload -> "下载模型"; profile.selected -> "正在使用"; else -> "使用" })
                }
            }
        }
      }
    }
}

/** 预制服务在小弹窗中绑定凭据；翻译服务可继续进入既有连接设置与探测流程。 */
@Composable
internal fun ServiceCredentialDialog(profile: ServiceProfileUiState, sessionBusy: Boolean,
    actions: SimulSubActions, onDismiss: () -> Unit, onEditConnection: (() -> Unit)? = null) {
    val options = listOf(UiOption("", "跟随默认密钥")) + profile.credentialOptions.filterNot { it.id.isEmpty() }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("选择密钥") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(profile.name, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("service-credential-name"))
                Box(Modifier.testTag("service-credential-picker")) {
                    OptionPicker("平台密钥", profile.credentialId, options, enabled = !sessionBusy) {
                        actions.onServiceCredentialChange(profile.id, it)
                    }
                }
                if (profile.credentialLabel.isNotBlank()) SupportingText("当前使用：${profile.credentialLabel}")
                Button(onClick = { actions.onOpenQuickPlatformKey(profile.platformId, profile.id) },
                    enabled = !sessionBusy && profile.platformId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("quick-key-add-${profile.id}")) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("添加密钥")
                }
                HorizontalDivider()
                Column {
                    ServiceCredentialSecondaryAction("管理平台密钥", !sessionBusy) {
                        onDismiss()
                        actions.onOpenPlatformKeys()
                    }
                    if (onEditConnection != null) ServiceCredentialSecondaryAction("连接设置与测试", !sessionBusy,
                        onClick = onEditConnection)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("完成") } })
}

/** 次级入口共用透明列表项与箭头，运行时同时关闭点击与无障碍可用状态。 */
@Composable
private fun ServiceCredentialSecondaryAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurface.let { if (enabled) it else it.copy(alpha = 0.38f) }
    ListItem(headlineContent = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(20.dp)) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent,
            headlineColor = color, trailingIconColor = color),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (!enabled) disabled() })
}
