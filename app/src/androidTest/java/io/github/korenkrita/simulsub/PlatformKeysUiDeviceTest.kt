package io.github.korenkrita.simulsub

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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

/** 手机和平板真实Compose交互，验证集中密钥、返回保护和两阶段入口。 */
@RunWith(Parameterized::class)
class PlatformKeysUiDeviceTest(private val tablet: Boolean, private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(SimulSubUiState(destination = UiDestination.SETTINGS,
        settings = SettingsUiState(platformKeys = listOf(PlatformKeyUiState("qwen", "阿里百炼 · 千问"),
            PlatformKeyUiState("deepseek", "DeepSeek 官方")))))
    private var saves = 0
    private var asrEdits = 0
    private var onlineEdits = 0
    private lateinit var back: OnBackPressedDispatcher

    companion object {
        /** 两种窗口和正常、放大字号都走相同用户操作。 */
        @JvmStatic @Parameterized.Parameters(name = "平板={0}，字号={1}")
        fun cases(): List<Array<Any>> = listOf(false, true).flatMap { tablet -> listOf(1f, 1.5f).map { arrayOf<Any>(tablet, it) } }
    }

    /** 密钥输入即时可见、原文回填，保存与隐藏切换都不清空。 */
    @Test fun longKeyCanBeEnteredSavedAndRevealed() {
        show()
        enter("平台密钥")
        val raw = "sk-ws-H.PYDLPID._+-/=" + "长密钥Abc-_=+".repeat(50)
        reveal(hasTestTag("platform-key-qwen:default"))
        compose.onNodeWithTag("platform-key-qwen:default").performTextReplacement(raw)
        compose.runOnIdle { assertEquals(raw, state.settings.platformKeys.first().key) }
        reveal(hasTestTag("platform-key-save-qwen:default"))
        compose.onNodeWithTag("platform-key-save-qwen:default").performClick()
        compose.runOnIdle { assertEquals(1, saves); assertEquals(raw, state.settings.platformKeys.first().key) }
        reveal(hasTestTag("platform-key-qwen:default"))
        compose.onNodeWithContentDescription("显示阿里百炼 · 千问密钥").performClick()
        compose.onNodeWithTag("platform-key-qwen:default").assertTextContains(raw)
        compose.onNodeWithContentDescription("隐藏阿里百炼 · 千问密钥").performClick()
        compose.onNodeWithTag("platform-key-status-qwen:default").assertTextContains("已配置", substring = true)
        compose.runOnIdle { assertEquals(raw, state.settings.platformKeys.first().key) }
        screenshot("平台密钥")
    }

    /** 未保存退出先确认，继续编辑保留草稿，放弃恢复已存值。 */
    @Test fun unsavedKeysAreProtectedOnBack() {
        show(); enter("平台密钥")
        reveal(hasTestTag("platform-key-qwen:default"))
        compose.onNodeWithTag("platform-key-qwen:default").performTextInput("draft")
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithText("配置尚未保存").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.runOnIdle { assertEquals("draft", state.settings.platformKeys.first().key); back.onBackPressed() }
        compose.onNodeWithText("放弃修改").performClick()
        compose.runOnIdle { assertEquals("", state.settings.platformKeys.first().key); assertEquals(0, saves) }
    }

    /** 逐项编辑时分别派发听写与直译动作，集中密钥不因独立编辑器再次出现输入框。 */
    @Test fun listeningFieldsDispatchToCorrectConfigurations() {
        show(); enter("听写服务")
        editService("fixture-asr")
        reveal(hasTestTag("cloud-asr-endpoint"))
        compose.onNodeWithTag("cloud-asr-endpoint").performTextReplacement("wss://workspace.example/api-ws/v1/inference")
        compose.runOnIdle { assertEquals(1, asrEdits); assertEquals(0, onlineEdits) }
        compose.onNodeWithTag("cloud-asr-key").assertDoesNotExist()
        compose.onNodeWithTag("online-endpoint").assertDoesNotExist()
        enter("保存听写服务")
        compose.onNodeWithContentDescription("返回听写服务列表").performClick()
        editService("fixture-online")
        reveal(hasTestTag("online-endpoint"))
        compose.onNodeWithTag("online-endpoint").performTextReplacement("wss://workspace.example/api-ws/v1/realtime")
        compose.runOnIdle { assertEquals(1, asrEdits); assertEquals(1, onlineEdits) }
        compose.onNodeWithTag("online-key").assertDoesNotExist()
        compose.onNodeWithTag("cloud-asr-endpoint").assertDoesNotExist()
        enter("保存听写服务")
        compose.runOnIdle {
            assertEquals("wss://workspace.example/api-ws/v1/inference", state.settings.cloudAsr.endpoint)
            assertEquals("wss://workspace.example/api-ws/v1/realtime", state.settings.online.endpoint)
        }
        screenshot("听写连接")
    }

    /** 引导在听写步骤进入中央密钥页，返回保持当前步骤且连接字段仍可编辑。 */
    @Test fun onboardingKeysReturnToSameListeningStep() {
        compose.runOnIdle { state = state.copy(onboarding = OnboardingUiState(step = OnboardingStep.ONLINE,
            listeningMode = ListeningMode.CLOUD_ASR), settings = state.settings.copy(listeningMode = ListeningMode.CLOUD_ASR)) }
        show()
        reveal(hasText("管理平台密钥"))
        compose.onNodeWithText("管理平台密钥").performClick()
        reveal(hasTestTag("platform-key-qwen:default"))
        compose.onNodeWithTag("platform-key-qwen:default").performTextInput("onboarding-test")
        reveal(hasTestTag("platform-key-save-qwen:default"))
        compose.onNodeWithTag("platform-key-save-qwen:default").performClick()
        compose.onNodeWithText("返回当前引导步骤").performClick()
        reveal(hasTestTag("cloud-asr-endpoint"))
        compose.onNodeWithTag("cloud-asr-endpoint").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(OnboardingStep.ONLINE, state.onboarding?.step)
            assertEquals("onboarding-test", state.settings.platformKeys.first().key)
        }
        screenshot("首次引导返回听写")
    }

    /** 直接听译明确免配文字翻译，设置里仍然可以访问翻译与本地模型。 */
    @Test fun directModeKeepsAllSettingsAndShowsTranslationIncluded() {
        compose.runOnIdle { state = state.copy(settings = state.settings.copy(listeningMode = ListeningMode.ONLINE),
            live = state.live.copy(listeningMode = ListeningMode.ONLINE, listeningServiceId = "profile:fixture-online")) }
        show()
        listOf("听写服务", "翻译服务", "模型库", "平台密钥").forEach { label -> reveal(hasText(label)); compose.onNodeWithText(label).assertIsDisplayed() }
        enter("听写服务")
        listOf("fixture-asr", "fixture-online", "local:fixture-model").forEach { id ->
            reveal(hasTestTag("service-profile-$id"))
            compose.onNodeWithTag("service-profile-$id").assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("返回设置").performClick()
        enter("翻译服务")
        compose.onNodeWithText("选择已接入的翻译服务。").assertIsDisplayed()
        compose.onNodeWithTag("create-translation-service").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回设置").performClick()
        enter("模型库")
        compose.runOnIdle { assertEquals(UiDestination.MODELS, state.destination) }
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.onNode(isSelectable() and hasText("同传")).performClick()
        reveal(hasTestTag("translation-included"))
        compose.onNodeWithTag("translation-included").assertIsDisplayed()
        compose.onNodeWithTag("translation-service-picker").assertDoesNotExist()
        screenshot("直接听译免配翻译")
    }

    /** 共用真实根路由与回填契约，仅网络和偏好保存替换成可观察的内存动作。 */
    private fun show() {
        val profiles = listOf(
            ServiceProfileUiState("fixture-asr", "测试在线听写", kind = "asr", builtIn = false),
            ServiceProfileUiState("fixture-online", "测试实时听译", kind = "realtime", builtIn = false),
            ServiceProfileUiState("local:fixture-model", "测试本地模型", kind = "local"),
        )
        state = state.copy(settings = state.settings.copy(listeningProfiles = profiles),
            live = state.live.copy(listeningServices = profiles.map {
                UiOption(if (it.kind == "local") it.id else "profile:${it.id}", it.name)
            }))
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            val density = LocalDensity.current
            val actions = SimulSubActions(
                onNavigate = { state = state.copy(destination = it) },
                onPlatformKeyLabelChange = { id, credentialId, label -> state = state.copy(settings = state.settings.copy(platformKeys = state.settings.platformKeys.map {
                    if (it.id == id && it.credentialId == credentialId) it.copy(credentialLabel = label, hasUnsavedChanges = true) else it })) },
                onPlatformCredentialChange = { id, credentialId, text -> state = state.copy(settings = state.settings.copy(platformKeys = state.settings.platformKeys.map {
                    if (it.id == id && it.credentialId == credentialId) it.copy(key = text, hasUnsavedChanges = true) else it })) },
                onSavePlatformCredential = { id, credentialId -> saves++; state = state.copy(settings = state.settings.copy(platformKeys = state.settings.platformKeys.map {
                    if (it.id == id && it.credentialId == credentialId) it.copy(hasSavedKey = true, hasUnsavedChanges = false) else it })) },
                onDiscardPlatformKeys = { state = state.copy(settings = state.settings.copy(platformKeys = state.settings.platformKeys.map { it.copy(key = "", hasUnsavedChanges = false) })) },
                onCloudAsrDraftChange = { asrEdits++; state = state.copy(settings = state.settings.copy(cloudAsr = it.copy(hasUnsavedChanges = true))) },
                onOnlineDraftChange = { onlineEdits++; state = state.copy(settings = state.settings.copy(online = it.copy(hasUnsavedChanges = true))) },
                onEditListeningService = { id ->
                    val profile = state.settings.listeningProfiles.single { it.id == id }
                    state = state.copy(settings = state.settings.copy(listeningEditor = ListeningEditorUiState(id, profile.name,
                        if (profile.kind == "asr") ListeningMode.CLOUD_ASR else ListeningMode.ONLINE)))
                },
                onSaveListeningService = {
                    val asr = state.settings.listeningEditor!!.mode == ListeningMode.CLOUD_ASR
                    state = state.copy(settings = state.settings.copy(
                        cloudAsr = if (asr) state.settings.cloudAsr.copy(hasUnsavedChanges = false, hasSavedConfiguration = true) else state.settings.cloudAsr,
                        online = if (asr) state.settings.online else state.settings.online.copy(hasUnsavedChanges = false, hasSavedConfiguration = true)))
                },
                onCloseListeningServiceEditor = { state = state.copy(settings = state.settings.copy(listeningEditor = null)) },
            )
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Box(Modifier.requiredSize(if (tablet) 1100.dp else 412.dp, if (tablet) 760.dp else 880.dp)) { SimulSubApp(state, actions) }
            }
        }
    }

    /** 列表使用真实滚动，不以直接调用回调冒充用户点击。 */
    private fun reveal(matcher: SemanticsMatcher) {
        compose.waitForIdle()
        try { compose.onNode(matcher).assertIsDisplayed(); return } catch (_: AssertionError) { }
        val lists = compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().size
        var lastFailure: AssertionError? = null
        for (index in 0 until lists) {
            try {
                compose.onAllNodes(hasScrollToIndexAction())[index].performScrollToNode(matcher)
                compose.onNode(matcher).performScrollTo()
                return
            } catch (error: AssertionError) { lastFailure = error }
        }
        throw lastFailure ?: AssertionError("没有可滚动的设置列表")
    }

    /** 设置入口在手机和平板都由真实菜单点击打开。 */
    private fun enter(label: String) { reveal(hasText(label)); compose.onNodeWithText(label).performClick() }

    /** 按稳定标识点击卡片编辑入口，不直接调用动作，确保同名按钮不会命中其他服务。 */
    private fun editService(id: String) {
        val matcher = hasText("编辑") and hasAnyAncestor(hasTestTag("service-profile-$id"))
        reveal(matcher)
        compose.onNode(matcher).performClick()
    }

    /** 密码保持隐藏，保存真实设备截图供视觉检查，不记录密钥正文。 */
    private fun screenshot(label: String) {
        compose.waitForIdle()
        val inst = InstrumentationRegistry.getInstrumentation()
        val directory = File(inst.targetContext.getExternalFilesDir(null), "platform-visual").apply { mkdirs() }
        val image = checkNotNull(inst.uiAutomation.takeScreenshot())
        File(directory, "$label-$tablet-$fontScale.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
