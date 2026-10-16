package io.github.korenkrita.simulsub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 用内存状态验证手机与平板的真实入口，不授予系统权限、不启动服务或下载。 */
@RunWith(Parameterized::class)
class HomeNavigationDeviceTest(private val tablet: Boolean) {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(SimulSubUiState())
    private val savedApi = TranslationApiUiState(url = "https://example.com/v1", model = "已保存的测试模型")
    private val apiEvents = mutableListOf<String>()
    private val permissions = listOf(
        PermissionUiState("record", "测试录音权限", "录音授权说明", true),
        PermissionUiState("overlay", "测试悬浮权限", "悬浮授权说明", true),
        PermissionUiState("notification", "测试通知权限", "通知可选说明", false, required = false),
    )

    companion object {
        /** 同一组断言同时检查底部导航和宽屏侧栏。 */
        @JvmStatic @Parameterized.Parameters(name = "平板={0}")
        fun cases(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }

    /** 必需权限均已授予时，未授予的可选通知也不能让首页权限区重新出现。 */
    @Test fun grantedRequiredPermissionsHideHomePermissionSection() {
        show()
        scrollToText("当前服务")
        compose.onNodeWithText("需要系统权限").assertDoesNotExist()
        permissions.forEach { compose.onNodeWithText(it.title).assertDoesNotExist() }
        compose.onNodeWithText("去允许").assertDoesNotExist()
    }

    /** 最后一个必需权限从未授予变为已授予后，首页权限区立即移除。 */
    @Test fun grantingLastRequiredPermissionRemovesHomeSection() {
        show(permissions.map { if (it.id == "overlay") it.copy(granted = false) else it })
        scrollToText("测试悬浮权限")
        compose.onNodeWithText("测试悬浮权限").assertIsDisplayed()
        compose.onNodeWithText("去允许").performScrollTo().performClick()
        compose.onNodeWithText("需要系统权限").assertDoesNotExist()
        compose.onNodeWithText("测试悬浮权限").assertDoesNotExist()
        compose.runOnIdle { assertEquals(true, state.live.permissions.first { it.id == "overlay" }.granted) }
    }

    /** 会话中仍有内存字幕且打开原文和耗时时，首页也不能重新展示本次字幕。 */
    @Test fun homeNeverRendersInMemoryCaptions() {
        show()
        compose.runOnIdle {
            state = state.copy(live = state.live.copy(running = true, showOriginal = true, showStats = true,
                captions = listOf(CaptionUiState("仅内存", "内存原文不得展示", "内存译文不得展示", detail = "内存耗时不得展示"))))
        }
        scrollToText("当前服务")
        listOf("本次字幕", "内存原文不得展示", "内存译文不得展示", "内存耗时不得展示").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        // 控制舱随内容滚动，先回到它；首屏位置由专门的主操作测试覆盖。
        scrollToText("停止同传")
        compose.onNodeWithText("停止同传").assertIsDisplayed()
    }

    /** 主导航只有同传、字幕和设置；字幕页直接可达并提供真实的预览与设置控件。 */
    @Test fun threeMainDestinationsReachSubtitleDirectly() {
        show()
        compose.onAllNodes(isSelectable() and (hasText("同传") or hasText("字幕") or hasText("设置"))).assertCountEquals(3)
        compose.onNode(isSelectable() and hasText("模型库")).assertDoesNotExist()
        compose.onNode(isSelectable() and hasText("场景术语")).assertDoesNotExist()
        navigation("字幕").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(UiDestination.SUBTITLE, state.destination) }
        compose.onNodeWithText("字幕预览示例").assertExists()
        compose.onNodeWithContentDescription("暂时收起字幕预览").performClick()
        scrollToText("显示原文")
        compose.onNodeWithText("显示原文").assertIsDisplayed()
        navigation("同传").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(UiDestination.LIVE, state.destination) }
        navigation("设置").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(UiDestination.SETTINGS, state.destination) }
    }

    /** 模型和场景通过设置入口打开，顶栏返回各自恢复到设置首页。 */
    @Test fun settingsDetailsReturnToSettingsWithTopBack() {
        show()
        navigation("设置").performClick()
        for ((label, destination, heading) in listOf(
            Triple("模型库", UiDestination.MODELS, "离线识别模型"),
            Triple("场景与术语", UiDestination.SCENES, "内容场景"),
        )) {
            scrollToText(label)
            compose.onNodeWithText(label).performClick()
            compose.runOnIdle { assertEquals(destination, state.destination) }
            compose.onNodeWithText(heading).assertIsDisplayed()
            compose.onNodeWithContentDescription("返回设置").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(UiDestination.SETTINGS, state.destination) }
            compose.onNodeWithText("字幕与翻译").assertExists()
        }
    }

    /** 首页快捷入口保留来源信息，详情返回不能误跳到设置首页。 */
    @Test fun homeShortcutsReturnToLiveWithTopBack() {
        show()
        for ((label, destination) in listOf("模型库" to UiDestination.MODELS, "场景与术语" to UiDestination.SCENES)) {
            scrollToText(label)
            compose.onNodeWithText(label).performClick()
            compose.runOnIdle { assertEquals(destination, state.destination) }
            compose.onNodeWithContentDescription("返回同传").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(UiDestination.LIVE, state.destination) }
            scrollToText("开始同传")
            compose.onNodeWithText("开始同传").assertIsDisplayed()
        }
    }

    /** 首页翻译快捷入口返回同传；普通设置内的接口详情仍返回设置，不能沿用上次来源。 */
    @Test fun translationSettingsTopBackReturnsToItsActualSource() {
        show()
        scrollToText("翻译设置")
        compose.onNodeWithText("翻译设置").performClick()
        compose.runOnIdle { assertEquals(UiDestination.SETTINGS, state.destination) }
        compose.onNodeWithContentDescription("返回同传").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(UiDestination.LIVE, state.destination) }
        navigation("同传").assertIsSelected()
        navigation("设置").performClick()
        scrollToText("翻译 API")
        compose.onNodeWithText("翻译 API").performClick()
        compose.onNodeWithContentDescription("返回设置").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(UiDestination.SETTINGS, state.destination) }
        scrollToText("个性化")
        compose.onNodeWithText("个性化").assertIsDisplayed()
    }

    /** 首页来源的接口草稿取消返回时保持原文，确认放弃后先恢复配置，再准确返回同传。 */
    @Test fun dirtyTranslationShortcutKeepsDraftUntilDiscardThenReturnsLive() {
        show()
        scrollToText("翻译设置")
        compose.onNodeWithText("翻译设置").performClick()
        compose.runOnIdle { apiEvents.clear() }
        scrollToText("模型 ID")
        compose.onNode(hasSetTextAction() and hasText("模型 ID", substring = true)).performTextReplacement("尚未保存的模型")
        val draft = compose.runOnIdle { state.settings.api }
        compose.onNodeWithContentDescription("返回同传").performClick()
        compose.onNodeWithText("配置尚未保存").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.runOnIdle {
            assertEquals(UiDestination.SETTINGS, state.destination)
            assertEquals(draft, state.settings.api)
            assertEquals(true, state.settings.api.hasUnsavedChanges)
            assertEquals(emptyList<String>(), apiEvents)
        }
        compose.onNodeWithContentDescription("返回同传").assertIsDisplayed().performClick()
        compose.onNodeWithText("放弃修改").performClick()
        compose.runOnIdle {
            assertEquals(listOf("放弃", "导航:LIVE"), apiEvents)
            assertEquals(savedApi, state.settings.api)
            assertEquals(UiDestination.LIVE, state.destination)
        }
        navigation("同传").assertIsSelected()
        scrollToText("开始同传")
        compose.onNodeWithText("开始同传").assertIsDisplayed()
    }

    /** 首页隐藏已授予权限后，设置页仍能查看必需权限和未授予的可选权限。 */
    @Test fun permissionsRemainAvailableInSettings() {
        show()
        navigation("设置").performClick()
        scrollToText("系统权限")
        compose.onNodeWithText("系统权限").performClick()
        scrollToText("测试通知权限")
        compose.onNodeWithText("测试通知权限").assertIsDisplayed()
        compose.onNodeWithText("可选权限").assertExists()
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.runOnIdle { assertEquals(UiDestination.SETTINGS, state.destination) }
        scrollToText("个性化")
        compose.onNodeWithText("个性化").assertIsDisplayed()
    }

    /** 只更新夹具状态，实际渲染根组件并沿用生产导航和权限入口。 */
    private fun show(value: List<PermissionUiState> = permissions) {
        apiEvents.clear()
        state = SimulSubUiState(live = LiveUiState(canStart = true, permissions = value),
            settings = SettingsUiState(permissions = value, api = savedApi))
        val actions = SimulSubActions(
            onNavigate = { apiEvents += "导航:${it.name}"; state = state.copy(destination = it) },
            onApiDraftChange = { draft -> state = state.copy(settings = state.settings.copy(api = draft.copy(hasUnsavedChanges = true))) },
            onDiscardApiDraft = { apiEvents += "放弃"; state = state.copy(settings = state.settings.copy(api = savedApi)) },
            onPermission = { id ->
                val granted = state.live.permissions.map { if (it.id == id) it.copy(granted = true) else it }
                state = state.copy(live = state.live.copy(permissions = granted), settings = state.settings.copy(permissions = granted))
            },
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(if (tablet) 0.6f else 1f, 1f)) {
                Box(Modifier.requiredSize(if (tablet) 1040.dp else 360.dp, 720.dp)) { SimulSubApp(state, actions) }
            }
        }
    }

    /** 只匹配真正可选择的主导航，避免与顶栏同名文本发生歧义。 */
    private fun navigation(label: String) = compose.onNode(isSelectable() and hasText(label))

    /** 在单列或双列懒列表中查找真实入口，最终仍要求目标实际可见。 */
    private fun scrollToText(label: String) {
        var failure: AssertionError? = null
        val lists = compose.onAllNodes(hasScrollToIndexAction())
        for (index in lists.fetchSemanticsNodes().indices) {
            try {
                lists[index].performScrollToNode(hasText(label))
                compose.onNodeWithText(label).assertIsDisplayed()
                return
            } catch (error: AssertionError) { failure = error }
        }
        throw AssertionError("找不到可见入口：$label", failure)
    }
}
