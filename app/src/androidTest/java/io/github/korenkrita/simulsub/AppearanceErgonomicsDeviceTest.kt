package io.github.korenkrita.simulsub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import io.github.korenkrita.simulsub.icons.IconBackground
import io.github.korenkrita.simulsub.icons.IconStyle
import io.github.korenkrita.simulsub.icons.LauncherIconChoice
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 用实际组件测量窄栏文字和触控区域，回调只更新内存，不切换真实桌面图标。 */
@RunWith(Parameterized::class)
class AppearanceErgonomicsDeviceTest(private val width: Int, private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()

    companion object {
        /** 同时覆盖两种手机宽度的普通字号与两倍字号，避免仅修复单一尺寸。 */
        @JvmStatic
        @Parameterized.Parameters(name = "宽度={0}dp，字号={1}")
        fun configurations(): List<Array<Any>> = listOf(
            arrayOf<Any>(320, 1f), arrayOf<Any>(360, 1f),
            arrayOf<Any>(320, 2f), arrayOf<Any>(360, 2f),
        )
    }

    /** 模式名称保持单行且整项可点，选择后单选语义与回调状态一致。 */
    @Test fun themeModesRemainReadableAndTouchable() {
        var state by mutableStateOf(SettingsUiState())
        compose.setContent {
            NarrowAppearance {
                AppearanceSettings(state, SimulSubActions(onThemeChange = { state = state.copy(theme = it) }), false, null)
            }
        }
        UiThemeMode.entries.forEach { mode ->
            revealText(mode.title).assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithText(mode.title).assertIsSelected()
            assertCompleteText(mode.title, singleLine = true)
            compose.runOnIdle { assertEquals(mode, state.theme) }
        }
        screenshot("主题模式")
    }

    /** 每个色盘选中后名称和勾选标记仍有足够空间，不依赖语义原文判断可读性。 */
    @Test fun selectedPaletteLabelsRemainOnOneLine() {
        var state by mutableStateOf(SettingsUiState(dynamicColors = false))
        compose.setContent {
            NarrowAppearance {
                AppearanceSettings(state, SimulSubActions(onThemeColorChange = {
                    state = state.copy(themeColorId = it)
                }), false, null)
            }
        }
        ThemePalettes.entries.forEach { palette ->
            revealText(palette.title).assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithText(palette.title).assertIsSelected()
            assertCompleteText(palette.title, singleLine = true)
        }
        screenshot("主题色板")
    }

    /** 两倍字号下摘要占满卡片正文宽度，避免图标挤出狭长文字列。 */
    @Test fun savedIconAndCustomColorSummariesHaveReadingWidth() {
        val state = SettingsUiState(
            dynamicColors = false, themeColorId = "custom",
            launcherIcon = LauncherIconChoice(style = IconStyle.MONO, background = IconBackground.SAGE),
        )
        val iconSummary = "鼠尾草绿 · 有飘落花瓣"
        val customSummary = "已选 · ${themeColorHex(state.customThemeSeed)}"
        compose.setContent {
            NarrowAppearance { AppearanceSettings(state, SimulSubActions(), false, null) }
        }
        listOf(iconSummary, customSummary).forEach { summary ->
            revealText(summary)
            screenshot(if (summary == iconSummary) "图标摘要" else "自定义颜色摘要")
            assertCompleteText(summary, minimumAvailableWidth = if (fontScale >= 1.5f) 180f else 0f)
        }
    }

    /** 惰性列表仅组合屏内项，先用列表查找再滚动，避免把未组合误判为控件缺失。 */
    private fun revealText(text: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        return compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    /** 保存真实排版供逐图核对，目录同时标记测试栏宽和字号。 */
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.externalCacheDir, "appearance-ergonomics/$width-$fontScale").apply { mkdirs() }
        try {
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    /** 仅覆盖当前组合的密度和测量宽度，不修改设备字号、屏幕配置或持久化偏好。 */
    @Composable
    private fun NarrowAppearance(content: @Composable () -> Unit) {
        val density = LocalDensity.current.density
        CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
            SimulSubTheme(SettingsUiState()) {
                Box(Modifier.requiredWidth(width.dp).fillMaxHeight()) { content() }
            }
        }
    }

    /** 读取真实文字排版，检查裁剪和溢出，并对短选项验证单行可读性。 */
    private fun assertCompleteText(text: String, singleLine: Boolean = false, minimumAvailableWidth: Float = 0f) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue("应获得文字测量结果：$text", results.isNotEmpty())
        results.forEach { layout ->
            assertFalse("文字不应被裁剪或溢出：$text；大小=${layout.size}；约束=${layout.layoutInput.constraints}；行数=${layout.lineCount}；横向溢出=${layout.didOverflowWidth}；纵向溢出=${layout.didOverflowHeight}", layout.hasVisualOverflow)
            if (singleLine) assertEquals("短选项应保持单行：$text", 1, layout.lineCount)
            val availableWidth = layout.layoutInput.constraints.maxWidth / layout.layoutInput.density.density
            assertTrue("摘要可用宽度不足：$text，当前为 $availableWidth dp", availableWidth >= minimumAvailableWidth)
        }
    }
}
