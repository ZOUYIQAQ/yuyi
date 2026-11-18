package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import io.github.korenkrita.simulsub.ListeningMode

/** 横向主按钮与状态卡共享对齐，保持大触控区而不再形成突兀的方形色块。 */
@Composable
internal fun LivePrimaryAction(state: LiveUiState, actions: SimulSubActions) {
    val missingPermission = state.permissions.firstOrNull { it.required && !it.granted }
    val online = state.listeningMode == ListeningMode.ONLINE
    val asr = state.listeningMode == ListeningMode.CLOUD_ASR
    val label = when {
        state.running -> "停止同传"
        asr && !state.cloudReady -> "填写千问密钥"
        asr && !state.translationReady -> "填写翻译平台密钥"
        online && !state.cloudReady -> "填写千问密钥"
        state.listeningMode == ListeningMode.LOCAL && state.modelLabel == "未启用模型" -> "下载并启用模型"
        state.listeningMode == ListeningMode.LOCAL && !state.translationReady -> "配置翻译服务"
        missingPermission != null -> "开启${missingPermission.title}"
        else -> "开始同传"
    }
    val action: () -> Unit = when {
        state.running -> actions.onStop
        asr && !state.cloudReady -> actions.onOpenPlatformKeys
        asr && !state.translationReady -> actions.onOpenPlatformKeys
        online && !state.cloudReady -> actions.onOpenPlatformKeys
        state.listeningMode == ListeningMode.LOCAL && state.modelLabel == "未启用模型" -> ({ actions.onNavigate(UiDestination.MODELS) })
        state.listeningMode == ListeningMode.LOCAL && !state.translationReady -> actions.onOpenPlatformKeys
        missingPermission != null -> ({ actions.onPermission(missingPermission.id) })
        else -> actions.onStart
    }
    Button(
        onClick = action, enabled = !state.busy,
        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().heightIn(min = 72.dp)
            .testTag("live-primary-action"),
        shape = MaterialTheme.shapes.extraLarge,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (state.running) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, null, Modifier.size(28.dp))
            Text(if (state.busy) "正在处理…" else label, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f, fill = false), textAlign = TextAlign.Center)
        }
    }
}
