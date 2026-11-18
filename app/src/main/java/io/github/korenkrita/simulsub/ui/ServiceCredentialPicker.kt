package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    var visible by remember { mutableStateOf(false) }
    var confirmingDiscard by remember { mutableStateOf(false) }
    val initialName = remember { state.name }
    val initialPlatform = remember { state.platformInput }
    val discard = {
        if (state.key.isNotEmpty() || state.name != initialName || state.platformInput != initialPlatform) confirmingDiscard = true
        else actions.onDismissQuickPlatformKey()
    }
    AlertDialog(onDismissRequest = discard,
        title = { Text("添加密钥") },
        text = {
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                KeyPlatformField(state, actions.onQuickPlatformKeyPlatformChange, enabled)
                OutlinedTextField(value = state.name, onValueChange = actions.onQuickPlatformKeyNameChange,
                    label = { Text("备注") }, singleLine = true, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("quick-key-name"))
                OutlinedTextField(value = state.key, onValueChange = actions.onQuickPlatformKeyValueChange,
                    label = { Text("Key") }, singleLine = true, enabled = enabled,
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
                enabled = enabled && state.platformId.isNotBlank() && state.name.isNotBlank() && state.key.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp).testTag("quick-key-save")) {
                Text(if (state.serviceId.isNotBlank()) "保存并使用" else "保存")
            }
        },
        dismissButton = { TextButton(onClick = discard, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
    if (confirmingDiscard) UnsavedConfigurationDialog(
        onContinueEditing = { confirmingDiscard = false },
        onDiscard = { confirmingDiscard = false; actions.onDismissQuickPlatformKey() })
}

/** 平台只有一个输入位置，可展开完整目录，也可直接填写自定义服务地址。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyPlatformField(state: QuickPlatformKeyUiState, onChange: (String) -> Unit, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = {
        if (enabled && state.platformEditable) expanded = it
    }) {
        OutlinedTextField(value = state.platformInput, onValueChange = { expanded = false; onChange(it) },
            label = { Text("平台") }, placeholder = { Text("选择平台或输入服务地址") },
            readOnly = !state.platformEditable, singleLine = true, enabled = enabled,
            isError = state.platformEditable && state.platformInput.isNotBlank() && state.platformId.isEmpty(),
            supportingText = if (state.platformEditable) { { Text("自定义平台：填写完整的服务地址") } } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            trailingIcon = if (state.platformEditable) { {
                IconButton(onClick = { focus.clearFocus(); keyboard?.hide(); expanded = !expanded }, enabled = enabled,
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.SecondaryEditable)
                        .testTag("quick-key-platform-menu")) {
                    Icon(Icons.Outlined.ArrowDropDown, "选择平台")
                }
            } } else null,
            modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus).menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable,
                enabled = enabled && state.platformEditable).testTag("quick-key-platform"))
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp)) {
            state.platforms.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, onClick = { onChange(option.id); expanded = false },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("quick-key-platform-${option.id}"))
            }
            DropdownMenuItem(text = { Text("自定义平台…") }, onClick = {
                onChange(""); expanded = false; fieldFocus.requestFocus(); keyboard?.show()
            },
                modifier = Modifier.heightIn(min = 48.dp).testTag("quick-key-platform-custom"))
        }
    }
}
