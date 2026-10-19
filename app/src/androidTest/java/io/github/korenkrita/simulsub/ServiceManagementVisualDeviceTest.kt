package io.github.korenkrita.simulsub

import android.graphics.Bitmap
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.online.CloudAsrConfig
import io.github.korenkrita.simulsub.online.LiveTranslateModels
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** 当前真实窗口单次验收；所有业务动作仅回填内存，不接触用户配置、网络、采音与通知。 */
class ServiceManagementVisualDeviceTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(SimulSubUiState())
    private lateinit var back: OnBackPressedDispatcher
    private var hideKeyboard: () -> Unit = {}
    private val asrId = "fixture-asr"
    private val realtimeId = "fixture-live"
    private val realtimeSnapshotId = "fixture-realtime-snapshot"
    private var createdKind = "asr"
    private val translations = mutableMapOf(
        "translation-deepseek" to TranslationApiUiState(profileId = "translation-deepseek",
            url = "https://api.deepseek.com", model = "deepseek-flash", platformId = "deepseek",
            platformLabel = "DeepSeek", hasSavedKey = true, hasSavedConfiguration = true),
        "translation-qwen" to TranslationApiUiState(profileId = "translation-qwen",
            url = "https://dashscope.aliyuncs.com/compatible-mode/v1", model = "qwen-plus",
            platformId = "dashscope", platformLabel = "千问", hasSavedKey = true, hasSavedConfiguration = true),
    )

    /** 通过真实点击完成首页、管理、新建、编辑和引导流程，每个关键页面保存完整设备窗口。 */
    @Test fun captureCurrentWindowServiceManagementFlow() {
        show()
        compose.onNodeWithText("填写千问密钥").assertIsDisplayed()
        screenshot("00-预制接口待填写密钥")
        compose.onNodeWithTag("live-primary-action").performClick()
        reveal(hasTestTag("platform-key-qwen"))
        compose.onNodeWithTag("platform-key-qwen").performTextReplacement("fixture-qwen-key")
        click(hasTestTag("platform-key-save-qwen"))
        screenshot("00a-中央平台密钥已保存")
        compose.onNodeWithContentDescription("返回同传").performClick()
        compose.onNodeWithTag("live-primary-action").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("开始同传").assertIsDisplayed()
        screenshot("01-首页就绪")
        reveal(hasTestTag("service-selection"))
        screenshot("02-首页服务选择")
        openPicker("listening-service-picker")
        compose.onNodeWithText("下载更多本地模型").assertDoesNotExist()
        screenshot("03-首页听写下拉")
        choosePopup("千问 · qwen-audio-3.1-asr-flash-streaming")
        openPicker("translation-service-picker")
        compose.onNodeWithText("管理翻译配置").assertDoesNotExist()
        screenshot("04-首页翻译下拉")
        choosePopup("DeepSeek · deepseek-flash")

        click(hasText("设置") and hasClickAction())
        val group = hasAnyAncestor(hasTestTag("settings-subtitle-translation-group"))
        listOf("听写服务", "翻译服务", "平台密钥", "场景术语", "高级设置").forEach { title ->
            reveal(hasText(title) and group)
            compose.onNode(hasText(title) and group).assertIsDisplayed()
        }
        compose.onNode(hasText("字幕样式") and group).assertDoesNotExist()
        compose.onNode(hasText("外观") and group).assertDoesNotExist()
        compose.onNode(hasText("模型库") and group).assertDoesNotExist()
        scrollPagesToTop()
        screenshot("05-设置听写与翻译分组")
        reveal(hasText("字幕样式"))
        reveal(hasText("外观"))
        screenshot("06-设置个性化分组")
        reveal(hasText("资源"))
        reveal(hasText("模型库"))
        screenshot("07-设置独立资源分组")

        click(hasText("听写服务"))
        listOf("local:fixture-model", asrId, realtimeId, realtimeSnapshotId).forEach { id ->
            reveal(hasTestTag("service-profile-$id"))
            compose.onNodeWithTag("service-profile-$id").assertIsDisplayed()
        }
        compose.onNodeWithTag("cloud-asr-endpoint").assertDoesNotExist()
        compose.onNodeWithTag("online-endpoint").assertDoesNotExist()
        scrollPagesToTop()
        assertBothServiceColumnsVisible("local:fixture-model", asrId)
        screenshot("08-听写服务列表")
        cardAction(asrId, "编辑")
        compose.onNodeWithTag("listening-service-name").assertDoesNotExist()
        reveal(hasText("千问在线听写接口"))
        screenshot("11-云听写技术类型")
        reveal(hasTestTag("cloud-asr-endpoint"))
        compose.onNodeWithTag("cloud-asr-endpoint").assertIsEnabled()
        screenshot("12-云听写编辑连接")
        val activeListening = state.live.listeningServiceId
        click(hasText("保存听写服务"))
        compose.runOnIdle {
            assertEquals(activeListening, state.live.listeningServiceId)
            assertFalse(state.settings.listeningEditor!!.isNew)
        }
        screenshot("13-云听写保存成功")
        compose.onNodeWithContentDescription("返回听写服务列表").performClick()
        cardAction(realtimeId, "使用")
        compose.runOnIdle { assertEquals("profile:$realtimeId", state.live.listeningServiceId) }
        reveal(hasTestTag("service-profile-$realtimeId"))
        screenshot("14-预制实时听译已使用")
        click(hasTestTag("create-listening-service"))
        compose.onNodeWithTag("new-service-name").assertDoesNotExist()
        compose.onNodeWithText("继续配置").assertIsEnabled()
        screenshot("09-可选自定义调用接口")
        compose.onNode(hasAnyAncestor(hasTestTag("new-service-type")) and hasClickAction()).performClick()
        screenshot("10-可选调用协议")
        // 真实点击菜单项关闭弹出层；直接调用宿主返回分发器会绕过 Popup 的系统返回。
        choosePopup("千问在线听写")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithContentDescription("返回设置").performClick()

        click(hasText("翻译服务"))
        reveal(hasTestTag("service-profile-translation-deepseek"))
        scrollPagesToTop()
        assertBothServiceColumnsVisible("translation-deepseek", "translation-qwen")
        screenshot("15-翻译服务列表")
        click(hasTestTag("create-translation-service"))
        compose.onNodeWithTag("new-translation-provider").assertIsDisplayed()
        compose.onNodeWithTag("new-service-name").assertDoesNotExist()
        screenshot("15a-自定义翻译平台选项")
        compose.onNodeWithText("取消").performClick()
        cardAction("translation-qwen", "编辑")
        compose.runOnIdle { assertEquals("translation-deepseek", state.live.translationServiceId) }
        compose.onNodeWithText("当前配置").assertDoesNotExist()
        compose.onNodeWithText("重命名").assertDoesNotExist()
        screenshot("16-翻译服务编辑预设")
        click(hasClickAction() and (hasText("服务预设") or hasAnyDescendant(hasText("服务预设"))))
        screenshot("17-翻译服务预设选项")
        choosePopup("阿里百炼 · 千问")
        click(hasText("保存翻译配置"))
        compose.runOnIdle { assertEquals("translation-deepseek", state.live.translationServiceId) }
        screenshot("18-翻译服务编辑连接")
        compose.onNodeWithContentDescription("返回翻译服务列表").performClick()
        cardAction("translation-qwen", "使用")
        compose.runOnIdle { assertEquals("translation-qwen", state.live.translationServiceId) }
        compose.onNodeWithContentDescription("返回设置").performClick()

        click(hasText("首次配置引导"))
        compose.onNodeWithText("允许必要权限").assertIsDisplayed()
        screenshot("19-引导首屏")
        compose.onNodeWithText("下一步").performClick()
        compose.onNodeWithText("选择听写与翻译服务").assertIsDisplayed()
        compose.onNodeWithTag("listening-mode-LOCAL").assertDoesNotExist()
        reveal(hasTestTag("create-listening-service"))
        screenshot("20-引导服务管理首屏")
        click(hasText("管理翻译服务"))
        reveal(hasTestTag("service-profile-translation-deepseek"))
        screenshot("21-引导翻译服务列表")
        cardAction("translation-deepseek", "编辑")
        val selectedTranslation = state.live.translationServiceId
        click(hasText("保存翻译配置"))
        compose.runOnIdle { assertEquals(selectedTranslation, state.live.translationServiceId) }
        compose.onNodeWithText("返回翻译服务列表").performClick()
        cardAction("translation-deepseek", "使用")
        compose.runOnIdle { assertEquals("translation-deepseek", state.live.translationServiceId) }
        screenshot("22-引导翻译保存后使用")
        compose.onNodeWithText("返回服务选择").performClick()
        reveal(hasTestTag("create-listening-service"))
        compose.onNodeWithText("下一步").assertIsEnabled()
    }

    /** 初始化固定展示数据；沿用设备原生窗口与字号，不设置模拟视口或系统参数。 */
    private fun show() {
        val profiles = listOf(
            ServiceProfileUiState("local:fixture-model", "ReazonSpeech", "已安装 · 日语识别模型", kind = "local"),
            ServiceProfileUiState(asrId, "千问 · qwen-audio-3.1-asr-flash-streaming", CloudAsrConfig.DEFAULT_MODEL, kind = "asr", selected = true),
            ServiceProfileUiState(realtimeId, "千问 · qwen3.5-livetranslate-flash-realtime", LiveTranslateModels.REALTIME_ID, kind = "realtime"),
            ServiceProfileUiState(realtimeSnapshotId, "千问 3.5 · 实时听译快照", "${LiveTranslateModels.REALTIME_ID}-2026-05-19", kind = "realtime"),
            ServiceProfileUiState("local:sensevoice", "SenseVoice Small", "需下载 · 多语种识别模型", kind = "local", configured = false),
            ServiceProfileUiState("local:whisper", "Whisper Tiny multilingual", "需下载 · 多语种识别模型", kind = "local", configured = false),
            ServiceProfileUiState("local:moonshine-en", "Moonshine Tiny English", "需下载 · 英语识别模型", kind = "local", configured = false),
            ServiceProfileUiState("local:moonshine-ja", "Moonshine Tiny Japanese", "需下载 · 日语识别模型", kind = "local", configured = false),
        )
        val translationProfiles = listOf(
            ServiceProfileUiState("translation-deepseek", "DeepSeek · deepseek-flash", "DeepSeek · deepseek-flash", selected = true),
            ServiceProfileUiState("translation-qwen", "千问 · qwen-plus", "千问 · qwen-plus"),
        )
        val permissions = listOf(
            PermissionUiState("audio", "录制播放音频", "仅识别应用播放的媒体声音。", true),
            PermissionUiState("overlay", "显示悬浮字幕", "允许字幕显示在播放器等应用上方。", true),
        )
        val listeningOptions = profiles.map { UiOption(if (it.kind == "local") it.id else "profile:${it.id}", it.name, it.detail) }
        val translationOptions = translationProfiles.map { UiOption(it.id, it.name, it.detail) }
        state = SimulSubUiState(live = LiveUiState(canStart = false, statusTitle = "填写平台密钥后即可使用", statusDetail = "已预制千问听写接口，请先填写中央平台密钥。",
            listeningMode = ListeningMode.CLOUD_ASR, cloudReady = false, translationReady = true,
            modelLabel = "千问 · qwen-audio-3.1-asr-flash-streaming", translationLabel = "DeepSeek · deepseek-flash", listeningServiceId = "profile:$asrId",
            listeningServices = listeningOptions, translationServiceId = "translation-deepseek", translationServices = translationOptions,
            permissions = permissions, sourceOptions = listOf(UiOption("ja", "日语"), UiOption("en", "英语")),
            targetOptions = listOf(UiOption("zh", "中文"), UiOption("en", "英语")),
            sceneOptions = listOf(UiOption("", "通用场景")), costs = UsageCostUiState(todayAmount = "¥0.2160", lastAmount = "¥0.0720")),
            settings = SettingsUiState(listeningMode = ListeningMode.CLOUD_ASR, listeningServiceId = "profile:$asrId",
                listeningServices = listeningOptions, translationServiceId = "translation-deepseek", translationServices = translationOptions,
                listeningProfiles = profiles, translationProfiles = translationProfiles, permissions = permissions,
                api = translationDraft("translation-deepseek", translationProfiles), cloudAsr = listeningDraft("asr"),
                platformKeys = listOf(PlatformKeyUiState("qwen", "千问", hasSavedKey = false),
                    PlatformKeyUiState("deepseek", "DeepSeek", hasSavedKey = true))))
        val actions = fixtureActions()
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            hideKeyboard = { focus.clearFocus(); keyboard?.hide() }
            Box(Modifier.fillMaxSize()) { SimulSubApp(state, actions) }
        }
    }

    /** 控制器替身只执行本次测试点击到的内存状态迁移；保存与使用保持独立。 */
    private fun fixtureActions() = SimulSubActions(
        onNavigate = { state = state.copy(destination = it) },
        onPlatformKeyChange = { id, value -> state = state.copy(settings = state.settings.copy(
            platformKeys = state.settings.platformKeys.map { if (it.id == id) it.copy(key = value, hasUnsavedChanges = true) else it })) },
        onSavePlatformKey = { id ->
            state = state.copy(settings = state.settings.copy(platformKeys = state.settings.platformKeys.map {
                if (it.id == id) it.copy(hasSavedKey = true, hasUnsavedChanges = false) else it
            }), live = state.live.copy(cloudReady = true, canStart = true, statusTitle = "已准备好",
                statusDetail = "千问听写模型与文字翻译接口已就绪。"))
        },
        onSelectListeningService = { id -> selectListening(id) },
        onSelectTranslationService = { id ->
            state = state.copy(live = state.live.copy(translationServiceId = id), settings = state.settings.copy(
                translationServiceId = id, translationProfiles = state.settings.translationProfiles.map { it.copy(selected = it.id == id) }))
        },
        onCreateListeningService = { kind, name ->
            check(name.isEmpty()) { "自定义调用接口不应要求用途名称" }
            createdKind = kind
            val mode = if (kind == "asr") ListeningMode.CLOUD_ASR else ListeningMode.ONLINE
            val draft = listeningDraft(kind).copy(hasUnsavedChanges = true, hasSavedConfiguration = false)
            state = state.copy(settings = state.settings.copy(
                listeningEditor = ListeningEditorUiState("fixture-created", "千问 · ${draft.model}", mode, isNew = true),
                cloudAsr = if (kind == "asr") draft else state.settings.cloudAsr,
                online = if (kind == "asr") state.settings.online else draft))
        },
        onEditListeningService = { id ->
            val profile = state.settings.listeningProfiles.single { it.id == id }
            createdKind = profile.kind
            state = state.copy(settings = state.settings.copy(
                listeningEditor = ListeningEditorUiState(id, profile.name,
                    if (profile.kind == "asr") ListeningMode.CLOUD_ASR else ListeningMode.ONLINE),
                cloudAsr = if (profile.kind == "asr") listeningDraft(profile.kind, profile.detail) else state.settings.cloudAsr,
                online = if (profile.kind == "asr") state.settings.online else listeningDraft(profile.kind, profile.detail)))
        },
        onSaveListeningService = {
            val editor = state.settings.listeningEditor!!
            val asr = editor.mode == ListeningMode.CLOUD_ASR
            val draft = (if (asr) state.settings.cloudAsr else state.settings.online).copy(
                hasSavedConfiguration = true, hasUnsavedChanges = false, message = UiMessage("服务已保存"))
            val profile = ServiceProfileUiState(editor.id, "千问 · ${draft.model}", draft.model, kind = createdKind,
                selected = editor.id == state.live.listeningServiceId.removePrefix("profile:"))
            val profiles = state.settings.listeningProfiles.filterNot { it.id == editor.id } + profile
            val options = profiles.map { UiOption(if (it.kind == "local") it.id else "profile:${it.id}", it.name, it.detail) }
            state = state.copy(live = state.live.copy(listeningServices = options), settings = state.settings.copy(
                listeningEditor = editor.copy(isNew = false), listeningProfiles = profiles, listeningServices = options,
                cloudAsr = if (asr) draft else state.settings.cloudAsr, online = if (asr) state.settings.online else draft))
        },
        onCloseListeningServiceEditor = { state = state.copy(settings = state.settings.copy(listeningEditor = null)) },
        onEditTranslationService = { id -> state = state.copy(settings = state.settings.copy(
            translationEditorId = id, api = translationDraft(id, state.settings.translationProfiles))) },
        onSaveApi = {
            val draft = state.settings.api.copy(hasUnsavedChanges = false, hasSavedConfiguration = true, message = UiMessage("翻译服务已保存"))
            translations[draft.profileId] = draft
            state = state.copy(settings = state.settings.copy(api = draft))
        },
        onCloseTranslationServiceEditor = {
            state = state.copy(settings = state.settings.copy(translationEditorId = null,
                api = translationDraft(state.live.translationServiceId, state.settings.translationProfiles)))
        },
        onRestartOnboarding = { state = state.copy(onboarding = OnboardingUiState(apiReady = true, modelReady = true,
            listeningMode = state.live.listeningMode, onlineReady = true)) },
        onOnboardingNext = { state = state.copy(onboarding = state.onboarding!!.copy(step = OnboardingStep.MODE)) },
    )

    /** 听写使用回调更新所选标识和卡片状态，不触发真实服务或配置持久化。 */
    private fun selectListening(id: String) {
        val profile = state.settings.listeningProfiles.single { it.id == id.removePrefix("profile:") }
        val mode = when (profile.kind) { "local" -> ListeningMode.LOCAL; "asr" -> ListeningMode.CLOUD_ASR; else -> ListeningMode.ONLINE }
        state = state.copy(live = state.live.copy(listeningServiceId = id, listeningMode = mode),
            settings = state.settings.copy(listeningServiceId = id, listeningMode = mode,
                listeningProfiles = state.settings.listeningProfiles.map { it.copy(selected = it.id == profile.id) }))
    }

    /** 草稿保留所编辑服务的实时快照模型，只供界面展示，不包含可调用的测试密钥。 */
    private fun listeningDraft(kind: String, model: String? = null) = OnlineListeningUiState(endpoint = when (kind) {
        "asr" -> CloudAsrConfig.DEFAULT_ENDPOINT
        else -> LiveTranslateModels.REALTIME_ENDPOINT
    }, model = model ?: when (kind) { "asr" -> CloudAsrConfig.DEFAULT_MODEL; else -> LiveTranslateModels.REALTIME_ID },
        hasSavedConfiguration = true)

    /** 翻译编辑草稿携带显示名称列表，当前使用标识独立保留在首页状态中。 */
    private fun translationDraft(id: String, profiles: List<ServiceProfileUiState>) = translations.getValue(id).copy(
        profiles = profiles.map { UiOption(it.id, it.name) })

    /** 定位服务卡片中的按钮，重复操作文案不会匹配到其他服务。 */
    private fun cardAction(id: String, label: String) = click(hasText(label) and hasAnyAncestor(hasTestTag("service-profile-$id")))

    /** 点击前滚动到真实控件，不直接调用夹具动作代替用户操作。 */
    private fun click(matcher: SemanticsMatcher) { reveal(matcher); compose.onNode(matcher).performClick() }

    /** 通过真实下拉锚点打开服务选项。 */
    private fun openPicker(tag: String) {
        reveal(hasTestTag(tag))
        compose.onNode(hasAnyAncestor(hasTestTag(tag)) and hasClickAction()).performClick()
        compose.onNode(isPopup()).assertExists()
    }

    /** 只选择弹出菜单内部文字，避免命中锚点当前选中名称。 */
    private fun choosePopup(label: String) {
        compose.onNode(hasText(label) and hasAnyAncestor(isPopup())).performScrollTo().performClick()
    }

    /** 先检查已显示控件，再逐列滚动，兼容固定页脚、手机与平板。 */
    private fun reveal(matcher: SemanticsMatcher) {
        compose.waitForIdle()
        try { compose.onNode(matcher).assertIsDisplayed(); return } catch (_: AssertionError) { }
        var failure: AssertionError? = null
        val count = compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().size
        for (index in 0 until count) {
            try {
                compose.onAllNodes(hasScrollToIndexAction())[index].performScrollToNode(matcher)
                compose.onNode(matcher).performScrollTo().assertIsDisplayed()
                return
            } catch (error: AssertionError) { failure = error }
        }
        throw failure ?: AssertionError("未找到视觉验收目标：$matcher")
    }

    /** 列表回到首屏截图，保留当前设备真实布局，不修改系统显示设置。 */
    private fun scrollPagesToTop() {
        compose.waitForIdle()
        val count = compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().size
        repeat(count) { compose.onAllNodes(hasScrollToIndexAction())[it].performScrollToIndex(0) }
    }

    /** 宽屏首行必须同时呈现左右服务卡，禁止说明独占一栏导致卡片全部挤在另一栏。 */
    private fun assertBothServiceColumnsVisible(firstId: String, secondId: String) {
        if (compose.onAllNodesWithTag("service-profiles-two-columns").fetchSemanticsNodes().isEmpty()) return
        val first = compose.onNodeWithTag("service-profile-$firstId").assertIsDisplayed().getUnclippedBoundsInRoot()
        val second = compose.onNodeWithTag("service-profile-$secondId").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("两张首行卡片必须分居左右两栏", first.right < second.left)
        assertTrue("两栏卡片必须处于同一首行", kotlin.math.abs(first.top.value - second.top.value) <= 1f)
    }

    /** 保存完整设备窗口，包含弹窗和下拉；尺寸与字号进入文件名，避免逐尺寸执行时互相覆盖。 */
    private fun screenshot(label: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(checkNotNull(context.externalCacheDir) { "无法取得外部缓存目录" }, "service-management")
        check(directory.isDirectory || directory.mkdirs()) { "无法创建服务管理验收目录：$directory" }
        // Compose 空闲不代表系统窗口和键盘动画结束；在测试线程等待真实时间后再同步最终布局。
        android.os.SystemClock.sleep(300L)
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "无法截取服务管理完整窗口" }
        try {
            val suffix = "${bitmap.width}x${bitmap.height}-字号${context.resources.configuration.fontScale}"
            val file = File(directory, "$label-$suffix.png")
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "服务管理截图保存失败：$file" } }
            android.util.Log.i("服务管理视觉验收", "截图已保存：${file.absolutePath}")
        } finally { bitmap.recycle() }
    }
}
