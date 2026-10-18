package io.github.korenkrita.simulsub

import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** 只渲染现有暖色组件并收集动作，不连接服务、不触发采音。 */
class CloudAsrSettingsUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    /** 第三模式运行时锁定文字 API 与上下文，切回本地模式仍允许原有后续句子更新。 */
    @Test fun cloudAsrLocksTranslationAndAdvancedWhileLocalKeepsLiveEditing() {
        val mode = mutableStateOf(ListeningMode.CLOUD_ASR)
        val section = mutableStateOf(SettingsSection.API)
        compose.setContent {
            val settings = SettingsUiState(listeningMode = mode.value)
            SimulSubTheme(settings) { SettingsScreen(settings, true, SimulSubActions(), false, selected = section.value) }
        }
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText("服务地址"))
        compose.onNodeWithText("服务地址").assertIsNotEnabled()
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText("保存翻译配置"))
        compose.onNodeWithText("保存翻译配置").assertIsNotEnabled()
        compose.runOnIdle { section.value = SettingsSection.ADVANCED }
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText("结合上文翻译"))
        compose.onNodeWithText("结合上文翻译").assertIsNotEnabled()
        compose.runOnIdle { mode.value = ListeningMode.LOCAL }
        compose.onNodeWithText("结合上文翻译").assertIsEnabled()
        compose.runOnIdle { section.value = SettingsSection.API }
        // AnimatedContent 退出期间仍保留旧 API 节点，必须等只剩本地模式的新表单。
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("服务地址")).fetchSemanticsNodes().size == 1 &&
                compose.onAllNodes(hasText("服务地址") and isEnabled()).fetchSemanticsNodes().size == 1
        }
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText("服务地址"))
        compose.onNodeWithText("服务地址").assertIsEnabled()
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText("保存翻译配置"))
        compose.onNodeWithText("保存翻译配置").assertIsEnabled()
    }

    /** 保存第三模式首页、两套连接表单与中央平台密钥截图。 */
    @Test fun cloudAsrHomeAndBothSettingsFormsScreenshots() {
        val state = mutableStateOf(SimulSubUiState(
            live = LiveUiState(listeningMode = ListeningMode.CLOUD_ASR, modelLabel = "未配置在线听写",
                translationLabel = "未配置", statusTitle = "还差一步：配置在线听写",
                sourceId = "ja", targetId = "zh",
                sourceOptions = Lang.entries.map { UiOption(it.code, it.zh) } + UiOption("auto", "自动识别"),
                targetOptions = Target.PRESETS.map { UiOption(it.stored, it.zh) },
                sceneId = Scene.VTUBER.id,
                sceneOptions = Scene.DEFAULTS.map { UiOption(it.id, it.label) },
                statusDetail = "请分别保存千问在线听写配置和文字翻译 API（推荐 DeepSeek）。"),
            settings = SettingsUiState(listeningMode = ListeningMode.CLOUD_ASR,
                platformKeys = listOf(PlatformKeyUiState("qwen", "阿里百炼 · 千问"),
                    PlatformKeyUiState("deepseek", "DeepSeek 官方"))),
        ))
        val actions = SimulSubActions(onNavigate = { state.value = state.value.copy(destination = it) })
        compose.setContent { SimulSubApp(state.value, actions) }
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasTestTag("live-primary-action"))
        saveScreenshot("第三模式_首页")
        compose.onNode(isSelectable() and hasText("设置")).performClick()
        saveScreenshot("第三模式_完整设置入口")
        compose.onNodeWithText("听写").performScrollTo().performClick()
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasTestTag("online-endpoint"))
        saveScreenshot("三模式配置_直接在线听译")
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasTestTag("cloud-asr-endpoint"))
        saveScreenshot("三模式配置_在线听写")
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.onNodeWithText("平台密钥").performScrollTo().performClick()
        saveScreenshot("三模式配置_平台密钥")
    }

    /** 听写页的两个翻译入口均直达同一个可编辑文字连接表单。 */
    @Test fun bothTranslationButtonsOpenApiUrlField() {
        val state = mutableStateOf(SimulSubUiState(destination = UiDestination.SETTINGS))
        val actions = SimulSubActions(onNavigate = { state.value = state.value.copy(destination = it) })
        compose.setContent { SimulSubApp(state.value, actions) }
        listOf("配置翻译 API", "配置文字翻译 API").forEach { label ->
            compose.onNodeWithText("听写").performScrollTo().performClick()
            compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText(label))
            compose.onNodeWithText(label).performScrollTo().performClick()
            compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText("服务地址"))
            compose.onNodeWithText("服务地址").performScrollTo().assertIsDisplayed().assert(hasSetTextAction())
            compose.onNodeWithContentDescription("返回设置").performClick()
        }
    }

    /** API 直达路由保护未保存的听写草稿，继续编辑不导航，放弃后才打开地址字段。 */
    @Test fun openingApiProtectsCloudAsrDraft() {
        val state = mutableStateOf(SimulSubUiState(destination = UiDestination.SETTINGS))
        var discarded = 0
        val actions = SimulSubActions(
            onNavigate = { state.value = state.value.copy(destination = it) },
            onDiscardCloudAsrDraft = {
                discarded++
                state.value = state.value.copy(settings = state.value.settings.copy(
                    cloudAsr = state.value.settings.cloudAsr.copy(hasUnsavedChanges = false)))
            },
        )
        compose.setContent { SimulSubApp(state.value, actions) }
        compose.onNodeWithText("听写").performScrollTo().performClick()
        compose.runOnIdle {
            state.value = state.value.copy(settings = state.value.settings.copy(
                cloudAsr = state.value.settings.cloudAsr.copy(endpoint = "wss://draft.example.test/asr", hasUnsavedChanges = true)))
        }
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText("配置文字翻译 API"))
        compose.onNodeWithText("配置文字翻译 API").performScrollTo().performClick()
        compose.onNodeWithText("配置尚未保存").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.runOnIdle {
            assertEquals(0, discarded)
            assertTrue(state.value.settings.cloudAsr.hasUnsavedChanges)
        }
        compose.onNodeWithText("配置文字翻译 API").performClick()
        compose.onNodeWithText("放弃修改").performClick()
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText("服务地址"))
        compose.onNodeWithText("服务地址").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, discarded) }
    }

    /** 手机紧凑模式使用短名称，三枚模式选项均保留至少 48dp 触控高度。 */
    @Test fun compactModeUsesShortLabelAndMinimumTouchHeight() {
        compose.setContent {
            SimulSubTheme(SettingsUiState()) {
                Box(Modifier.width(360.dp)) {
                    ListeningModePicker(ListeningMode.CLOUD_ASR, true, {}, compact = true)
                }
            }
        }
        compose.onNodeWithText("千问在线听写").assertIsDisplayed()
        compose.onNodeWithText("千问语音直译").assertIsDisplayed()
        ListeningMode.entries.forEach { mode ->
            compose.onNodeWithTag("listening-mode-${mode.name}").assertHeightIsAtLeast(48.dp)
        }
    }

    /** 三种模式下设置入口始终完整，模式切换只决定会话处理路径。 */
    @Test fun everyModeKeepsAllSettingsEntries() {
        val state = mutableStateOf(SettingsUiState())
        compose.setContent { SimulSubTheme(state.value) { SettingsScreen(state.value, false, SimulSubActions(), false) } }
        ListeningMode.entries.forEach { mode ->
            compose.runOnIdle { state.value = state.value.copy(listeningMode = mode) }
            listOf("听写", "翻译", "平台密钥", "模型库", "场景与术语", "高级设置").forEach { label ->
                compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText(label))
                compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            }
        }
    }

    /** 当前为本地模式时也可直接编辑两套表单，回调不会写入另一套草稿。 */
    @Test fun bothFormsAreVisibleAndEditIndependentDraftsWithoutSwitchingMode() {
        val state = mutableStateOf(SettingsUiState())
        val events = mutableListOf<String>()
        val actions = SimulSubActions(
            onOnlineDraftChange = { state.value = state.value.copy(online = it); events += "直接听译" },
            onCloudAsrDraftChange = { state.value = state.value.copy(cloudAsr = it); events += "在线听写" },
        )
        compose.setContent { SimulSubTheme(state.value) { ListeningSettings(state.value, false, actions, false) } }
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasTestTag("online-endpoint"))
        compose.onNodeWithTag("online-endpoint").performTextReplacement("wss://direct.example/realtime")
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasTestTag("cloud-asr-endpoint"))
        compose.onNodeWithTag("cloud-asr-endpoint").performTextReplacement("wss://asr.example/api-ws/v1/inference")
        compose.runOnIdle {
            assertEquals(ListeningMode.LOCAL, state.value.listeningMode)
            assertEquals("wss://direct.example/realtime", state.value.online.endpoint)
            assertEquals("wss://asr.example/api-ws/v1/inference", state.value.cloudAsr.endpoint)
            assertEquals(listOf("直接听译", "在线听写"), events)
        }
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasTestTag("cloud-asr-model"))
        compose.onNodeWithTag("cloud-asr-model").assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsActions.SetText))
        compose.onNodeWithText("千问 3.1 · Streaming 听写").performClick()
        compose.onNodeWithText("千问 3 · 省钱分段").assertDoesNotExist()
    }

    /** 运行时两个连接分区与中央密钥输入均锁定，管理入口仍可打开。 */
    @Test fun runningSessionLocksBothConnectionForms() {
        val state = SettingsUiState(listeningMode = ListeningMode.CLOUD_ASR,
            platformKeys = listOf(PlatformKeyUiState("qwen", "阿里百炼 · 千问", "完整测试密钥", true)))
        val section = mutableStateOf(SettingsSection.LISTENING)
        var opened = 0
        val actions = SimulSubActions(onOpenPlatformKeys = { opened++ ; section.value = SettingsSection.KEYS })
        compose.setContent { SimulSubTheme(state) { SettingsScreen(state, true, actions, false, selected = section.value) } }
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText("直接在线听译连接信息"))
        compose.onNodeWithTag("online-endpoint").assertIsNotEnabled()
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText("在线听写连接信息"))
        compose.onNodeWithTag("cloud-asr-endpoint").assertIsNotEnabled()
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText("保存听写配置"))
        compose.onNodeWithText("保存听写配置").assertIsNotEnabled()
        compose.onNodeWithText("测试听写连接").assertIsNotEnabled()
        compose.onAllNodes(hasText("管理平台密钥")).onFirst().performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText("阿里百炼 · 千问"))
        compose.onNodeWithTag("platform-key-qwen")
            .assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsActions.SetText))
        compose.onNodeWithTag("platform-key-save-qwen").assertIsNotEnabled()
    }

    /** 截图写入应用外部缓存的独立目录，便于通过设备工具统一提取。 */
    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.externalCacheDir ?: context.cacheDir, "cloud-asr-ui")
        check(directory.isDirectory || directory.mkdirs()) { "无法创建听写界面截图目录" }
        val output = File(directory, "$name.png")
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        output.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) { "保存听写界面截图失败" } }
        android.util.Log.i("听写界面验收", "截图已保存：${output.absolutePath}")
    }
}
