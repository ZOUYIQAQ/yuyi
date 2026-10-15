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

/** 主操作固定在导航栏上方，手机占满可用宽度，平板限宽靠右，滚动时仍可触达。 */
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
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.widthIn(max = 1120.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.CenterEnd) {
                Button(
                    onClick = action, enabled = !state.busy,
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(min = 72.dp).testTag("live-primary-action"),
                    shape = MaterialTheme.shapes.extraLarge,
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp, pressedElevation = 1.dp),
                ) {
                    Icon(if (state.running) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, null, Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(if (state.busy) "正在处理…" else label, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}
