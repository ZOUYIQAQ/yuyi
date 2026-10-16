package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 字幕全部尺寸与保留策略按旧界面能力提供，示例明确与真实悬浮窗区分。 */
@Composable
internal fun SubtitleSettings(style: SubtitleStyleUiState, actions: SimulSubActions, wide: Boolean, navigation: (@Composable () -> Unit)?, finalOnly: Boolean = false) {
    Box(Modifier.fillMaxSize()) {
        // 底部预览恢复按钮独立悬浮，末项需能完整滚动到按钮上方。
        PageColumns(wide, header = navigation, bottomPadding = 96.dp, first = {
            item("subtitle-preview") {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SectionHeading("字幕预览示例", "可拖动、切换横竖屏或暂时收起。完整画面等比缩小，放大查看字幕局部。")
                    if (style.palettes.isNotEmpty()) SubtitlePalettePicker(style, actions.onSubtitleStyleChange)
                    else SupportingText("字幕配色尚未加载；示例使用黑底白字。")
                    FilledTonalButton(onClick = actions.onResetOverlayPosition, modifier = Modifier.heightIn(min = 48.dp)) { Text("重置悬浮字幕位置") }
                }
            }
        }, second = {
            item("subtitle-look") {
                SettingsCard {
                    SectionHeading("文字与背景", "调整后自动保存，并立即应用到悬浮字幕。")
                    ValueSlider("文字大小", style.textSp, style.minTextSp..style.maxTextSp.coerceAtLeast(style.minTextSp + 1f), "${style.textSp.roundToInt()} sp", steps = (style.maxTextSp - style.minTextSp).roundToInt().minus(1).coerceAtLeast(0)) { actions.onSubtitleStyleChange(style.copy(textSp = it.roundToInt().toFloat())) }
                    ValueSlider("背景不透明度", style.backgroundAlpha, 0f..1f, "${(style.backgroundAlpha * 100).roundToInt()}%", steps = 19) { actions.onSubtitleStyleChange(style.copy(backgroundAlpha = it)) }
                    ToggleSetting("显示原文", "在译文上方显示识别出的原文。", style.showOriginal) { actions.onSubtitleStyleChange(style.copy(showOriginal = it)) }
                    ToggleSetting("显示耗时信息", "显示识别和翻译耗时，便于检查字幕延迟。", style.showStats) { actions.onSubtitleStyleChange(style.copy(showStats = it)) }
                    ToggleSetting("预留临时字幕空间",
                        if (finalOnly) "关闭「仅显示定稿」后可调整。" else "临时字幕消失后仍保留一行背景，避免黑框收缩。",
                        style.reserveDraftSpace, enabled = !finalOnly) {
                        actions.onSubtitleStyleChange(style.copy(reserveDraftSpace = it))
                    }
                }
            }
            item("subtitle-size") {
                SettingsCard {
                    SectionHeading("字幕框尺寸")
                    ValueSlider("字幕宽度", style.widthFraction, style.minWidthFraction.coerceIn(0.1f, 0.99f)..1f, "屏幕的 ${(style.widthFraction * 100).roundToInt()}%") { actions.onSubtitleStyleChange(style.copy(widthFraction = it)) }
                    ValueSlider("字幕高度", style.heightFraction, 0f..0.4f, if (style.heightFraction <= 0f) "自动随内容变化" else "屏幕的 ${(style.heightFraction * 100).roundToInt()}%", steps = 7) { actions.onSubtitleStyleChange(style.copy(heightFraction = it)) }
                    SupportingText("默认固定高度，内容超出时向上滚动；拖到最左可选自动高度，黑框会平滑伸缩。")
                }
            }
            item("subtitle-history") {
                SettingsCard {
                    SectionHeading("字幕保留方式", "只按所选方式保留最新字幕，不叠加其他内容限制。")
                    OptionPicker("保留方式", style.limitMode.name, CaptionLimitMode.entries.map { UiOption(it.name, it.title) }) {
                        actions.onSubtitleStyleChange(style.copy(limitMode = CaptionLimitMode.valueOf(it)))
                    }
                    when (style.limitMode) {
                        CaptionLimitMode.SENTENCES -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SupportingText("只统计正式字幕，临时字幕不占句数。")
                            OptionPicker("显示句数", style.maxSentences.coerceIn(1, 10).toString(), (1..10).map { UiOption(it.toString(), "$it 句") }) {
                                actions.onSubtitleStyleChange(style.copy(maxSentences = it.toInt()))
                            }
                        }
                        CaptionLimitMode.CHARACTERS -> {
                            ValueSlider("最多译文字数", style.keepChars.coerceIn(10, 300).toFloat(), 10f..300f,
                                "${style.keepChars.coerceIn(10, 300)} 字", steps = 28) {
                                actions.onSubtitleStyleChange(style.copy(keepChars = ((it / 10).roundToInt() * 10).coerceIn(10, 300)))
                            }
                            SupportingText("只计算译文字数。单句超长时显示最新尾部，原文与原始定稿不会被改写。")
                        }
                    }
                }
            }
        })
        FloatingSubtitlePreview(style, finalOnly = finalOnly)
    }
}
