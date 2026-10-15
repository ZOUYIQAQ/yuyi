package io.github.korenkrita.simulsub

import android.view.KeyEvent
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 用纯状态驱动真实应用，逐一覆盖手机、平板和三种离开意图，不读取持久化配置。 */
@RunWith(Parameterized::class)
class ApiUnsavedNavigationDeviceTest(private val tablet: Boolean, private val exit: String) {
    @get:Rule val compose = createComposeRule()
    private val saved = TranslationApiUiState(url = "https://example.com/v1", model = "已存模型")
    private var state by mutableStateOf(SimulSubUiState())
    private val events = mutableListOf<String>()
    private lateinit var backDispatcher: OnBackPressedDispatcher

    companion object {
        /** 每个测试同时覆盖底部导航和侧栏，并分别触发顶栏、系统返回和主页面导航。 */
        @JvmStatic @Parameterized.Parameters(name = "平板={0}，离开入口={1}")
        fun cases(): List<Array<Any>> = listOf(false, true).flatMap { tablet ->
            listOf("顶栏", "系统返回", "主导航").map { arrayOf<Any>(tablet, it) }
        }
    }

    /** 继续编辑应保留完整草稿和当前详情，不派发恢复或导航动作。 */
    @Test fun continueEditingRetainsDraft() {
        openApi(dirty = true)
        val draft = state.settings.api
        requestExit()
        compose.onNodeWithText("配置尚未保存").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        assertEditing(draft)
    }

    /** 放弃时恢复动作必须先于原导航，原退出目标不能被弹窗替换。 */
    @Test fun discardRestoresBeforeOriginalExit() {
        openApi(dirty = true)
        requestExit()
        compose.onNodeWithText("放弃修改").performClick()
        assertExited(discarded = true)
        compose.runOnIdle { assertEquals(saved, state.settings.api) }
    }

    /** 没有改动时立即执行原导航，不调用恢复动作。 */
    @Test fun cleanDraftLeavesWithoutConfirmation() {
        openApi(dirty = false)
        requestExit()
        assertExited(discarded = false)
    }

    /** 通过真实保存按钮清除脏标记后，本次离开不再需要确认。 */
    @Test fun savedDraftLeavesWithoutConfirmation() {
        openApi(dirty = true)
        clickFormAction("保存翻译配置")
        compose.runOnIdle { assertFalse(state.settings.api.hasUnsavedChanges) }
        requestExit()
        assertExited(discarded = false, preceding = listOf("保存"))
    }

    /** 测试连接仅回填探测结果，不应让未保存的配置绕过离开确认。 */
    @Test fun testingConnectionDoesNotSaveDraft() {
        openApi(dirty = true)
        clickFormAction("测试连接")
        compose.runOnIdle { assertTrue(state.settings.api.hasUnsavedChanges) }
        requestExit()
        compose.onNodeWithText("配置尚未保存").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.runOnIdle { assertEquals(listOf("测试"), events) }
        compose.onNodeWithContentDescription("返回设置").assertIsDisplayed()
    }

    /** 弹窗的系统返回等同继续编辑，随后仍可重新确认并只放弃一次。 */
    @Test fun dialogBackKeepsDraftAndCanReopen() {
        openApi(dirty = true)
        val draft = state.settings.api
        requestExit()
        compose.onNodeWithText("配置尚未保存").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        assertEditing(draft)
        requestExit()
        // 连续调用同一语义点击回调，验证重组之前的重复点击也不会重复执行。
        val confirm = compose.onNodeWithText("放弃修改").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        compose.runOnIdle { confirm(); confirm() }
        assertExited(discarded = true)
    }

    /** 根状态及保存、测试、放弃动作全部使用内存夹具，入口仍渲染真实应用。 */
    private fun openApi(dirty: Boolean) {
        state = SimulSubUiState(destination = UiDestination.SETTINGS,
            settings = SettingsUiState(api = if (dirty) saved.copy(model = "未保存模型", hasUnsavedChanges = true) else saved))
        val actions = SimulSubActions(
            onNavigate = { destination ->
                events += "导航:${destination.name}"
                assertFalse("派发导航前应已恢复或保存草稿", state.settings.api.hasUnsavedChanges)
                state = state.copy(destination = destination)
            },
            onDiscardApiDraft = {
                events += "放弃"
                state = state.copy(settings = state.settings.copy(api = saved))
            },
            onSaveApi = {
                events += "保存"
                state = state.copy(settings = state.settings.copy(api = state.settings.api.copy(hasUnsavedChanges = false)))
            },
            onTestApi = {
                events += "测试"
                state = state.copy(settings = state.settings.copy(api = state.settings.api.copy(message = UiMessage("测试成功"))))
            },
        )
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(if (tablet) 1040.dp else 360.dp, 640.dp)) {
                    SimulSubApp(state, actions)
                }
            }
        }
        compose.onNodeWithText("翻译 API").performClick()
        compose.onNodeWithContentDescription("返回设置").assertIsDisplayed()
    }

    /** 根据参数派发原有三个入口，系统返回直接经过应用的真实返回分发器。 */
    private fun requestExit() {
        when (exit) {
            "顶栏" -> compose.onNodeWithContentDescription("返回设置").performClick()
            "系统返回" -> compose.runOnIdle { backDispatcher.onBackPressed() }
            "主导航" -> compose.onNode(isSelectable() and hasText("字幕")).performClick()
        }
        compose.waitForIdle()
    }

    /** 在实际表单中滚动到操作，兼容手机单列和平板双列布局。 */
    private fun clickFormAction(label: String) {
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performScrollTo().performClick()
    }

    /** 取消退出之后不允许草稿、页面或业务动作发生变化。 */
    private fun assertEditing(draft: TranslationApiUiState) {
        compose.onNodeWithText("配置尚未保存").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回设置").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(UiDestination.SETTINGS, state.destination)
            assertEquals(draft, state.settings.api)
            assertTrue(events.isEmpty())
        }
    }

    /** 原有返回进入设置首页，主导航进入字幕页，所有副作用只允许发生一次。 */
    private fun assertExited(discarded: Boolean, preceding: List<String> = emptyList()) {
        compose.onNodeWithText("配置尚未保存").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回设置").assertDoesNotExist()
        val expected = preceding + (if (discarded) listOf("放弃") else emptyList()) +
            (if (exit == "主导航") listOf("导航:SUBTITLE") else emptyList())
        compose.runOnIdle {
            assertEquals(expected, events)
            assertEquals(if (exit == "主导航") UiDestination.SUBTITLE else UiDestination.SETTINGS, state.destination)
        }
        if (exit != "主导航") compose.onNodeWithText("翻译 API").assertIsDisplayed()
    }
}
