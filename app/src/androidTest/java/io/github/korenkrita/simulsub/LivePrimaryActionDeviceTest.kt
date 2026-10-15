package io.github.korenkrita.simulsub

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 主操作的位置与派发使用真实布局检查，不用静态截图代替可达性。 */
class LivePrimaryActionDeviceTest {
    @get:Rule val compose = createComposeRule()

    /** 手机首屏即可开始，滚动到语言设置后按钮位置不变，点击仍发起同一操作。 */
    @Test fun phoneActionIsVisibleAndStableWhileScrolling() {
        var starts = 0
        compose.setContent {
            SimulSubTheme(SettingsUiState()) {
                Box(Modifier.requiredSize(360.dp, 640.dp)) {
                    LiveScreen(readyState(), SimulSubActions(onStart = { starts++ }), wide = false)
                }
            }
        }
        val button = compose.onNodeWithTag("live-primary-action").assertIsDisplayed().assertHeightIsAtLeast(72.dp)
        val before = button.fetchSemanticsNode().boundsInRoot
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("声音与译文"))
        button.assertIsDisplayed().performClick()
        val after = button.fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
        compose.runOnIdle { assertEquals(1, starts) }
    }

    /** 低矮窗口和200%字号下仍完整显示停止按钮，并派发停止而非开始。 */
    @Test fun shortLargeTextWindowKeepsStopReachable() {
        var stops = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                SimulSubTheme(SettingsUiState()) {
                    Box(Modifier.requiredSize(640.dp, 300.dp)) {
                        LiveScreen(readyState().copy(running = true), SimulSubActions(onStop = { stops++ }), wide = false)
                    }
                }
            }
        }
        compose.onNodeWithTag("live-primary-action").assertIsDisplayed().assertHeightIsAtLeast(72.dp)
        compose.onNodeWithText("停止同传").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    /** 平板主操作在页面底部居中，限宽且保持大触控面积，点击只派发一次开始。 */
    @Test fun tabletActionIsLimitedAndCenteredAtBottom() {
        var starts = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.3f, 1f)) {
                SimulSubTheme(SettingsUiState()) {
                    Box(Modifier.requiredSize(1040.dp, 640.dp).testTag("live-test-page")) {
                        LiveScreen(readyState(), SimulSubActions(onStart = { starts++ }), wide = true)
                    }
                }
            }
        }
        val page = compose.onNodeWithTag("live-test-page").fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithTag("live-primary-action").assertIsDisplayed()
            .assertHeightIsAtLeast(72.dp).assertWidthIsAtLeast(320.dp)
        val bounds = action.fetchSemanticsNode().boundsInRoot
        assertTrue("平板主操作宽度不应超过480dp", bounds.width <= 480f * 0.3f + 1f)
        assertEquals("主操作必须相对页面居中", page.center.x, bounds.center.x, 1f)
        assertTrue("按钮两侧必须保留明显留白", bounds.width < page.width * 0.6f)
        assertTrue("主操作应完整位于页面底部", bounds.top > page.top + page.height * 0.75f && bounds.bottom <= page.bottom)
        compose.onAllNodesWithText("准备就绪").assertCountEquals(1)
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText("仅显示定稿"))
        assertEquals("滚动不能改变主操作位置", bounds, action.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        action.performClick()
        compose.runOnIdle { assertEquals(1, starts) }
    }

    /** 模拟配置完整的状态，仅验证界面，不调用权限、付费接口或识别服务。 */
    private fun readyState() = LiveUiState(statusTitle = "准备就绪", modelLabel = "ReazonSpeech", translationLabel = "测试翻译服务", canStart = true)
}
