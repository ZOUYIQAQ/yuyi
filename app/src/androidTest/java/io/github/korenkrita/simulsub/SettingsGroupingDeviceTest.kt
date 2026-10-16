package io.github.korenkrita.simulsub

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 手机和平板共用真实应用入口，配置和回调只保存在各测试实例的内存中。 */
class SettingsGroupingDeviceTest {
    private val tablet: Boolean
        get() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.configuration.screenWidthDp >= 840
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(SimulSubUiState())
    private val finalOnlyEvents = mutableListOf<Boolean>()

    /** 高级入口必须是字幕与翻译卡片的最后一项，不能留在应用分组或重复出现。 */
    @Test fun advancedEntryBelongsToSubtitleTranslationGroup() {
        show()
        navigate("设置")
        compose.onAllNodes(hasScrollToIndexAction()).assertCountEquals(if (tablet) 2 else 1)
        scrollToText("高级设置")
        compose.onAllNodesWithText("高级设置").assertCountEquals(1)
        compose.onNodeWithText("高级设置")
            .assert(hasAnyAncestor(hasTestTag("settings-subtitle-translation-group")))
        compose.onNodeWithText("上下文与在线辅助")
            .assert(hasAnyAncestor(hasTestTag("settings-subtitle-translation-group")))
        val entries = compose.onAllNodes(hasClickAction() and
            hasAnyAncestor(hasTestTag("settings-subtitle-translation-group")))
        entries.assertCountEquals(5)
        entries.onLast().assert(hasText("高级设置"))
        screenshot("设置分组")
        scrollToText("系统权限")
        compose.onNodeWithText("系统权限")
            .assert(hasAnyAncestor(hasTestTag("settings-application-group")))
        compose.onAllNodes(hasText("高级设置") and
            hasAnyAncestor(hasTestTag("settings-application-group"))).assertCountEquals(0)
    }

    /** 点击分组内的真实入口打开上下文详情，顶栏返回恢复设置首页而非同传页。 */
    @Test fun advancedEntryAndTopBarBackUseRealSettingsRoute() {
        show()
        navigate("设置")
        scrollToText("高级设置")
        compose.onNodeWithText("高级设置").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("翻译上下文").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回设置").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(UiDestination.SETTINGS, state.destination) }
        compose.onNode(isSelectable() and hasText("设置")).assertIsSelected()
        compose.onNodeWithContentDescription("返回设置").assertDoesNotExist()
        scrollToText("高级设置")
        compose.onNodeWithText("高级设置")
            .assert(hasAnyAncestor(hasTestTag("settings-subtitle-translation-group")))
    }

    /** 真实同传开关展示定稿说明，关闭到开启再关闭分别派发准确布尔值且即时回显。 */
    @Test fun finalOnlyToggleExplainsAndDispatchesBothBooleanValues() {
        show()
        scrollToText("仅显示定稿")
        val toggle = compose.onNode(isToggleable() and hasText("仅显示定稿"))
        toggle.assertIsDisplayed().assertIsOff()
            .assert(hasText("开启后隐藏临时字幕，只显示定稿内容。"))
        screenshot("定稿开关")
        toggle.performClick().assertIsOn()
        compose.runOnIdle {
            assertEquals(listOf(true), finalOnlyEvents)
            assertEquals(true, state.live.finalOnly)
        }
        toggle.performClick().assertIsOff()
        compose.runOnIdle {
            assertEquals(listOf(true, false), finalOnlyEvents)
            assertEquals(false, state.live.finalOnly)
        }
    }

    /** 用受约束的逻辑窗口渲染真实根组件，不读取偏好、不启动服务或网络操作。 */
    private fun show() {
        state = SimulSubUiState(live = LiveUiState(canStart = true, finalOnly = false))
        finalOnlyEvents.clear()
        val actions = SimulSubActions(
            onNavigate = { state = state.copy(destination = it) },
            onFinalOnly = { value ->
                finalOnlyEvents += value
                state = state.copy(live = state.live.copy(finalOnly = value))
            },
        )
        // 使用设备的真实尺寸和系统密度，不用低密度伪装平板，避免与系统安全区不一致。
        compose.setContent { SimulSubApp(state, actions) }
        compose.waitForIdle()
    }

    /** 保存真实合成画面供手机、平板视觉验收，不修改应用偏好。 */
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(300)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "无法取得验收截图" }
        try {
            val directory = java.io.File(instrumentation.targetContext.externalCacheDir, "release-v1.0.5")
            check(directory.isDirectory || directory.mkdirs()) { "无法创建验收截图目录" }
            val output = java.io.File(directory, "$name-${if (tablet) "平板" else "手机"}.png")
            output.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    /** 只点击可选择的主导航，避开同名顶栏标题。 */
    private fun navigate(label: String) {
        compose.onNode(isSelectable() and hasText(label)).performClick()
        compose.waitForIdle()
    }

    /** 在真实懒列表中逐列定位，并要求目标最终出现在可见区域。 */
    private fun scrollToText(label: String) {
        var failure: AssertionError? = null
        val lists = compose.onAllNodes(hasScrollToIndexAction())
        for (index in lists.fetchSemanticsNodes().indices) {
            try {
                lists[index].performScrollToNode(hasText(label))
                compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
                return
            } catch (error: AssertionError) { failure = error }
        }
        throw AssertionError("找不到可见入口：$label", failure)
    }
}
