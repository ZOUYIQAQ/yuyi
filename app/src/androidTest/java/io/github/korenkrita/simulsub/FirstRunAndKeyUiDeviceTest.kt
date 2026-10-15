package io.github.korenkrita.simulsub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 首屏与密钥编辑只使用内存状态，不弹出真实授权、不访问用户存储或服务。 */
class FirstRunAndKeyUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    /** 空权限快照仍展示两个真实授权入口，不能用空集合放行下一步。 */
    @Test fun emptyFirstRunPermissionsShowBothActionsAndAllowSkip() {
        val permissions = mutableListOf<String>()
        var skips = 0
        compose.setContent {
            Box(Modifier.requiredSize(360.dp, 740.dp)) {
                OnboardingScreen(SimulSubUiState(onboarding = OnboardingUiState()),
                    SimulSubActions(onPermission = { permissions += it }, onOnboardingSkip = { skips++ }))
            }
        }
        compose.onNodeWithText("第 1/3 步").assertIsDisplayed()
        compose.onNodeWithText("录制播放音频").assertIsDisplayed()
        compose.onNodeWithText("下一步").assertIsNotEnabled()
        compose.onAllNodesWithText("允许").onFirst().performScrollTo().performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("显示悬浮字幕"))
        compose.onAllNodesWithText("允许").onLast().performScrollTo().performClick()
        compose.onNodeWithText("跳过这步").performClick()
        compose.runOnIdle { assertEquals(listOf("audio", "overlay"), permissions); assertEquals(1, skips) }
    }

    /** 普通留空保留旧密钥，只有清空按钮发出显式清除，保存状态始终紧邻按钮。 */
    @Test fun blankEditingAndExplicitClearHaveDifferentMeaning() {
        var api by mutableStateOf(TranslationApiUiState(url = "https://example.com/v1", model = "测试模型",
            key = "已存测试密钥", hasSavedKey = true, hasSavedConfiguration = true))
        compose.setContent {
            SimulSubTheme(SettingsUiState()) {
                Box(Modifier.requiredSize(360.dp, 740.dp)) {
                    TranslationApiSettings(api, false, SimulSubActions(onApiDraftChange = {
                        api = it.copy(hasUnsavedChanges = it.key != null && it.key != "已存测试密钥")
                    }), false, null)
                }
            }
        }
        scrollTo("API 密钥")
        compose.onNodeWithContentDescription("显示密钥").performClick()
        compose.onNode(hasSetTextAction() and hasText("API 密钥", substring = true))
            .assertTextContains("已存测试密钥").performTextClearance()
        compose.runOnIdle { assertNull("手工留空不能请求清除", api.key) }
        scrollTo("清空密钥")
        compose.onNodeWithText("清空密钥").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("", api.key); assertTrue(api.hasUnsavedChanges) }
        scrollTo("保存状态：有未保存的修改")
        compose.onNodeWithText("保存状态：有未保存的修改").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { api = api.copy(key = "已存测试密钥", hasUnsavedChanges = false) }
        scrollTo("保存状态：已保存")
        compose.onNodeWithText("保存状态：已保存").performScrollTo().assertIsDisplayed()
        scrollTo("API 密钥")
        compose.onNode(hasSetTextAction() and hasText("API 密钥", substring = true)).assertTextContains("已存测试密钥")
    }

    /** 眼睛只切换显示，不清空密钥、不修改草稿；默认隐藏，明文时允许直接编辑。 */
    @Test fun passwordEyeTogglesWithoutChangingSavedKey() {
        var api by mutableStateOf(TranslationApiUiState(url = "https://example.com/v1", model = "测试模型",
            key = "测试密钥123", hasSavedKey = true, hasSavedConfiguration = true))
        var changes = 0
        compose.setContent {
            SimulSubTheme(SettingsUiState()) {
                Box(Modifier.requiredSize(360.dp, 740.dp)) {
                    TranslationApiSettings(api, false, SimulSubActions(onApiDraftChange = { api = it; changes++ }), false, null)
                }
            }
        }
        scrollTo("API 密钥")
        val field = compose.onNode(hasSetTextAction() and hasText("API 密钥", substring = true))
        assertRenderedText(field, "•".repeat("测试密钥123".length))
        capture("key-hidden")
        compose.onNodeWithContentDescription("显示密钥").performClick()
        assertRenderedText(field, "测试密钥123")
        capture("key-visible")
        compose.runOnIdle { assertEquals(0, changes); assertEquals("测试密钥123", api.key) }
        field.performTextReplacement("修改后的密钥456")
        compose.runOnIdle { assertEquals("修改后的密钥456", api.key) }
        compose.onNodeWithContentDescription("隐藏密钥").performClick()
        assertRenderedText(field, "•".repeat("修改后的密钥456".length))
        compose.runOnIdle { assertEquals("修改后的密钥456", api.key) }
    }

    /** 检查真实排版字符串；密码键盘会一直保留Password语义，不能把它当成可见性。 */
    private fun assertRenderedText(field: SemanticsNodeInteraction, expected: String) {
        field.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { action ->
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            assertTrue(action(results))
            assertTrue("实际排版应为：$expected", results.any { it.layoutInput.text.text == expected })
        }
    }

    /** 只保存隔离内存示例的真实界面，不读取或修改用户密钥。 */
    private fun capture(name: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val file = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "qa-first-release-fixes/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { output ->
            instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    /** 用真实表单滚动容器定位，避免依赖布局高度或键盘开闭。 */
    private fun scrollTo(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
    }
}
