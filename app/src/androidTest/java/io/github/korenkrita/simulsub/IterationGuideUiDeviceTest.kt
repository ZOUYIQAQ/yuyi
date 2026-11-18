package io.github.korenkrita.simulsub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** 本轮引导回归仅渲染内存假数据，不访问偏好、网络、系统授权或真实模型文件。 */
class IterationGuideUiDeviceTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(SimulSubUiState())
    private val events = mutableListOf<String>()
    private val realtimeId = ListeningServiceRepository.BUILT_INS.first { it.mode == ListeningMode.ONLINE }.id

    /** 通知未授权仍可继续；通知按钮只派发授权标识，不触发真实系统授权。 */
    @Test fun optionalNotificationDoesNotBlockNextAndDispatchesPermission() {
        show(OnboardingStep.PERMISSIONS)
        compose.onNodeWithText("下一步").assertIsEnabled()
        reveal(hasText("通知（可选）"))
        compose.onNodeWithText("通知（可选）").assertIsDisplayed()
        compose.onNodeWithText("允许").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("授权:notification"), events)
            assertFalse(state.live.permissions.first { it.id == "notification" }.granted)
        }
        compose.onNodeWithText("下一步").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("下一步", events.last()) }
    }

    /** 完整八项目录只能筛出两个基础本地模型和三项官方云服务，且云服务可从列表选择。 */
    @Test fun modeFiltersEightProfilesToFiveCatalogChoicesAndSelectsCloudService() {
        val profiles = profiles()
        val expected = listOf("local:reazonspeech", "local:sensevoice") +
            ListeningServiceRepository.BUILT_INS.map { it.id }
        assertEquals(8, profiles.size)
        assertEquals(5, expected.size)
        assertEquals(expected, onboardingListeningChoices(profiles).map { it.id })
        show(OnboardingStep.MODE)
        compose.onNodeWithText("选择听写服务").assertIsDisplayed()
        expected.forEach { id ->
            reveal(hasTestTag("onboarding-service-$id"))
            compose.onNodeWithTag("onboarding-service-$id").assertIsDisplayed()
        }
        profiles.filterNot { it.id in expected }.forEach { profile ->
            compose.onNodeWithTag("onboarding-service-${profile.id}").assertDoesNotExist()
            compose.onNodeWithText(profile.name).assertDoesNotExist()
        }
        chooseRealtime()
        compose.onNodeWithText("配置千问语音直译").assertIsDisplayed()
        compose.onNodeWithText("选择听写服务").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("选择:profile:$realtimeId", events.single())
            assertEquals(OnboardingStep.ONLINE, state.onboarding?.step)
            assertEquals(ListeningMode.ONLINE, state.onboarding?.listeningMode)
        }
    }

    /** 模型准备页只显示已选模型，下载和启用均回传同一标识，不再出现第二次选择入口。 */
    @Test fun modelStepOnlyPreparesPreviouslySelectedModel() {
        show(OnboardingStep.MODEL)
        compose.onNodeWithText("准备本地模型").assertIsDisplayed()
        compose.onNodeWithText("已选择「测试日英模型」，下载后启用即可。").assertIsDisplayed()
        compose.onAllNodesWithText("测试日英模型").assertCountEquals(1)
        compose.onNodeWithText("测试五语模型").assertDoesNotExist()
        compose.onNodeWithText("测试扩展模型").assertDoesNotExist()
        listOf("选择本地模型", "选择识别模型", "选择并继续").forEach { text ->
            compose.onNodeWithText(text).assertDoesNotExist()
        }
        reveal(hasText("下载模型"))
        compose.onNodeWithText("下载模型").performClick()
        compose.runOnIdle {
            assertEquals(listOf("下载:reazonspeech"), events)
            state = state.copy(models = state.models.copy(items = state.models.items.map {
                if (it.id == "reazonspeech") it.copy(status = ModelUiStatus.READY,
                    canDownload = false, canActivate = true) else it
            }))
        }
        reveal(hasText("启用模型"))
        compose.onNodeWithText("启用模型").performClick()
        compose.runOnIdle { assertEquals(listOf("下载:reazonspeech", "启用:reazonspeech"), events) }
    }

    /** 手动时钟捕获新旧步骤共存的中间帧，往返两轮验证快照和保存状态标识不会冲突。 */
    @Test fun modeToOnlineRetainsExitPageDuringTransitionAndSettlesToSinglePage() {
        show(OnboardingStep.MODE)
        repeat(2) {
            // 先在自动时钟下滚动定位，避免关闭时钟后等待列表滚动动画。
            reveal(hasTestTag("onboarding-service-$realtimeId"))
            compose.mainClock.autoAdvance = false
            chooseRealtime(revealFirst = false)
            advanceToMiddleFrame()
            compose.onNodeWithText("选择听写服务").assertExists()
            compose.onNodeWithText("配置千问语音直译").assertExists()
            compose.onAllNodesWithTag("onboarding-footer").assertCountEquals(2)
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
            compose.onNodeWithText("选择听写服务").assertDoesNotExist()
            compose.onNodeWithText("配置千问语音直译").assertIsDisplayed()
            compose.onAllNodesWithTag("onboarding-footer").assertCountEquals(1)
            compose.onNodeWithText("上一步").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
            compose.onNodeWithText("选择听写服务").assertIsDisplayed()
            compose.onNodeWithText("配置千问语音直译").assertDoesNotExist()
            compose.onAllNodesWithTag("onboarding-footer").assertCountEquals(1)
            compose.mainClock.autoAdvance = true
        }
        compose.runOnIdle { assertEquals(2, events.count { it == "选择:profile:$realtimeId" }) }
    }

    /** 最后一步完成后引导和首页先共存再收敛，未捕获的重复保存标识异常会直接使测试失败。 */
    @Test fun finishingGuideAnimatesIntoHomeWithoutDuplicateSaveableKeys() {
        show(OnboardingStep.MODEL, modelReady = true)
        compose.onNodeWithText("进入羽译").assertIsEnabled()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("进入羽译").performClick()
        advanceToMiddleFrame()
        compose.onNodeWithText("准备本地模型").assertExists()
        compose.onNodeWithTag("onboarding-footer").assertExists()
        compose.onNodeWithTag("live-control-viewport").assertExists()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithText("准备本地模型").assertDoesNotExist()
        compose.onNodeWithTag("onboarding-footer").assertDoesNotExist()
        compose.onAllNodesWithTag("live-control-viewport").assertCountEquals(1)
        compose.onNodeWithTag("live-control-viewport").assertIsDisplayed()
        compose.runOnIdle { assertNull(state.onboarding) }
        compose.mainClock.autoAdvance = true
    }

    /** 向真实根界面注入固定尺寸与纯内存回调，所有外部动作只记录事件。 */
    private fun show(step: OnboardingStep, modelReady: Boolean = false) {
        state = SimulSubUiState(
            onboarding = OnboardingUiState(step = step, modelReady = modelReady),
            live = LiveUiState(listeningServiceId = "local:reazonspeech", permissions = listOf(
                PermissionUiState("audio", "录制播放音频", "测试录音说明", true),
                PermissionUiState("overlay", "显示悬浮字幕", "测试悬浮说明", true),
                PermissionUiState("notification", "通知", "测试通知说明", false, required = false),
            )),
            settings = SettingsUiState(listeningProfiles = profiles(), theme = UiThemeMode.LIGHT),
            models = ModelsUiState(items = listOf(
                model("reazonspeech", "测试日英模型"), model("sensevoice", "测试五语模型"),
                model("whisper", "测试扩展模型"),
            )),
        )
        val actions = SimulSubActions(
            onPermission = { events += "授权:$it" },
            onDownloadModel = { events += "下载:$it" },
            onActivateModel = { events += "启用:$it" },
            onChooseOnboardingListeningService = { id ->
                events += "选择:$id"
                state = state.copy(
                    onboarding = OnboardingUiState(step = OnboardingStep.ONLINE, listeningMode = ListeningMode.ONLINE),
                    live = state.live.copy(listeningMode = ListeningMode.ONLINE, listeningServiceId = id),
                    settings = state.settings.copy(listeningMode = ListeningMode.ONLINE, listeningServiceId = id),
                )
            },
            onOnboardingBack = {
                state = state.copy(onboarding = OnboardingUiState(step = OnboardingStep.MODE),
                    live = state.live.copy(listeningMode = ListeningMode.LOCAL, listeningServiceId = "local:reazonspeech"),
                    settings = state.settings.copy(listeningMode = ListeningMode.LOCAL, listeningServiceId = "local:reazonspeech"))
            },
            onOnboardingNext = {
                events += "下一步"
                if (state.onboarding?.step == OnboardingStep.MODEL) state = state.copy(onboarding = null)
            },
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(360.dp, 640.dp)) { SimulSubApp(state, actions) }
            }
        }
        compose.waitForIdle()
    }

    /** 八项假目录打乱顺序，包含额外本地模型和自定义云服务，防止仅按类型或名称过滤。 */
    private fun profiles(): List<ServiceProfileUiState> = listOf(
        ServiceProfileUiState("local:whisper", "测试扩展本地模型", kind = "local"),
        ServiceProfileUiState("custom-cloud", "测试自定义云服务", kind = "realtime", builtIn = false),
        ServiceProfileUiState("local:moonshine", "测试额外本地模型", kind = "local"),
        ServiceProfileUiState("local:sensevoice", "测试五语服务", kind = "local", configured = false),
        ServiceProfileUiState("local:reazonspeech", "测试日英服务", kind = "local", configured = false),
    ) + ListeningServiceRepository.BUILT_INS.reversed().map {
        ServiceProfileUiState(it.id, it.name, kind = if (it.mode == ListeningMode.CLOUD_ASR) "asr" else "realtime",
            configured = false)
    }

    /** 模型元数据均为假数据，下载能力只用于验证回调。 */
    private fun model(id: String, name: String) = ModelUiState(id = id, name = name,
        description = "测试说明", languages = "日语、英语", sizeLabel = "测试大小", license = "测试许可",
        sourceLabel = "内存目录", canDownload = true)

    /** 懒列表按目标语义定位，不依赖尚未组合的离屏节点。 */
    private fun reveal(matcher: SemanticsMatcher) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
    }

    /** 从指定云服务卡片内点击，避免多个同名选择按钮造成歧义。 */
    private fun chooseRealtime(revealFirst: Boolean = true) {
        val tag = "onboarding-service-$realtimeId"
        if (revealFirst) reveal(hasTestTag(tag))
        compose.onNode(hasText("选择并继续") and hasAnyAncestor(hasTestTag(tag))).performClick()
    }

    /** 先提交新状态并启动转场，再推进至退出淡出结束前的中间帧。 */
    private fun advanceToMiddleFrame() {
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
    }
}
