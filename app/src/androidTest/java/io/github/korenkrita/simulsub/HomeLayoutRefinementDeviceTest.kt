package io.github.korenkrita.simulsub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextLayoutResult
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 首页布局回归只使用内存状态，不启动识别、网络请求或系统授权。 */
@RunWith(Parameterized::class)
class HomeLayoutRefinementDeviceTest(private val dark: Boolean) {
    @get:Rule val compose = createComposeRule()
    private val restoration = StateRestorationTester(compose)
    private var state by mutableStateOf(readyState())

    companion object {
        /** 所有首页验收同时覆盖明暗主题，避免透明摘要只在单一主题下成立。 */
        @JvmStatic @Parameterized.Parameters(name = "深色主题={0}")
        fun themes(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }

    /** 手机先选语言再开始，服务、场景与定稿控制依次排列，主操作开屏完整可见。 */
    @Test fun phoneKeepsRequestedOrderAndVisiblePrimaryAction() {
        var starts = 0
        show(actions = SimulSubActions(onStart = { starts++ }))
        val language = compose.onNodeWithTag("live-language-controls").assertIsDisplayed().getUnclippedBoundsInRoot()
        val panel = compose.onNodeWithTag("live-control-panel").getUnclippedBoundsInRoot()
        assertTrue("语言必须位于准备就绪卡之前", language.bottom <= panel.top)
        val initialServices = compose.onNodeWithTag("service-selection").getUnclippedBoundsInRoot()
        assertTrue("准备就绪卡必须位于服务之前", panel.bottom <= initialServices.top)
        compose.onNodeWithTag("live-primary-action").assertIsDisplayed().assertHeightIsAtLeast(72.dp).performClick()
        compose.runOnIdle { assertEquals(1, starts) }
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasTestTag("live-scene-controls"))
        val scene = compose.onNodeWithTag("live-scene-controls").getUnclippedBoundsInRoot()
        val services = compose.onNodeWithTag("service-selection").getUnclippedBoundsInRoot()
        assertTrue("服务选择必须位于场景之前", services.bottom <= scene.top)
        list.performScrollToNode(hasTestTag("live-final-only"))
        val finalOnly = compose.onNodeWithTag("live-final-only").assertIsDisplayed().getUnclippedBoundsInRoot()
        val serviceBottom = compose.onNodeWithTag("service-selection").getUnclippedBoundsInRoot().bottom
        assertTrue("定稿控制必须位于服务选择之后", serviceBottom <= finalOnly.top)
    }

    /** 服务菜单继续排除管理占位项，快捷管理入口仍然直接派发原有动作。 */
    @Test fun readyServiceMenuAndManagementShortcutsRemainAvailable() {
        state = readyState().copy(listeningServices = readyState().listeningServices +
            listOf(UiOption("catalog", "不应出现的模型目录"), UiOption("manage", "不应出现的管理占位")))
        val events = mutableListOf<String>()
        show(actions = SimulSubActions(onOpenListeningSettings = { events += "听写" }, onOpenApiSettings = { events += "翻译" }))
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasTestTag("service-selection"))
        compose.onNode(hasAnyAncestor(hasTestTag("listening-service-picker")) and hasClickAction()).performClick()
        compose.onNodeWithText("不应出现的模型目录").assertDoesNotExist()
        compose.onNodeWithText("不应出现的管理占位").assertDoesNotExist()
        compose.onNode(hasText("本地模型") and hasAnyAncestor(isPopup())).assertIsDisplayed().performClick()
        compose.onNodeWithText("管理听写").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("管理翻译").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(listOf("听写", "翻译"), events) }
    }

    /** 平板双倍字号保留双列，各列分别滚动，长状态不挤压主按钮。 */
    @Test fun tabletLargeFontKeepsPrimaryActionAndFinalControlReachable() {
        state = readyState().copy(statusDetail = "较长的准备状态说明应完整换行，不能挤占主操作的触控区域。")
        show(tablet = true, fontScale = 2f)
        val lists = compose.onAllNodes(hasScrollToIndexAction()).assertCountEquals(2)
        lists[1].performScrollToNode(hasTestTag("live-primary-action"))
        compose.onNodeWithTag("live-primary-action").assertIsDisplayed().assertHeightIsAtLeast(72.dp)
        lists[0].performScrollToNode(hasTestTag("live-final-only"))
        compose.onNodeWithTag("live-final-only").assertIsDisplayed()
    }

    /** 平板左侧服务和场景、右侧语言与就绪卡，主按钮和费用属于同一张限宽卡。 */
    @Test fun tabletUsesBoundedCardWithIntegratedCosts() {
        show(tablet = true)
        val panel = compose.onNodeWithTag("live-control-panel").assertIsDisplayed()
        val bounds = panel.getUnclippedBoundsInRoot()
        assertTrue("平板卡片不得铺满窗口", bounds.right - bounds.left <= 640.dp)
        val services = compose.onNodeWithTag("service-selection").assertIsDisplayed().getUnclippedBoundsInRoot()
        val languages = compose.onNodeWithTag("live-language-controls").assertIsDisplayed().getUnclippedBoundsInRoot()
        val scene = compose.onNodeWithTag("live-scene-controls").getUnclippedBoundsInRoot()
        assertTrue("服务和场景必须位于就绪卡左侧", services.right <= bounds.left && scene.right <= bounds.left)
        assertTrue("语言必须与就绪卡对齐并位于其上方", languages.left == bounds.left && languages.bottom <= bounds.top)
        compose.onNode(hasTestTag("live-primary-action") and hasAnyAncestor(hasTestTag("live-control-panel")))
            .assertIsDisplayed().assertHeightIsAtLeast(72.dp)
        compose.onNode(hasTestTag("live-cost-summary") and hasAnyAncestor(hasTestTag("live-control-panel")))
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    /** 明暗主题的费用背景均与准备就绪卡一致，摘要仍能打开费用明细。 */
    @Test fun embeddedCostsShareCardColorAndKeepDetailsAction() {
        var opens = 0
        show(actions = SimulSubActions(onOpenCosts = { opens++ }))
        val card = compose.onNodeWithTag("live-control-panel").captureToImage().toPixelMap()
        val summary = compose.onNodeWithTag("live-cost-summary").assertIsDisplayed()
        val pixels = summary.captureToImage().toPixelMap()
        assertEquals("计价区域应延续控制卡底色", card[card.width / 2, 2].toArgb(), pixels[pixels.width / 2, 2].toArgb())
        summary.performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    /** 直译不显示无效场景，但保留服务之后的定稿控制和原回调。 */
    @Test fun directTranslationKeepsFinalOnlyAtEnd() {
        state = readyState().copy(listeningMode = ListeningMode.ONLINE)
        var finalOnly = false
        show(actions = SimulSubActions(onFinalOnly = { finalOnly = it }))
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasTestTag("live-final-only"))
        compose.onNodeWithTag("live-scene-controls").assertDoesNotExist()
        compose.onNodeWithText("仅显示定稿").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(finalOnly) }
    }

    /** 缺权限卡始终为首项，授权后稳定条目标识不应把已滚到末尾的位置重置。 */
    @Test fun permissionsStayFirstAndSavedScrollSurvivesRecreation() {
        state = readyState().copy(permissions = listOf(PermissionUiState("audio", "录制播放音频", "授权说明", false)))
        show()
        compose.onNodeWithTag("live-permission-warning").assertIsDisplayed()
        val warning = compose.onNodeWithTag("live-permission-warning").getUnclippedBoundsInRoot()
        val languages = compose.onNodeWithTag("live-language-controls").getUnclippedBoundsInRoot()
        assertTrue("缺权限提示必须位于语言选择之前", warning.bottom <= languages.top)
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasTestTag("live-final-only"))
        val before = compose.onNodeWithTag("live-final-only").getUnclippedBoundsInRoot()
        restoration.emulateSavedInstanceStateRestore()
        assertEquals("重建后应恢复定稿控制的位置", before, compose.onNodeWithTag("live-final-only").getUnclippedBoundsInRoot())
        compose.runOnIdle { state = state.copy(permissions = emptyList()) }
        compose.onNodeWithTag("live-final-only").assertIsDisplayed()
        list.performScrollToIndex(0)
        compose.onNodeWithTag("live-permission-warning").assertDoesNotExist()
        compose.onNodeWithTag("live-language-controls").assertIsDisplayed()
    }

    /** 窄屏双倍字号下主操作与长金额可完整滚动触达，触控区保持至少48dp。 */
    @Test fun largeFontKeepsActionAndLongAmountsReachable() {
        state = readyState().copy(costs = UsageCostUiState(todayAmount = "¥123456.7890", lastAmount = "¥98765.4321"))
        show(fontScale = 2f)
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasTestTag("live-primary-action"))
        compose.onNodeWithTag("live-primary-action").assertIsDisplayed().assertHeightIsAtLeast(72.dp)
        list.performScrollToNode(hasTestTag("live-cost-summary"))
        assertFullText("¥123456.7890")
        assertFullText("¥98765.4321")
        compose.onNodeWithTag("live-cost-summary").assertHeightIsAtLeast(48.dp)
        list.performScrollToNode(hasTestTag("live-final-only"))
        compose.onNodeWithTag("live-final-only").assertIsDisplayed()
    }

    /** 按宿主约束缩放测试视口，不修改唯一模拟器的分辨率和字体设置。 */
    private fun show(tablet: Boolean = false, fontScale: Float = 1f, actions: SimulSubActions = SimulSubActions()) {
        restoration.setContent {
            BoxWithConstraints {
                val width = if (tablet) 1040.dp else 360.dp
                val height = 740.dp
                val density = minOf(constraints.maxWidth / width.value, constraints.maxHeight / height.value)
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                    SimulSubTheme(SettingsUiState(theme = if (dark) UiThemeMode.DARK else UiThemeMode.LIGHT)) {
                        Box(Modifier.requiredSize(width, height)) { LiveScreen(state, actions, tablet) }
                    }
                }
            }
        }
    }

    /** 检查真实文字排版的行宽与段高，防止仅节点可见但金额实际被裁切。 */
    private fun assertFullText(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("金额必须提供排版结果", layouts.isNotEmpty())
        layouts.forEach { layout ->
            assertFalse("金额不应截断", layout.multiParagraph.didExceedMaxLines)
            assertTrue("金额段高不应裁切", layout.multiParagraph.height <= layout.size.height + 1f)
            repeat(layout.lineCount) { line ->
                assertFalse("金额不应省略", layout.isLineEllipsized(line))
                assertTrue("金额不应越过左边界", layout.getLineLeft(line) >= -1f)
                assertTrue("金额不应越过右边界", layout.getLineRight(line) <= layout.size.width + 1f)
            }
        }
    }

    /** 就绪服务和语言均为本地夹具，避免触发任何真实业务流程。 */
    private fun readyState() = LiveUiState(statusTitle = "准备就绪", statusDetail = "点按开始，识别应用播放的声音。",
        modelLabel = "已安装模型", translationReady = true, cloudReady = true, canStart = true,
        sourceOptions = listOf(UiOption("ja", "日语")), targetOptions = listOf(UiOption("zh", "简体中文")),
        sceneId = "live", sceneOptions = listOf(UiOption("live", "直播")),
        listeningServiceId = "local:fixture", listeningServices = listOf(UiOption("local:fixture", "本地模型")),
        translationServiceId = "translation", translationServices = listOf(UiOption("translation", "文字翻译")))
}
