package io.github.korenkrita.simulsub

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 真实组件交互验证预设可编辑、行数选择和配色动作，不调用云服务。 */
class RefinementUiTest {
    @get:Rule val compose = createComposeRule()

    /** 选择服务后地址和模型字段继续接受用户修改，不自动触发保存。 */
    @Test fun presetFieldsRemainEditable() {
        var state by mutableStateOf(SimulSubUiState(destination = UiDestination.SETTINGS))
        var saves = 0
        compose.setContent { SimulSubApp(state, SimulSubActions(
            onApplyApiPreset = { id -> state = state.copy(settings = state.settings.copy(api = TranslationPresets.apply(state.settings.api, id))) },
            onApiDraftChange = { api -> state = state.copy(settings = state.settings.copy(api = api)) },
            onSaveApi = { saves++ },
        )) }
        compose.onNodeWithText("服务预设").performScrollTo().performClick()
        compose.onNodeWithText("DeepSeek 官方").performClick()
        compose.onNodeWithText("模型 ID").performScrollTo().performTextReplacement("我修改的模型")
        compose.onNodeWithText("服务地址").performScrollTo().performTextReplacement("https://my.example/v1")
        compose.runOnIdle {
            assertEquals("我修改的模型", state.settings.api.model)
            assertEquals("https://my.example/v1", state.settings.api.url)
            assertEquals(0, saves)
        }
    }

    /** 行数选择与真实预览接收同一设置，外观色块派发可持久化的主题ID。 */
    @Test fun captionLimitAndColorControlsDispatch() {
        var state by mutableStateOf(SimulSubUiState(destination = UiDestination.SETTINGS))
        compose.setContent { SimulSubApp(state, SimulSubActions(
            onSubtitleStyleChange = { style -> state = state.copy(settings = state.settings.copy(subtitle = style)) },
            onThemeColorChange = { id -> state = state.copy(settings = state.settings.copy(themeColorId = id, dynamicColors = false)) },
        )) }
        compose.onNodeWithText("字幕样式").performScrollTo().performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("最多显示行数"))
        compose.onNodeWithText("最多显示行数").performClick()
        compose.onNodeWithText("最多 3 行").performClick()
        compose.runOnIdle { assertEquals(3, state.settings.subtitle.maxLines) }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithText("外观").performScrollTo().performClick()
        compose.onNodeWithText("松绿").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals("forest", state.settings.themeColorId) }
    }
}
