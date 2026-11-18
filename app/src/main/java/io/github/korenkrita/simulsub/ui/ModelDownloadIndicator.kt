package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 顶部细条只呈现正在处理的任务；优先显示下载项真实比例，未知进度和校验使用不定进度。 */
@Composable
internal fun ModelDownloadIndicator(models: ModelsUiState, modifier: Modifier = Modifier) {
    // 现有排队任务由下载中且未知进度表示，也兼容后续独立的排队状态。
    val tasks = models.items.filter { it.status in listOf(ModelUiStatus.DOWNLOADING, ModelUiStatus.VERIFYING) || it.status.name == "QUEUED" }
    if (tasks.isEmpty()) return
    val active = tasks.firstOrNull { it.status == ModelUiStatus.DOWNLOADING && it.progress?.isFinite() == true }
        ?: tasks.firstOrNull { it.status == ModelUiStatus.DOWNLOADING }
        ?: tasks.firstOrNull { it.status == ModelUiStatus.VERIFYING } ?: tasks.first()
    val fraction = if (active.status == ModelUiStatus.DOWNLOADING)
        active.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) else null
    val detail = when {
        active.status == ModelUiStatus.VERIFYING -> "正在校验"
        active.status.name == "QUEUED" -> "正在排队"
        fraction != null -> "已下载 ${(fraction * 100).roundToInt()}%"
        else -> "下载或排队中，等待进度"
    }
    val indicator = modifier.fillMaxWidth().height(3.dp).testTag("model-download-indicator")
        .semantics { contentDescription = "${active.name}，$detail，共 ${tasks.size} 个任务" }
    if (fraction == null) LinearProgressIndicator(modifier = indicator)
    else LinearProgressIndicator(progress = { fraction }, modifier = indicator)
}
