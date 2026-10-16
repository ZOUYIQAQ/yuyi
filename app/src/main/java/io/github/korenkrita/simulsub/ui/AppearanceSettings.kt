package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import android.os.Build

/** 外观预览使用当前主题的真实色彩角色，模式、配色和动态色分别设置。 */
@Composable
internal fun AppearanceSettings(state: SettingsUiState, actions: SimulSubActions, wide: Boolean, navigation: (@Composable () -> Unit)?) {
    var colorPickerOpen by rememberSaveable { mutableStateOf(false) }
    val dark = state.theme == UiThemeMode.DARK || (state.theme == UiThemeMode.SYSTEM && androidx.compose.foundation.isSystemInDarkTheme())
    if (colorPickerOpen) ThemeColorPickerDialog(
        initialSeed = state.customThemeSeed,
        dark = dark,
        onDismiss = { colorPickerOpen = false },
        onConfirm = { seed ->
            colorPickerOpen = false
            actions.onCustomThemeColorChange(seed)
        },
    )
    PageColumns(wide, header = navigation, first = {
        item { ThemePreviewCard() }
        item { SupportingText("预览会随配色立即变化。悬浮字幕颜色在「字幕样式」中单独设置。") }
        item { LauncherIconSettings(state.launcherIcon, actions.onLauncherIconChange) }
    }, second = {
        item { SettingsCard {
            SectionHeading("主题模式")
            ThemeModePicker(state.theme, actions.onThemeChange)
        } }
        item { SettingsCard {
            SectionHeading("主题配色", "选一种喜欢的色调。")
            ThemePalettePicker(state, actions)
            CustomThemeEntry(state, dark, onClick = { colorPickerOpen = true })
            ToggleSetting("动态取色", if (Build.VERSION.SDK_INT >= 31) "使用系统壁纸配色。" else "需要 Android 12 及以上，当前使用手动配色。", state.dynamicColors,
                enabled = Build.VERSION.SDK_INT >= 31, onChange = actions.onDynamicColorsChange)
        } }
    })
}

/** 窄栏或大字号使用整行单选，避免三等分挤压「跟随系统」和选中标记。 */
@Composable
private fun ThemeModePicker(selected: UiThemeMode, onSelect: (UiThemeMode) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 300.dp || fontScale >= 1.5f) {
            Column(Modifier.fillMaxWidth().selectableGroup()) {
                UiThemeMode.entries.forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .selectable(selected = selected == mode, role = Role.RadioButton, onClick = { onSelect(mode) })
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = selected == mode, onClick = null)
                        Text(mode.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        } else {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                UiThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = selected == mode, onClick = { onSelect(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, UiThemeMode.entries.size),
                        border = SegmentedButtonDefaults.borderStroke(color = MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.heightIn(min = 52.dp),
                    ) { Text(mode.title) }
                }
            }
        }
    }
}

/** 自定义入口始终可见，色样、十六进制值和选中文字共同表达当前状态。 */
@Composable
private fun CustomThemeEntry(state: SettingsUiState, dark: Boolean, onClick: () -> Unit) {
    val selected = !state.dynamicColors && state.themeColorId == "custom"
    val scheme = remember(state.customThemeSeed, dark) { ThemePalettes.scheme("custom", dark, state.customThemeSeed) }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = "自定义主题颜色"
            this.selected = selected
        },
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.5f
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.size(48.dp).clip(MaterialTheme.shapes.small)) {
                        Box(Modifier.weight(1f).fillMaxHeight().background(scheme.primary))
                        Box(Modifier.weight(1f).fillMaxHeight().background(scheme.primaryContainer))
                    }
                    if (stacked) Spacer(Modifier.weight(1f))
                    else CustomThemeSummary(state, selected, Modifier.weight(1f))
                    Icon(if (selected) Icons.Outlined.Check else Icons.Outlined.Palette, null, Modifier.size(24.dp))
                }
                if (stacked) CustomThemeSummary(state, selected, Modifier.fillMaxWidth())
            }
        }
    }
}

/** 文字占满摘要列，避免非整数密度下自然宽度取整裁剪末尾；紧凑布局移到色样下方。 */
@Composable
private fun CustomThemeSummary(state: SettingsUiState, selected: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("自定义颜色", Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall)
        Text(if (selected) "已选 · ${themeColorHex(state.customThemeSeed)}" else "${themeColorHex(state.customThemeSeed)} · 点击选色",
            Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
    }
}

/** 以同传按钮、卡片和字幕占位展示真实主题，便于直接比较配色。 */
@Composable
private fun ThemePreviewCard() {
    val colors = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.GraphicEq, null, tint = colors.primary)
                Text("羽译", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 12.dp))
                Icon(Icons.Outlined.MoreHoriz, null, tint = colors.onSurfaceVariant)
            }
            Surface(shape = MaterialTheme.shapes.large, color = colors.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("花朵绽放出来吧", style = MaterialTheme.typography.titleMedium, color = colors.onPrimaryContainer)
                    Text("花が咲くでしょう～", style = MaterialTheme.typography.bodyMedium, color = colors.onPrimaryContainer)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(Modifier.weight(1f).height(52.dp), shape = MaterialTheme.shapes.medium, color = colors.secondaryContainer) {}
                Surface(Modifier.weight(1f).height(52.dp), shape = MaterialTheme.shapes.medium, color = colors.tertiaryContainer,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant)) {}
            }
        }
    }
}

/** 根据实际栏宽和字号等分色盘，大字号窄屏保留完整名称及选中标记。 */
@Composable
private fun ThemePalettePicker(state: SettingsUiState, actions: SimulSubActions) {
    val dark = state.theme == UiThemeMode.DARK || (state.theme == UiThemeMode.SYSTEM && androidx.compose.foundation.isSystemInDarkTheme())
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 内边距、勾选图标与间距共四十八单位，名称按两个汉字随字号预留宽度。
        val minimumCellWidth = 48.dp + 28.dp * fontScale
        val columns = ((maxWidth + 12.dp) / (minimumCellWidth + 12.dp)).toInt().coerceIn(1, 3)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ThemePalettes.entries.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { palette ->
                        val selected = !state.dynamicColors && state.themeColorId == palette.id
                        val scheme = ThemePalettes.scheme(palette.id, dark)
                        Surface(
                            onClick = { actions.onThemeColorChange(palette.id) },
                            modifier = Modifier.weight(1f).semantics { this.selected = selected },
                            border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                            shape = MaterialTheme.shapes.medium,
                            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(Modifier.fillMaxWidth().height(24.dp).clip(MaterialTheme.shapes.extraSmall)) {
                                    Box(Modifier.weight(1f).fillMaxHeight().background(scheme.primary))
                                    Box(Modifier.weight(1f).fillMaxHeight().background(scheme.secondaryContainer))
                                    Box(Modifier.weight(1f).fillMaxHeight().background(scheme.tertiaryContainer))
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(palette.title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                    if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
