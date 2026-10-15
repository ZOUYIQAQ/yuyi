package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** 更新设置与手动结果集中显示；自动检查失败只在这里留结果，不打断同传。 */
@Composable
internal fun UpdateSettings(state: SettingsUiState, actions: SimulSubActions, wide: Boolean) {
    val updates = state.updates
    PageColumns(wide, first = {
        item { SettingsCard {
            SectionHeading("更新偏好")
            ToggleSetting("启动时检查更新", "每次启动检查一次，发现正式新版后提醒。", updates.autoCheck,
                onChange = actions.onAutoUpdateChange)
            if (updates.skippedVersion.isNotEmpty()) SupportingText("已跳过版本：${updates.skippedVersion}")
            if (updates.laterVersion.isNotEmpty()) SupportingText("已延后提醒的版本：${updates.laterVersion}")
            if (updates.skippedVersion.isNotEmpty() || updates.laterVersion.isNotEmpty()) {
                OutlinedButton(onClick = actions.onRestoreUpdateReminders, modifier = Modifier.heightIn(min = 48.dp)) { Text("恢复版本提醒") }
            }
        } }
    }, second = {
        item { SettingsCard {
            SectionHeading("检查版本")
            Text("当前版本 ${state.version}", style = MaterialTheme.typography.titleMedium)
            if (updates.lastChecked.isNotEmpty()) SupportingText("上次检查：${updates.lastChecked}")
            Button(onClick = actions.onCheckUpdate, enabled = !state.checkingUpdate,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (state.checkingUpdate) "正在检查更新…" else "检查更新") }
            TextButton(onClick = actions.onOpenReleasePage, modifier = Modifier.heightIn(min = 48.dp)) { Text("查看 GitHub 发布页") }
            if (state.checkingUpdate) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.updateMessage.isNotEmpty()) MessagePanel(UiMessage(state.updateMessage, updates.checkFailed))
            updates.latest?.let { release ->
                HorizontalDivider()
                Text("新版本 ${release.versionName}", style = MaterialTheme.typography.titleMedium)
                Text(release.notes, style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton(onClick = actions.onOpenUpdate, enabled = !state.checkingUpdate,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(if (release.downloadUrl == null) "查看发布页" else "更新 · 浏览器下载")
                }
            }
        } }
    })
}

/** 新版提示不包含安装器或下载任务，低矮横屏与大字号时所有动作仍可滚动到达。 */
@Composable
internal fun UpdatePrompt(release: UpdateReleaseUiState, actions: SimulSubActions) {
    Dialog(onDismissRequest = actions.onLaterUpdate) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp,
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.85f)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("发现新版本 ${release.versionName}", style = MaterialTheme.typography.headlineSmall)
                Text(release.notes, style = MaterialTheme.typography.bodyMedium)
                SupportingText(if (release.downloadUrl == null) "将在浏览器中打开 GitHub 发布页。" else "将在浏览器中打开 GitHub 安装包下载链接。")
                ActionRow {
                    Button(onClick = actions.onOpenUpdate, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (release.downloadUrl == null) "查看发布页" else "更新")
                    }
                    FilledTonalButton(onClick = actions.onLaterUpdate, modifier = Modifier.heightIn(min = 48.dp)) { Text("稍后提醒") }
                }
                TextButton(onClick = actions.onSkipUpdate, modifier = Modifier.heightIn(min = 48.dp)) { Text("跳过此版本") }
                TextButton(onClick = { actions.onAutoUpdateChange(false) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭自动检查") }
            }
        }
    }
}
