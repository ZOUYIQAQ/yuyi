package io.github.korenkrita.simulsub

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 使用真实布局、点击与滚动检查控制舱的人体工学位置，不启动系统授权或识别服务。 */
class LivePrimaryActionDeviceTest {
    @get:Rule val compose = createComposeRule()

    /** 手机首屏中部即可开始，滚动配置后可找回控制舱并停止，两个状态都避开底部字幕区。 */
    @Test fun phoneActionIsVisibleInMiddleAndCanBeFoundAfterScrolling() {
        var starts = 0
        var stops = 0
        var state by mutableStateOf(readyState())
        show(actions = SimulSubActions(onStart = { starts++ }, onStop = { stops++ }), state = { state })
        val page = viewport()
        val action = compose.onNodeWithTag("live-primary-action").assertIsDisplayed().assertIsEnabled()
            .assertHeightIsAtLeast(144.dp).assertWidthIsAtLeast(144.dp)
        val initial = action.fetchSemanticsNode().boundsInRoot
        assertAboveSubtitles(page, initial)
        assertTrue("手机主操作中心应在首屏中部", initial.center.y in
            (page.top + page.height * 0.4f)..(page.top + page.height * 0.65f))
        assertTrue("手机不能恢复为全宽长条", initial.width < page.width * 0.6f)
        action.performClick()
        compose.runOnIdle {
            assertEquals(1, starts)
            state = state.copy(running = true)
        }
        assertAboveSubtitles(page, action.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("停止同传").assertIsDisplayed()
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasText("仅显示定稿"))
        list.performScrollToNode(hasTestTag("live-primary-action"))
        action.assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    /** 低横屏与200%字号允许滚动到完整停止按钮，按钮和标签均可见且真实派发停止。 */
    @Test fun shortLargeTextWindowKeepsStopReachableByScrolling() {
        var stops = 0
        show(width = 640.dp, height = 300.dp, fontScale = 2f,
            actions = SimulSubActions(onStop = { stops++ }), state = { readyState().copy(running = true) })
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("live-primary-action"))
        val action = compose.onNodeWithTag("live-primary-action").assertIsDisplayed().assertHeightIsAtLeast(144.dp)
        compose.onNodeWithText("停止同传").assertIsDisplayed()
        assertInside(viewport(), action.fetchSemanticsNode().boundsInRoot)
        action.performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    /** 常规字号的低横屏优先露出控制舱，停止按钮首屏可见且不会落入底部字幕区。 */
    @Test fun shortWindowShowsStopAboveSubtitlesWithoutScrolling() {
        var stops = 0
        show(width = 640.dp, height = 300.dp, actions = SimulSubActions(onStop = { stops++ }),
            state = { readyState().copy(running = true) })
        val action = compose.onNodeWithTag("live-primary-action").assertIsDisplayed().assertHeightIsAtLeast(144.dp)
        assertAboveSubtitles(viewport(), action.fetchSemanticsNode().boundsInRoot)
        action.performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    /** 平板左侧配置、右侧独立紧凑控制；配置滚动不牵动右栏，开始和停止均避开字幕区。 */
    @Test fun tabletActionIsCompactAndInIndependentRightPanel() {
        var starts = 0
        var stops = 0
        var state by mutableStateOf(readyState())
        show(width = 1040.dp, wide = true,
            actions = SimulSubActions(onStart = { starts++ }, onStop = { stops++ }), state = { state })
        val page = viewport()
        val panel = compose.onNodeWithTag("live-control-panel").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val languages = compose.onNodeWithTag("live-language-controls").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithTag("live-primary-action").assertIsDisplayed()
            .assertHeightIsAtLeast(144.dp).assertWidthIsAtLeast(144.dp)
        val bounds = action.fetchSemanticsNode().boundsInRoot
        assertTrue("语言配置应位于控制面板左侧", languages.right <= panel.left)
        assertTrue("主操作应属于右侧控制面板", bounds.center.x > page.center.x)
        assertInside(panel, bounds)
        assertAboveSubtitles(page, bounds)
        assertTrue("平板主操作应保持紧凑而非长条", bounds.width / bounds.height in 0.9f..1.3f)
        assertTrue("主操作不应填满右栏", bounds.width < panel.width * 0.6f)
        compose.onAllNodesWithText("准备就绪").assertCountEquals(1)
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText("仅显示定稿"))
        assertEquals("左侧配置滚动不应移动右侧控制", bounds, action.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        action.performClick()
        compose.runOnIdle {
            assertEquals(1, starts)
            state = state.copy(running = true, elapsed = "00:42")
        }
        assertAboveSubtitles(page, action.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("停止同传").assertIsDisplayed()
        action.performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    /** 配置未齐时主控制继续引导模型、翻译与权限，不能误触发开始。 */
    @Test fun incompleteConfigurationKeepsDirectGuidance() {
        var state by mutableStateOf(readyState().copy(canStart = false, modelLabel = "未启用模型"))
        val events = mutableListOf<String>()
        show(actions = SimulSubActions(onStart = { events += "开始" },
            onNavigate = { events += it.name }, onPermission = { events += it }), state = { state })
        val action = compose.onNodeWithTag("live-primary-action")
        compose.onNodeWithText("选择识别模型").assertIsDisplayed()
        action.performClick()
        compose.runOnIdle { state = readyState().copy(canStart = false, translationLabel = "未配置") }
        compose.onNodeWithText("配置翻译服务").assertIsDisplayed()
        action.performClick()
        compose.runOnIdle {
            state = readyState().copy(canStart = false,
                permissions = listOf(PermissionUiState("audio", "录制播放音频", "授权后可识别媒体声音。", false)))
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("live-primary-action"))
        compose.onNodeWithText("开启录制播放音频").assertIsDisplayed()
        action.assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("MODELS", "SETTINGS", "audio"), events) }
    }

    /** 忙碌状态阻断开始与停止的真实触摸，解除忙碌后同一按钮恢复停止动作。 */
    @Test fun busyActionRejectsTouchesAndRestoresStop() {
        var state by mutableStateOf(readyState().copy(busy = true))
        var starts = 0
        var stops = 0
        show(actions = SimulSubActions(onStart = { starts++ }, onStop = { stops++ }), state = { state })
        val action = compose.onNodeWithTag("live-primary-action")
        action.assertIsDisplayed().assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText("正在处理…").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(running = true) }
        action.assertIsDisplayed().assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(0, starts)
            assertEquals(0, stops)
            state = state.copy(busy = false)
        }
        compose.onNodeWithText("停止同传").assertIsDisplayed()
        action.assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    /** 窄屏200%字号下配置、控制与长服务名称均可滚动触达，主操作不越过横向边界。 */
    @Test fun narrowLargeTextWindowKeepsControlsAndServicesReachable() {
        val model = "ReazonSpeech 完整模型名称与识别服务说明"
        show(width = 280.dp, height = 640.dp, fontScale = 2f,
            state = { readyState().copy(modelLabel = model) })
        // 大字号时优先控制舱，开屏不必先滚过两行语言配置。
        compose.onNodeWithTag("live-primary-action").assertIsDisplayed()
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasTestTag("live-primary-action"))
        val action = compose.onNodeWithTag("live-primary-action").assertIsDisplayed().assertHeightIsAtLeast(144.dp)
        assertInside(viewport(), action.fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("开始同传").assertIsDisplayed()
        list.performScrollToNode(hasText("使用其他字幕语言"))
        compose.onNodeWithText("使用其他字幕语言").assertIsDisplayed().performClick()
        list.performScrollToNode(hasText("自定义字幕语言"))
        compose.onNodeWithText("自定义字幕语言").assertIsDisplayed()
        list.performScrollToNode(hasText(model))
        compose.onNodeWithText(model).assertIsDisplayed()
        list.performScrollToNode(hasText("翻译设置"))
        compose.onNodeWithText("翻译设置").assertIsDisplayed()
    }

    /** 将真实组件放入有界测试窗口，压低像素密度以在同一设备覆盖手机、低横屏与平板。 */
    private fun show(width: Dp = 360.dp, height: Dp = 640.dp, fontScale: Float = 1f,
                     wide: Boolean = false, actions: SimulSubActions = SimulSubActions(), state: () -> LiveUiState) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(if (wide) 0.3f else 0.8f, fontScale)) {
                SimulSubTheme(SettingsUiState()) {
                    Box(Modifier.requiredSize(width, height)) { LiveScreen(state(), actions, wide) }
                }
            }
        }
    }

    /** 从实际页面节点读取可用视口，位置断言使用相对比例而非设备绝对坐标。 */
    private fun viewport(): Rect = compose.onNodeWithTag("live-control-viewport").fetchSemanticsNode().boundsInRoot

    /** 首屏控制完整位于上方三分之二区域，给默认底部字幕保留独立空间。 */
    private fun assertAboveSubtitles(page: Rect, action: Rect) {
        assertInside(page, action)
        assertTrue("主操作不得进入底部字幕区域", action.bottom <= page.top + page.height * 2f / 3f)
    }

    /** 校验整个触控区在父区域内，避免仅中心点可见掩盖边缘越界。 */
    private fun assertInside(parent: Rect, child: Rect) {
        assertTrue("完整触控区应位于可用区域内", child.left >= parent.left && child.right <= parent.right &&
            child.top >= parent.top && child.bottom <= parent.bottom)
    }

    /** 配置完整且不依赖系统权限或外部服务的预览状态。 */
    private fun readyState() = LiveUiState(statusTitle = "准备就绪", statusDetail = "点按开始，识别应用播放的声音。",
        modelLabel = "ReazonSpeech", translationLabel = "测试翻译服务", canStart = true,
        sourceOptions = listOf(UiOption("ja", "日语")), targetOptions = listOf(UiOption("zh", "简体中文")),
        sceneOptions = listOf(UiOption("live", "B站直播")), sceneId = "live")
}
