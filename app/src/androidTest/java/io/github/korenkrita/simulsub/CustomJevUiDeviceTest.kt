package io.github.korenkrita.simulsub

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/** 单台设备以两种布局专项检查自定义辅助服务，不使用用户密钥或外部网络。 */
@RunWith(Parameterized::class)
class CustomJevUiDeviceTest(private val tablet: Boolean) {
    companion object {
        /** 复用当前唯一模拟器，仅改变布局约束。 */
        @JvmStatic @Parameterized.Parameters(name = "平板={0}")
        fun sizes() = listOf(arrayOf(false), arrayOf(true))
    }
    @get:Rule val compose = createComposeRule()

    /** 完整填写连接、保存、平台密钥入口与结果显示都可达。 */
    @Test fun connectionDialogAndSavedServiceAreReadable() {
        val state = mutableStateOf(AdvancedUiState(jevProviderId = JevProvider.CUSTOM.name, jevNeedsKey = true, jevProviders = JevProvider.entries.map { UiOption(it.name, it.label) }))
        val actions = SimulSubActions(onSaveJevCustom = { c -> state.value = state.value.copy(jevCustom = c, jevEndpoint = c.endpoint().second.orEmpty()) })
        compose.setContent {
            SimulSubTheme(SettingsUiState(dynamicColors = false)) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                    AdvancedSettings(state.value, false, actions, tablet, null)
                }
            }
        }
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasTestTag("jev-custom-config"))
        compose.onNodeWithTag("jev-custom-config").performClick()
        compose.onNodeWithTag("jev-custom-save").assertIsNotEnabled()
        compose.onNodeWithTag("jev-custom-url").performTextReplacement("https://jev.example/v1")
        compose.onNodeWithTag("jev-custom-model").performTextReplacement("jev-latest")
        compose.onNodeWithTag("jev-custom-model").performImeAction()
        screenshot("连接弹窗")
        compose.onNodeWithTag("jev-custom-save").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("https://jev.example/v1/systemone", state.value.jevEndpoint)
            assertEquals("jev-latest", state.value.jevCustom!!.model)
        }
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasTestTag("quick-key-add-jev:CUSTOM"))
        compose.onNodeWithTag("quick-key-add-jev:CUSTOM").assertIsDisplayed().assertIsEnabled()
        screenshot("已保存连接")
    }

    /** 截图只含虚构连接，供手机和平板的局部视觉核查。 */
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val inst = InstrumentationRegistry.getInstrumentation()
        val file = File(inst.targetContext.getExternalFilesDir(null), "upstream-109/$name-$tablet.png")
        file.parentFile!!.mkdirs()
        val bitmap = inst.uiAutomation.takeScreenshot()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
