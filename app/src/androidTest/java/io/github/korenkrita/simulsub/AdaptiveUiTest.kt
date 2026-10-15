package io.github.korenkrita.simulsub

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 验证真实 Compose 组件的首次引导与下载动作，不用截图代替交互断言。 */
class AdaptiveUiTest {
    @get:Rule val compose = createComposeRule()

    /** 未安装模型时主要操作应引导到模型库，而不是展示无法点击的开始按钮。 */
    @Test fun firstUseLeadsToModelLibrary() {
        var destination: UiDestination? = null
        compose.setContent {
            SimulSubApp(SimulSubUiState(live = LiveUiState(modelLabel = "未启用模型", translationLabel = "未配置")), SimulSubActions(onNavigate = { destination = it }))
        }
        compose.onAllNodesWithText("选择识别模型").onFirst().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(UiDestination.MODELS, destination) }
    }

    /** 下载按钮必须将对应模型标识交还控制器，不能只是改变卡片上的文字。 */
    @Test fun modelDownloadDispatchesCorrectId() {
        var requested: String? = null
        val model = ModelUiState("test-model", "测试模型", "仅用于界面交互测试", "日语", "1 MiB", "测试许可", "测试来源", canDownload = true)
        compose.setContent {
            SimulSubApp(SimulSubUiState(destination = UiDestination.MODELS, models = ModelsUiState(items = listOf(model))), SimulSubActions(onDownloadModel = { requested = it }))
        }
        compose.onNodeWithText("下载模型").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("test-model", requested) }
    }
}
