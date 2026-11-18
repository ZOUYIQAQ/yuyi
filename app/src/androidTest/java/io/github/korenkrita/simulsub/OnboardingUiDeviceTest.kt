package io.github.korenkrita.simulsub

import android.view.KeyEvent
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

/** 三页引导使用纯内存夹具；普通设置的草稿保护另走真实根路由，覆盖手机、平板和两倍字号。 */
@RunWith(Parameterized::class)
class OnboardingUiDeviceTest(private val tablet: Boolean, private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(SimulSubUiState())
    private val events = mutableListOf<String>()
    private lateinit var backDispatcher: OnBackPressedDispatcher
    private val savedApi = TranslationApiUiState(profileId = "fixture", url = "https://example.com/v1", model = "测试模型",
        userAgent = "不应展示的进阶参数", headers = listOf(HeaderUiState("额外请求头", "X-Test", "测试值")))

    companion object {
        /** 同一契约在手机、平板及两倍字号下执行，不修改真实系统显示配置。 */
        @JvmStatic @Parameterized.Parameters(name = "平板={0}，字号={1}")
        fun cases(): List<Array<Any>> = listOf(false, true).flatMap { tablet ->
            listOf(1f, 2f).map { arrayOf<Any>(tablet, it) }
        }
    }

    /** 在线引导完成后仍能管理全部服务和高级设置，极简引导不裁剪日常设置能力。 */
    @Test fun completedOnlineOnboardingKeepsAllSettingsEntries() {
        show(OnboardingStep.ONLINE)
        compose.onNodeWithText("跳过这步").performClick()
        compose.onNodeWithTag("onboarding-footer").assertDoesNotExist()
        compose.onNode(isSelectable() and hasText("设置")).performClick()
        listOf("听写服务", "翻译服务", "平台密钥", "场景术语", "高级设置").forEach { label ->
            scrollBodyTo(label)
            compose.onNodeWithText(label).assertIsDisplayed()
        }
        compose.runOnIdle { assertNull(state.onboarding) }
    }

    /** 必要与可选权限分别分组；可选未授权不阻断，两个分组的按钮都派发原标识。 */
    @Test fun permissionsIncludeOptionalNotificationsWithoutGatingNext() {
        show(OnboardingStep.PERMISSIONS)
        compose.onNodeWithText("第 1/3 步").assertIsDisplayed()
        scrollBodyTo("必要权限")
        compose.onNodeWithTag("onboarding-required-permissions").assertIsDisplayed()
        compose.onNodeWithText("下一步").assertIsNotEnabled()
        scrollBodyTo("录制播放音频")
        compose.onNode(hasText("允许") and hasAnySibling(hasText("录制播放音频"))).performScrollTo().performClick()
        compose.onNodeWithText("下一步").assertIsEnabled()
        compose.runOnIdle { assertFalse(state.live.permissions.single { !it.required }.granted) }
        scrollBodyTo("可选权限")
        compose.onNodeWithTag("onboarding-optional-permissions").assertIsDisplayed()
        scrollBodyTo("可选通知权限")
        compose.onNode(hasText("允许") and hasAnySibling(hasText("可选通知权限"))).performScrollTo().performClick()
        compose.onNodeWithText("下一步").assertIsEnabled()
        scrollBodyTo("系统屏幕共享会在每次开始同传时询问，这里无需提前授权。")
        compose.runOnIdle { assertEquals(listOf("权限:audio", "权限:notification"), events) }
        assertNoLaterSettings()
    }

    /** 三步均可跳过，跳过模式不伪造一次显式下载，最后一步才退出引导。 */
    @Test fun skippingAllThreeLocalStepsFinishesInOrder() {
        show(OnboardingStep.PERMISSIONS)
        for (index in 1..3) {
            compose.onNodeWithText("第 $index/3 步").assertIsDisplayed()
            if (index == 3) compose.onNodeWithText("进入羽译").assertIsNotEnabled()
            compose.onNodeWithText("跳过这步").performClick()
        }
        compose.runOnIdle {
            assertNull(state.onboarding)
            assertEquals(listOf("跳过:PERMISSIONS", "跳过:MODE", "跳过:API"), events)
        }
        compose.onNodeWithTag("onboarding-footer").assertDoesNotExist()
    }

    /** 本地配置只保留供应商、预制模型、密钥和下载；模型与翻译两项就绪缺一不可。 */
    @Test fun apiOnlyShowsEssentialConfigurationAndUsesSavedReadiness() {
        show(OnboardingStep.API)
        compose.onNodeWithText("第 3/3 步").assertIsDisplayed()
        scrollBodyTo("翻译供应商")
        compose.onNodeWithTag("onboarding-provider").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-provider").performClick()
        compose.onNodeWithText("测试千问").performClick()
        scrollBodyTo("翻译模型")
        compose.onNodeWithTag("onboarding-model").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-model").performClick()
        compose.onNodeWithText("测试模型乙").performClick()
        compose.runOnIdle {
            assertEquals("qwen", state.onboarding!!.setup.providerId)
            assertEquals("测试模型乙", state.onboarding!!.setup.model)
            assertEquals(listOf("供应商:qwen", "模型:测试模型乙"), events)
        }
        scrollToTag("onboarding-key")
        compose.onNodeWithTag("onboarding-key").assertIsEnabled()
        listOf("服务地址", "模型 ID", "管理平台密钥", "保存翻译配置", "测试连接", "User-Agent（可选）", "请求头名称").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNodeWithText("进入羽译").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(onboarding = state.onboarding!!.copy(apiReady = true)) }
        compose.onNodeWithText("进入羽译").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(onboarding = state.onboarding!!.copy(apiReady = false, modelReady = true)) }
        compose.onNodeWithText("进入羽译").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(onboarding = state.onboarding!!.copy(apiReady = true)) }
        compose.onNodeWithText("进入羽译").assertIsEnabled().performClick()
        compose.runOnIdle { assertNull(state.onboarding) }
    }

    /** 模式仅两张卡，显式本地操作触发下载并进入同页翻译配置，不展示具体服务目录。 */
    @Test fun modeOnlyOffersTwoRoutesAndLocalChoiceStartsDownload() {
        show(OnboardingStep.MODE)
        scrollToTag("onboarding-route-local")
        compose.onNodeWithTag("onboarding-route-local").assertIsDisplayed()
        scrollToTag("onboarding-route-online")
        compose.onNodeWithTag("onboarding-route-online").assertIsDisplayed()
        listOf("SenseVoice", "千问分段听译", "千问在线听写", "选择并继续").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNodeWithTag("onboarding-next").assertDoesNotExist()
        compose.runOnIdle { assertTrue(events.isEmpty()) }
        scrollToTag("onboarding-choose-local")
        compose.onNodeWithTag("onboarding-choose-local").performClick()
        compose.onNodeWithText("配置本地同传").assertIsDisplayed()
        scrollToTag("onboarding-key")
        compose.onNodeWithTag("onboarding-key").assertIsEnabled()
        scrollBodyTo("已下载 25%")
        compose.runOnIdle { assertEquals(listOf("路线:LOCAL", "下载:reazonspeech"), events) }
        assertNoLaterSettings()
    }

    /** 下载和校验期间可填写密钥，只有固定模型启用回填后完成按钮才放开，无单独模型页。 */
    @Test fun modelsStayMinimalAndRequireActivation() {
        show(OnboardingStep.API)
        for (status in listOf(ModelUiStatus.DOWNLOADING, ModelUiStatus.VERIFYING, ModelUiStatus.READY)) {
            compose.runOnIdle { updateModel("reazonspeech", status) }
            scrollToTag("onboarding-key")
            compose.onNodeWithTag("onboarding-key").assertIsEnabled().performTextReplacement("测试自动保存密钥")
            compose.onNodeWithTag("onboarding-key").performImeAction()
            compose.onNodeWithText("进入羽译").assertIsNotEnabled()
        }
        scrollToTag("onboarding-reazon-progress")
        compose.onNodeWithText("识别模型乙").assertDoesNotExist()
        listOf("启用模型", "查看模型详情", "评测来源与条件", "删除模型", "不应展示的模型简介").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.runOnIdle {
            updateModel("reazonspeech", ModelUiStatus.ACTIVE)
            state = state.copy(onboarding = state.onboarding!!.copy(modelReady = true))
        }
        compose.onNodeWithText("进入羽译").assertIsEnabled().performClick()
        compose.runOnIdle { assertNull(state.onboarding); assertEquals("继续:API", events.last()) }
    }

    /** 失败和取消原因保持完整可见，重试只下载固定模型，不能提前放开完成门槛。 */
    @Test fun failedAndCancelledModelsExposeErrorsAndRetryWithoutBecomingReady() {
        show(OnboardingStep.API)
        for ((status, error) in listOf(
            ModelUiStatus.FAILED to "下载失败：测试源 HTTP 503，模型尚未安装",
            ModelUiStatus.CANCELLED to "测试下载已取消，暂存文件保留",
        )) {
            compose.runOnIdle {
                events.clear()
                state = state.copy(models = ModelsUiState(items = listOf(model("reazonspeech").copy(status = status, error = error))))
            }
            scrollBodyTo(error)
            compose.onNodeWithText(error).assertIsDisplayed()
            compose.onNodeWithText("进入羽译").assertIsNotEnabled()
            scrollToTag("onboarding-retry-model")
            compose.onNodeWithTag("onboarding-retry-model").performClick()
            scrollBodyTo("已下载 25%")
            compose.onNodeWithText("进入羽译").assertIsNotEnabled()
            compose.runOnIdle { assertEquals(listOf("下载:reazonspeech"), events); assertFalse(state.onboarding!!.modelReady) }
        }
    }

    /** 普通设置仍拦截脏草稿离页；取消保留草稿，重复确认也只恢复一次。 */
    @Test fun dirtyNavigationCanContinueEditingOrDiscardExactlyOnce() {
        showSettingsEditor()
        val draft = state.settings.api
        compose.onNode(isSelectable() and hasText("字幕")).performClick()
        compose.onNodeWithText("配置尚未保存").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.runOnIdle { assertEquals(draft, state.settings.api); assertFalse(events.contains("放弃")) }
        compose.onNode(isSelectable() and hasText("字幕")).performClick()
        val confirm = compose.onNodeWithText("放弃修改").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        compose.runOnIdle { confirm(); confirm() }
        compose.runOnIdle {
            assertEquals(savedApi, state.settings.api)
            assertEquals(1, events.count { it == "放弃" })
            assertEquals(UiDestination.SUBTITLE, state.destination)
        }
    }

    /** 普通设置编辑器的顶栏返回继续保护草稿，不受引导自动保存行为影响。 */
    @Test fun dirtyPreviousRestoresBeforeGoingBack() {
        showSettingsEditor()
        compose.onNodeWithContentDescription("返回翻译服务列表").performClick()
        compose.onNodeWithText("配置尚未保存").assertIsDisplayed()
        compose.onNodeWithText("放弃修改").performClick()
        compose.runOnIdle { assertEquals(savedApi, state.settings.api); assertNull(state.settings.translationEditorId) }
        compose.onNodeWithText("编辑翻译服务").assertDoesNotExist()
    }

    /** 普通设置的系统返回先询问，弹窗返回相当于继续编辑，确认后才关闭编辑器。 */
    @Test fun dirtySystemBackAndDialogBackKeepDraftUntilConfirmed() {
        showSettingsEditor()
        val draft = state.settings.api
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.onNodeWithText("配置尚未保存").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithText("配置尚未保存").assertDoesNotExist()
        compose.runOnIdle { assertEquals(draft, state.settings.api); assertFalse(events.contains("放弃")) }
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.onNodeWithText("放弃修改").performClick()
        compose.runOnIdle { assertEquals(1, events.count { it == "放弃" }); assertNull(state.settings.translationEditorId) }
    }

    /** 普通设置的连接测试仍不保存草稿，显式保存后才允许无确认离页。 */
    @Test fun testingDoesNotSaveAndSavingAllowsNavigation() {
        showSettingsEditor()
        scrollBodyTo("测试连接")
        compose.onNodeWithText("测试连接").performClick()
        compose.runOnIdle { assertTrue(state.settings.api.hasUnsavedChanges); assertTrue(events.contains("测试")) }
        scrollBodyTo("保存翻译配置")
        compose.onNodeWithText("保存翻译配置").performClick()
        compose.onNode(isSelectable() and hasText("字幕")).performClick()
        compose.onNodeWithText("配置尚未保存").assertDoesNotExist()
        compose.runOnIdle { assertFalse(state.settings.api.hasUnsavedChanges); assertTrue(events.contains("保存")) }
    }

    /** 引导密钥自动保存后返回或跳过均无放弃确认，普通设置的独立脏草稿也不会被恢复。 */
    @Test fun autosavedGuideKeyCanGoBackAndSkipWithoutConfirmation() {
        show(OnboardingStep.API)
        compose.runOnIdle { state = state.copy(settings = state.settings.copy(api = savedApi.copy(hasUnsavedChanges = true))) }
        scrollToTag("onboarding-key")
        compose.onNodeWithTag("onboarding-key").performTextReplacement("自动保存后返回密钥")
        compose.onNodeWithTag("onboarding-key").performImeAction()
        compose.onNodeWithText("上一步").performClick()
        compose.onNodeWithText("第 2/3 步").assertIsDisplayed()
        compose.onNodeWithText("配置尚未保存").assertDoesNotExist()
        compose.onNodeWithText("跳过这步").performClick()
        scrollToTag("onboarding-key")
        compose.onNodeWithTag("onboarding-key").assertTextContains("自动保存后返回密钥")
        compose.onNodeWithText("跳过这步").performClick()
        compose.runOnIdle { assertNull(state.onboarding); assertFalse(events.contains("放弃")); assertTrue(state.settings.api.hasUnsavedChanges) }
    }

    /** 普通设置中的地址修正仍需明确确认，测试建议本身不能替换或保存地址。 */
    @Test fun suggestedEndpointRequiresExplicitConfirmation() {
        showSettingsEditor()
        compose.runOnIdle { state = state.copy(settings = state.settings.copy(api = state.settings.api.copy(
            suggestedUrl = "https://example.com/v1/chat/completions"))) }
        scrollBodyTo("替换并保存")
        compose.onNodeWithText("保持原地址").assertExists()
        compose.runOnIdle { assertEquals("https://example.com/v1", state.settings.api.url); assertTrue(state.settings.api.hasUnsavedChanges) }
        compose.onNodeWithText("替换并保存").performClick()
        compose.runOnIdle { assertEquals("https://example.com/v1/chat/completions", state.settings.api.url); assertFalse(state.settings.api.hasUnsavedChanges) }
        compose.onNode(isSelectable() and hasText("字幕")).performClick()
        compose.onNodeWithText("配置尚未保存").assertDoesNotExist()
    }

    /** 在线第三页仅一个密钥输入，测试可选，错误提示不会把已保存且就绪的密钥变成未就绪。 */
    @Test fun onlineOnlyNeedsKeyAndOptionalTestDoesNotGateCompletion() {
        show(OnboardingStep.MODE)
        scrollToTag("onboarding-choose-online")
        compose.onNodeWithTag("onboarding-choose-online").performClick()
        compose.onNodeWithText("配置在线听译").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-provider").assertDoesNotExist()
        compose.onNodeWithTag("onboarding-model").assertDoesNotExist()
        compose.onNodeWithTag("onboarding-reazon-progress").assertDoesNotExist()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        scrollToTag("onboarding-test")
        compose.onNodeWithTag("onboarding-test").assertIsNotEnabled()
        scrollToTag("onboarding-key")
        compose.onNodeWithTag("onboarding-key").performTextReplacement("测试在线自动保存密钥")
        compose.onNodeWithTag("onboarding-key").performImeAction()
        compose.onNodeWithText("进入羽译").assertIsEnabled()
        scrollToTag("onboarding-test")
        compose.onNodeWithTag("onboarding-test").assertIsEnabled().performClick()
        compose.runOnIdle {
            state = state.copy(onboarding = state.onboarding!!.copy(setup = state.onboarding!!.setup.copy(
                message = UiMessage("测试连接失败：测试网络不可用", true))))
        }
        scrollBodyTo("测试连接失败：测试网络不可用")
        compose.onNodeWithText("进入羽译").assertIsEnabled().performClick()
        compose.runOnIdle { assertNull(state.onboarding); assertTrue(events.contains("引导测试")); assertFalse(events.any { it.startsWith("下载:") }) }
    }

    /** 真实三页流程的按钮固定在页脚，正文滚动和大字号不改变位置或四十八单位触控高度。 */
    @Test fun fixedFooterRemainsReachableWhileBodyScrolls() {
        show(OnboardingStep.PERMISSIONS)
        assertEquals(listOf(OnboardingStep.PERMISSIONS, OnboardingStep.MODE, OnboardingStep.API), onboardingSteps(ListeningMode.LOCAL))
        assertEquals(onboardingSteps(ListeningMode.LOCAL), onboardingSteps(ListeningMode.CLOUD_ASR))
        assertEquals(listOf(OnboardingStep.PERMISSIONS, OnboardingStep.MODE, OnboardingStep.ONLINE), onboardingSteps(ListeningMode.ONLINE))
        for (step in listOf(OnboardingStep.PERMISSIONS, OnboardingStep.MODE, OnboardingStep.API, OnboardingStep.ONLINE)) {
            compose.runOnIdle { state = state.copy(onboarding = OnboardingUiState(step = step,
                setup = setup().copy(key = "测试滚动密钥", keySaved = true),
                listeningMode = if (step == OnboardingStep.ONLINE) ListeningMode.ONLINE else ListeningMode.LOCAL)) }
            val footer = compose.onNodeWithTag("onboarding-footer").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val buttons = listOf("跳过这步") + if (step == OnboardingStep.PERMISSIONS) listOf("下一步")
                else if (step == OnboardingStep.MODE) listOf("上一步") else listOf("上一步", "进入羽译")
            buttons.forEach { label ->
                val bounds = compose.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue("按钮必须保持至少四十八单位触控高度", bounds.height >= 48f * renderDensity - 1f)
            }
            if (step in listOf(OnboardingStep.API, OnboardingStep.ONLINE)) {
                scrollToTag("onboarding-key")
                compose.onNodeWithTag("onboarding-key").assertIsDisplayed()
            }
            when (step) {
                OnboardingStep.PERMISSIONS -> scrollBodyTo("可选通知权限")
                OnboardingStep.MODE -> scrollToTag("onboarding-choose-online")
                OnboardingStep.API -> scrollToTag("onboarding-retry-model")
                else -> scrollToTag("onboarding-test")
            }
            assertEquals(footer, compose.onNodeWithTag("onboarding-footer").fetchSemanticsNode().boundsInRoot)
        }
    }

    /** 低像素密度提供平板测试空间，不修改系统显示或设备偏好。 */
    private val renderDensity: Float get() = if (tablet) 0.6f else 1f

    /** 初始化极简引导的内存状态，下载、授权和自动保存都只回填当前夹具。 */
    private fun show(step: OnboardingStep) {
        val mode = if (step == OnboardingStep.ONLINE) ListeningMode.ONLINE else ListeningMode.LOCAL
        state = SimulSubUiState(onboarding = OnboardingUiState(step = step, listeningMode = mode, setup = setup()),
            live = LiveUiState(listeningServiceId = "local:reazonspeech", permissions = listOf(
                PermissionUiState("audio", "录制播放音频", "仅识别应用播放声音。", false),
                PermissionUiState("overlay", "显示悬浮字幕", "仅用于在其他应用上显示字幕。", true),
                PermissionUiState("notification", "可选通知权限", "可选通知不阻止进入下一步。", false, required = false))),
            settings = SettingsUiState(api = savedApi, listeningMode = mode),
            models = ModelsUiState(items = listOf(model("reazonspeech"), model("乙"))))
        val actions = SimulSubActions(
            onNavigate = { state = state.copy(destination = it) },
            onOnboardingNext = { advance("继续") }, onOnboardingSkip = { advance("跳过") },
            onOnboardingBack = {
                val current = state.onboarding!!
                events += "返回:${current.step.name}"
                val steps = onboardingSteps(current.listeningMode)
                state = state.copy(onboarding = current.copy(step = steps[steps.indexOf(current.step) - 1]))
            },
            onChooseOnboardingRoute = { selected ->
                events += "路线:${selected.name}"
                state = state.copy(onboarding = state.onboarding!!.copy(listeningMode = selected,
                    step = if (selected == ListeningMode.ONLINE) OnboardingStep.ONLINE else OnboardingStep.API),
                    settings = state.settings.copy(listeningMode = selected), live = state.live.copy(listeningMode = selected))
                if (selected == ListeningMode.LOCAL) { events += "下载:reazonspeech"; updateModel("reazonspeech", ModelUiStatus.DOWNLOADING) }
            },
            onPermission = { id ->
                events += "权限:$id"
                state = state.copy(live = state.live.copy(permissions = state.live.permissions.map { if (it.id == id) it.copy(granted = true) else it }))
            },
            onOnboardingProviderChange = { id ->
                events += "供应商:$id"
                state = state.copy(onboarding = state.onboarding!!.copy(setup = state.onboarding!!.setup.copy(providerId = id)))
            },
            onOnboardingModelChange = { id ->
                events += "模型:$id"
                state = state.copy(onboarding = state.onboarding!!.copy(setup = state.onboarding!!.setup.copy(model = id)))
            },
            onOnboardingKeyChange = { key ->
                events += "自动保存"
                val guide = state.onboarding!!
                state = state.copy(onboarding = guide.copy(apiReady = key.isNotBlank(), onlineReady = key.isNotBlank(),
                    setup = guide.setup.copy(key = key, keySaved = key.isNotBlank())))
            },
            onOnboardingTest = { events += "引导测试" },
            onDownloadModel = { id -> events += "下载:$id"; updateModel(id, ModelUiStatus.DOWNLOADING) },
            onDiscardApiDraft = { events += "放弃"; state = state.copy(settings = state.settings.copy(api = savedApi)) },
        )
        render(actions)
    }

    /** 普通设置仍使用自定义服务编辑路由，进入后才注入脏草稿，避免初始状态拦截打开动作。 */
    private fun showSettingsEditor() {
        state = SimulSubUiState(destination = UiDestination.SETTINGS, settings = SettingsUiState(api = savedApi,
            translationProfiles = listOf(ServiceProfileUiState("fixture", "测试翻译接口", builtIn = false))))
        val actions = SimulSubActions(
            onNavigate = { state = state.copy(destination = it) },
            onEditTranslationService = { state = state.copy(settings = state.settings.copy(translationEditorId = it)) },
            onCloseTranslationServiceEditor = { state = state.copy(settings = state.settings.copy(translationEditorId = null)) },
            onDiscardApiDraft = { events += "放弃"; state = state.copy(settings = state.settings.copy(api = savedApi)) },
            onApiDraftChange = { api -> state = state.copy(settings = state.settings.copy(api = api.copy(hasUnsavedChanges = true))) },
            onTestApi = { events += "测试" },
            onSaveApi = { events += "保存"; state = state.copy(settings = state.settings.copy(api = state.settings.api.copy(hasUnsavedChanges = false))) },
            onApplyTestedApiUrl = {
                events += "替换并保存"
                state = state.copy(settings = state.settings.copy(api = state.settings.api.copy(
                    url = state.settings.api.suggestedUrl, suggestedUrl = "", hasUnsavedChanges = false)))
            },
        )
        render(actions)
        scrollBodyTo("翻译服务")
        compose.onNodeWithText("翻译服务").performClick()
        scrollBodyTo("编辑")
        compose.onNodeWithText("编辑").performClick()
        compose.onNodeWithText("编辑翻译服务").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(settings = state.settings.copy(api = savedApi.copy(model = "未保存模型", hasUnsavedChanges = true))) }
    }

    /** 所有测试使用真实根路由与实际返回处理，只有业务数据和动作采用内存夹具。 */
    private fun render(actions: SimulSubActions) {
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CompositionLocalProvider(LocalDensity provides Density(renderDensity, fontScale)) {
                Box(Modifier.requiredSize(if (tablet) 1040.dp else 320.dp, 640.dp)) { SimulSubApp(state, actions) }
            }
        }
    }

    /** 完成与跳过仅推进三页内存状态，不访问偏好或真实下载器。 */
    private fun advance(label: String) {
        val current = state.onboarding!!
        events += "$label:${current.step.name}"
        val steps = onboardingSteps(current.listeningMode)
        state = state.copy(onboarding = if (current.step == steps.last()) null else current.copy(step = steps[steps.indexOf(current.step) + 1]))
    }

    /** 简化表单的固定测试目录不依赖真实供应商服务或任何设备密钥。 */
    private fun setup() = OnboardingSetupUiState(providers = listOf(UiOption("deepseek", "测试 DeepSeek"), UiOption("qwen", "测试千问")),
        model = "测试模型甲", models = listOf(UiOption("测试模型甲", "测试模型甲"), UiOption("测试模型乙", "测试模型乙")))

    /** 模型夹具保留多余简介和删除能力，以防高级信息重新混入引导。 */
    private fun model(id: String) = ModelUiState(id = id, name = if (id == "reazonspeech") "ReazonSpeech" else "识别模型$id",
        description = "不应展示的模型简介", languages = "中英日", sizeLabel = "100 MB", license = "测试许可",
        sourceLabel = "测试来源", canDownload = true, canDelete = true)

    /** 下载、校验及终态都只更新内存状态，不伪造真实模型文件或启用结果。 */
    private fun updateModel(id: String, status: ModelUiStatus) {
        state = state.copy(models = state.models.copy(items = state.models.items.map {
            if (it.id != id) it else it.copy(status = status, progress = 0.25f, error = null,
                canDownload = status in listOf(ModelUiStatus.CANCELLED, ModelUiStatus.FAILED),
                canCancel = status == ModelUiStatus.DOWNLOADING, canActivate = false)
        }))
    }

    /** 遍历实际懒列表，兼容手机单列、平板双列及大字号布局，不猜测目标所属列。 */
    private fun scrollBodyTo(text: String) { scrollTo(hasText(text)) }

    /** 按稳定标识滚动到实际表单控件，避免业务文案调整影响定位。 */
    private fun scrollToTag(tag: String) { scrollTo(hasTestTag(tag)) }

    /** 逐列定位目标，滚动失败只尝试另一列，不吞掉最终缺失断言。 */
    private fun scrollTo(matcher: SemanticsMatcher) {
        compose.waitForIdle()
        val lists = compose.onAllNodes(hasScrollToIndexAction())
        var failure: AssertionError? = null
        for (index in lists.fetchSemanticsNodes().indices) {
            try {
                lists[index].performScrollToNode(matcher)
                compose.onNode(matcher).assertIsDisplayed()
                return
            } catch (error: AssertionError) { failure = error }
        }
        throw AssertionError("找不到引导或设置中的可见目标：$matcher", failure)
    }

    /** 主题、字幕、场景和高级设置均不应出现在引导正文中。 */
    private fun assertNoLaterSettings() {
        listOf("外观", "字幕样式", "场景术语", "高级设置", "使用记录").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }
}
