package io.github.korenkrita.simulsub

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 场景编辑的返回入口固定在顶栏，不依赖滚动到底部的关闭动作。 */
class SceneNavigationDeviceTest {
    @get:Rule val compose=createComposeRule()

    /** 到达表单末尾后仍可立即使用左上返回，并真正退出编辑状态。 */
    @Test fun topBackClosesEditorAfterScrolling() {
        var closed=0
        var state by mutableStateOf(SimulSubUiState(destination=UiDestination.SCENES,
            scenes=ScenesUiState(editor=SceneEditorUiState(name="待编辑场景",terms=(1..12).map { TermUiState("$it","术语$it","译法$it") }))))
        compose.setContent { SimulSubApp(state,SimulSubActions(onCloseSceneEditor={closed++;state=state.copy(scenes=state.scenes.copy(editor=null))})) }
        val lists = compose.onAllNodes(hasScrollToIndexAction())
        lists[lists.fetchSemanticsNodes().lastIndex].performScrollToNode(hasText("关闭编辑"))
        compose.onNodeWithContentDescription("返回场景列表").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1,closed);assertNull(state.scenes.editor) }
        compose.onNodeWithContentDescription("返回场景列表").assertDoesNotExist()
    }

    /** 保存或生成未结束时保持一致的禁用状态，避免只关闭一半正在进行的编辑。 */
    @Test fun topBackRespectsEditorBusyState() {
        compose.setContent { SimulSubApp(SimulSubUiState(destination=UiDestination.SCENES,
            scenes=ScenesUiState(editor=SceneEditorUiState(name="场景",saving=true))),SimulSubActions()) }
        compose.onNodeWithContentDescription("返回场景列表").assertIsDisplayed().assertIsNotEnabled()
    }
}
