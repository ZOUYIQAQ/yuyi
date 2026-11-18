package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** 本地听写条目直接复用模型卡；下载、选择与删除均交回已有控制器动作。 */
@Composable
internal fun LocalModelServiceCard(profile: ServiceProfileUiState, model: ModelUiState?,
    enabled: Boolean, loading: Boolean, actions: SimulSubActions) {
    Box(Modifier.fillMaxWidth().testTag("service-profile-${profile.id}")) {
        if (model == null) {
            SettingsCard {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                if (profile.detail.isNotBlank()) SupportingText(profile.detail)
                SupportingText(if (loading) "正在加载模型状态…" else "模型详情尚未加载，请刷新模型状态。")
                TextButton(onClick = actions.onRefreshModels, enabled = !loading,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("刷新模型状态") }
            }
        } else {
            ModelCardRow(listOf(model), actions, selected = profile.selected, selectionAction = {
                if (model.status == ModelUiStatus.READY || model.status == ModelUiStatus.ACTIVE) {
                    FilledTonalButton(onClick = { actions.onSelectListeningService(profile.id) },
                        enabled = enabled && !profile.selected, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (profile.selected) "正在使用" else "使用")
                    }
                }
            })
        }
    }
}
