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
import io.github.korenkrita.simulsub.ui.*
import io.github.korenkrita.simulsub.online.LiveTranslateModels
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 用纯内存状态验证两种窗口与字号下的在线引导、直达路由、锁定和草稿保护，不访问网络或录音。 */
@RunWith(Parameterized::class)
class OnlineListeningUiDeviceTest(private val tablet: Boolean, private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(SimulSubUiState())
    private val events = mutableListOf<String>()
    private lateinit var backDispatcher: OnBackPressedDispatcher
    private var testView: android.view.View? = null
    private val saved = OnlineListeningUiState(endpoint = "wss://workspace.example.test/api-ws/v1/realtime",
        model = LiveTranslateModels.REALTIME_ID, key = "完整测试密钥", hasSavedConfiguration = true)

    companion object {
        /** 每条交互覆盖手机、平板与两倍字号，全部使用同一应用组合入口。 */
        @JvmStatic @Parameterized.Parameters(name = "平板={0}，字号={1}")
        fun cases(): List<Array<Any>> = listOf(false, true).flatMap { tablet ->
            listOf(1f, 2f).map { arrayOf<Any>(tablet, it) }
        }
    }

    /** 在线缺配置只进入在线设置，不能被本地缺模型状态误导，返回仍回到同传首页。 */
    @Test fun missingCloudConfigurationOpensListeningDetailAndReturnsHome() {
        show()
        revealLivePrimaryAction()
        compose.onNodeWithText("配置在线听译").performClick()
        compose.onNodeWithContentDescription("返回同传").assertIsDisplayed()
        scrollFormTo(hasTestTag("online-endpoint"))
        compose.onNodeWithTag("online-endpoint").assertTextContains(saved.endpoint)
        compose.onNodeWithContentDescription("返回同传").performClick()
        revealLivePrimaryAction()
        compose.runOnIdle {
            assertEquals(UiDestination.LIVE, state.destination)
            assertFalse(events.any { it == "开始" || it == "导航:MODELS" || it == "测试" })
            assertEquals(1, events.count { it == "取消测试" })
        }
    }

    /** 直接听译首页保持当前能力边界，设置仍完整保留所有其他路径的入口。 */
    @Test fun onlineModeKeepsAllSettingsEntries() {
        show()
        compose.onNodeWithText("内容场景").assertDoesNotExist()
        compose.onNodeWithText("其他字幕语言").assertDoesNotExist()
        compose.onNodeWithText("场景与术语").assertDoesNotExist()
        compose.onNode(isSelectable() and hasText("设置")).performClick()
        listOf("听写", "翻译", "平台密钥", "模型库", "场景与术语", "高级设置").forEach { label ->
            compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText(label))
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
    }

    /** 测试结果与保存状态分开；编辑完整草稿后，只有显式保存才能清除脏标记。 */
    @Test fun explicitTestShowsResultWithoutSaving() {
        show()
        openListening()
        scrollFormTo(hasTestTag("online-model"))
        compose.onNodeWithText("千问 3.5 · 实时听译").performClick()
        compose.onNodeWithText("千问 3 · 省钱分段").performScrollTo().performClick()
        clickForm("测试在线连接")
        scrollFormTo(hasText("连接验证成功，未发送音频"))
        compose.onNodeWithText("连接验证成功，未发送音频").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(state.settings.online.hasUnsavedChanges)
            assertEquals(LiveTranslateModels.ECONOMY_ID, state.settings.online.model)
            assertEquals(listOf("草稿", "测试"), events.filter { it in listOf("草稿", "测试", "保存") })
            assertFalse("滚动查看测试结果不能取消连接测试", events.contains("取消测试"))
        }
        clickForm("保存在线配置")
        scrollFormTo(hasTestTag("online-save-status"))
        compose.onNodeWithTag("online-save-status").performScrollTo()
            .assertIsDisplayed().assertTextEquals("保存状态：已保存")
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.onNodeWithText("配置尚未保存").assertDoesNotExist()
        compose.runOnIdle { assertFalse(state.settings.online.hasUnsavedChanges) }
    }

    /** 模型是只读下拉框；切换省钱版同步协议路由，密钥和未保存保护不受影响。 */
    @Test fun modelMenuUsesSupportedOptionsAndUpdatesTheEndpoint() {
        show()
        openListening()
        scrollFormTo(hasTestTag("online-model"))
        compose.onNodeWithTag("online-model").assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsActions.SetText))
        compose.onNodeWithText("千问 3.5 · 实时听译").performClick()
        compose.onNodeWithText("千问 3 · 省钱分段").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(LiveTranslateModels.ECONOMY_ID, state.settings.online.model)
            assertEquals("https://workspace.example.test/compatible-mode/v1/chat/completions", state.settings.online.endpoint)
            assertEquals(saved.key, state.settings.platformKeys.single { it.id == "qwen" }.key)
            assertTrue(state.settings.online.hasUnsavedChanges)
            assertFalse(events.contains("测试") || events.contains("保存"))
        }
        compose.onNodeWithText("千问 3 · 省钱分段").performClick()
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("返回设置").assertExists()
        compose.runOnIdle { assertEquals(1, events.count { it == "草稿" }) }
    }

    /** 中央密钥显隐只影响显示，听写页官方说明只打开帮助。 */
    @Test fun keyVisibilityAndOfficialHelpAreExplicitActions() {
        show()
        openListening()
        scrollFormTo(hasTestTag("online-help"))
        compose.onNodeWithTag("online-help").performScrollTo().performClick()
        scrollFormTo(hasText("管理平台密钥"))
        compose.onAllNodes(hasText("管理平台密钥")).onLast().performScrollTo().performClick()
        scrollFormTo(hasTestTag("platform-key-qwen"))
        compose.onNodeWithContentDescription("显示阿里百炼 · 千问密钥").performScrollTo().performClick()
        compose.onNodeWithContentDescription("隐藏阿里百炼 · 千问密钥").assertExists().performClick()
        compose.onNodeWithContentDescription("显示阿里百炼 · 千问密钥").assertExists()
        compose.runOnIdle {
            assertEquals("完整测试密钥", state.settings.platformKeys.single { it.id == "qwen" }.key)
            assertFalse(state.settings.platformKeys.single { it.id == "qwen" }.hasUnsavedChanges)
            assertFalse(state.settings.online.hasUnsavedChanges)
            assertTrue(events.contains("帮助"))
            assertFalse(events.contains("保存") || events.contains("测试"))
        }
    }

    /** 中央千问密钥显式保存后回填两条连接，连接地址草稿保持独立。 */
    @Test fun sharedQwenKeySavesOnceForBothConnections() {
        show()
        openListening()
        scrollFormTo(hasText("管理平台密钥"))
        compose.onAllNodes(hasText("管理平台密钥")).onLast().performScrollTo().performClick()
        scrollFormTo(hasTestTag("platform-key-qwen"))
        compose.onNodeWithTag("platform-key-qwen").performTextReplacement("更新后的千问密钥")
        compose.onNodeWithTag("platform-key-status-qwen").assertTextEquals("密钥状态：有未保存的修改")
        compose.runOnIdle {
            assertEquals("更新后的千问密钥", state.settings.platformKeys.single { it.id == "qwen" }.key)
            assertEquals(saved.endpoint, state.settings.online.endpoint)
            assertFalse(events.contains("保存平台密钥"))
        }
        scrollFormTo(hasTestTag("platform-key-save-qwen"))
        compose.onNodeWithTag("platform-key-save-qwen").performScrollTo().performClick()
        compose.onNodeWithTag("platform-key-status-qwen").assertTextEquals("密钥状态：已配置")
        compose.runOnIdle {
            assertEquals("更新后的千问密钥", state.settings.online.key)
            assertEquals("更新后的千问密钥", state.settings.cloudAsr.key)
            assertEquals(1, events.count { it == "保存平台密钥" })
            assertFalse(state.settings.online.hasUnsavedChanges)
        }
    }

    /** 脏草稿切模式先确认；继续编辑完整保留，放弃必须先恢复再派发模式切换。 */
    @Test fun dirtyModeSwitchCanBeCancelledOrDiscardedExactlyOnce() {
        show(dirty = true)
        val draft = state.settings.online
        selectListeningService("本地测试模型")
        compose.onNodeWithText("配置尚未保存").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.runOnIdle { assertEquals(draft, state.settings.online); assertTrue(events.isEmpty()) }
        selectListeningService("本地测试模型")
        val confirm = compose.onNodeWithText("放弃修改").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        compose.runOnIdle { confirm(); confirm() }
        compose.runOnIdle {
            assertEquals(listOf("放弃", "模式:LOCAL"), events)
            assertEquals(saved, state.settings.online)
            assertEquals(ListeningMode.LOCAL, state.live.listeningMode)
        }
    }

    /** 根页面系统返回同样保护在线草稿，取消后不回写任何配置。 */
    @Test fun rootBackProtectsOnlineDraft() {
        show(dirty = true)
        val draft = state.settings.online
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.onNodeWithText("配置尚未保存").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.runOnIdle { assertEquals(draft, state.settings.online); assertTrue(events.isEmpty()) }
    }

    /** 顶栏、系统返回与主导航共用同一确认，取消后保留当前详情，最终仅恢复和导航一次。 */
    @Test fun dirtySettingsExitProtectsAllNavigationPaths() {
        show()
        openListening()
        compose.runOnIdle {
            state = state.copy(settings = state.settings.copy(online = saved.copy(model = "未保存在线模型", hasUnsavedChanges = true)))
        }
        val draft = state.settings.online
        val exits = listOf<() -> Unit>(
            { compose.onNodeWithContentDescription("返回设置").performClick() },
            { compose.runOnIdle { backDispatcher.onBackPressed() } },
            { compose.onNode(isSelectable() and hasText("字幕")).performClick() },
        )
        exits.forEach { exit ->
            exit()
            compose.onNodeWithText("配置尚未保存").assertIsDisplayed()
            compose.onNodeWithText("继续编辑").performClick()
            compose.onNodeWithContentDescription("返回设置").assertIsDisplayed()
            compose.runOnIdle { assertEquals(draft, state.settings.online) }
        }
        exits.last()()
        val confirm = compose.onNodeWithText("放弃修改").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        compose.runOnIdle { confirm(); confirm() }
        compose.runOnIdle {
            assertEquals(saved, state.settings.online)
            assertEquals(UiDestination.SUBTITLE, state.destination)
            assertEquals(1, events.count { it == "放弃" })
            assertEquals(1, events.count { it == "导航:SUBTITLE" })
            assertTrue(events.indexOf("放弃") < events.indexOf("导航:SUBTITLE"))
        }
    }

    /** 在线运行时保留停止操作，同时锁定首页语言、模式和设置表单。 */
    @Test fun runningCloudSessionLocksLanguagesModeAndConnection() {
        show(running = true)
        revealLivePrimaryAction()
        compose.onNodeWithText("停止同传").assertIsDisplayed()
        compose.onAllNodes(hasScrollToIndexAction()).onFirst()
            .performScrollToNode(hasTestTag("listening-service-picker"))
        compose.onNodeWithText("① 听写服务").performScrollTo().assertIsNotEnabled()
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText("原声语言"))
        compose.onNodeWithText("原声语言").performScrollTo().performClick()
        compose.onNodeWithText("英语").assertDoesNotExist()
        compose.onNodeWithText("字幕语言").performScrollTo().performClick()
        compose.onNodeWithText("英语字幕").assertDoesNotExist()
        openListening()
        compose.onNodeWithText("① 听写服务").assertIsNotEnabled()
        scrollFormTo(hasTestTag("online-endpoint"))
        compose.onNodeWithTag("online-endpoint").assertIsNotEnabled()
        scrollFormTo(hasTestTag("online-model"))
        compose.onNodeWithText("千问 3.5 · 实时听译").assertIsNotEnabled()
        scrollFormTo(hasText("保存在线配置"))
        compose.onNodeWithText("保存在线配置").assertIsNotEnabled()
        compose.onNodeWithText("测试在线连接").assertIsNotEnabled()
        compose.runOnIdle { assertFalse(events.contains("模式:LOCAL") || events.contains("测试") || events.contains("开始")) }
    }

    /** 未滚到表单时返回和滚出表单后导航都只取消测试，滚动本身不能取消或修改配置。 */
    @Test fun leavingOnlineFormCancelsOnlyProbe() {
        show()
        openListening()
        compose.runOnIdle { state = state.copy(settings = state.settings.copy(online = saved.copy(testing = true))) }
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.runOnIdle {
            assertEquals(saved, state.settings.online)
            assertEquals(1, events.count { it == "取消测试" })
            assertFalse(events.contains("放弃") || events.contains("保存"))
        }
        openListening()
        compose.runOnIdle { state = state.copy(settings = state.settings.copy(online = saved.copy(testing = true))) }
        scrollFormTo(hasTestTag("online-model"))
        // 标签属于无交互容器；锁定语义位于容器内的真实模型选择控件。
        compose.onNode(hasText("千问 3.5 · 实时听译") and hasAnyAncestor(hasTestTag("online-model")))
            .performScrollTo().assertIsDisplayed().assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText("千问 3 · 省钱分段").assertDoesNotExist()
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToIndex(0)
        compose.runOnIdle {
            assertEquals("滚动离开表单不能取消测试或修改配置", saved.copy(testing = true), state.settings.online)
            assertEquals(1, events.count { it == "取消测试" })
        }
        compose.onNode(isSelectable() and hasText("字幕")).performClick()
        compose.runOnIdle {
            assertEquals(saved, state.settings.online)
            assertEquals(UiDestination.SUBTITLE, state.destination)
            assertEquals(2, events.count { it == "取消测试" })
            assertFalse(events.contains("放弃") || events.contains("保存"))
        }
    }

    /** 模式选择立即把三步进度切到在线分支，跳过后不会进入文字接口或本地模型页。 */
    @Test fun onboardingSelectsCloudBranchWithDynamicThreeStepProgress() {
        show(onboarding = OnboardingUiState(step = OnboardingStep.MODE))
        compose.onNodeWithText("第 2/4 步").assertIsDisplayed()
        selectListeningService("千问测试实时直译")
        compose.onNodeWithText("第 2/3 步").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(ListeningMode.ONLINE, state.onboarding?.listeningMode)
            assertEquals(ListeningMode.ONLINE, state.settings.listeningMode)
        }
        compose.onNodeWithText("下一步").performClick()
        compose.onNodeWithText("第 3/3 步").assertIsDisplayed()
        compose.onNodeWithText("进入羽译").assertIsNotEnabled()
        compose.onNodeWithText("跳过这步").performClick()
        compose.runOnIdle {
            assertNull(state.onboarding)
            assertTrue(events.contains("继续:MODE") && events.contains("跳过:ONLINE"))
            assertFalse(events.any { it.endsWith(":API") || it.endsWith(":MODEL") })
        }
    }

    /** 引导在线页沿用相同的确认，放弃草稿后才能跳过并完成。 */
    @Test fun onboardingDirtySkipRetainsOrDiscardsDraft() {
        show(dirty = true, onboarding = OnboardingUiState(step = OnboardingStep.ONLINE, listeningMode = ListeningMode.ONLINE))
        val draft = state.settings.online
        scrollFormTo(hasTestTag("online-endpoint"))
        compose.onNodeWithTag("online-endpoint").performScrollTo().assertIsDisplayed()
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToIndex(0)
        compose.onNodeWithText("跳过这步").performClick()
        compose.onNodeWithText("继续编辑").performClick()
        compose.runOnIdle {
            assertEquals(draft, state.settings.online)
            assertTrue("滚动和继续编辑不能产生业务动作，实际动作：$events", events.isEmpty())
        }
        compose.onNodeWithText("跳过这步").performClick()
        compose.onNodeWithText("放弃修改").performClick()
        compose.runOnIdle {
            assertEquals(saved, state.settings.online)
            assertNull(state.onboarding)
            assertEquals(listOf("放弃", "跳过:ONLINE"), events.filter { it != "取消测试" })
            assertEquals(1, events.count { it == "取消测试" })
        }
    }

    /** 从真实主导航进入设置，避免测试直接依赖根界面的私有路由状态。 */
    private fun openListening() {
        compose.onNode(isSelectable() and hasText("设置")).performClick()
        compose.onNodeWithText("听写").performClick()
    }

    /** 首页主操作可能位于语言卡片下方，先真实滚动再验证可见性与点击行为。 */
    private fun revealLivePrimaryAction() {
        compose.onAllNodes(hasScrollToIndexAction()).onLast()
            .performScrollToNode(hasTestTag("live-primary-action"))
        compose.onNodeWithTag("live-primary-action").performScrollTo().assertIsDisplayed()
    }

    /** 选取表单所在滚动列，兼容单列手机和双列平板。 */
    private fun scrollFormTo(matcher: SemanticsMatcher) {
        hideKeyboardForSyntheticViewport()
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(matcher)
    }

    /** 测试缩放窗口不会缩放系统键盘，编辑后先收起真实键盘，避免它将合成的小窗口完全挤空。 */
    private fun hideKeyboardForSyntheticViewport() {
        compose.runOnIdle {
            testView?.let { view ->
                androidx.core.view.ViewCompat.getWindowInsetsController(view)?.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
        }
        compose.waitUntil(5_000) {
            testView?.rootWindowInsets?.let { insets ->
                !androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(insets).isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
            } ?: true
        }
    }

    /** 操作真实表单按钮，不以调用回调代替界面交互。 */
    private fun clickForm(label: String) {
        scrollFormTo(hasText(label))
        compose.onNodeWithText(label).performScrollTo().performClick()
    }

    /** 使用正式服务下拉选择型号，保留旧测试对草稿确认及引导分支的覆盖。 */
    private fun selectListeningService(label: String) {
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasTestTag("listening-service-picker"))
        compose.onNode(hasAnyAncestor(hasTestTag("listening-service-picker")) and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasText(label) and hasAnyAncestor(isPopup())).performScrollTo().performClick()
    }

    /** 所有业务动作只修改内存夹具，设备用例不连接在线服务或触发播放捕获。 */
    private fun show(dirty: Boolean = false, running: Boolean = false, onboarding: OnboardingUiState? = null) {
        val mode = onboarding?.listeningMode ?: ListeningMode.ONLINE
        val listeningServices = listOf(UiOption("local:test-model", "本地测试模型"),
            UiOption("direct:${LiveTranslateModels.REALTIME_ID}", "千问测试实时直译"))
        val listeningServiceId = if (mode == ListeningMode.LOCAL) "local:test-model" else "direct:${LiveTranslateModels.REALTIME_ID}"
        state = SimulSubUiState(onboarding = onboarding,
            live = LiveUiState(listeningMode = mode, running = running, cloudReady = false,
                listeningServiceId = listeningServiceId, listeningServices = listeningServices,
                modelLabel = "未启用模型", translationLabel = "未配置", statusTitle = "在线听译",
                statusDetail = "点击开始后发送播放音频。", sourceId = "ja", targetId = "zh",
                sourceOptions = listOf(UiOption("ja", "日语"), UiOption("en", "英语")),
                targetOptions = listOf(UiOption("zh", "中文"), UiOption("en", "英语字幕")),
                permissions = listOf(PermissionUiState("audio", "录制播放音频", "测试权限", true),
                    PermissionUiState("overlay", "显示悬浮字幕", "测试权限", true))),
            settings = SettingsUiState(listeningMode = mode, online = if (dirty)
                saved.copy(model = "未保存在线模型", hasUnsavedChanges = true) else saved,
                listeningServiceId = listeningServiceId, listeningServices = listeningServices,
                platformKeys = listOf(PlatformKeyUiState("qwen", "阿里百炼 · 千问", "完整测试密钥", true))))
        val actions = SimulSubActions(
            onNavigate = { state = state.copy(destination = it); events += "导航:${it.name}" },
            onStart = { events += "开始" },
            onSelectListeningService = { id ->
                val selected = if (id.startsWith("local:")) ListeningMode.LOCAL else ListeningMode.ONLINE
                assertFalse("切模式前应先确认草稿", state.settings.online.hasUnsavedChanges)
                events += "模式:${selected.name}"
                state = state.copy(live = state.live.copy(listeningMode = selected, listeningServiceId = id),
                    settings = state.settings.copy(listeningMode = selected, listeningServiceId = id), onboarding = state.onboarding?.copy(listeningMode = selected))
            },
            onOnlineDraftChange = { events += "草稿"; state = state.copy(settings = state.settings.copy(online = it.copy(hasUnsavedChanges = true))) },
            onPlatformKeyChange = { id, key ->
                state = state.copy(settings = state.settings.copy(platformKeys = state.settings.platformKeys.map {
                    if (it.id == id) it.copy(key = key, hasUnsavedChanges = true) else it
                }))
            },
            onSavePlatformKey = { id ->
                events += "保存平台密钥"
                val key = state.settings.platformKeys.single { it.id == id }.key
                state = state.copy(settings = state.settings.copy(
                    platformKeys = state.settings.platformKeys.map {
                        if (it.id == id) it.copy(hasSavedKey = true, hasUnsavedChanges = false) else it
                    }, online = state.settings.online.copy(key = key), cloudAsr = state.settings.cloudAsr.copy(key = key)))
            },
            onSaveOnline = {
                events += "保存"
                state = state.copy(settings = state.settings.copy(online = state.settings.online.copy(hasUnsavedChanges = false, hasSavedConfiguration = true)),
                    onboarding = state.onboarding?.copy(onlineReady = true))
            },
            onTestOnline = { events += "测试"; state = state.copy(settings = state.settings.copy(online = state.settings.online.copy(message = UiMessage("连接验证成功，未发送音频")))) },
            onCancelOnlineTest = { events += "取消测试"; state = state.copy(settings = state.settings.copy(online = state.settings.online.copy(testing = false))) },
            onDiscardOnlineDraft = { events += "放弃"; state = state.copy(settings = state.settings.copy(online = saved)) },
            onOpenOnlineHelp = { events += "帮助" },
            onOnboardingNext = { advance("继续") }, onOnboardingSkip = { advance("跳过") },
        )
        compose.setContent {
            testView = androidx.compose.ui.platform.LocalView.current
            backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CompositionLocalProvider(LocalDensity provides Density(if (tablet) 0.6f else 1f, fontScale)) {
                Box(Modifier.requiredSize(if (tablet) 1040.dp else 360.dp, 720.dp)) { SimulSubApp(state, actions) }
            }
        }
    }

    /** 夹具按真实分支推进，最后一步结束引导，不依赖枚举序号。 */
    private fun advance(label: String) {
        val current = state.onboarding!!
        assertFalse("离开在线页前必须先恢复或保存草稿", state.settings.online.hasUnsavedChanges)
        events += "$label:${current.step.name}"
        val steps = onboardingSteps(current.listeningMode)
        state = state.copy(onboarding = if (current.step == steps.last()) null
            else current.copy(step = steps[steps.indexOf(current.step) + 1]))
    }
}
