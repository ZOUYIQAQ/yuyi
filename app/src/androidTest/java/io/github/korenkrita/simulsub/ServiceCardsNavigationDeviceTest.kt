package io.github.korenkrita.simulsub

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import android.graphics.Bitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 仅以内存数据验证服务身份及底部导航动画，不启动听译和网络。 */
class ServiceCardsNavigationDeviceTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(SimulSubUiState(destination = UiDestination.SUBTITLE))

    /** 服务卡片必须说明服务类型，配置入口也应明确指向服务。 */
    @Test fun translationCardHasServiceIdentity() {
        compose.setContent {
            SimulSubTheme(SettingsUiState(dynamicColors = false)) {
                TranslationServiceManagement(SettingsUiState(translationProfiles = listOf(
                    ServiceProfileUiState("demo", "DeepSeek · deepseek-flash", "deepseek-flash", "translation",
                        configured = true, credentialLabel = "直播账号"))), false, SimulSubActions(), false, {})
            }
        }
        compose.onNodeWithText("文字翻译").assertIsDisplayed()
        compose.onNodeWithText("配置服务").assertIsDisplayed()
    }

    /** 在线听写与直接听译明确说明是否另需翻译，配置操作仍打开既有服务弹窗。 */
    @Test fun listeningCardsExplainTheTwoServiceRoles() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                SimulSubTheme(SettingsUiState(dynamicColors = false)) {
                    Box(Modifier.requiredSize(360.dp, 720.dp).testTag("listening-card-fixture")
                        .consumeWindowInsets(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
                        ListeningServiceManagement(SettingsUiState(listeningProfiles = listOf(
                            ServiceProfileUiState("asr", "千问 3.1 Streaming · 听写", kind = "asr", platformId = "qwen",
                                credentialLabel = "直播账号", credentialOptions = listOf(UiOption("default", "直播账号", "已保存"))),
                            ServiceProfileUiState("direct", "千问 3.5 · 实时听译", kind = "direct", selected = true,
                                credentialLabel = "直播账号", platformId = "qwen"))), false, SimulSubActions(), false)
                    }
                }
            }
        }
        compose.onNodeWithText("在线听写").assertIsDisplayed()
        compose.onNodeWithText("在线识别语音，需搭配文字翻译服务生成译文。").assertIsDisplayed()
        compose.onNodeWithText("实时听译").assertIsDisplayed()
        compose.onNodeWithText("语音直接生成译文字幕，无需另配文字翻译服务。").assertIsDisplayed()
        val inst = InstrumentationRegistry.getInstrumentation()
        val image = compose.onNodeWithTag("listening-card-fixture").captureToImage().asAndroidBitmap()
        val file = File(inst.targetContext.getExternalFilesDir(null), "service-cards-navigation/听写服务.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        compose.onNodeWithTag("configure-service-asr").performClick()
        compose.onNodeWithText("选择密钥").assertIsDisplayed()
        compose.onNodeWithText("当前使用：直播账号").assertIsDisplayed()
    }

    /** 从右侧设置返回中间字幕时，新页应从左进入，旧实现一直从右进入。 */
    @Test fun settingsToSubtitleEntersFromTheLeft() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(360.dp, 720.dp)
                    .consumeWindowInsets(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
                    SimulSubApp(state, SimulSubActions(onNavigate = { state = state.copy(destination = it) }))
                }
            }
        }
        val resting = compose.onNodeWithText("字幕预览示例").fetchSemanticsNode().boundsInRoot.left
        compose.onNode(isSelectable() and hasText("设置")).performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNode(isSelectable() and hasText("字幕")).performClick()
        compose.mainClock.advanceTimeBy(80)
        val moving = compose.onNodeWithText("字幕预览示例").fetchSemanticsNode().boundsInRoot.left
        assertTrue("返回左侧页面应从左进入：稳定=$resting，中间帧=$moving", moving < resting - 0.5f)
        compose.mainClock.advanceTimeBy(400)
    }
}
