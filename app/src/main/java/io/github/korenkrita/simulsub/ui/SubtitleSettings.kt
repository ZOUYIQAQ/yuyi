package io.github.korenkrita.simulsub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import io.github.korenkrita.simulsub.CaptionOverlayView
import kotlin.math.roundToInt

/** 字幕全部尺寸与保留策略按旧界面能力提供，示例明确与真实悬浮窗区分。 */
@Composable
internal fun SubtitleSettings(style: SubtitleStyleUiState, actions: SimulSubActions, wide: Boolean, navigation: @Composable () -> Unit) {
    PageColumns(wide, header = navigation, first = {
        item("subtitle-preview") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionHeading("字幕预览示例", "预览字幕的文字与配色。同传时可以拖动悬浮字幕，并调整大小。")
                SubtitleExample(style)
                if (style.palettes.isNotEmpty()) OptionPicker("字幕配色", style.paletteId, style.palettes.map { UiOption(it.id, it.label) }) { actions.onSubtitleStyleChange(style.copy(paletteId = it)) }
                else SupportingText("字幕配色尚未加载；示例使用黑底白字。")
                OutlinedButton(onClick = actions.onResetOverlayPosition, modifier = Modifier.heightIn(min = 48.dp)) { Text("重置悬浮字幕位置") }
            }
        }
    }, second = {
        item("subtitle-look") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeading("文字与背景", "调整后自动保存，并立即应用到悬浮字幕。")
                ValueSlider("文字大小", style.textSp, style.minTextSp..style.maxTextSp.coerceAtLeast(style.minTextSp + 1f), "${style.textSp.roundToInt()} sp", steps = (style.maxTextSp - style.minTextSp).roundToInt().minus(1).coerceAtLeast(0)) { actions.onSubtitleStyleChange(style.copy(textSp = it.roundToInt().toFloat())) }
                ValueSlider("背景不透明度", style.backgroundAlpha, 0f..1f, "${(style.backgroundAlpha * 100).roundToInt()}%", steps = 19) { actions.onSubtitleStyleChange(style.copy(backgroundAlpha = it)) }
                ToggleSetting("显示原文", "在译文上方显示识别出的原文。", style.showOriginal) { actions.onSubtitleStyleChange(style.copy(showOriginal = it)) }
                ToggleSetting("显示耗时信息", "显示识别和翻译耗时，便于检查字幕延迟。", style.showStats) { actions.onSubtitleStyleChange(style.copy(showStats = it)) }
                HorizontalDivider()
            }
        }
        item("subtitle-size") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeading("字幕框尺寸")
                OptionPicker("最多显示行数", style.maxLines.toString(), listOf(UiOption("0", "不限行数")) + (1..20).map { UiOption(it.toString(), "最多 $it 行") }) {
                    actions.onSubtitleStyleChange(style.copy(maxLines = it.toInt()))
                }
                SupportingText("按实际换行计算，原文和译文合计。超过上限保留最新行；耗时和状态提示不计入。屏幕高度不足时会进一步收起。")
                ValueSlider("字幕宽度", style.widthFraction, style.minWidthFraction.coerceIn(0.1f, 0.99f)..1f, "屏幕的 ${(style.widthFraction * 100).roundToInt()}%") { actions.onSubtitleStyleChange(style.copy(widthFraction = it)) }
                ValueSlider("字幕高度", style.heightFraction, 0f..0.4f, if (style.heightFraction <= 0f) "自动随内容变化" else "屏幕的 ${(style.heightFraction * 100).roundToInt()}%", steps = 7) { actions.onSubtitleStyleChange(style.copy(heightFraction = it)) }
                SupportingText("高度为零时自动适应内容。固定高度容纳不下全部字幕时，悬浮窗显示最新内容；本页示例保留文字可读性。")
                HorizontalDivider()
            }
        }
        item("subtitle-history") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeading("保留最近几句", "句数和字数都超过设定值后，最早的一句才会收起。")
                ValueSlider("至少保留句数", style.keepSentences.toFloat(), 1f..6f, "${style.keepSentences} 句", steps = 4) { actions.onSubtitleStyleChange(style.copy(keepSentences = it.roundToInt())) }
                ValueSlider("收起旧句的字数阈值", style.keepChars.toFloat(), 0f..300f, "${style.keepChars} 字", steps = 29) { actions.onSubtitleStyleChange(style.copy(keepChars = (it / 10).roundToInt() * 10)) }
            }
        }
    })
}

/** 直接使用真实原生字幕视图预览，行数、换行、字号和透明度与浮窗一致。 */
@Composable
private fun SubtitleExample(style: SubtitleStyleUiState) {
    val density = LocalDensity.current.density
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("示例画面", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.fillMaxWidth().heightIn(min = 200.dp), contentAlignment = Alignment.BottomCenter) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth(style.widthFraction.coerceIn(0.1f, 1f)).testTag("subtitle-native-preview"),
                    factory = { context -> CaptionOverlayView(context, interactive = false) },
                    update = { view ->
                        view.setColors(CaptionOverlayView.palette(style.paletteId))
                        view.applyStyle(style.textSp, style.backgroundAlpha)
                        view.setMaxContentHeight((280 * density).roundToInt(), (72 * density).roundToInt())
                        view.setCaptionHeight((style.heightFraction * 360 * density).roundToInt())
                        view.setMaxCaptionLines(style.maxLines)
                        view.setLines(listOf(
                            CaptionOverlayView.Line("这一句已经说完，是正式字幕。", if (style.showOriginal) "ここまでが確定した字幕です。" else null, true),
                            CaptionOverlayView.Line("正在说的这一句会继续更新。", if (style.showOriginal) "次の言葉を聞き取っています。" else null, false),
                        ), if (style.showStats) "识别 120ms · 翻译 320ms" else null)
                    },
                )
            }
            Text("宽 ${(style.widthFraction * 100).roundToInt()}% · ${if (style.heightFraction <= 0) "自动高度" else "高 ${(style.heightFraction * 100).roundToInt()}%"} · ${if (style.maxLines == 0) "不限行数" else "最多 ${style.maxLines} 行"}", style = MaterialTheme.typography.labelMedium)
        }
    }
}
