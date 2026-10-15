package io.github.korenkrita.simulsub

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 配置页内的固定内容按钮确认建议，不依赖系统悬浮窗或短暂消息。 */
class EndpointSuggestionUiTest {
    @get:Rule val compose = createComposeRule()

    /** 可用地址和两个明确按钮在普通可滚动页面中呈现并正确派发。 */
    @Test fun confirmationActionsAreInlineAndReachable() {
        var applied = 0
        var kept = 0
        compose.setContent { SimulSubApp(SimulSubUiState(destination = UiDestination.SETTINGS,
            settings = SettingsUiState(api = TranslationApiUiState(url = "https://example.com", model = "m",
                suggestedUrl = "https://example.com/chat/completions"))),
            SimulSubActions(onApplyTestedApiUrl = { applied++ }, onKeepApiUrl = { kept++ })) }
        compose.onNodeWithText("翻译 API").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("替换并保存"))
        compose.onNodeWithText("发现可用地址，是否替换？").assertIsDisplayed()
        compose.onNodeWithText("https://example.com/chat/completions").assertIsDisplayed()
        compose.onNodeWithText("替换并保存").performClick()
        compose.onNodeWithText("保持原地址").performClick()
        compose.runOnIdle { assertEquals(1, applied); assertEquals(1, kept) }
    }
}
