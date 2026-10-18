package io.github.korenkrita.simulsub

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import io.github.korenkrita.simulsub.ui.*
import io.github.korenkrita.simulsub.online.CloudAsrConfig
import io.github.korenkrita.simulsub.online.LiveTranslateModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 纯内存界面夹具验证内置调用服务与自定义接口流程，不访问控制器、网络、录音或飞书通知。 */
class NamedServicesUiDeviceTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(SimulSubUiState())
    private val events = mutableListOf<String>()
    private lateinit var back: OnBackPressedDispatcher
    private val cloudId = "fa21d808-ccda-4d1c-8aea-d74ad8c8f916"
    private val listeningProfiles get() = listOf(
        ServiceProfileUiState("local:fixture-model", "ReazonSpeech", "已安装模型", kind = "local"),
        ServiceProfileUiState(cloudId, "千问 · qwen-audio-3.1-asr-flash-streaming", "qwen-audio-3.1-asr-flash-streaming · 待填写平台密钥", kind = "asr"),
        ServiceProfileUiState("direct-fixture", "千问 · qwen3.5-livetranslate-flash-realtime", "实时听译模型", kind = "realtime"),
        ServiceProfileUiState("segmented-fixture", "千问 · qwen3-livetranslate-flash", LiveTranslateModels.ECONOMY_ID, kind = "segmented"),
        ServiceProfileUiState("local:sensevoice", "SenseVoice Small", "需下载", kind = "local", configured = false),
        ServiceProfileUiState("local:whisper", "Whisper Tiny multilingual", "需下载", kind = "local", configured = false),
        ServiceProfileUiState("local:moonshine-en", "Moonshine Tiny English", "需下载", kind = "local", configured = false),
        ServiceProfileUiState("local:moonshine-ja", "Moonshine Tiny Japanese", "需下载", kind = "local", configured = false),
    )

    /** 首页下拉只列服务，旧资源与管理项不能再次混入选项。 */
    @Test fun homePickersExcludeLegacyManagementOptions() {
        show(SimulSubUiState(live = LiveUiState(
            listeningServiceId = "profile:$cloudId", listeningServices = listOf(
                UiOption("profile:$cloudId", "千问 · qwen-audio-3.1-asr-flash-streaming"), UiOption("catalog", "下载更多本地模型")),
            translationServiceId = "translation-fixture", translationServices = listOf(
                UiOption("translation-fixture", "DeepSeek · deepseek-flash"), UiOption("manage", "管理翻译配置")),
        )))
        openPicker("listening-service-picker")
        compose.onNodeWithText("下载更多本地模型").assertDoesNotExist()
        compose.onNode(hasText("千问 · qwen-audio-3.1-asr-flash-streaming") and hasAnyAncestor(isPopup())).performClick()
        openPicker("translation-service-picker")
        compose.onNodeWithText("管理翻译配置").assertDoesNotExist()
        compose.onNode(hasText("DeepSeek · deepseek-flash") and hasAnyAncestor(isPopup())).performClick()
    }

    /** 未收到服务列表时提示检查内置服务，不要求先创建；查看入口进入默认服务页。 */
    @Test fun emptyHomeOffersServiceManagementWithoutCreation() {
        show(SimulSubUiState(live = LiveUiState(listeningServices = listOf(UiOption("catalog", "下载模型")),
            translationServices = listOf(UiOption("manage", "管理翻译")))))
        reveal(hasTestTag("listening-services-empty"))
        compose.onNodeWithText("听写服务尚未就绪").assertIsDisplayed()
        reveal(hasTestTag("translation-services-empty"))
        compose.onNodeWithText("翻译服务尚未就绪").assertIsDisplayed()
        compose.onNodeWithTag("listening-service-picker").assertDoesNotExist()
        compose.onNodeWithTag("translation-service-picker").assertDoesNotExist()
        reveal(hasText("查看听写服务"))
        compose.onNodeWithText("查看听写服务").performClick()
        compose.onNodeWithText("正在读取内置听写服务；本地模型与千问接口会在此列出。").assertIsDisplayed()
        compose.onNodeWithTag("create-listening-service").assertDoesNotExist()
    }

    /** 内置云服务选择密钥使用原始标识，使用服务带前缀，本地卡没有编辑入口。 */
    @Test fun listeningListKeepsAllProfilesAndDispatchesDistinctIds() {
        show(settingsState(), SimulSubActions(onServiceCredentialChange = { serviceId, credentialId ->
            events += "密钥:$serviceId:$credentialId"
        }, onSelectListeningService = { events += "使用:$it" }))
        openSection("听写服务")
        if (compose.onAllNodesWithTag("service-profiles-two-columns").fetchSemanticsNodes().isNotEmpty()) {
            val left = compose.onNodeWithTag("service-profile-local:fixture-model").assertIsDisplayed().getUnclippedBoundsInRoot()
            val right = compose.onNodeWithTag("service-profile-$cloudId").assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("平板首行左右两栏都必须显示服务卡", left.right < right.left)
            assertTrue("两栏首行必须对齐", kotlin.math.abs(left.top.value - right.top.value) <= 1f)
        }
        listOf("local:fixture-model", cloudId, "direct-fixture").forEach { id ->
            reveal(hasTestTag("service-profile-$id"))
            compose.onNodeWithTag("service-profile-$id").assertIsDisplayed()
        }
        cardAction(cloudId, "选择密钥")
        compose.onNode(hasText("选择密钥") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
        cardAction(cloudId, "使用")
        compose.runOnIdle { assertEquals(listOf("使用:profile:$cloudId"), events) }
        cardAction("local:fixture-model", "使用")
        compose.runOnIdle { assertEquals("使用:local:fixture-model", events.last()) }
        compose.onNode(hasText("编辑") and hasAnyAncestor(hasTestTag("service-profile-local:fixture-model"))).assertDoesNotExist()
    }

    /** 内置服务列表没有新建入口，点击密钥选择不会创建自定义听写配置。 */
    @Test fun builtInListeningListDoesNotCreateCustomService() {
        show(settingsState(), SimulSubActions(onCreateListeningService = { _, _ -> events += "意外创建" }))
        openSection("听写服务")
        compose.onNodeWithTag("create-listening-service").assertDoesNotExist()
        compose.onNodeWithTag("new-service-type").assertDoesNotExist()
        cardAction(cloudId, "选择密钥")
        compose.onNodeWithText("完成").performClick()
        compose.runOnIdle { assertTrue(events.isEmpty()) }
    }

    /** 连接地址修改受统一返回保护，继续编辑不关闭，放弃后先恢复再关闭。 */
    @Test fun listeningConnectionChangesProtectBothBackActions() {
        show(settingsState().copy(settings = settingsState().settings.copy(
            listeningProfiles = listeningProfiles.map { if (it.id == cloudId) it.copy(builtIn = false) else it },
            listeningEditor = ListeningEditorUiState(cloudId, "千问 · qwen-audio-3.1-asr-flash-streaming", ListeningMode.CLOUD_ASR))),
            SimulSubActions(onCloudAsrDraftChange = { draft ->
                state = state.copy(settings = state.settings.copy(
                    cloudAsr = draft.copy(hasUnsavedChanges = true)))
            }, onDiscardCloudAsrDraft = {
                events += "恢复草稿"
                state = state.copy(settings = state.settings.copy(cloudAsr = state.settings.cloudAsr.copy(
                    endpoint = CloudAsrConfig.DEFAULT_ENDPOINT, hasUnsavedChanges = false)))
            }, onCloseListeningServiceEditor = {
                events += "关闭编辑"
                state = state.copy(settings = state.settings.copy(listeningEditor = null))
            }))
        openSection("听写服务")
        reveal(hasTestTag("cloud-asr-endpoint"))
        compose.onNodeWithTag("cloud-asr-endpoint").performTextReplacement("wss://custom.example/api-ws/v1/inference")
        compose.onNodeWithContentDescription("返回听写服务列表").performClick()
        compose.onNodeWithText("配置尚未保存").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.runOnIdle {
            assertEquals("wss://custom.example/api-ws/v1/inference", state.settings.cloudAsr.endpoint)
            assertTrue(events.isEmpty()); back.onBackPressed()
        }
        compose.onNodeWithText("放弃修改").performClick()
        compose.runOnIdle {
            assertEquals(listOf("恢复草稿", "关闭编辑"), events)
            assertEquals(CloudAsrConfig.DEFAULT_ENDPOINT, state.settings.cloudAsr.endpoint)
        }
        reveal(hasTestTag("service-profile-$cloudId"))
        compose.onNodeWithTag("create-listening-service").assertDoesNotExist()
    }

    /** 翻译编辑、保存与关闭均保持当前使用项；关闭编辑先调用独立恢复动作。 */
    @Test fun translationEditorUsesIndependentCallbacksAndClosesToList() {
        val initial = settingsState()
        show(initial, SimulSubActions(onEditTranslationService = { events += "编辑翻译:$it" },
            onSelectApiProfile = { events += "旧选择:$it" }, onSelectTranslationService = { events += "使用翻译:$it" },
            onSaveApi = { events += "保存翻译" }, onCloseTranslationServiceEditor = { events += "关闭翻译" }))
        openSection("翻译服务")
        cardAction("translation-fixture", "编辑")
        reveal(hasText("保存翻译配置"))
        compose.onNodeWithText("保存翻译配置").performClick()
        compose.onNodeWithText("当前配置").assertDoesNotExist()
        compose.onNodeWithText("重命名").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回翻译服务列表").performClick()
        compose.runOnIdle {
            assertEquals(listOf("编辑翻译:translation-fixture", "保存翻译", "关闭翻译"), events)
            assertEquals(initial.settings.translationServiceId, state.settings.translationServiceId)
        }
        cardAction("translation-fixture", "使用")
        compose.runOnIdle { assertEquals("使用翻译:translation-fixture", events.last()) }
    }

    /** 翻译目录明确选择自定义兼容接口后才创建草稿，旧新增动作不参与。 */
    @Test fun translationListCreatesCustomProviderAfterSelection() {
        show(settingsState(), SimulSubActions(onCreateTranslationProvider = { id ->
            events += "新建翻译:$id"
            state = state.copy(settings = state.settings.copy(translationEditorId = "new-custom",
                api = state.settings.api.copy(profileId = "new-custom", presetId = id)))
        },
            onAddApiProfile = { events += "旧新增:$it" }, onCloseTranslationServiceEditor = { events += "关闭翻译" }))
        openSection("翻译服务")
        reveal(hasTestTag("create-translation-service"))
        compose.onNodeWithTag("create-translation-service").performClick()
        compose.onNodeWithTag("translation-provider-search").assertIsDisplayed()
        compose.onNodeWithText("继续配置").assertIsNotEnabled()
        compose.onNode(hasAnyAncestor(hasTestTag("new-translation-provider")) and hasClickAction()).performClick()
        compose.onNode(hasText("自定义兼容接口") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("继续配置").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("返回翻译服务列表").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("新建翻译:custom"), events) }
    }

    /** 引导当前模式与编辑模式不同时，表单及测试仍必须指向编辑器模式。 */
    @Test fun onboardingPrioritizesListeningEditorMode() {
        val initial = settingsState()
        show(initial.copy(onboarding = OnboardingUiState(step = OnboardingStep.MODE, listeningMode = ListeningMode.ONLINE),
            settings = initial.settings.copy(
                listeningProfiles = initial.settings.listeningProfiles.map { if (it.id == cloudId) it.copy(builtIn = false) else it },
                listeningEditor = ListeningEditorUiState(cloudId, "千问 · qwen-audio-3.1-asr-flash-streaming", ListeningMode.CLOUD_ASR))),
            SimulSubActions(onTestOnline = { events += "错误的直译测试" }, onTestCloudAsr = { events += "听写测试" }))
        reveal(hasTestTag("cloud-asr-endpoint"))
        compose.onNodeWithTag("cloud-asr-endpoint").assertIsDisplayed()
        compose.onNodeWithTag("online-endpoint").assertDoesNotExist()
        reveal(hasText("测试听写连接"))
        compose.onNodeWithText("测试听写连接").performClick()
        compose.runOnIdle { assertEquals(listOf("听写测试"), events) }
    }

    /** 引导中的本地卡只允许使用，模型下载走独立入口。 */
    @Test fun onboardingLocalCardHasNoEditButton() {
        show(settingsState().copy(onboarding = OnboardingUiState(step = OnboardingStep.MODE)))
        reveal(hasTestTag("service-profile-local:fixture-model"))
        compose.onNode(hasText("编辑") and hasAnyAncestor(hasTestTag("service-profile-local:fixture-model"))).assertDoesNotExist()
        cardAction("local:fixture-model", "使用")
        compose.onNodeWithTag("create-listening-service").assertDoesNotExist()
    }

    /** 引导跳过旧自定义未保存草稿时，先恢复再关闭编辑器。 */
    @Test fun onboardingSkipClosesLegacyDraftBeforeAdvancing() {
        val initial = settingsState()
        show(initial.copy(onboarding = OnboardingUiState(step = OnboardingStep.MODE),
            settings = initial.settings.copy(
                listeningEditor = ListeningEditorUiState("legacy-draft", "未保存听写", ListeningMode.CLOUD_ASR),
                cloudAsr = initial.settings.cloudAsr.copy(hasUnsavedChanges = true))),
            SimulSubActions(onDiscardCloudAsrDraft = { events += "恢复旧草稿" },
                onCloseListeningServiceEditor = {
                    events += "关闭旧草稿"
                    state = state.copy(settings = state.settings.copy(listeningEditor = null,
                        cloudAsr = state.settings.cloudAsr.copy(hasUnsavedChanges = false)))
                }, onOnboardingSkip = { events += "跳过步骤" }))
        compose.onNodeWithText("跳过这步").performClick()
        compose.onNodeWithText("配置尚未保存").assertIsDisplayed()
        compose.onNodeWithText("放弃修改").performClick()
        compose.runOnIdle {
            assertEquals(listOf("恢复旧草稿", "关闭旧草稿", "跳过步骤"), events)
            assertEquals(null, state.settings.listeningEditor)
        }
    }

    /** 内置服务默认列出，未填密钥仍能选择密钥，不要求先添加服务。 */
    @Test fun builtInServicesAreListedBeforeKeysOrCustomCreation() {
        val initial = settingsState()
        show(initial.copy(settings = initial.settings.copy(
            listeningProfiles = initial.settings.listeningProfiles.map { if (it.kind == "local") it else it.copy(configured = false) })),
            SimulSubActions(onCreateListeningService = { _, _ -> events += "意外新建听写" },
                onCreateTranslationProvider = { events += "意外新建翻译" },
                onEditListeningService = { events += "编辑听写:$it" }, onEditTranslationService = { events += "编辑翻译:$it" }))
        openSection("听写服务")
        listOf(cloudId, "direct-fixture", "segmented-fixture").forEach { id ->
            val status = hasText("待填写平台密钥") and hasAnyAncestor(hasTestTag("service-profile-$id"))
            reveal(status)
            compose.onNode(status).assertIsDisplayed()
        }
        cardAction(cloudId, "选择密钥")
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithContentDescription("返回设置").performClick()
        openSection("翻译服务")
        listOf("translation-fixture", "translation-qwen").forEach { id ->
            reveal(hasTestTag("service-profile-$id"))
            compose.onNodeWithTag("service-profile-$id").assertIsDisplayed()
        }
        cardAction("translation-qwen", "选择密钥")
        compose.onNodeWithText("连接设置与测试").assertIsDisplayed()
        compose.runOnIdle { assertTrue(events.isEmpty()) }
    }

    /** 未下载的本地服务仍显示在目录，下载按钮进入模型库，不切换首页当前使用项。 */
    @Test fun missingLocalModelOffersDownloadWithoutSelectingAnotherService() {
        val initial = settingsState()
        show(initial.copy(live = initial.live.copy(listeningServiceId = "profile:$cloudId"),
            settings = initial.settings.copy(listeningProfiles = initial.settings.listeningProfiles.map {
                if (it.kind == "local") it.copy(configured = false) else it
            })), SimulSubActions(onSelectListeningService = { events += "意外使用:$it" }))
        openSection("听写服务")
        reveal(hasTestTag("service-profile-local:fixture-model"))
        compose.onNode(hasText("模型文件尚未下载") and hasAnyAncestor(hasTestTag("service-profile-local:fixture-model"))).assertIsDisplayed()
        cardAction("local:fixture-model", "下载模型")
        compose.runOnIdle {
            assertEquals(UiDestination.MODELS, state.destination)
            assertEquals("profile:$cloudId", state.live.listeningServiceId)
            assertTrue(events.isEmpty())
        }
    }

    /** 首页保留用户选择的待下载本地模型，主操作引导进入独立模型库而不偷换服务。 */
    @Test fun pendingLocalSelectionKeepsIdAndOpensModelLibrary() {
        show(SimulSubUiState(live = LiveUiState(listeningServiceId = "local:whisper", modelLabel = "未启用模型",
            statusTitle = "需要下载识别模型", statusDetail = "Whisper Tiny 的模型文件尚未下载。",
            listeningServices = listOf(UiOption("local:whisper", "Whisper Tiny · 需下载"), UiOption("profile:$cloudId", "千问在线听写")))),
            SimulSubActions(onSelectListeningService = { events += "意外切换:$it" }, onStart = { events += "意外启动" }))
        compose.onNodeWithText("下载并启用模型").assertIsDisplayed()
        compose.onNodeWithTag("live-primary-action").performClick()
        compose.runOnIdle {
            assertEquals(UiDestination.MODELS, state.destination)
            assertEquals("local:whisper", state.live.listeningServiceId)
            assertTrue(events.isEmpty())
        }
    }

    /** 平台密钥是编辑子页；返回时保留同一接口的地址草稿和编辑目标，不隐式保存或使用。 */
    @Test fun platformKeysBackPreservesLegacyConnectionDraft() {
        val initial = settingsState()
        val draftUrl = "wss://workspace.example/api-ws/v1/inference"
        show(initial.copy(live = initial.live.copy(listeningMode = ListeningMode.CLOUD_ASR,
            listeningServiceId = "profile:$cloudId", cloudReady = false),
            settings = initial.settings.copy(platformKeys = listOf(PlatformKeyUiState("qwen", "千问")),
                listeningProfiles = initial.settings.listeningProfiles.map {
                    if (it.id == cloudId) it.copy(builtIn = false) else it
                })),
            SimulSubActions(onEditListeningService = { id ->
                state = state.copy(settings = state.settings.copy(listeningEditor = ListeningEditorUiState(id,
                    "千问 · ${CloudAsrConfig.DEFAULT_MODEL}", ListeningMode.CLOUD_ASR)))
            }, onCloudAsrDraftChange = { draft -> state = state.copy(settings = state.settings.copy(cloudAsr = draft.copy(hasUnsavedChanges = true))) },
                onPlatformCredentialChange = { id, credentialId, value -> state = state.copy(settings = state.settings.copy(platformKeys = state.settings.platformKeys.map {
                    if (it.id == id && it.credentialId == credentialId) it.copy(key = value, hasUnsavedChanges = true) else it
                })) }, onSavePlatformCredential = { id, credentialId ->
                    events += "保存密钥:$id:$credentialId"
                    state = state.copy(live = state.live.copy(cloudReady = true), settings = state.settings.copy(
                        platformKeys = state.settings.platformKeys.map { if (it.id == id && it.credentialId == credentialId) it.copy(hasSavedKey = true, hasUnsavedChanges = false) else it }))
                },
                onSaveListeningService = { events += "意外保存" }, onSelectListeningService = { events += "意外使用:$it" }))
        openSection("听写服务")
        cardAction(cloudId, "编辑")
        reveal(hasTestTag("cloud-asr-endpoint"))
        compose.onNodeWithTag("cloud-asr-endpoint").performTextReplacement(draftUrl)
        reveal(hasText("管理平台密钥"))
        compose.onNodeWithText("管理平台密钥").performClick()
        reveal(hasTestTag("platform-key-qwen:default"))
        compose.onNodeWithTag("platform-key-qwen:default").assertIsDisplayed().performTextReplacement("fixture-platform-key")
        reveal(hasTestTag("platform-key-save-qwen:default"))
        compose.onNodeWithTag("platform-key-save-qwen:default").performClick()
        compose.onNodeWithContentDescription("返回听写服务").performClick()
        compose.onNodeWithText("配置尚未保存").assertDoesNotExist()
        reveal(hasTestTag("cloud-asr-endpoint"))
        compose.onNodeWithTag("cloud-asr-endpoint").assertTextContains(draftUrl)
        compose.runOnIdle {
            assertEquals(cloudId, state.settings.listeningEditor?.id)
            assertTrue(state.settings.cloudAsr.hasUnsavedChanges)
            assertEquals("profile:$cloudId", state.live.listeningServiceId)
            assertTrue(state.live.cloudReady)
            assertEquals(listOf("保存密钥:qwen:default"), events)
        }
    }

    /** 配置变更保留根页编辑目标和草稿，保存不得改写首页正在使用的服务。 */
    @Test fun rootRestorationKeepsExistingTranslationEditorTarget() {
        verifyTranslationEditorRestoration(onboarding = false, keepTarget = true)
    }

    /** 模拟进程丢失控制器目标后，根页不能把保存的编辑路由绑定到活动服务。 */
    @Test fun rootRestorationClosesTranslationEditorWithoutTarget() {
        verifyTranslationEditorRestoration(onboarding = false, keepTarget = false)
    }

    /** 引导配置变更保留翻译子页和原编辑目标，不回退到直接编辑活动服务。 */
    @Test fun onboardingRestorationKeepsExistingTranslationEditorTarget() {
        verifyTranslationEditorRestoration(onboarding = true, keepTarget = true)
    }

    /** 引导进程恢复缺少编辑目标时返回服务列表，不恢复为活动服务的连接表单。 */
    @Test fun onboardingRestorationClosesTranslationEditorWithoutTarget() {
        verifyTranslationEditorRestoration(onboarding = true, keepTarget = false)
    }

    /** 分别模拟目标存活和丢失；先真实打开编辑路由，确保恢复验证不是从默认列表开始。 */
    private fun verifyTranslationEditorRestoration(onboarding: Boolean, keepTarget: Boolean) {
        val activeId = "active-translation"
        val targetId = "edited-translation"
        val profiles = listOf(ServiceProfileUiState(activeId, "DeepSeek · deepseek-flash", "deepseek-flash", selected = true, builtIn = false),
            ServiceProfileUiState(targetId, "千问 · qwen-plus", "qwen-plus", builtIn = false))
        val options = profiles.map { UiOption(it.id, it.name) }
        val activeDraft = TranslationApiUiState(profileId = activeId, profiles = options,
            url = "https://active.example/v1", model = "deepseek-flash", hasSavedConfiguration = true)
        state = settingsState().copy(
            live = LiveUiState(translationServiceId = activeId, translationServices = options),
            settings = settingsState().settings.copy(api = activeDraft, translationServiceId = activeId,
                translationServices = options, translationProfiles = profiles),
            onboarding = if (onboarding) OnboardingUiState(step = OnboardingStep.MODE) else null)
        val actions = SimulSubActions(onNavigate = { state = state.copy(destination = it) },
            onEditTranslationService = { id ->
                events += "编辑:$id"
                state = state.copy(settings = state.settings.copy(translationEditorId = id,
                    api = activeDraft.copy(profileId = id, url = "https://edited.example/v1", model = "qwen-plus",
                        hasUnsavedChanges = true)))
            }, onSaveApi = {
                events += "保存:${state.settings.translationEditorId}:${state.settings.api.model}"
                state = state.copy(settings = state.settings.copy(api = state.settings.api.copy(hasUnsavedChanges = false)))
            }, onCloseTranslationServiceEditor = {
                events += "关闭:${state.settings.translationEditorId}"
                state = state.copy(settings = state.settings.copy(translationEditorId = null, api = activeDraft))
            }, onSelectTranslationService = { events += "意外使用:$it" })
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            SimulSubApp(state, actions)
        }
        if (onboarding) compose.onNodeWithText("管理翻译服务").performClick() else openSection("翻译服务")
        cardAction(targetId, "编辑")
        reveal(hasText("qwen-plus"))
        compose.onNodeWithText("qwen-plus").assertIsDisplayed()
        if (onboarding) compose.onNodeWithText("返回翻译服务列表").assertIsDisplayed()
        else compose.onNodeWithContentDescription("返回翻译服务列表").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(targetId, state.settings.translationEditorId)
            assertEquals(activeId, state.live.translationServiceId)
            // 控制器状态在恢复包之外；目标丢失时仅 UI 的 rememberSaveable 路由被保留。
            if (!keepTarget) state = state.copy(settings = state.settings.copy(translationEditorId = null, api = activeDraft))
        }
        compose.waitForIdle()
        // 恢复前路由仍是编辑页，证明丢失目标用例真实覆盖了恢复保护。
        if (onboarding) compose.onNodeWithText("返回翻译服务列表").assertIsDisplayed()
        else compose.onNodeWithContentDescription("返回翻译服务列表").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        if (keepTarget) {
            reveal(hasText("qwen-plus"))
            compose.onNodeWithText("qwen-plus").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(targetId, state.settings.translationEditorId)
                assertEquals(targetId, state.settings.api.profileId)
                assertEquals("https://edited.example/v1", state.settings.api.url)
                assertTrue(state.settings.api.hasUnsavedChanges)
            }
            reveal(hasText("保存翻译配置"))
            compose.onNodeWithText("保存翻译配置").performClick()
            compose.runOnIdle { assertEquals(listOf("编辑:$targetId", "保存:$targetId:qwen-plus"), events) }
            if (onboarding) compose.onNodeWithText("返回翻译服务列表").performClick()
            else compose.onNodeWithContentDescription("返回翻译服务列表").performClick()
            compose.runOnIdle { assertEquals("关闭:$targetId", events.last()) }
        } else {
            compose.onNodeWithText("保存翻译配置").assertDoesNotExist()
            if (onboarding) compose.onNodeWithText("返回翻译服务列表").assertDoesNotExist()
            else compose.onNodeWithContentDescription("返回翻译服务列表").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(null, state.settings.translationEditorId)
                assertEquals(activeId, state.settings.api.profileId)
                assertEquals(listOf("编辑:$targetId"), events)
            }
        }
        reveal(hasTestTag("create-translation-service"))
        compose.onNodeWithTag("create-translation-service").assertIsDisplayed()
        reveal(hasTestTag("service-profile-$targetId"))
        compose.onNodeWithTag("service-profile-$targetId").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(activeId, state.live.translationServiceId)
            assertEquals(activeId, state.settings.translationServiceId)
            assertTrue(state.settings.translationProfiles.single { it.id == activeId }.selected)
        }
    }

    /** 创建完全独立的 UI 状态；已保存但未填平台密钥的听写服务仍可使用。 */
    private fun settingsState() = SimulSubUiState(destination = UiDestination.SETTINGS,
        settings = SettingsUiState(listeningProfiles = listeningProfiles,
            translationProfiles = listOf(ServiceProfileUiState("translation-fixture", "DeepSeek · deepseek-flash", "DeepSeek 官方接口", builtIn = false),
                ServiceProfileUiState("translation-qwen", "千问 · qwen-plus", "阿里百炼兼容接口")),
            api = TranslationApiUiState(profileId = "translation-fixture", profiles = listOf(UiOption("translation-fixture", "DeepSeek · deepseek-flash"))),
            translationServiceId = "previous-translation"))

    /** 根路由仍由真实组合处理，业务回调仅记录事件或回填内存。 */
    private fun show(initial: SimulSubUiState, callbacks: SimulSubActions = SimulSubActions()) {
        state = initial
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            SimulSubApp(state, callbacks.copy(onNavigate = { state = state.copy(destination = it) }))
        }
    }

    /** 通过设置入口进入详情，避免直接操作根界面的私有路由。 */
    private fun openSection(title: String) {
        reveal(hasText(title))
        compose.onNodeWithText(title).performClick()
        compose.waitForIdle()
    }

    /** 卡片按钮根据稳定服务标识定位，避免同名操作点中其他服务。 */
    private fun cardAction(id: String, label: String) {
        val matcher = hasText(label) and hasAnyAncestor(hasTestTag("service-profile-$id"))
        reveal(matcher)
        compose.onNode(matcher).performClick()
    }

    /** 打开真实下拉菜单，断言用户实际可见的选项。 */
    private fun openPicker(tag: String) {
        reveal(hasTestTag(tag))
        compose.onNode(hasAnyAncestor(hasTestTag(tag)) and hasClickAction()).performClick()
        compose.onNode(isPopup()).assertExists()
    }

    /** 在手机单列与平板双列中寻找目标，测试不修改设备显示设置。 */
    private fun reveal(matcher: SemanticsMatcher) {
        compose.waitForIdle()
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
        throw failure ?: AssertionError("未找到服务界面内容：$matcher")
    }
}
