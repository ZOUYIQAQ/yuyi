package io.github.korenkrita.simulsub

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 三页引导仅用内存夹具验证，不访问真实密钥、网络、下载器、偏好或通知。 */
@RunWith(Parameterized::class)
class SimpleOnboardingUiDeviceTest(private val tablet: Boolean, private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(SimulSubUiState())
    private val events = mutableListOf<String>()
    private var closeKeyboard: () -> Unit = {}
    private val rawKey = "  假Key原文._+/=:  "
    private val renderDensity: Float get() = if (tablet) 0.6f else 1f

    companion object {
        /** 同组交互覆盖窄屏、平板及两倍字号，不启动设备配置或改变系统字号。 */
        @JvmStatic @Parameterized.Parameters(name = "平板={0}，字号={1}")
        fun cases(): List<Array<Any>> = listOf(false, true).flatMap { tablet ->
            listOf(1f, 2f).map { arrayOf<Any>(tablet, it) }
        }
    }

    /** 三种历史模式均为三页，本地路径卡片须显式确认才能下载并进入配置。 */
    @Test fun modeHasTwoCardsAndNoFooterNextBeforeExplicitLocalChoice() {
        assertEquals(listOf(OnboardingStep.PERMISSIONS, OnboardingStep.MODE, OnboardingStep.API), onboardingSteps(ListeningMode.LOCAL))
        assertEquals(onboardingSteps(ListeningMode.LOCAL), onboardingSteps(ListeningMode.CLOUD_ASR))
        assertEquals(listOf(OnboardingStep.PERMISSIONS, OnboardingStep.MODE, OnboardingStep.ONLINE), onboardingSteps(ListeningMode.ONLINE))
        show(OnboardingStep.MODE)
        reveal(hasTestTag("onboarding-choose-local"))
        compose.onNodeWithText("选择本地").assertIsDisplayed()
        reveal(hasTestTag("onboarding-choose-online"))
        compose.onNodeWithText("选择在线").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-next").assertDoesNotExist()
        compose.onNodeWithText("上一步").assertIsDisplayed()
        compose.onNodeWithText("跳过这步").assertIsDisplayed()
        compose.runOnIdle { assertTrue(events.isEmpty()) }
        clickTag("onboarding-choose-local")
        compose.onNodeWithText("第 3/3 步").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf("路径:LOCAL"), events)
            assertEquals(OnboardingStep.API, state.onboarding!!.step)
            assertEquals(ModelUiStatus.NOT_INSTALLED, state.models.items.single().status)
        }
    }

    /** 已装模型显示使用确认；在线选择固定直译路线，不触发模型下载动作。 */
    @Test fun installedModelUsesContinueAndOnlineChoiceNeedsNoDownload() {
        show(OnboardingStep.MODE, modelReady = true)
        reveal(hasText("选择本地"))
        compose.onNodeWithText("选择本地").assertIsDisplayed()
        clickTag("onboarding-choose-online")
        compose.onNodeWithText("第 3/3 步").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-provider").assertDoesNotExist()
        compose.onNodeWithTag("onboarding-reazon-progress").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("路径:ONLINE"), events)
            assertEquals(ListeningMode.ONLINE, state.onboarding!!.listeningMode)
            assertEquals(OnboardingStep.ONLINE, state.onboarding!!.step)
            assertEquals(ModelUiStatus.ACTIVE, state.models.items.single().status)
        }
    }

    /** 权限有必要和可选两标题；必要项授予后，即使通知未授权也能继续。 */
    @Test fun permissionsAreGroupedAndOptionalNotificationDoesNotGateNext() {
        show(OnboardingStep.PERMISSIONS)
        reveal(hasTestTag("onboarding-required-permissions"))
        compose.onNodeWithTag("onboarding-required-permissions").assertTextEquals("必要权限")
        reveal(hasTestTag("onboarding-optional-permissions"))
        compose.onNodeWithTag("onboarding-optional-permissions").assertTextEquals("可选权限")
        compose.onNodeWithTag("onboarding-next").assertIsNotEnabled()
        val allowAudio = hasText("允许") and hasAnyAncestor(hasTestTag("onboarding-permission-audio"))
        reveal(allowAudio)
        compose.onNode(allowAudio).performScrollTo().performClick()
        compose.onNodeWithTag("onboarding-next").assertIsEnabled().performClick()
        compose.onNodeWithText("第 2/3 步").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(state.live.permissions.single { it.id == "notifications" }.granted)
            assertEquals(listOf("权限:audio", "继续:PERMISSIONS"), events)
        }
    }

    /** 在线配置只有一个密码框，原文即时回填、可显隐且不测试也可完成。 */
    @Test fun onlineHasOneKeyFieldAndCanFinishWithoutTesting() {
        show(OnboardingStep.ONLINE, mode = ListeningMode.ONLINE, onlineReady = true)
        reveal(hasTestTag("onboarding-key"))
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithTag("onboarding-provider").assertDoesNotExist()
        compose.onNodeWithTag("onboarding-model").assertDoesNotExist()
        assertNoAdvancedRoutes()
        compose.onNodeWithTag("onboarding-key").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .performTextReplacement(rawKey)
        compose.runOnIdle { closeKeyboard(); assertEquals(rawKey, state.onboarding!!.setup.key) }
        reveal(hasContentDescription("显示密钥"))
        compose.onNodeWithContentDescription("显示密钥").performClick()
        compose.onNodeWithTag("onboarding-key").assertTextContains(rawKey)
        compose.onNodeWithContentDescription("隐藏密钥").performClick()
        compose.onNodeWithTag("onboarding-next").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertNull(state.onboarding)
            assertEquals(listOf("密钥:$rawKey", "继续:ONLINE"), events)
        }
    }

    /** 测试按钮可选，测试进行中和失败消息均不额外改变在线完成门槛。 */
    @Test fun onlineProbeAndFailureMessageDoNotGateReadiness() {
        show(OnboardingStep.ONLINE, mode = ListeningMode.ONLINE, onlineReady = true)
        clickTag("onboarding-test")
        compose.onNodeWithTag("onboarding-next").assertIsEnabled()
        compose.runOnIdle {
            assertTrue(state.onboarding!!.setup.testing)
            updateSetup { it.copy(testing = false, message = UiMessage("假测试失败，可稍后重试", isError = true)) }
        }
        reveal(hasText("假测试失败，可稍后重试"))
        compose.onNodeWithText("假测试失败，可稍后重试").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-next").assertIsEnabled()
        compose.onNodeWithText("跳过这步").performClick()
        compose.runOnIdle { assertEquals(listOf("测试", "跳过:ONLINE"), events); assertNull(state.onboarding) }
    }

    /** 本地模型在配置上方且说明用途，用户明确点击下载，模型准备好前无法完成。 */
    @Test fun localRecognitionCardPrecedesTranslationAndDownloadsOnlyOnClick() {
        show(OnboardingStep.API, notInstalledModel = true)
        compose.onNodeWithTag("onboarding-reazon-progress").assertIsDisplayed()
        reveal(hasText("本地识别模型"))
        compose.onNodeWithText("本地识别模型").assertIsDisplayed()
        compose.runOnIdle { assertTrue(events.isEmpty()) }
        compose.onNodeWithTag("onboarding-next").assertIsNotEnabled()
        clickTag("onboarding-retry-model")
        compose.runOnIdle {
            assertEquals(listOf("下载:reazonspeech"), events)
            assertEquals(ModelUiStatus.DOWNLOADING, state.models.items.single().status)
        }
        reveal(hasText("已下载 25%"))
        compose.onNodeWithText("已下载 25%").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-next").assertIsNotEnabled()
    }

    /** 本地翻译配置提供可选测试，填密钥后可点击，测试中禁止重复触发。 */
    @Test fun localTranslationOffersOptionalConnectionAndModelDiscovery() {
        show(OnboardingStep.API)
        reveal(hasTestTag("onboarding-test"))
        compose.onNodeWithTag("onboarding-test").assertIsDisplayed().assertIsNotEnabled()
        reveal(hasTestTag("onboarding-key"))
        compose.onNodeWithTag("onboarding-key").performTextReplacement(rawKey)
        compose.runOnIdle { closeKeyboard() }
        clickTag("onboarding-test")
        compose.onNodeWithTag("onboarding-test").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf("密钥:$rawKey", "测试"), events)
            updateSetup { it.copy(testing = false, model = "account-a",
                models = listOf(UiOption("account-a", "账户模型A"), UiOption("account-b", "账户模型B")),
                message = UiMessage("连接测试通过，已自动保存")) }
        }
        reveal(hasTestTag("onboarding-model"))
        compose.onNode(hasText("账户模型A") and hasAnyAncestor(hasTestTag("onboarding-model"))).assertIsDisplayed()
        pick("onboarding-model", "账户模型B")
        compose.runOnIdle { assertEquals("account-b", state.onboarding!!.setup.model) }
    }

    /** 本地配置只含两个选择器和一个密钥框，下载时可输入，完成必须同时就绪。 */
    @Test fun localHasThreeInputsAndAllowsKeyEntryWhileDownloading() {
        show(OnboardingStep.API)
        reveal(hasTestTag("onboarding-provider"))
        compose.onNode(hasText("服务A") and hasAnyAncestor(hasTestTag("onboarding-provider"))).assertIsDisplayed()
        reveal(hasTestTag("onboarding-model"))
        compose.onNode(hasText("模型A") and hasAnyAncestor(hasTestTag("onboarding-model"))).assertIsDisplayed()
        pick("onboarding-provider", "服务B")
        pick("onboarding-model", "模型B")
        reveal(hasTestTag("onboarding-key"))
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithTag("onboarding-key").assertIsEnabled().performTextReplacement(rawKey)
        compose.runOnIdle { closeKeyboard() }
        reveal(hasText("已下载 25%"))
        compose.onNodeWithText("已下载 25%").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-next").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(rawKey, state.onboarding!!.setup.key)
            assertEquals(listOf("供应商:qwen", "模型:qwen-plus", "密钥:$rawKey"), events)
            state = state.copy(onboarding = state.onboarding!!.copy(apiReady = true))
        }
        compose.onNodeWithTag("onboarding-next").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(onboarding = state.onboarding!!.copy(modelReady = true)) }
        compose.onNodeWithTag("onboarding-next").assertIsEnabled()
        assertNoAdvancedRoutes()
    }

    /** 旧模型页降级到本地简化配置，错误原文可见且重试只针对固定识别模型。 */
    @Test fun legacyModelStepUsesLocalSetupWithRetryAndSkip() {
        show(OnboardingStep.MODEL, mode = ListeningMode.CLOUD_ASR, failedModel = true)
        compose.onNodeWithText("第 3/3 步").assertIsDisplayed()
        reveal(hasTestTag("onboarding-provider"))
        compose.onNodeWithTag("onboarding-provider").assertIsDisplayed()
        reveal(hasText("假下载失败，保留原文"))
        compose.onNodeWithText("假下载失败，保留原文").assertIsDisplayed()
        clickTag("onboarding-retry-model")
        reveal(hasText("已下载 25%"))
        compose.onNodeWithText("已下载 25%").assertIsDisplayed()
        compose.onNodeWithText("跳过这步").performClick()
        compose.runOnIdle { assertEquals(listOf("下载:reazonspeech", "跳过:MODEL"), events); assertNull(state.onboarding) }
    }

    /** 下载细条固定在正文外；完成后隐藏，并保留可关闭的应用内完成提示。 */
    @Test fun downloadIndicatorStaysAtTopAndCompletionMessageCanBeDismissed() {
        show(OnboardingStep.API)
        val indicator = compose.onNodeWithTag("model-download-indicator")
        indicator.assertIsDisplayed()
        // Material为读屏扩大语义边界；细条的真实布局高度仍必须严格为3dp。
        assertEquals(with(Density(renderDensity, fontScale)) { 3.dp.roundToPx() }, indicator.fetchSemanticsNode().layoutInfo.height)
        indicator.assertContentDescriptionEquals("ReazonSpeech，已下载 25%，共 1 个任务")
        val before = indicator.getUnclippedBoundsInRoot()
        reveal(hasText("已下载 25%"))
        assertEquals(before, indicator.getUnclippedBoundsInRoot())
        compose.runOnIdle {
            state = state.copy(models = state.models.copy(items = state.models.items.map { it.copy(progress = null) }))
        }
        indicator.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
        compose.runOnIdle {
            state = state.copy(models = state.models.copy(items = state.models.items.map {
                it.copy(status = ModelUiStatus.ACTIVE, progress = null)
            }), message = UiMessage("ReazonSpeech 假下载已完成"))
        }
        indicator.assertDoesNotExist()
        compose.onNodeWithTag("onboarding-global-message").assertIsDisplayed()
        compose.onNodeWithText("ReazonSpeech 假下载已完成").assertIsDisplayed()
        compose.onNode(hasText("关闭提示") and hasAnyAncestor(hasTestTag("onboarding-global-message"))).performClick()
        compose.onNodeWithTag("onboarding-global-message").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("关闭消息"), events) }
    }

    /** 主应用的三处导航共用同一细条，下载完成后移除细条并显示通知。 */
    @Test fun downloadProgressSurvivesMainNavigationAndAnnouncesCompletion() {
        state = SimulSubUiState(models = ModelsUiState(items = listOf(ModelUiState(
            "reazonspeech", "ReazonSpeech", "假模型", "日英", "假大小", "假许可", "假来源",
            status = ModelUiStatus.DOWNLOADING, progress = 0.25f))))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(renderDensity, fontScale)) {
                Box(Modifier.requiredSize(if (tablet) 1040.dp else 320.dp, 640.dp)
                    .consumeWindowInsets(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                    .padding(top = 24.dp, bottom = 24.dp)) {
                    SimulSubApp(state, SimulSubActions(onNavigate = { destination ->
                        state = state.copy(destination = destination)
                    }))
                }
            }
        }
        val indicator = compose.onNodeWithTag("model-download-indicator")
        indicator.assertIsDisplayed()
        val top = indicator.fetchSemanticsNode().positionInRoot.y
        for (destination in listOf(UiDestination.SUBTITLE, UiDestination.SETTINGS, UiDestination.LIVE)) {
            compose.onNode(hasText(destination.title) and hasClickAction()).performClick()
            compose.waitForIdle()
            indicator.assertIsDisplayed()
            assertEquals(top, indicator.fetchSemanticsNode().positionInRoot.y, 0.01f)
            compose.runOnIdle { assertEquals(destination, state.destination) }
        }
        compose.runOnIdle {
            state = state.copy(models = state.models.copy(items = state.models.items.map { it.copy(status = ModelUiStatus.READY) }),
                message = UiMessage("ReazonSpeech 假下载已完成"))
        }
        indicator.assertDoesNotExist()
        compose.onNodeWithText("ReazonSpeech 假下载已完成").assertIsDisplayed()
    }

    /** 渲染真实引导，只在内存模拟新契约动作和模型进度，不调用业务控制器。 */
    private fun show(step: OnboardingStep, mode: ListeningMode = ListeningMode.LOCAL,
        modelReady: Boolean = false, onlineReady: Boolean = false, failedModel: Boolean = false, notInstalledModel: Boolean = false) {
        state = SimulSubUiState(onboarding = OnboardingUiState(step = step, listeningMode = mode,
            modelReady = modelReady, onlineReady = onlineReady,
            setup = OnboardingSetupUiState(providers = listOf(UiOption("deepseek", "服务A"), UiOption("qwen", "服务B")),
                models = listOf(UiOption("deepseek-flash", "模型A"), UiOption("qwen-plus", "模型B")), keySaved = onlineReady)),
            live = LiveUiState(permissions = listOf(
                PermissionUiState("audio", "录制播放音频", "必要声音权限", false),
                PermissionUiState("overlay", "显示悬浮字幕", "必要显示权限", true),
                PermissionUiState("notifications", "通知", "可选通知权限", false, required = false))),
            models = ModelsUiState(items = listOf(ModelUiState("reazonspeech", "ReazonSpeech", "假模型", "日英", "假大小", "假许可", "假来源",
                status = when { failedModel -> ModelUiStatus.FAILED; modelReady -> ModelUiStatus.ACTIVE;
                    notInstalledModel || step == OnboardingStep.MODE -> ModelUiStatus.NOT_INSTALLED; else -> ModelUiStatus.DOWNLOADING },
                progress = 0.25f, canDownload = failedModel || notInstalledModel || step == OnboardingStep.MODE, error = if (failedModel) "假下载失败，保留原文" else null))))
        val actions = SimulSubActions(
            onChooseOnboardingRoute = { selected ->
                events += "路径:$selected"
                state = state.copy(onboarding = state.onboarding!!.copy(listeningMode = selected,
                    step = if (selected == ListeningMode.ONLINE) OnboardingStep.ONLINE else OnboardingStep.API))
            },
            onOnboardingProviderChange = { id -> events += "供应商:$id"; updateSetup { it.copy(providerId = id) } },
            onOnboardingModelChange = { id -> events += "模型:$id"; updateSetup { it.copy(model = id) } },
            onOnboardingKeyChange = { key -> events += "密钥:$key"; updateSetup { it.copy(key = key, keySaved = key.isNotEmpty()) } },
            onOnboardingTest = { events += "测试"; updateSetup { it.copy(testing = true) } },
            onDownloadModel = { id ->
                events += "下载:$id"
                state = state.copy(models = state.models.copy(items = state.models.items.map {
                    it.copy(status = ModelUiStatus.DOWNLOADING, progress = 0.25f, canDownload = false, error = null)
                }))
            },
            onPermission = { id ->
                events += "权限:$id"
                state = state.copy(live = state.live.copy(permissions = state.live.permissions.map {
                    if (it.id == id) it.copy(granted = true) else it
                }))
            },
            onOnboardingNext = { advance(skip = false) }, onOnboardingSkip = { advance(skip = true) },
            onDismissMessage = { events += "关闭消息"; state = state.copy(message = null) },
            onOnboardingBack = {
                events += "返回"
                state = state.copy(onboarding = state.onboarding!!.copy(step = OnboardingStep.MODE))
            },
        )
        compose.setContent {
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            closeKeyboard = { focus.clearFocus(); keyboard?.hide() }
            CompositionLocalProvider(LocalDensity provides Density(renderDensity, fontScale)) {
                // 模拟尺寸按自己的密度渲染，不将宿主设备的物理缺口错误放大到测试窗口。
                Box(Modifier.requiredSize(if (tablet) 1040.dp else 320.dp, 640.dp)
                    .consumeWindowInsets(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                    .padding(top = 24.dp, bottom = 24.dp).testTag("onboarding-test-surface")) { OnboardingScreen(state, actions) }
            }
        }
        captureGuide(step.name)
    }

    /** 新表单草稿仅回填内存，原始密钥不进入保存状态容器或真实存储。 */
    private fun updateSetup(change: (OnboardingSetupUiState) -> OnboardingSetupUiState) {
        state = state.copy(onboarding = state.onboarding!!.let { it.copy(setup = change(it.setup)) })
    }

    /** 模拟三页前进和跳过，旧模型步骤也按最后一页退出。 */
    private fun advance(skip: Boolean) {
        val guide = state.onboarding!!
        events += "${if (skip) "跳过" else "继续"}:${guide.step}"
        val steps = onboardingSteps(guide.listeningMode)
        val current = steps.indexOf(guide.step).takeIf { it >= 0 } ?: steps.lastIndex
        state = state.copy(onboarding = if (current == steps.lastIndex) null else guide.copy(step = steps[current + 1]))
    }

    /** 原高级连接和服务管理入口不再出现在极简引导中。 */
    private fun assertNoAdvancedRoutes() {
        listOf("online-endpoint", "api-url", "service-selection", "platform-keys", "create-translation-service").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
        compose.onNodeWithText("管理平台密钥").assertDoesNotExist()
    }

    /** 两栏各自检索目标，窄屏同一列表也能定位尚未组合的底部内容。 */
    private fun reveal(matcher: SemanticsMatcher) {
        val lists = compose.onAllNodes(hasScrollToIndexAction())
        val count = lists.fetchSemanticsNodes().size
        var lastError: AssertionError? = null
        repeat(count) { index ->
            try {
                lists[index].performScrollToNode(matcher)
                compose.onNode(matcher).performScrollTo()
                return
            }
            catch (error: AssertionError) { lastError = error }
        }
        throw lastError ?: AssertionError("没有找到引导正文滚动列表")
    }

    /** 只保存内存夹具页面的像素，用于手机、平板与大字号的引导布局检查。 */
    private fun captureGuide(step: String) {
        compose.waitForIdle()
        val directory = java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.getExternalFilesDir(null), "qa-simple-onboarding").apply { mkdirs() }
        val bitmap = compose.onNodeWithTag("onboarding-test-surface").captureToImage().asAndroidBitmap()
        java.io.File(directory, "$step-$tablet-$fontScale.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /** 可操作按钮先滚动到触控范围，再通过真实点击派发动作。 */
    private fun clickTag(tag: String) {
        reveal(hasTestTag(tag))
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
    }

    /** 在弹出菜单内按名称选择，不直接调用供应商或模型回调。 */
    private fun pick(tag: String, label: String) {
        reveal(hasTestTag(tag))
        compose.onNode(hasAnyAncestor(hasTestTag(tag)) and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasText(label) and hasAnyAncestor(isPopup())).performClick()
    }
}
