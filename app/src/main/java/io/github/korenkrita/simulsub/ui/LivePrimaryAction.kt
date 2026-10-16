package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** 主操作嵌入会话控制舱，以紧凑圆角大按钮保持辨识度，大字号允许文案换行增高。 */
@Composable
internal fun LivePrimaryAction(state: LiveUiState, actions: SimulSubActions) {
    val missingPermission = state.permissions.firstOrNull { it.required && !it.granted }
    val label = when {
        state.running -> "停止同传"
        state.modelLabel == "未启用模型" -> "选择识别模型"
        state.translationLabel == "未配置" -> "配置翻译服务"
        missingPermission != null -> "开启${missingPermission.title}"
        else -> "开始同传"
    }
    val action: () -> Unit = when {
        state.running -> actions.onStop
        state.modelLabel == "未启用模型" -> ({ actions.onNavigate(UiDestination.MODELS) })
        state.translationLabel == "未配置" -> ({ actions.onNavigate(UiDestination.SETTINGS) })
        missingPermission != null -> ({ actions.onPermission(missingPermission.id) })
        else -> actions.onStart
    }
    Button(
        onClick = action, enabled = !state.busy,
        modifier = Modifier.widthIn(max = if (LocalDensity.current.fontScale >= 1.5f) 240.dp else 160.dp)
            .fillMaxWidth().heightIn(min = 148.dp).testTag("live-primary-action"),
        shape = MaterialTheme.shapes.extraLarge,
        contentPadding = PaddingValues(16.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp, pressedElevation = 1.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (state.running) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, null, Modifier.size(40.dp))
            Text(if (state.busy) "正在处理…" else label, style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center)
        }
    }
}
