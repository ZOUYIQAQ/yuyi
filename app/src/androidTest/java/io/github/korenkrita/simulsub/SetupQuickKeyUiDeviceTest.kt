package io.github.korenkrita.simulsub

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** 沿当前真实窗口验证首次配置和服务内快捷密钥，业务动作仅使用隔离内存。 */
class SetupQuickKeyUiDeviceTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(SimulSubUiState())
    private val events = mutableListOf<String>()
    private var hideIme: () -> Unit = {}
    private var widthDp = 0
    private var heightDp = 0
    private var quickSequence = 0
    private var savedQuick: QuickPlatformKeyUiState? = null
    private val asrId = "setup-qwen-asr"
    private val apiId = "setup-custom-api"

    /** MODE 中未填密钥的云端服务仍可选择，并且只派发首次配置专用动作。 */
    @Test fun onboardingCanChooseCloudServiceBeforeEnteringKey() {
        fixture()
        state = state.copy(onboarding = OnboardingUiState(step = OnboardingStep.MODE,
            listeningMode = ListeningMode.CLOUD_ASR))
        showApp()
        val choice = hasText("选择并继续") and hasAnyAncestor(hasTestTag("onboarding-service-$asrId"))
        reveal(choice)
        compose.onNode(choice).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf("引导选择:profile:$asrId"), events)
            assertEquals(OnboardingStep.ONLINE, state.onboarding?.step)
            assertFalse(state.onboarding!!.onlineReady)
            assertNull(state.settings.quickPlatformKey)
            assertTrue(state.settings.platformKeys.none { it.id == "qwen" && it.hasSavedKey })
        }
        compose.onNodeWithText("配置千问在线听写").assertIsDisplayed()
    }

    /** 服务内填写按钮完整传递平台与服务标识；已有凭据后显示添加，锁定时不可点击。 */
    @Test fun credentialPickerDispatchesPlatformAndServiceWithoutNavigation() {
        var enabled by mutableStateOf(true)
        var options by mutableStateOf(emptyList<UiOption>())
        val opened = mutableListOf<Pair<String, String>>()
        compose.setContent {
            SimulSubTheme(SettingsUiState(dynamicColors = false)) {
                Column {
                    ServiceCredentialPicker("deepseek", "translation:$apiId", options, "", "", enabled,
                        SimulSubActions(onOpenQuickPlatformKey = { platformId, serviceId -> opened += platformId to serviceId }))
                }
            }
        }
        compose.onNodeWithTag("quick-key-add-translation:$apiId").assertTextContains("填写密钥").performClick()
        compose.runOnIdle {
            assertEquals(listOf("deepseek" to "translation:$apiId"), opened)
            options = listOf(UiOption("saved", "已保存的测试账号", "已保存"))
        }
        compose.onNodeWithTag("quick-key-add-translation:$apiId").assertTextContains("添加密钥")
        compose.runOnIdle { enabled = false }
        compose.onNodeWithTag("quick-key-add-translation:$apiId").assertIsNotEnabled()
    }

    /** 正式翻译编辑器内打开全局弹窗，显隐与保存均保留任意原文，父表单草稿保持不变。 */
    @Test fun quickKeySavesRawTextAndKeepsParentApiDraft() {
        fixture(); showApp(); openApiDraft()
        val parent = state.settings.api
        click(hasTestTag("quick-key-add-translation:$apiId"))
        val raw = "  假密钥._+-/=:中文🔑\\\"'$<> &%!?  "
        compose.onNodeWithTag("quick-key-name").performTextReplacement("  课堂备用账号  ")
        compose.onNodeWithTag("quick-key-value").performTextReplacement(raw)
        hideKeyboard()
        assertKeyLayout("•".repeat(raw.length))
        compose.onNodeWithContentDescription("显示密钥").performClick()
        assertKeyLayout(raw)
        compose.onNodeWithTag("quick-key-value").assertTextContains(raw)
        compose.onNodeWithContentDescription("隐藏密钥").performClick()
        assertKeyLayout("•".repeat(raw.length))
        compose.runOnIdle {
            assertEquals(raw, state.settings.quickPlatformKey?.key)
            assertEquals("  课堂备用账号  ", state.settings.quickPlatformKey?.name)
            assertEquals(parent, state.settings.api)
            assertEquals(UiDestination.SETTINGS, state.destination)
        }
        compose.onNodeWithTag("quick-key-save").assertTextContains("保存并使用").performClick()
        compose.onNodeWithTag("quick-key-value").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回翻译服务列表").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(raw, savedQuick?.key)
            assertEquals("translation:$apiId", savedQuick?.serviceId)
            assertEquals(savedQuick?.credentialId, state.settings.translationProfiles.single { it.id == apiId }.credentialId)
            assertEquals(parent, state.settings.api)
            assertEquals(apiId, state.settings.translationEditorId)
            assertEquals(1, events.count { it == "快捷保存:translation:$apiId" })
            assertTrue(events.none { it.startsWith("导航:") || it.startsWith("丢弃:") })
        }
    }

    /** 取消未保存弹窗先确认，继续编辑保留原文，放弃只关闭快捷草稿而不动父 API。 */
    @Test fun dismissConfirmationKeepsEditingOrDiscardsOnlyQuickDraft() {
        fixture(); showApp(); openApiDraft()
        val parent = state.settings.api
        click(hasTestTag("quick-key-add-translation:$apiId"))
        compose.onNodeWithTag("quick-key-value").performTextReplacement("未保存 +/= 中文原文")
        hideKeyboard()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("配置尚未保存").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithTag("quick-key-value").assertTextContains("未保存 +/= 中文原文")
        compose.runOnIdle { assertEquals(parent, state.settings.api); assertNull(savedQuick) }
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("放弃修改").performClick()
        compose.onNodeWithTag("quick-key-value").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回翻译服务列表").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(parent, state.settings.api)
            assertEquals(apiId, state.settings.translationEditorId)
            assertNull(state.settings.quickPlatformKey)
            assertEquals(1, events.count { it == "快捷关闭" })
            assertTrue(events.none { it.startsWith("导航:") || it.startsWith("丢弃:") || it.startsWith("快捷保存:") })
        }
    }

    /** 空名称或空密钥不能保存；会话锁定禁用编辑、眼睛和保存，解锁后恢复。 */
    @Test fun quickDialogValidationAndSessionLockFollowGlobalState() {
        fixture()
        state = state.copy(settings = state.settings.copy(quickPlatformKey =
            QuickPlatformKeyUiState("qwen", "阿里百炼 · 千问", "validation", asrId)))
        showApp()
        compose.onNodeWithTag("quick-key-save").assertIsNotEnabled()
        compose.onNodeWithTag("quick-key-name").performTextReplacement("备用账号")
        compose.onNodeWithTag("quick-key-save").assertIsNotEnabled()
        compose.onNodeWithTag("quick-key-value").performTextReplacement("任意测试字符+/=")
        compose.onNodeWithTag("quick-key-name").performTextReplacement("   ")
        compose.onNodeWithTag("quick-key-save").assertIsNotEnabled()
        compose.onNodeWithTag("quick-key-name").performTextReplacement("备用账号")
        hideKeyboard()
        compose.onNodeWithTag("quick-key-save").assertIsEnabled()
        compose.runOnIdle { state = state.copy(live = state.live.copy(busy = true)) }
        listOf("quick-key-name", "quick-key-value", "quick-key-save").forEach {
            compose.onNodeWithTag(it).assertIsNotEnabled()
        }
        compose.onNodeWithContentDescription("显示密钥").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(live = state.live.copy(busy = false, running = true)) }
        compose.onNodeWithTag("quick-key-save").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(live = state.live.copy(running = false)) }
        compose.onNodeWithTag("quick-key-save").assertIsEnabled()
        compose.runOnIdle { assertNull(savedQuick) }
    }

    /** 正式应用旅程截图覆盖首页排序、听写模型管理、平台新外观和服务内快捷弹窗。 */
    @Test fun fullAppJourneyCapturesCurrentPhoneOrTabletWindow() {
        fixture(UiDestination.LIVE); showApp()
        reveal(hasTestTag("live-language-controls"))
        val languages = compose.onNodeWithTag("live-language-controls").fetchSemanticsNode().boundsInRoot
        val session = compose.onNodeWithTag("live-control-panel").fetchSemanticsNode().boundsInRoot
        assertTrue("首页语言选择应位于会话卡之前", languages.bottom <= session.top)
        screenshot("01-首页语言与会话")
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        compose.onNodeWithTag("live-scene-controls").assertIsDisplayed()
        screenshot("02-首页场景先于服务")
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(3)
        compose.onNodeWithTag("listening-service-picker").assertIsDisplayed()
        screenshot("03-首页服务选择")
        click(isSelectable() and hasText("设置"))
        click(hasText("听写服务"))
        val local = hasAnyAncestor(hasTestTag("service-profile-local:setup-installed"))
        reveal(hasText("删除模型") and local)
        compose.onNode(hasText("删除模型") and local).assertIsDisplayed()
        compose.onNode(hasText("使用") and local).assertIsEnabled()
        screenshot("04-听写卡内模型管理")
        click(hasText("使用") and local)
        compose.runOnIdle { assertTrue(events.contains("使用听写:local:setup-installed")) }
        val missing = hasAnyAncestor(hasTestTag("service-profile-local:setup-missing"))
        click(hasText("下载模型") and missing)
        compose.runOnIdle { assertTrue(events.contains("下载模型:setup-missing")) }
        screenshot("05-听写卡内下载入口")
        compose.onNodeWithContentDescription("返回设置").performClick()
        click(hasText("平台密钥"))
        screenshot("06a-平台密钥入口")
        reveal(hasTestTag("platform-key-default-deepseek:backup"))
        compose.onNodeWithTag("platform-key-default-deepseek:backup").assertIsEnabled()
        screenshot("06-平台密钥分组与默认操作")
        compose.onNodeWithContentDescription("返回设置").performClick()
        click(hasText("听写服务"))
        click(hasText("选择密钥") and hasAnyAncestor(hasTestTag("service-profile-$asrId")))
        compose.onNodeWithTag("quick-key-value").assertIsDisplayed()
        compose.onNodeWithTag("quick-key-name").performTextReplacement("课程专用")
        compose.onNodeWithTag("quick-key-value").performTextReplacement("仅用于界面验收._+-/=:中文")
        hideKeyboard()
        screenshot("07-服务内快捷密钥弹窗")
        compose.onNodeWithTag("quick-key-save").assertTextContains("保存并使用").performClick()
        compose.onNodeWithTag("quick-key-value").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(asrId, savedQuick?.serviceId)
            val service = state.settings.listeningProfiles.single { it.id == asrId }
            assertEquals(savedQuick?.credentialId, service.credentialId)
            assertTrue(service.configured)
            assertNull(state.settings.listeningEditor)
            assertEquals(UiDestination.SETTINGS, state.destination)
        }
        screenshot("08-保存后仍在听写服务")
    }

    /** 创建完整内存展示资料，不接触用户偏好、真实密钥、网络和下载器。 */
    private fun fixture(destination: UiDestination = UiDestination.SETTINGS) {
        val credentials = listOf(
            PlatformKeyUiState("qwen", "阿里百炼 · 千问"),
            PlatformKeyUiState("deepseek", "DeepSeek", "测试默认密钥", true, isDefault = true),
            PlatformKeyUiState("deepseek", "DeepSeek", "测试备用密钥", true,
                credentialId = "backup", credentialLabel = "备用账号"))
        state = SimulSubUiState(destination = destination,
            live = LiveUiState(canStart = true, statusTitle = "已准备好", statusDetail = "",
                cloudReady = true, translationReady = true, listeningMode = ListeningMode.CLOUD_ASR,
                listeningServiceId = "profile:$asrId", translationServiceId = apiId,
                sourceOptions = listOf(UiOption("ja", "日语")), targetOptions = listOf(UiOption("zh", "中文")),
                sceneOptions = listOf(UiOption("", "通用")),
                listeningServices = listOf(UiOption("profile:$asrId", "千问 · 在线听写")),
                translationServices = listOf(UiOption(apiId, "DeepSeek · 课程翻译")),
                permissions = listOf(PermissionUiState("audio", "录制播放音频", "测试权限", true),
                    PermissionUiState("overlay", "显示悬浮字幕", "测试权限", true))),
            models = ModelsUiState(items = listOf(
                ModelUiState("setup-installed", "本地 · 已安装模型", "在设备上识别日语", "日语", "120 MB", "MIT", "测试来源",
                    status = ModelUiStatus.READY, canActivate = true, canDelete = true, deletionScope = "仅测试模型"),
                ModelUiState("setup-missing", "本地 · 待下载模型", "可在听写卡内下载", "日语", "80 MB", "MIT", "测试来源",
                    canDownload = true))),
            settings = SettingsUiState(dynamicColors = false, listeningMode = ListeningMode.CLOUD_ASR,
                platformKeys = credentials, listeningServiceId = "profile:$asrId", translationServiceId = apiId,
                listeningProfiles = listOf(
                    ServiceProfileUiState("local:setup-installed", "本地 · 已安装模型", kind = "local"),
                    ServiceProfileUiState(asrId, "千问 · 在线听写", kind = "asr", configured = false, platformId = "qwen"),
                    ServiceProfileUiState("local:setup-missing", "本地 · 待下载模型", kind = "local", configured = false)),
                translationProfiles = listOf(ServiceProfileUiState(apiId, "DeepSeek · 课程翻译",
                    builtIn = false, platformId = "deepseek"))))
    }

    /** 通过正式列表编辑按钮打开父表单，再实际输入 URL 和模型形成未保存草稿。 */
    private fun openApiDraft() {
        click(hasText("翻译服务"))
        click(hasText("编辑") and hasAnyAncestor(hasTestTag("service-profile-$apiId")))
        val url = hasText("服务地址") and hasSetTextAction()
        reveal(url); compose.onNode(url).performTextReplacement("https://api.deepseek.com/v1/课堂草稿?x=+/=")
        val model = hasText("模型 ID") and hasSetTextAction()
        reveal(model); compose.onNode(model).performTextReplacement("课程草稿-model._+-")
        hideKeyboard()
        compose.runOnIdle { assertTrue(state.settings.api.hasUnsavedChanges) }
    }

    /** 挂载完整根应用，读取实际配置并保留真实顶栏、安全区和系统键盘行为。 */
    private fun showApp() {
        val actions = SimulSubActions(
            onNavigate = { events += "导航:$it"; state = state.copy(destination = it) },
            onChooseOnboardingListeningService = { id ->
                events += "引导选择:$id"
                state = state.copy(onboarding = state.onboarding?.copy(step = OnboardingStep.ONLINE,
                    listeningMode = ListeningMode.CLOUD_ASR))
            },
            onSelectListeningService = { events += "使用听写:$it" },
            onDownloadModel = { events += "下载模型:$it" },
            onEditTranslationService = { id -> state = state.copy(settings = state.settings.copy(
                translationEditorId = id, api = TranslationApiUiState(profileId = id, platformId = "deepseek",
                    platformLabel = "DeepSeek", url = "https://api.deepseek.com/v1", model = "deepseek-chat"))) },
            onApiDraftChange = { state = state.copy(settings = state.settings.copy(api = it.copy(hasUnsavedChanges = true))) },
            onDiscardApiDraft = { events += "丢弃:API" },
            onOpenQuickPlatformKey = { platformId, serviceId ->
                events += "快捷打开:$platformId:$serviceId"
                quickSequence++
                state = state.copy(settings = state.settings.copy(quickPlatformKey = QuickPlatformKeyUiState(
                    platformId, if (platformId == "qwen") "阿里百炼 · 千问" else "DeepSeek",
                    "setup-quick-$quickSequence", serviceId, name = "新密钥")))
            },
            onQuickPlatformKeyNameChange = { name -> updateQuick { it.copy(name = name) } },
            onQuickPlatformKeyValueChange = { key -> updateQuick { it.copy(key = key) } },
            onSaveQuickPlatformKey = {
                val draft = checkNotNull(state.settings.quickPlatformKey)
                savedQuick = draft
                events += "快捷保存:${draft.serviceId}"
                val credential = PlatformKeyUiState(draft.platformId, draft.platformLabel, draft.key, true,
                    credentialId = draft.credentialId, credentialLabel = draft.name.trim())
                val option = UiOption(draft.credentialId, draft.name.trim(), "已保存")
                state = state.copy(settings = state.settings.copy(quickPlatformKey = null,
                    platformKeys = state.settings.platformKeys + credential,
                    listeningProfiles = state.settings.listeningProfiles.map {
                        if (it.id == draft.serviceId) it.copy(configured = true, credentialId = draft.credentialId,
                            credentialLabel = draft.name.trim(), credentialOptions = it.credentialOptions + option) else it
                    },
                    translationProfiles = state.settings.translationProfiles.map {
                        if ("translation:${it.id}" == draft.serviceId) it.copy(configured = true, credentialId = draft.credentialId,
                            credentialLabel = draft.name.trim(), credentialOptions = it.credentialOptions + option) else it
                    }))
            },
            onDismissQuickPlatformKey = {
                events += "快捷关闭"
                state = state.copy(settings = state.settings.copy(quickPlatformKey = null))
            },
        )
        compose.setContent {
            val configuration = LocalConfiguration.current
            widthDp = configuration.screenWidthDp
            heightDp = configuration.screenHeightDp
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            hideIme = { focus.clearFocus(force = true); keyboard?.hide() }
            SimulSubApp(state, actions)
        }
    }

    /** 仅回填快捷表单字段，保留其余父级状态和值。 */
    private fun updateQuick(update: (QuickPlatformKeyUiState) -> QuickPlatformKeyUiState) {
        state = state.copy(settings = state.settings.copy(quickPlatformKey = state.settings.quickPlatformKey?.let(update)))
    }

    /** 检查实际视觉转换的排版结果，避免把密码框语义原文误认为可见文本。 */
    private fun assertKeyLayout(expected: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("quick-key-value").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue("快捷密钥应提供实际排版", it(layouts))
        }
        assertTrue("快捷密钥应有排版结果", layouts.isNotEmpty())
        layouts.forEach { assertEquals(expected, it.layoutInput.text.text) }
    }

    /** 输入完成后隐藏 IME 并清除焦点，避免截图残留 Gboard 浮动工具条。 */
    private fun hideKeyboard() {
        if (compose.onAllNodesWithTag("quick-key-value").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("quick-key-value").performImeAction()
        compose.runOnIdle { hideIme() }
        compose.waitForIdle()
        nativeIdle()
    }

    /** 等待应用消息队列和真实窗口事件静止，不用 Compose 时钟代替系统空闲。 */
    private fun nativeIdle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(100L, 2_000L)
    }

    /** 所有旅程点击均走真实控件，先按当前单列或双列滚动到目标。 */
    private fun click(matcher: SemanticsMatcher) { reveal(matcher); compose.onNode(matcher).performClick() }

    /** 遍历实际懒加载列精准定位，不改设备大小或伪造平板布局。 */
    private fun reveal(matcher: SemanticsMatcher) {
        hideKeyboard()
        try { compose.onNode(matcher).assertIsDisplayed(); return } catch (_: AssertionError) { }
        val count = compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().size
        var failure: AssertionError? = null
        for (index in 0 until count) {
            try {
                compose.onAllNodes(hasScrollToIndexAction())[index].performScrollToNode(matcher)
                compose.onNode(matcher).performScrollTo().assertIsDisplayed()
                return
            } catch (error: AssertionError) { failure = error }
        }
        throw failure ?: AssertionError("当前窗口未找到首次配置控件：$matcher")
    }

    /** 输出完整真实窗口，主代理分别在手机和平板运行时文件名会保留各自配置及像素尺寸。 */
    private fun screenshot(label: String) {
        hideKeyboard()
        compose.waitForIdle()
        SystemClock.sleep(350L)
        nativeIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir("setup-refinement"))
        check(directory.isDirectory || directory.mkdirs()) { "无法创建首次配置截图目录：$directory" }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "无法截取完整真实窗口" }
        try {
            val device = if (widthDp >= 600) "平板" else "手机"
            val output = File(directory, "$label-$device-${widthDp}x${heightDp}dp-${bitmap.width}x${bitmap.height}.png")
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "无法保存首次配置截图" } }
            android.util.Log.i("首次配置验收", "完整窗口截图已保存：${output.absolutePath}")
        } finally { bitmap.recycle() }
    }
}
