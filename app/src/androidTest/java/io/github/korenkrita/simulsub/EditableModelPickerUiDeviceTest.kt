package io.github.korenkrita.simulsub

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.ui.*
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 输入型模型下拉只驱动内存草稿，不访问用户配置、网络或控制器。 */
class EditableModelPickerUiDeviceTest {
    @get:Rule val compose = createComposeRule()
    private var hideKeyboard: (() -> Unit)? = null
    private val options = listOf(
        UiOption("deepseek-chat", "DeepSeek Chat"),
        UiOption("deepseek-reasoner", "DeepSeek Reasoner"),
    )

    /** 真实 DeepSeek 表单使用来源预设；选择只改变模型草稿，并留下当前尺寸的菜单与回填截图。 */
    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun deepSeekSettingsPresetSelectionChangesOnlyModelAndCapturesScreenshots() {
        val preset = TranslationPresets.entries.first { it.id == "deepseek" }
        val original = TranslationApiUiState(
            profileId = "deepseek-fixture",
            presetId = preset.id,
            url = preset.url,
            model = preset.model,
            credentialId = "fixture-key",
            credentialLabel = "测试密钥",
            hasSavedKey = true,
        )
        val target = TranslationPresets.modelsFor(original.url, original.model)
            .first { it.id != original.model }
        val api = mutableStateOf(original)
        val changes = mutableListOf<TranslationApiUiState>()
        var saves = 0
        var tests = 0
        val actions = SimulSubActions(
            onApiDraftChange = { api.value = it; changes += it },
            onSaveApi = { saves++ },
            onTestApi = { tests++ },
        )
        compose.setContent {
            SimulSubTheme(SettingsUiState()) {
                val keyboard = LocalSoftwareKeyboardController.current
                hideKeyboard = { keyboard?.hide() }
                Scaffold(topBar = { TopAppBar(title = { Text("翻译服务") }) }) { padding ->
                    Box(Modifier.padding(padding)) {
                        TranslationApiSettings(api.value, false, actions,
                            wide = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp >= 600,
                            navigation = null, showProfiles = false)
                    }
                }
            }
        }
        compose.onNodeWithText("模型 ID").performScrollTo()
        compose.onNodeWithTag("model-picker-toggle", useUnmergedTree = true).performClick()
        compose.onNode(isPopup()).assertExists()
        captureModelPicker("deepseek-menu")
        menuOption(target.label).performScrollTo().performClick()
        compose.onNodeWithText("模型 ID").assertTextContains(target.id)
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(original.copy(model = target.id), api.value)
            assertEquals(listOf(original.copy(model = target.id)), changes)
            assertEquals(0, saves)
            assertEquals(0, tests)
        }
        captureModelPicker("deepseek-selected")
    }

    /** 箭头展示预设及当前选中项，选择后仅发送一次模型 ID 并收起菜单。 */
    @Test fun presetSelectionUpdatesModelAndSelectedCheck() {
        val model = mutableStateOf("deepseek-chat")
        val changes = mutableListOf<String>()
        compose.setContent {
            SimulSubTheme(SettingsUiState()) {
                EditableModelPicker(model.value, options, onValueChange = {
                    model.value = it
                    changes += it
                })
            }
        }
        compose.onNodeWithTag("model-picker-toggle", useUnmergedTree = true).performClick()
        menuOption("DeepSeek Chat").assertIsSelected()
        menuOption("DeepSeek Reasoner").assertIsNotSelected().performClick()
        compose.onNodeWithText("模型 ID").assertTextContains("deepseek-reasoner")
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("deepseek-reasoner"), changes) }
        compose.onNodeWithTag("model-picker-toggle", useUnmergedTree = true).performClick()
        menuOption("DeepSeek Reasoner").assertIsSelected()
        menuOption("DeepSeek Chat").assertIsNotSelected()
    }

    /** 真实连接表单可直接输入任意模型；地址、密钥绑定及保存和测试动作均保持不变。 */
    @Test fun customModelEditingChangesOnlyDraftWithoutSaving() {
        val original = TranslationApiUiState(
            profileId = "custom-api",
            url = "https://example.com/v1",
            model = "existing-model",
            credentialId = "backup-key",
            credentialLabel = "备用密钥",
            hasSavedKey = true,
        )
        val api = mutableStateOf(original)
        var saves = 0
        var tests = 0
        val actions = SimulSubActions(
            onApiDraftChange = { api.value = it },
            onSaveApi = { saves++ },
            onTestApi = { tests++ },
        )
        compose.setContent {
            SimulSubTheme(SettingsUiState()) {
                TranslationApiSettings(api.value, false, actions, false, null, showProfiles = false)
            }
        }
        compose.onNodeWithText("模型 ID").performScrollTo().performClick().assertIsFocused()
            .performTextReplacement("custommodel/my-private-deployment-v2")
        compose.onNodeWithText("模型 ID").assertTextContains("custommodel/my-private-deployment-v2")
        compose.runOnIdle {
            assertEquals(original.copy(model = "custommodel/my-private-deployment-v2"), api.value)
            assertEquals(0, saves)
            assertEquals(0, tests)
        }
    }

    /** 已存未知 ID 原样回填，不被预设第一项覆盖；再次手动输入仍不限制取值。 */
    @Test fun unknownModelRemainsEditableOutsidePresets() {
        val model = mutableStateOf("my-provider/unknown-model-2026")
        val changes = mutableListOf<String>()
        compose.setContent {
            SimulSubTheme(SettingsUiState()) {
                EditableModelPicker(model.value, options, onValueChange = {
                    model.value = it
                    changes += it
                })
            }
        }
        compose.onNodeWithText("模型 ID").assertTextContains("my-provider/unknown-model-2026")
        compose.onNodeWithTag("model-picker-toggle", useUnmergedTree = true).performClick()
        menuOption("DeepSeek Chat").assertIsNotSelected()
        menuOption("DeepSeek Reasoner").assertIsNotSelected()
        compose.runOnIdle { assertEquals(emptyList<String>(), changes) }
        compose.onNodeWithTag("model-picker-toggle", useUnmergedTree = true).performClick()
        compose.onNodeWithText("模型 ID").performTextReplacement("another-custom-id")
        compose.onNodeWithText("模型 ID").assertTextContains("another-custom-id")
        compose.runOnIdle { assertEquals(listOf("another-custom-id"), changes) }
    }

    /** 禁用状态保留已存值，并同时禁止输入和从箭头展开预设。 */
    @Test fun disabledPickerPreservesValueAndCannotOpenMenu() {
        var changes = 0
        compose.setContent {
            SimulSubTheme(SettingsUiState()) {
                EditableModelPicker("saved-custom-model", options, enabled = false,
                    onValueChange = { changes++ })
            }
        }
        compose.onNodeWithText("模型 ID").assertIsNotEnabled().assertTextContains("saved-custom-model")
        compose.onNodeWithTag("model-picker-toggle", useUnmergedTree = true).assertHasNoClickAction()
            .performTouchInput { click() }
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, changes) }
    }

    /** 测试或保存使编辑器禁用时，已展开菜单立即关闭且不提交当前模型。 */
    @Test fun disablingAnOpenPickerClosesMenuWithoutChangingModel() {
        val enabled = mutableStateOf(true)
        var changes = 0
        compose.setContent {
            SimulSubTheme(SettingsUiState()) {
                EditableModelPicker("deepseek-chat", options, enabled = enabled.value,
                    onValueChange = { changes++ })
            }
        }
        compose.onNodeWithTag("model-picker-toggle", useUnmergedTree = true).performClick()
        compose.onNode(isPopup()).assertExists()
        compose.runOnIdle { enabled.value = false }
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.onNodeWithText("模型 ID").assertIsNotEnabled().assertTextContains("deepseek-chat")
        compose.runOnIdle { assertEquals(0, changes) }
    }

    /** 只匹配弹出菜单内的预设，避免与输入框回填值混淆。 */
    private fun menuOption(label: String) = compose.onNode(hasText(label) and hasAnyAncestor(isPopup()))

    /** 等待 Compose 与真实窗口动画稳定后截图，按设备尺寸保存到应用外部文件目录。 */
    private fun captureModelPicker(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.runOnIdle { hideKeyboard?.invoke() }
        compose.waitForIdle()
        SystemClock.sleep(350L)
        instrumentation.waitForIdleSync()
        compose.waitForIdle()
        val context = instrumentation.targetContext
        val directory = File(checkNotNull(context.getExternalFilesDir(null)), "model-picker")
        check(directory.isDirectory || directory.mkdirs()) { "无法创建模型选择截图目录" }
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "无法获取模型选择截图" }
        try {
            val size = if (context.resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "phone"
            File(directory, "$size-${screenshot.width}x${screenshot.height}-$name.png").outputStream().use {
                check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) { "模型选择截图保存失败" }
            }
        } finally {
            screenshot.recycle()
        }
    }
}
