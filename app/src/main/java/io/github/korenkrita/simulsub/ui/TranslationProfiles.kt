package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.korenkrita.simulsub.LlmProfile

/** 配置目录与服务预设分开：目录保存用户自己的整套服务，切换前确认未保存输入。 */
@Composable
internal fun TranslationProfiles(api: TranslationApiUiState, actions: SimulSubActions, sessionBusy: Boolean) {
    val enabled = !api.testing && !api.saving
    val selected = api.profiles.firstOrNull { it.id == api.profileId }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var nameDialog by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    val profiles = api.profiles.map { LlmProfile(it.id, it.label) }
    val nameError = LlmProfile.name(name, profiles, api.profileId.takeIf { nameDialog == "rename" }).second

    /** 所有配置切换和管理入口共用草稿保护，不隐式保存或覆盖已填内容。 */
    fun request(action: () -> Unit) {
        if (api.hasUnsavedChanges) pending = action else action()
    }

    SettingsCard {
        SectionHeading("翻译配置", "保存多套服务，需要时直接切换。")
        OptionPicker("当前配置", api.profileId, api.profiles, enabled) { id ->
            if (id != api.profileId) request { actions.onSelectApiProfile(id) }
        }
        ActionRow {
            TextButton(onClick = { request { name = ""; nameDialog = "add" } },
                enabled = enabled && api.profiles.size < LlmProfile.MAX_PROFILES,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("新增配置") }
            TextButton(onClick = { request { name = selected?.label.orEmpty(); nameDialog = "rename" } },
                enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("重命名") }
            TextButton(onClick = { request { deleting = true } }, enabled = enabled && api.profiles.size > 1,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.heightIn(min = 48.dp)) { Text("删除配置") }
        }
    }
    if (pending != null) AlertDialog(
        onDismissRequest = { pending = null }, title = { Text("配置尚未保存") },
        text = { Text("继续操作会放弃当前未保存的修改。") },
        confirmButton = { TextButton(onClick = {
            val action = pending
            pending = null
            actions.onDiscardApiDraft()
            action?.invoke()
        }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { pending = null }) { Text("继续编辑") } },
    )
    if (nameDialog != null) AlertDialog(
        onDismissRequest = { nameDialog = null },
        title = { Text(if (nameDialog == "add") "新增翻译配置" else "重命名配置") },
        text = {
            TextField(value = name, onValueChange = { name = it }, label = { Text("配置名称") },
                singleLine = true, isError = name.isNotEmpty() && nameError != null,
                supportingText = { if (name.isNotEmpty() && nameError != null) Text(nameError) },
                modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = {
            if (nameDialog == "add") actions.onAddApiProfile(name.trim()) else actions.onRenameApiProfile(name.trim())
            nameDialog = null
        }, enabled = nameError == null) { Text("确定") } },
        dismissButton = { TextButton(onClick = { nameDialog = null }) { Text("取消") } },
    )
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false }, title = { Text("删除翻译配置？") },
        text = { Text("删除“${selected?.label.orEmpty()}”及其地址、密钥和请求头。其他配置保留。" +
            if (sessionBusy) "未选择有效的新配置前，当前会话会继续使用原服务。" else "") },
        confirmButton = { TextButton(onClick = { deleting = false; actions.onDeleteApiProfile() }) {
            Text("删除", color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } },
    )
}
