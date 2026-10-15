package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 关于页面明确Fork关系，原作者和许可不会被品牌名称覆盖。 */
@Composable
internal fun AboutSettings(state: SettingsUiState, actions: SimulSubActions, wide: Boolean, navigation: (@Composable () -> Unit)?) {
    var showLicense by rememberSaveable { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    PageColumns(wide, header = navigation, first = {
        item { Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Subtitles, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
                Text("羽译", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("版本 ${state.version}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("基于 simul-subtitle 的 Android Fork", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        } }
        item { SettingsCard {
            SectionHeading("原作者与上游")
            Text("KorenKrita", style = MaterialTheme.typography.headlineSmall)
            SupportingText("原作者 · simul-subtitle")
            SupportingText("羽译是在原项目基础上独立修改的 Fork，保留原作者的版权与许可声明。")
            OutlinedButton(onClick = actions.onOpenUpstreamRepository, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("查看上游项目") }
        } }
    }, second = {
        item { SettingsCard {
            SectionHeading("Fork 维护者")
            Text("ZOUYIQAQ", style = MaterialTheme.typography.titleLarge)
            SupportingText("Android界面与功能改造")
            TextButton(onClick = actions.onOpenRepository, modifier = Modifier.heightIn(min = 48.dp)) { Text("查看 Fork 仓库") }
        } }
        item { SettingsCard {
            SectionHeading("开源许可")
            Text("MIT License", style = MaterialTheme.typography.titleMedium)
            SupportingText("Copyright (c) 2026 KorenKrita")
            OutlinedButton(onClick = { showLicense = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("查看完整许可") }
            SupportingText("语音模型与其他依赖遵循各自许可，模型许可可在模型库查看。")
        } }
        item { SettingsCard {
            SectionHeading("项目", "更新检查与提醒在「设置 → 应用更新」中管理。")
            OutlinedButton(onClick = actions.onOpenRepository, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("打开本 Fork 仓库") }

        } }
    })
    if (showLicense) AlertDialog(onDismissRequest = { showLicense = false }, title = { Text("MIT License") },
        text = { Text(remember { context.assets.open("licenses/App-LICENSE.txt").bufferedReader().use { it.readText() } },
            modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton(onClick = { showLicense = false }) { Text("关闭") } })
}
