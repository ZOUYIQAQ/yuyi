package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** 列表只显示首尾；短密钥也不完整泄露，编辑与保存始终使用原文。 */
internal fun keyRingPreview(key: String): String = when {
    key.isEmpty() -> "未填写"
    key.length <= 2 -> "*".repeat(key.length)
    key.length <= 8 -> "${key.take(1)}${"*".repeat(key.length - 2)}${key.takeLast(1)}"
    else -> "${key.take(4)}******${key.takeLast(4)}"
}

/** 紧凑钥匙扣按平台分组，名称与原文首尾直接可见，编辑器点击后才打开。 */
@Composable
internal fun PlatformKeysSettings(platforms: List<PlatformKeyUiState>, sessionBusy: Boolean,
    actions: SimulSubActions, wide: Boolean) {
    val groups = platforms.filterNot { it.isPlaceholder || it.isNew }.groupBy { it.id }.entries.toList()
    val split = (groups.size + 1) / 2
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    val selected = platforms.firstOrNull { "${it.id}:${it.credentialId}" == editing }
    val removal = platforms.firstOrNull { "${it.id}:${it.credentialId}" == deleting }
    val editable = !sessionBusy
    PageColumns(wide, header = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("同一平台可保存多把密钥", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { actions.onOpenQuickPlatformKey("", "") }, enabled = editable,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("platform-key-add")) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加")
                }
            }
            if (sessionBusy) SupportingText("会话进行中，暂时无法修改密钥。")
        }
    }, first = {
        if (groups.isEmpty()) item("platform-keys-empty") {
            SettingsCard { SupportingText("尚未添加密钥，点击右上角添加。") }
        }
        groups.take(split).forEach { (id, entries) ->
            item("platform-heading-$id") { PlatformKeyHeading(id, entries.first().label) }
            items(entries, key = { "platform-credential-${it.id}:${it.credentialId}" }) { key ->
                KeyRing(key, editable, { editing = "${key.id}:${key.credentialId}" },
                    { deleting = "${key.id}:${key.credentialId}" })
            }
        }
    }, second = {
        groups.drop(split).forEach { (id, entries) ->
            item("platform-heading-$id") { PlatformKeyHeading(id, entries.first().label) }
            items(entries, key = { "platform-credential-${it.id}:${it.credentialId}" }) { key ->
                KeyRing(key, editable, { editing = "${key.id}:${key.credentialId}" },
                    { deleting = "${key.id}:${key.credentialId}" })
            }
        }
    })
    if (selected != null) {
        val dismiss = { if (selected.hasUnsavedChanges) discard = true else editing = null }
        AlertDialog(onDismissRequest = dismiss, title = { Text(selected.label, style = MaterialTheme.typography.titleLarge) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { PlatformKeyEditor(selected, editable, actions) } },
            confirmButton = { TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("完成") } })
        if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("配置尚未保存") },
            text = { Text("是否放弃这把密钥的修改？") }, confirmButton = {
                TextButton(onClick = {
                    actions.onDiscardPlatformKey(selected.id, selected.credentialId)
                    discard = false; editing = null
                }) { Text("放弃修改") }
            }, dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
    }
    if (removal != null) AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除密钥？") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("将删除${removal.label}的「${removal.credentialLabel}」。")
                if (removal.usedBy.isNotEmpty()) Text("以下服务需要重新选择密钥：\n" + removal.usedBy.joinToString("\n"))
                else Text("使用这把密钥的服务将无法调用，需重新选择密钥。")
            }
        }, confirmButton = {
            TextButton(onClick = {
                actions.onDeletePlatformKey(removal.id, removal.credentialId); deleting = null
            }, enabled = editable, modifier = Modifier.testTag("platform-key-confirm-delete"),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") }
        }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
}

/** 平台标题与钥匙扣文字对齐，不重复堆叠计数或说明。 */
@Composable
private fun PlatformKeyHeading(id: String, label: String) {
    Text(label, Modifier.padding(horizontal = 16.dp).testTag("platform-heading-$id").semantics { heading() },
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 编辑点击区与删除触控区分开，整条仅显示名称、首尾预览、默认标记与删除入口。 */
@Composable
private fun KeyRing(key: PlatformKeyUiState, editable: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    val itemId = "${key.id}:${key.credentialId}"
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "编辑密钥", onClick = onEdit).padding(16.dp)
                .testTag("platform-key-ring-$itemId"), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Key, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(key.credentialLabel, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (key.isDefault) Surface(shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text("默认", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                    Text(keyRingPreview(key.key), style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onDelete, enabled = editable,
                modifier = Modifier.testTag("platform-key-delete-$itemId")) {
                Icon(Icons.Outlined.DeleteOutline, "删除${key.credentialLabel}", Modifier.size(20.dp))
            }
        }
    }
}

/** 每条凭据独立编辑名称和密钥；眼睛只改变显示方式，不复制或转换原文。 */
@Composable
private fun PlatformKeyEditor(platform: PlatformKeyUiState, editable: Boolean, actions: SimulSubActions) {
    var visible by remember(platform.id, platform.credentialId) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val itemId = "${platform.id}:${platform.credentialId}"
        OutlinedTextField(value = platform.credentialLabel,
            onValueChange = { actions.onPlatformKeyLabelChange(platform.id, platform.credentialId, it) },
            label = { Text("密钥名称") }, enabled = editable, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("platform-key-label-$itemId"))
        OutlinedTextField(
            value = platform.key,
            onValueChange = { actions.onPlatformCredentialChange(platform.id, platform.credentialId, it) },
            label = { Text("API 密钥") },
            enabled = editable,
            singleLine = true,
            maxLines = 1,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }, enabled = editable) {
                    Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (visible) "隐藏${platform.label}密钥" else "显示${platform.label}密钥")
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().testTag("platform-key-$itemId"),
        )
        Text(when {
            platform.hasUnsavedChanges -> "密钥状态：有未保存的修改"
            platform.isNew -> "密钥状态：待保存"
            platform.hasSavedKey -> "密钥状态：已配置"
            else -> "密钥状态：未配置"
        }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("platform-key-status-$itemId"))
        platform.message?.let { MessagePanel(it) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ActionRow {
            Button(onClick = {
                focus.clearFocus(); keyboard?.hide()
                actions.onSavePlatformCredential(platform.id, platform.credentialId)
            }, enabled = editable,
                modifier = Modifier.heightIn(min = 48.dp).testTag("platform-key-save-$itemId")) {
                Text("保存密钥")
            }
            val canSetDefault = editable && platform.hasSavedKey && platform.key.isNotBlank() &&
                !platform.hasUnsavedChanges && !platform.isNew && !platform.isDefault
            FilledTonalButton(onClick = {
                focus.clearFocus(); keyboard?.hide()
                actions.onSetDefaultPlatformKey(platform.id, platform.credentialId)
            }, enabled = canSetDefault,
                modifier = Modifier.heightIn(min = 48.dp).testTag("platform-key-default-$itemId")) {
                if (platform.isDefault) {
                    Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (platform.isDefault) "已默认" else "设为默认")
            }
        }
    }
}

/** 业务表单只展示平台绑定与已保存状态，管理入口在运行时仍保持可达。 */
@Composable
internal fun PlatformKeyStatus(label: String, hasSavedKey: Boolean, onManage: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SupportingText("$label：${if (hasSavedKey) "已配置" else "未配置"}")
        TextButton(onClick = onManage, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("管理平台密钥")
        }
    }
}
