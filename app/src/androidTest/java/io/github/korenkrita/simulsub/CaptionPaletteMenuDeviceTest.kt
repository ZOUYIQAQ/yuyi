package io.github.korenkrita.simulsub

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.roundToInt

/** 只挂载配色组件并使用内存回调，不启动主界面、录音、网络服务或偏好保存。 */
@RunWith(Parameterized::class)
class CaptionPaletteMenuDeviceTest(private val width: Int, private val fontScale: Float) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val palettes = CaptionOverlayView.PALETTES.map {
        CaptionPaletteUiState(it.id, it.label, it.zh, it.bg, it.ja)
    }

    companion object {
        /** 窄手机与平板栏宽均覆盖普通和两倍字号；实际窗口宽度仍由运行设备限制。 */
        @JvmStatic
        @Parameterized.Parameters(name = "配色菜单宽度={0}dp，字号={1}")
        fun configurations(): List<Array<Any>> = listOf(
            arrayOf<Any>(320, 1f), arrayOf<Any>(320, 2f),
            arrayOf<Any>(800, 1f), arrayOf<Any>(800, 2f),
        )
    }

    /** 逐个核对生产调色板的背景合成像素及原文、译文实际排版色，包含全透明和全不透明。 */
    @Test fun previewsRenderProductionColorsAndCurrentOpacity() {
        var palette by mutableStateOf(palettes.first())
        var alpha by mutableStateOf(0f)
        show { SubtitlePalettePreview(palette, alpha) }
        palettes.forEach { expected ->
            listOf(0f, 0.37f, 0.8f, 1f).forEach { opacity ->
                compose.runOnIdle { palette = expected; alpha = opacity }
                val image = compose.onNodeWithTag("caption-palette-preview-${expected.id}", useUnmergedTree = true)
                    .captureToImage()
                // 色卡左上内侧六个逻辑像素处无文字、无圆角裁剪，底图固定为棋盘深格。
                val sample = with(compose.density) { 6.dp.toPx() }.roundToInt()
                val actual = image.toPixelMap()[sample, sample]
                val expectedBackground = Color(
                    (expected.backgroundArgb and 0x00FFFFFF) or ((opacity * 255).toInt() shl 24),
                ).compositeOver(Color(0xFF404040))
                assertColor(expectedBackground, actual)
                assertText("caption-palette-original-${expected.id}", Color(expected.originalArgb))
                assertText("caption-palette-translated-${expected.id}", Color(expected.foregroundArgb))
            }
        }
    }

    /** 输入背景自带透明通道时仍应被当前设置替换，而不是相乘或直接使用原始背景。 */
    @Test fun opacityReplacesThePaletteAlphaWithProductionTruncation() {
        val palette = CaptionPaletteUiState("custom", "自定义配色", 0xFF112233.toInt(), 0x19445566)
        assertEquals(0x00445566, captionPalettePreviewBackground(palette, 0f))
        assertEquals(0x5E445566, captionPalettePreviewBackground(palette, 0.37f))
        assertEquals(0xFF445566.toInt(), captionPalettePreviewBackground(palette, 1f))
    }

    /** 菜单打开即展示每项两种字色，比较与滚动不应派发任何保存动作。 */
    @Test fun browsingAndBackDoNotSaveOrChangeTheStyle() {
        val initial = configuredStyle()
        val events = mutableListOf<SubtitleStyleUiState>()
        show { SubtitlePalettePicker(initial) { events += it } }
        compose.onNodeWithText("字幕配色").performClick()
        option(palettes.first().label).assertIsSelected()
        palettes.forEach { palette ->
            option(palette.label).performScrollTo().assertIsDisplayed()
            assertText("caption-palette-original-${palette.id}", Color(palette.originalArgb))
            assertText("caption-palette-translated-${palette.id}", Color(palette.foregroundArgb))
            assertCompleteLabel(palette.label)
        }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle { assertTrue("仅浏览与返回不能触发配置保存", events.isEmpty()) }
        compose.onNodeWithText(palettes.first().label).assertIsDisplayed()
    }

    /** 从长列表滚动到末项并选择，两倍字号仍可点选，回调完整保留所有其他样式字段。 */
    @Test fun longMenuLastOptionIsSelectableAndOnlyChangesPaletteId() {
        val longPalettes = (1..30).map { index ->
            palettes[index % palettes.size].copy(id = "palette-$index", label = "配色 $index：花朵字幕原文与译文")
        }
        val initial = configuredStyle().copy(paletteId = longPalettes.first().id, palettes = longPalettes)
        var style by mutableStateOf(initial)
        val events = mutableListOf<SubtitleStyleUiState>()
        show { SubtitlePalettePicker(style) { style = it; events += it } }
        compose.onNodeWithText("字幕配色").performClick()
        option(longPalettes.first().label).assertIsSelected()
        val last = longPalettes.last()
        option(last.label).performScrollTo().assertIsDisplayed().assertIsNotSelected()
        assertCompleteLabel(last.label)
        assertText("caption-palette-original-${last.id}", Color(last.originalArgb))
        assertText("caption-palette-translated-${last.id}", Color(last.foregroundArgb))
        compose.runOnIdle { assertTrue("滚动末项前不能保存", events.isEmpty()) }
        option(last.label).performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(initial.copy(paletteId = last.id), style)
            assertEquals(listOf(initial.copy(paletteId = last.id)), events)
        }
        compose.onNodeWithText("字幕配色").performClick()
        option(last.label).performScrollTo().assertIsSelected()
    }

    /** 使用非默认样式，避免回调错误重建默认状态时被测试遗漏。 */
    private fun configuredStyle(): SubtitleStyleUiState = SubtitleStyleUiState(
        textSp = 27f, backgroundAlpha = 0.37f, widthFraction = 0.74f, heightFraction = 0.31f,
        keepSentences = 6, keepChars = 230, maxLines = 7, showOriginal = false, showStats = true,
        paletteId = palettes.first().id, palettes = palettes, minTextSp = 12f, maxTextSp = 48f,
        minWidthFraction = 0.33f, maxSentences = 8, limitMode = CaptionLimitMode.CHARACTERS,
        reserveDraftSpace = false,
    )

    /** 测试字号仅覆盖组合局部，栏宽限制为当前窗口可容纳范围，不修改系统与用户配置。 */
    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                SimulSubTheme(SettingsUiState()) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
                        Column(Modifier.widthIn(max = width.dp).fillMaxWidth()) { content() }
                    }
                }
            }
        }
    }

    /** 限定独立弹窗内的选项，避免与入口当前名称重名。 */
    private fun option(label: String): SemanticsNodeInteraction =
        compose.onNode(hasText(label) and hasAnyAncestor(isPopup()))

    /** 检查标签真实排版与完整字符范围，允许换行但不允许裁剪。 */
    private fun assertCompleteLabel(label: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(label) and hasAnyAncestor(isPopup()), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue("名称必须有实际排版结果", results.isNotEmpty())
        results.forEach { layout ->
            assertFalse("配色名称不能裁字：$label，${layout.size}，${layout.layoutInput.constraints}，段落=${layout.multiParagraph.width}×${layout.multiParagraph.height}，行=${layout.lineCount}", layout.hasVisualOverflow)
            assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1))
        }
    }

    /** 从真实文字布局读取颜色，避免只校验测试语义而遗漏实际绘制样式。 */
    private fun assertText(tag: String, color: Color) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue("示例文字必须有实际排版结果：$tag", results.isNotEmpty())
        results.forEach { layout ->
            assertEquals("字幕文字必须使用调色板真实颜色：$tag", color, layout.layoutInput.style.color)
            assertFalse("示例文字不能裁字：$tag，${layout.size}，${layout.layoutInput.constraints}，段落=${layout.multiParagraph.width}×${layout.multiParagraph.height}，行=${layout.lineCount}", layout.hasVisualOverflow)
        }
    }

    /** 容许设备合成时单通道两个色阶的舍入差异，仍可发现错误底色及透明度。 */
    private fun assertColor(expected: Color, actual: Color) {
        assertEquals("背景红色通道", expected.red.toDouble(), actual.red.toDouble(), 2.0 / 255)
        assertEquals("背景绿色通道", expected.green.toDouble(), actual.green.toDouble(), 2.0 / 255)
        assertEquals("背景蓝色通道", expected.blue.toDouble(), actual.blue.toDouble(), 2.0 / 255)
        assertEquals("棋盘底图合成后应不透明", 1.0, actual.alpha.toDouble(), 2.0 / 255)
    }
}
