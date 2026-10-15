package io.github.korenkrita.simulsub

import androidx.compose.runtime.*
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 自定义主题只渲染独立界面，验证草稿校验、颜色精度和取消边界。 */
class CustomThemeDeviceTest {
    @get:Rule val compose = createComposeRule()

    /** 空值、非法字符、位数错误和透明色都不能提交。 */
    @Test fun invalidColorCannotBeSaved() {
        val saved = mutableListOf<Int>()
        compose.setContent {
            SimulSubTheme(SettingsUiState()) {
                ThemeColorPickerDialog(ThemePalettes.DEFAULT_CUSTOM_SEED, false, {}, { saved += it })
            }
        }
        listOf("", "#GG1122", "#12345", "#1234567", "#80336699").forEach { invalid ->
            compose.onNodeWithText("颜色值").performTextReplacement(invalid)
            compose.onNodeWithText("保存").assertIsNotEnabled()
        }
        compose.runOnIdle { assertTrue("非法草稿不能派发颜色", saved.isEmpty()) }
    }

    /** 精确输入仅在确认时派发一次完整的不透明 RGB，保存后显示选中状态。 */
    @Test fun confirmationDispatchesExactRgbAndSelectsCustomTheme() {
        val saved = mutableListOf<Int>()
        var state by mutableStateOf(SettingsUiState(dynamicColors = true))
        compose.setContent {
            SimulSubTheme(state) {
                AppearanceSettings(state, SimulSubActions(onCustomThemeColorChange = {
                    saved += it
                    state = state.copy(customThemeSeed = it, themeColorId = "custom", dynamicColors = false)
                }), false, null)
            }
        }
        compose.onNodeWithContentDescription("自定义主题颜色").performScrollTo().performClick()
        compose.onNodeWithText("颜色值").performTextReplacement("#12ab34")
        compose.runOnIdle { assertTrue("编辑草稿时不能提前派发", saved.isEmpty()) }
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals(listOf(0xFF12AB34.toInt()), saved) }
        compose.onNodeWithContentDescription("自定义主题颜色").performScrollTo().assertIsSelected()
        compose.onNodeWithText("已选 · #12AB34").assertExists()
    }

    /** 取消丢弃编辑中的颜色，再次打开应恢复已保存的种子。 */
    @Test fun cancellationDoesNotDispatchOrRetainDraft() {
        val saved = mutableListOf<Int>()
        compose.setContent {
            SimulSubTheme(SettingsUiState()) {
                AppearanceSettings(SettingsUiState(), SimulSubActions(onCustomThemeColorChange = { saved += it }), false, null)
            }
        }
        compose.onNodeWithContentDescription("自定义主题颜色").performScrollTo().performClick()
        compose.onNodeWithText("颜色值").performTextReplacement("#FFFFFF")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("保存").assertDoesNotExist()
        compose.runOnIdle { assertTrue("取消不得派发颜色", saved.isEmpty()) }
        compose.onNodeWithContentDescription("自定义主题颜色").performScrollTo().performClick()
        compose.onNodeWithText("颜色值").assertTextContains("#D38569")
    }

    /** 三个可访问滑杆共同生成可预期的 RGB，不依赖拖动坐标。 */
    @Test fun hsvSlidersDispatchExactRgb() {
        val saved = mutableListOf<Int>()
        compose.setContent {
            SimulSubTheme(SettingsUiState()) {
                ThemeColorPickerDialog(ThemePalettes.DEFAULT_CUSTOM_SEED, false, {}, { saved += it })
            }
        }
        listOf("色相" to 120f, "饱和度" to 1f, "明度" to 1f).forEach { (label, value) ->
            compose.onNodeWithContentDescription(label).performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        }
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals(listOf(0xFF00FF00.toInt()), saved) }
    }

    /** 自定义配色尊重种子与明暗模式，预设不受自定义种子影响。 */
    @Test fun customSchemeUsesSeedAndPresetsRemainStable() {
        assertEquals(0xFFD38569.toInt(), ThemePalettes.DEFAULT_CUSTOM_SEED)
        val customLight = ThemePalettes.scheme("custom", false, 0xFF365F82.toInt())
        val customDark = ThemePalettes.scheme("custom", true, 0xFF365F82.toInt())
        assertEquals(themeRoles(ThemePalettes.scheme("ocean", false)), themeRoles(customLight))
        assertEquals(themeRoles(ThemePalettes.scheme("ocean", true)), themeRoles(customDark))
        assertTrue("浅深色主题应有所区别", customLight.primary != customDark.primary)
        ThemePalettes.entries.forEach { palette ->
            listOf(false, true).forEach { dark ->
                assertEquals(themeRoles(ThemePalettes.scheme(palette.id, dark)),
                    themeRoles(ThemePalettes.scheme(palette.id, dark, 0xFF00FF00.toInt())))
            }
        }
        assertEquals(themeRoles(ThemePalettes.scheme(ThemePalettes.DEFAULT_ID, false)),
            themeRoles(ThemePalettes.scheme("旧主题", false)))
    }

    /** 比较实际颜色角色，避免将配色对象的引用相等误作颜色相等。 */
    private fun themeRoles(scheme: ColorScheme): List<Color> = listOf(
        scheme.primary, scheme.onPrimary, scheme.primaryContainer, scheme.onPrimaryContainer,
        scheme.secondary, scheme.onSecondary, scheme.tertiary, scheme.onTertiary,
        scheme.surface, scheme.onSurface, scheme.surfaceContainer, scheme.outline,
    )
}
