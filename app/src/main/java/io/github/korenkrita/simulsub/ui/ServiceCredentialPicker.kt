package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** 服务内选择与新增共处一处，新增弹窗不会离开表单或丢失连接草稿。 */
@Composable
internal fun ServiceCredentialPicker(platformId: String, serviceId: String, options: List<UiOption>,
    selectedId: String, selectedLabel: String, enabled: Boolean, actions: SimulSubActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OptionPicker("使用的密钥", selectedId,
            listOf(UiOption("", "跟随默认密钥")) + options.filterNot { it.id.isEmpty() },
            enabled = enabled, onSelect = { actions.onServiceCredentialChange(serviceId, it) })
        if (selectedLabel.isNotBlank()) SupportingText("当前：$selectedLabel")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { actions.onOpenQuickPlatformKey(platformId, serviceId) },
                enabled = enabled && platformId.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp).testTag("quick-key-add-$serviceId")) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (options.any { it.description == "已保存" }) "添加密钥" else "填写密钥")
            }
            TextButton(onClick = actions.onOpenPlatformKeys, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("管理平台密钥")
            }
        }
    }
}

/** 密钥原文由控制器持有，显隐和放弃确认只使用不可持久化的局部状态。 */
@Composable
internal fun QuickPlatformKeyDialog(state: QuickPlatformKeyUiState, actions: SimulSubActions, enabled: Boolean) {
    var visible by remember(state.credentialId) { mutableStateOf(false) }
    var confirmingDiscard by remember(state.credentialId) { mutableStateOf(false) }
    val initialName = remember(state.credentialId) { state.name }
    val discard = {
        if (state.key.isNotEmpty() || state.name != initialName) confirmingDiscard = true
        else actions.onDismissQuickPlatformKey()
    }
    AlertDialog(onDismissRequest = discard,
        title = { Text("添加密钥") },
        text = {
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(state.platformLabel, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextField(value = state.name, onValueChange = actions.onQuickPlatformKeyNameChange,
                    label = { Text("密钥名称") }, singleLine = true, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("quick-key-name"))
                TextField(value = state.key, onValueChange = actions.onQuickPlatformKeyValueChange,
                    label = { Text("API 密钥") }, singleLine = true, enabled = enabled,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false,
                        imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }, enabled = enabled) {
                            Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                if (visible) "隐藏密钥" else "显示密钥")
                        }
                    }, modifier = Modifier.fillMaxWidth().testTag("quick-key-value"))
                state.message?.let { MessagePanel(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onSaveQuickPlatformKey,
                enabled = enabled && state.name.isNotBlank() && state.key.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp).testTag("quick-key-save")) {
                Text(if (state.serviceId.isNotBlank()) "保存并使用" else "保存")
            }
        },
        dismissButton = { TextButton(onClick = discard, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
    if (confirmingDiscard) UnsavedConfigurationDialog(
        onContinueEditing = { confirmingDiscard = false },
        onDiscard = { confirmingDiscard = false; actions.onDismissQuickPlatformKey() })
}
