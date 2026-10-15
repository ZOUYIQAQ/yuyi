package io.github.korenkrita.simulsub

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 验证官方设置组件的真实交互、来源声明及新的显示范围选择。 */
class MaterialSettingsUiTest {
    @get:Rule val compose = createComposeRule()

    /** 关于页必须在安装包内声明Fork与原作者，并可离线打开完整许可。 */
    @Test fun aboutDeclaresForkAuthorAndOfflineLicense() {
        var upstreamOpened = false
        compose.setContent { SimulSubApp(SimulSubUiState(destination = UiDestination.SETTINGS),
            SimulSubActions(onOpenUpstreamRepository = { upstreamOpened = true })) }
        scrollToText("关于"); compose.onNodeWithText("关于").performClick()
        compose.onNodeWithText("基于 simul-subtitle 的 Android Fork").assertIsDisplayed()
        scrollToText("KorenKrita"); compose.onNodeWithText("KorenKrita").assertIsDisplayed()
        scrollToText("查看上游项目"); compose.onNodeWithText("查看上游项目").performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(upstreamOpened) }
        scrollToText("查看完整许可"); compose.onNodeWithText("查看完整许可").performClick()
        compose.onNodeWithText("Permission is hereby granted", substring = true).assertExists()
        compose.onNode(hasText("Permission is hereby granted", substring = true) and hasText("Copyright (c) 2026 KorenKrita", substring = true)).assertExists()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.onNodeWithText("个性化").assertExists()
    }

    /** 只显示当前保留模式，旧行数和范围不叠加，尺寸控件仍可直接操作。 */
    @Test fun retentionModesAreExclusiveAndDimensionsRemainSelectable() {
        var state by mutableStateOf(SimulSubUiState())
        compose.setContent { SimulSubApp(state, SimulSubActions(
            onNavigate = { state = state.copy(destination = it) },
            onSubtitleStyleChange = { state = state.copy(settings = state.settings.copy(subtitle = it)) },
        )) }
        compose.onNode(isSelectable() and hasText("字幕")).performClick()
        compose.runOnIdle { assertEquals(UiDestination.SUBTITLE, state.destination) }
        compose.onNodeWithContentDescription("暂时收起字幕预览").performClick()
        scrollToText("字幕保留方式")
        compose.onNodeWithText("按句数").assertIsDisplayed()
        compose.onNodeWithText("最少显示").assertDoesNotExist()
        compose.onNodeWithText("最多显示").assertDoesNotExist()
        compose.onNodeWithText("最多显示行数").assertDoesNotExist()
        compose.onNodeWithText("最多译文字数", substring = true).assertDoesNotExist()
        scrollToText("显示句数"); compose.onNodeWithText("显示句数").performClick()
        compose.onNode(hasText("5 句") and isSelectable()).performClick()
        compose.runOnIdle { assertEquals(5, state.settings.subtitle.maxSentences) }
        compose.onNodeWithText("保留方式").performClick()
        compose.onNode(hasText("按字数") and isSelectable()).performClick()
        compose.onNodeWithText("显示句数").assertDoesNotExist()
        scrollToText("最多译文字数")
        compose.onNodeWithContentDescription("最多译文字数").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(10f) }
        compose.runOnIdle {
            assertEquals(CaptionLimitMode.CHARACTERS, state.settings.subtitle.limitMode)
            assertEquals(10, state.settings.subtitle.keepChars)
        }
        compose.onNodeWithContentDescription("最多译文字数")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(300f) }
        compose.runOnIdle { assertEquals(300, state.settings.subtitle.keepChars) }
        compose.onNodeWithText("保留方式").performClick()
        compose.onNode(hasText("按句数") and isSelectable()).performClick()
        compose.onNodeWithText("最多译文字数", substring = true).assertDoesNotExist()
        scrollToText("字幕宽度"); compose.onNodeWithContentDescription("字幕宽度").performScrollTo().assertIsDisplayed()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.8f) }
        compose.runOnIdle { assertEquals(0.8f, state.settings.subtitle.widthFraction, 0.001f) }
        scrollToText("字幕高度"); compose.onNodeWithContentDescription("字幕高度").performScrollTo().assertIsDisplayed()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.2f) }
        compose.runOnIdle { assertEquals(0.2f, state.settings.subtitle.heightFraction, 0.001f) }
    }

    /** 主题模式和色盘使用真实选中状态，选择后仍可返回设置首页。 */
    @Test fun appearanceModePaletteAndBackWork() {
        var state by mutableStateOf(SimulSubUiState(destination = UiDestination.SETTINGS))
        compose.setContent { SimulSubApp(state, SimulSubActions(
            onThemeChange = { state = state.copy(settings = state.settings.copy(theme = it)) },
            onThemeColorChange = { state = state.copy(settings = state.settings.copy(themeColorId = it, dynamicColors = false)) },
        )) }
        compose.onNodeWithText("外观").performClick()
        scrollToText("深色"); compose.onNodeWithText("深色").performClick().assertIsSelected()
        scrollToText("蔷薇"); compose.onNodeWithText("蔷薇").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.onNodeWithText("个性化").assertExists()
    }

    /** 在真实懒列表中定位未组合的设置项，不通过放宽断言掩盖控件不可达。 */
    private fun scrollToText(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text, substring = true))
    }
}
