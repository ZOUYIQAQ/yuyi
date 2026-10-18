package io.github.korenkrita.simulsub

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 用纯内存状态检查 Jev 联通按钮与平台密钥入口，不触发网络。 */
class JevConnectionUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    /** 两个 Jev 开关关闭时仍可手动测试，点击只发测试动作。 */
    @Test fun testButtonWorksWithoutEnablingJev() {
        val state = mutableStateOf(AdvancedUiState(jevProviderId = JevProvider.ZEN.name))
        var requests = 0
        val actions = SimulSubActions(onTestJevConnection = { requests++ })
        compose.setContent {
            SimulSubTheme(SettingsUiState()) { AdvancedSettings(state.value, false, actions, false, null) }
        }
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasTestTag("jev-test-connection"))
        compose.onNodeWithTag("jev-test-connection").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, requests)
            assertFalse(state.value.jevOn)
            assertFalse(state.value.jevCheck)
        }
    }

    /** 测试期间禁止重复提交，但主动取消按钮可达且只发送一次取消动作。 */
    @Test fun testingDisablesDuplicateClickAndAllowsCancel() {
        val state = AdvancedUiState(jevProviderId = JevProvider.ZEN.name, jevTesting = true)
        var requests = 0
        var cancels = 0
        val actions = SimulSubActions(onTestJevConnection = { requests++ }, onCancelJevTest = { cancels++ })
        compose.setContent {
            SimulSubTheme(SettingsUiState()) { AdvancedSettings(state, false, actions, false, null) }
        }
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasTestTag("jev-test-connection"))
        compose.onNodeWithTag("jev-test-connection").assertIsNotEnabled()
        compose.onNodeWithText("正在测试…").assertExists()
        compose.onNodeWithText("取消测试").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(0, requests); assertEquals(1, cancels) }
    }

    /** 官方供应商展示中央密钥管理入口，Jev 区域不另设密钥输入框。 */
    @Test fun officialProviderOpensCentralPlatformKeys() {
        val state = AdvancedUiState(jevProviderId = JevProvider.OFFICIAL.name,
            jevNeedsKey = true, jevHasSavedKey = false)
        var opened = 0
        val actions = SimulSubActions(onOpenPlatformKeys = { opened++ })
        compose.setContent {
            SimulSubTheme(SettingsUiState()) { AdvancedSettings(state, false, actions, false, null) }
        }
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText("管理平台密钥"))
        compose.onNodeWithText("管理平台密钥").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
        compose.onNode(hasSetTextAction() and hasText("TypeSafe", substring = true)).assertDoesNotExist()
    }

    /** 会话忙碌时测试入口锁定，提示仍可显示给用户。 */
    @Test fun busySessionDisablesProbeWithoutHidingResult() {
        val state = AdvancedUiState(jevProviderId = JevProvider.ZEN.name,
            jevMessage = UiMessage("连接测试通过 · HTTP 200 · 37 毫秒"))
        compose.setContent {
            SimulSubTheme(SettingsUiState()) { AdvancedSettings(state, true, SimulSubActions(), false, null) }
        }
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasTestTag("jev-test-connection"))
        compose.onNodeWithTag("jev-test-connection").assertIsNotEnabled()
        compose.onNodeWithText("连接测试通过 · HTTP 200 · 37 毫秒").assertExists()
    }
}
