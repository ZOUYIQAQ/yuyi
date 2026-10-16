package io.github.korenkrita.simulsub.ui

import android.widget.ImageView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.korenkrita.simulsub.icons.*

/** 外观页显示已保存的真实桌面图标，点开后预览组合，确认才切换。 */
@Composable
internal fun LauncherIconSettings(choice: LauncherIconChoice, onConfirm: (LauncherIconChoice) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    if (open) LauncherIconDialog(choice, { open = false }) { selected -> open = false; onConfirm(selected) }
    SettingsCard {
        SectionHeading("应用图标")
        Surface(onClick = { open = true }, shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "选择应用图标" }) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stacked = maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.5f
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        LauncherIconPreview(choice, Modifier.size(64.dp))
                        if (stacked) Spacer(Modifier.weight(1f))
                        else LauncherIconSummary(choice, Modifier.weight(1f))
                        Icon(Icons.Outlined.ChevronRight, null)
                    }
                    if (stacked) LauncherIconSummary(choice, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** 紧凑布局将当前组合说明放到图标下方，避免大字号被预览和箭头挤成竖列。 */
@Composable
private fun LauncherIconSummary(choice: LauncherIconChoice, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(choice.style.title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
        Text("${choice.background.title} · ${if (choice.petals) "有飘落花瓣" else "无飘落花瓣"}", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
        if (choice.style == IconStyle.MONO) Text(choice.colorSummary, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
    }
}

/** 图标草稿与当前桌面入口分开，取消和返回都不触发组件切换。 */
@Composable
internal fun LauncherIconDialog(initial: LauncherIconChoice, onDismiss: () -> Unit, onConfirm: (LauncherIconChoice) -> Unit) {
    val themeTone = LauncherIcons(LocalContext.current).themeTone()
    var key by rememberSaveable(initial) { mutableStateOf(initial.key) }
    var follow by rememberSaveable(initial) { mutableStateOf(initial.followTheme) }
    var toneId by rememberSaveable(initial) { mutableStateOf(initial.resolvedTone.id) }
    val parsed = LauncherIconChoice.parse(key)
    val draft = parsed.copy(followTheme = follow,
        resolvedTone = if (follow && parsed.style == IconStyle.MONO) themeTone else IconTone.entries.first { it.id == toneId })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择应用图标") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LauncherIconPreview(draft, Modifier.size(144.dp).align(Alignment.CenterHorizontally))
                OptionPicker("图标样式", draft.style.id, IconStyle.entries.map { UiOption(it.id, it.title) }) { id ->
                    key = draft.copy(style = IconStyle.entries.first { it.id == id }).key
                }
                if (draft.style == IconStyle.MONO) {
                    ToggleSetting("跟随主题", "跟随主题相近色", draft.followTheme) {
                        toneId = draft.resolvedTone.id
                        follow = it
                    }
                    IconTonePicker(draft.resolvedTone, draft.background, !draft.followTheme) { tone ->
                        toneId = tone.id
                        follow = false
                    }
                    SupportingText("桌面图标使用预设相近色，并按背景调整深浅。")
                }
                OptionPicker("图标背景", draft.background.id, IconBackground.entries.map { UiOption(it.id, it.title) }) { id ->
                    key = draft.copy(background = IconBackground.entries.first { it.id == id }).key
                }
                ToggleSetting("飘落花瓣", "显示或隐藏飘落装饰。", draft.petals) {
                    key = draft.copy(petals = it).key
                }
                SupportingText("保存后更新桌面图标。若系统启用了主题图标，颜色会跟随系统壁纸。")
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(draft) }, enabled = draft != initial) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 色样按可用宽度和字号排列，整项至少四十八单位高并保留文字选中状态。 */
@Composable
private fun IconTonePicker(tone: IconTone, background: IconBackground, manual: Boolean, onSelect: (IconTone) -> Unit) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 112.dp * fontScale * 2 + 8.dp) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("纯色颜色", style = MaterialTheme.typography.titleSmall)
            IconTone.entries.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { candidate ->
                        val selected = manual && candidate == tone
                        Surface(onClick = { onSelect(candidate) },
                            modifier = Modifier.weight(1f).semantics {
                                contentDescription = "图标颜色：${candidate.title}"
                                this.selected = selected
                            },
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(if (selected) 2.dp else 1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Row(Modifier.heightIn(min = 48.dp).padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(20.dp).background(Color(candidate.foregroundArgb(background)), CircleShape))
                                Text(candidate.title + if (selected) " ✓" else "", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 交给Android加载自适应资源，预览与启动器使用同一图层和系统遮罩。 */
@Composable
internal fun LauncherIconPreview(choice: LauncherIconChoice, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO } },
        update = { view -> view.setImageResource(choice.resourceId) },
    )
}
