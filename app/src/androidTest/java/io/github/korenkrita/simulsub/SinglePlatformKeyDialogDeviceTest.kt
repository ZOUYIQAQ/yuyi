package io.github.korenkrita.simulsub

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
import java.io.File

/** 同一设备上专项检查单弹窗的三项设置、目录、自定义输入与窄屏大字显示。 */
@RunWith(Parameterized::class)
class SinglePlatformKeyDialogDeviceTest(private val tablet: Boolean, private val font: Float) {
    companion object {
        /** 只改变界面尺寸与字号，不创建第二台设备。 */
        @JvmStatic @Parameterized.Parameters(name = "平板={0},字号={1}")
        fun sizes() = listOf(arrayOf(false, 1f), arrayOf(true, 1f), arrayOf(false, 1.5f), arrayOf(true, 1.5f))
    }
    @get:Rule val compose = createComposeRule()
    private var draft by mutableStateOf<QuickPlatformKeyUiState?>(null)
    private var saved: QuickPlatformKeyUiState? = null

    /** 点击新增一次即可填写三项，目录可滚动选择原本不存在的海外供应商。 */
    @Test fun oneDialogContainsAllFieldsAndTheFullPlatformCatalog() {
        show()
        compose.onNodeWithTag("platform-key-add").performClick()
        compose.onNodeWithTag("quick-key-platform").assertIsDisplayed()
        compose.onNodeWithTag("quick-key-name").assertIsDisplayed()
        compose.onNodeWithTag("quick-key-value").assertIsDisplayed()
        compose.onNodeWithTag("quick-key-platform-menu").performClick()
        val openai = TextServiceProviders.entries.single { it.id == "openai" }
        compose.onNodeWithTag("quick-key-platform-${openai.platformId}").performScrollTo().performClick()
        compose.onNodeWithTag("quick-key-platform").assertTextContains(openai.title)
        compose.onNodeWithTag("quick-key-name").performTextReplacement("海外备用账号")
        compose.onNodeWithTag("quick-key-value").performTextReplacement("  假密钥 +/=  ")
        compose.onNodeWithTag("quick-key-value").performImeAction()
        screenshot("预设平台")
        compose.onNodeWithTag("quick-key-save").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(openai.platformId, saved!!.platformId)
            assertEquals("海外备用账号", saved!!.name)
            assertEquals("  假密钥 +/=  ", saved!!.key)
        }
    }

    /** 自定义平台直接在第一项填写地址；切换来源不丢草稿，未保存退出仍有确认。 */
    @Test fun customAddressUsesTheSameFieldAndKeepsUnsavedChangesProtected() {
        show()
        compose.onNodeWithTag("platform-key-add").performClick()
        compose.onNodeWithTag("quick-key-platform").performTextReplacement("https://custom.example/v1")
        compose.onNodeWithTag("quick-key-name").performTextReplacement("自定义接口")
        compose.onNodeWithTag("quick-key-value").performTextReplacement("假密钥原文")
        compose.onNodeWithTag("quick-key-value").performImeAction()
        screenshot("自定义平台")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("配置尚未保存").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithTag("quick-key-value").assertTextContains("假密钥原文")
        compose.onNodeWithTag("quick-key-save").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("origin:https://custom.example", saved!!.platformId) }
    }

    /** 使用生产界面与同目录选项，动作只回填内存假数据，业务持久化另有控制器测试。 */
    private fun show() {
        val actions = SimulSubActions(onOpenQuickPlatformKey = { _, _ ->
            draft = QuickPlatformKeyUiState("", "", "fixture", name = "默认密钥", platformEditable = true,
                platforms = PlatformCredentials.defaults.map { UiOption(it.id, it.label) })
        }, onQuickPlatformKeyPlatformChange = { input ->
            val option = draft!!.platforms.firstOrNull { it.id == input || it.label == input }
            val platform = option?.let { CredentialPlatform(it.id, it.label) } ?: PlatformCredentials.forUrl(input)
            draft = draft!!.copy(platformInput = option?.label ?: input, platformId = platform?.id.orEmpty(), platformLabel = platform?.label.orEmpty())
        }, onQuickPlatformKeyNameChange = { draft = draft!!.copy(name = it) },
            onQuickPlatformKeyValueChange = { draft = draft!!.copy(key = it) },
            onSaveQuickPlatformKey = { saved = draft; draft = null }, onDismissQuickPlatformKey = { draft = null })
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(if (tablet) 0.6f else 1f, font)) {
                SimulSubTheme(SettingsUiState(dynamicColors = false)) {
                    Box(Modifier.requiredSize(if (tablet) 1040.dp else 360.dp, 720.dp)
                        .consumeWindowInsets(WindowInsets.systemBars.union(WindowInsets.displayCutout)).padding(vertical = 24.dp)) {
                        PlatformKeysSettings(emptyList(), false, actions, tablet)
                        draft?.let { QuickPlatformKeyDialog(it, actions, true) }
                    }
                }
            }
        }
    }

    /** 截图只有虚构密钥，供本轮检查间距、按钮与大字号可读性。 */
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val inst = InstrumentationRegistry.getInstrumentation()
        val file = File(inst.targetContext.getExternalFilesDir(null), "key-dialog/$name-$tablet-$font.png")
        file.parentFile!!.mkdirs()
        val bitmap = inst.uiAutomation.takeScreenshot()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
