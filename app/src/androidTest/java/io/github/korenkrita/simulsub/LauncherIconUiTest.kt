package io.github.korenkrita.simulsub

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.korenkrita.simulsub.icons.*
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 图标选项先预览再确认，取消不能变更真实桌面选择。 */
class LauncherIconUiTest {
    @get:Rule val compose = createComposeRule()

    /** 默认完整矢量图含花瓣，版本、背景、花瓣确认后一次派发正确组合。 */
    @Test fun choicePreviewDispatchesOnlyAfterConfirmation() {
        val saved = mutableListOf<LauncherIconChoice>()
        var choice by mutableStateOf(LauncherIconChoice.DEFAULT)
        compose.setContent { SimulSubTheme(SettingsUiState()) {
            LauncherIconSettings(choice) { saved += it; choice = it }
        } }
        compose.onNodeWithContentDescription("选择应用图标").performClick()
        compose.onNodeWithText("图标样式").performClick()
        compose.onNodeWithText("纯色").performClick()
        compose.onNodeWithText("图标背景").performScrollTo().performClick()
        compose.onNodeWithText("奶油白").performClick()
        compose.onNodeWithText("飘落花瓣").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(saved.isEmpty()) }
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertEquals(1, saved.size)
            assertEquals(IconStyle.MONO, saved.single().style)
            assertEquals(IconBackground.CREAM, saved.single().background)
            assertFalse(saved.single().petals)
            assertTrue(saved.single().followTheme)
        }
        compose.onNodeWithText("奶油白 · 无飘落花瓣").assertExists()
    }

    /** 取消后重新打开，恢复包含所有元素的默认矢量图，不保留未保存草稿。 */
    @Test fun cancellingKeepsFullOriginalChoice() {
        val saved = mutableListOf<LauncherIconChoice>()
        compose.setContent { SimulSubTheme(SettingsUiState()) { LauncherIconSettings(LauncherIconChoice.DEFAULT) { saved += it } } }
        compose.onNodeWithContentDescription("选择应用图标").performClick()
        compose.onNodeWithText("飘落花瓣").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithContentDescription("选择应用图标").performClick()
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(saved.isEmpty()) }
    }
    /** 手动色样在确认前只改变草稿，保存后携带独立的跟随状态。 */
    @Test fun manualColorIsSavedOnlyOnConfirmation() {
        val saved = mutableListOf<LauncherIconChoice>()
        val initial = LauncherIconChoice(style = IconStyle.MONO)
        compose.setContent { SimulSubTheme(SettingsUiState()) { LauncherIconSettings(initial) { saved += it } } }
        compose.onNodeWithContentDescription("选择应用图标").performClick()
        compose.onNodeWithContentDescription("图标颜色：海蓝").performScrollTo().performClick()
        compose.onNodeWithContentDescription("图标颜色：海蓝").assertIsSelected()
        compose.runOnIdle { assertTrue(saved.isEmpty()) }
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertEquals(IconTone.OCEAN, saved.single().resolvedTone)
            assertFalse(saved.single().followTheme)
        }
    }

    /** 取消选色再次打开时恢复自动跟随，未保存色样不残留。 */
    @Test fun cancelledColorDoesNotReplaceFollowTheme() {
        val saved = mutableListOf<LauncherIconChoice>()
        compose.setContent { SimulSubTheme(SettingsUiState()) {
            LauncherIconSettings(LauncherIconChoice(style = IconStyle.MONO)) { saved += it }
        } }
        compose.onNodeWithContentDescription("选择应用图标").performClick()
        compose.onNodeWithContentDescription("图标颜色：鸢尾").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithContentDescription("选择应用图标").performClick()
        compose.onNodeWithContentDescription("图标颜色：鸢尾").performScrollTo().assertIsNotSelected()
        compose.runOnIdle { assertTrue(saved.isEmpty()) }
    }

}
