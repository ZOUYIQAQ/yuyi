package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** 中央密钥页按平台汇总多条凭据，手机顺序阅读、平板分栏管理。 */
@Composable
internal fun PlatformKeysSettings(platforms: List<PlatformKeyUiState>, sessionBusy: Boolean,
    actions: SimulSubActions, wide: Boolean) {
    val groups = platforms.groupBy { it.id }.entries.toList()
    // 连续分组按条目数尽量均衡；窄屏合并两栏后仍保持平台原始顺序。
    val split = if (groups.size > 1) {
        val total = groups.sumOf { it.value.size + 2 }
        (1 until groups.size).minByOrNull { index ->
            kotlin.math.abs(groups.take(index).sumOf { it.value.size + 2 } * 2 - total)
        } ?: groups.size
    } else groups.size
    PageColumns(wide, header = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("平台密钥", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f).semantics { heading() })
                Text("${groups.size} 个平台", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SupportingText("同一平台可保存多把密钥")
            if (sessionBusy) SupportingText("会话进行中，暂时无法修改密钥。")
        }
    }, first = {
        if (groups.isEmpty()) item("platform-keys-empty") {
            SettingsCard { SupportingText("暂无平台密钥信息，请返回后重新进入。") }
        }
        groups.take(split).forEach { (id, credentials) -> platformKeyGroup(id, credentials, sessionBusy, actions) }
    }, second = {
        groups.drop(split).forEach { (id, credentials) -> platformKeyGroup(id, credentials, sessionBusy, actions) }
    })
}

/** 平台标题沿用卡片正文的内缩，标题、编辑器和新增入口分别懒加载，长列表仍可精确定位。 */
private fun LazyListScope.platformKeyGroup(platformId: String, credentials: List<PlatformKeyUiState>,
    sessionBusy: Boolean, actions: SimulSubActions) {
    item("platform-heading-$platformId") {
        // 与 SettingsCard 默认的 20dp 内边距一致，对齐正文与输入框边缘，并保留输入框自身的文字留白。
        Row(Modifier.fillMaxWidth().testTag("platform-heading-$platformId").padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionHeading(credentials.first().label)
                Text("${credentials.count { it.hasSavedKey && !it.isNew }} 把已保存",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text("${credentials.size} 把", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
    items(credentials, key = { "platform-credential-${it.id}:${it.credentialId}" }) { credential ->
        SettingsCard { PlatformKeyEditor(credential, !sessionBusy, actions) }
    }
    item("platform-add-$platformId") {
        OutlinedButton(onClick = { actions.onAddPlatformKey(platformId) }, enabled = !sessionBusy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("platform-key-add-$platformId")) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("添加密钥")
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
