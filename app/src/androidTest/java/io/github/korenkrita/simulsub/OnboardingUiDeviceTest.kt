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

/** 引导测试仅使用内存状态，覆盖窄屏、平板及两倍字号，不读取用户配置或发起真实下载。 */
@RunWith(Parameterized::class)
class OnboardingUiDeviceTest(private val tablet: Boolean, private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(SimulSubUiState())
    private val events = mutableListOf<String>()
    private lateinit var backDispatcher: OnBackPressedDispatcher
    private val savedApi = TranslationApiUiState(url = "https://example.com/v1", model = "测试模型",
        userAgent = "不应展示的进阶参数", headers = listOf(HeaderUiState("额外请求头", "X-Test", "测试值")))

    companion object {
        /** 同一组行为在手机、平板和百分之二百字号下执行。 */
        @JvmStatic @Parameterized.Parameters(name = "平板={0}，字号={1}")
        fun cases(): List<Array<Any>> = listOf(false, true).flatMap { tablet ->
            listOf(1f, 2f).map { arrayOf<Any>(tablet, it) }
        }
    }

    /** 首步仅列必需权限；授权全部必需项后可继续，非必需项不构成门槛。 */
    @Test fun permissionsOnlyShowRequiredItemsAndGateNext() {
        show(OnboardingStep.PERMISSIONS)
        compose.onNodeWithText("第 1/3 步").assertIsDisplayed()
        compose.onNodeWithText("可选通知权限").assertDoesNotExist()
        compose.onNodeWithText("上一步").assertDoesNotExist()
        compose.onNodeWithText("下一步").assertIsNotEnabled()
        scrollBodyTo("允许")
        compose.onNodeWithText("允许").performScrollTo().performClick()
        compose.onNodeWithText("下一步").assertIsEnabled()
        scrollBodyTo("系统屏幕共享会在每次开始同传时询问，这里无需提前授权。")
        compose.onNodeWithText("系统屏幕共享会在每次开始同传时询问，这里无需提前授权。").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("权限:必需权限"), events) }
        assertNoLaterSettings()
    }

    /** 跳过按权限、接口、模型顺序前进，最后完成；不额外创建任何配置入口。 */
    @Test fun skippingAllThreeStepsFinishesInOrder() {
        show(OnboardingStep.PERMISSIONS)
        compose.onNodeWithText("跳过这步").performClick()
        compose.onNodeWithText("第 2/3 步").assertIsDisplayed()
        compose.onNodeWithText("跳过这步").performClick()
        compose.onNodeWithText("第 3/3 步").assertIsDisplayed()
        compose.onNodeWithText("进入羽译").assertIsNotEnabled()
        compose.onNodeWithText("跳过这步").performClick()
        compose.runOnIdle {
            assertNull(state.onboarding)
            assertEquals(listOf("跳过:PERMISSIONS", "跳过:API", "跳过:MODEL"), events)
        }
        compose.onNodeWithTag("onboarding-footer").assertDoesNotExist()
    }

    /** 接口页保留基础配置、保存和测试，隐藏所有附加参数与后续设置。 */
    @Test fun apiOnlyShowsEssentialConfigurationAndUsesSavedReadiness() {
        show(OnboardingStep.API)
        compose.onNodeWithText("第 2/3 步").assertIsDisplayed()
        compose.onNodeWithText("下一步").assertIsNotEnabled()
        scrollBodyTo("服务预设")
        compose.onNodeWithText("服务预设").assertExists()
        scrollBodyTo("模型 ID")
        compose.onNode(hasSetTextAction() and hasText("模型 ID", substring = true)).assertExists()
        scrollBodyTo("保存翻译配置")
        compose.onNodeWithText("保存翻译配置").assertExists()
        compose.onNodeWithText("测试连接").assertExists()
        compose.onNodeWithText("附加请求参数（可选）").assertDoesNotExist()
        compose.onNodeWithText("收起附加请求参数").assertDoesNotExist()
        compose.onNodeWithText("User-Agent（可选）").assertDoesNotExist()
        compose.onNodeWithText("请求头名称").assertDoesNotExist()
        assertNoLaterSettings()
        compose.runOnIdle { state = state.copy(onboarding = state.onboarding!!.copy(apiReady = true)) }
        compose.onNodeWithText("下一步").assertIsEnabled().performClick()
        compose.onNodeWithText("第 3/3 步").assertIsDisplayed()
    }

    /** 模型只提供实际卡片和必要动作，启用完成后才允许进入应用。 */
    @Test fun modelsStayMinimalAndRequireActivation() {
        show(OnboardingStep.MODEL)
        compose.onNodeWithText("进入羽译").assertIsNotEnabled()
        scrollBodyTo("识别模型甲")
        compose.onNodeWithText("识别模型甲").assertExists()
        scrollBodyTo("下载模型")
        compose.onNodeWithText("下载模型").performScrollTo().performClick()
        scrollBodyTo("已下载 25%")
        compose.onNodeWithText("已下载 25%").assertExists()
        compose.onNodeWithText("取消下载").performScrollTo().performClick()
        scrollBodyTo("识别模型乙")
        compose.onNodeWithText("识别模型乙").assertExists()
        scrollBodyTo("启用模型")
        compose.onNodeWithText("启用模型").performScrollTo().performClick()
        compose.onNodeWithText("进入羽译").assertIsEnabled()
        compose.onNodeWithText("查看模型详情").assertDoesNotExist()
        compose.onNodeWithText("评测来源与条件").assertDoesNotExist()
        compose.onNodeWithText("删除模型").assertDoesNotExist()
        compose.onNodeWithText("不应展示的模型简介").assertDoesNotExist()
        assertNoLaterSettings()
        compose.onNodeWithText("进入羽译").performClick()
        compose.runOnIdle {
            assertNull(state.onboarding)
            assertEquals(listOf("下载:甲", "取消:甲", "启用:乙", "继续:MODEL"), events)
        }
    }

    /** 模型失败或取消时显示完整原因并允许重试，重新下载不能冒充已启用或放开完成门槛。 */
    @Test fun failedAndCancelledModelsExposeErrorsAndRetryWithoutBecomingReady() {
        show(OnboardingStep.MODEL)
        for ((status, error) in listOf(
            ModelUiStatus.FAILED to "下载失败：测试源 HTTP 503，模型尚未安装",
            ModelUiStatus.CANCELLED to "测试下载已取消，暂存文件保留",
        )) {
            compose.runOnIdle {
                events.clear()
                state = state.copy(models = ModelsUiState(items = listOf(
                    model("甲", canDownload = true).copy(status = status, error = error))))
            }
            scrollBodyTo(error)
            compose.onNodeWithText(error).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(status.title).assertExists()
            compose.onNodeWithText("进入羽译").assertIsNotEnabled()
            scrollBodyTo("重试下载")
            compose.onNodeWithText("重试下载").performScrollTo().assertIsDisplayed().performClick()
            scrollBodyTo("已下载 25%")
            compose.onNodeWithText("已下载 25%").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("进入羽译").assertIsNotEnabled()
            compose.runOnIdle {
                assertEquals(listOf("下载:甲"), events)
                assertEquals(ModelUiStatus.DOWNLOADING, state.models.items.single().status)
                assertFalse(state.onboarding!!.modelReady)
            }
        }
    }

    /** 未保存时取消跳过应保留完整草稿，确认放弃后才恢复并进入下一步。 */
    @Test fun dirtySkipCanContinueEditingOrDiscardExactlyOnce() {
        show(OnboardingStep.API, dirty = true)
        val draft = state.settings.api
        compose.onNodeWithText("跳过这步").performClick()
        compose.onNodeWithText("配置尚未保存").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.runOnIdle { assertEquals(draft, state.settings.api); assertTrue(events.isEmpty()) }
        compose.onNodeWithText("第 2/3 步").assertIsDisplayed()
        compose.onNodeWithText("跳过这步").performClick()
        val confirm = compose.onNodeWithText("放弃修改").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        compose.runOnIdle { confirm(); confirm() }
        compose.onNodeWithText("第 3/3 步").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(savedApi, state.settings.api)
            assertEquals(listOf("放弃", "跳过:API"), events)
        }
    }

    /** 上一步离开接口页时也必须先恢复草稿，不能暗中保存。 */
    @Test fun dirtyPreviousRestoresBeforeGoingBack() {
        show(OnboardingStep.API, dirty = true)
        compose.onNodeWithText("上一步").performClick()
        compose.onNodeWithText("配置尚未保存").assertIsDisplayed()
        compose.onNodeWithText("放弃修改").performClick()
        compose.onNodeWithText("第 1/3 步").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("放弃", "返回:API"), events) }
    }

    /** 系统返回先确认，弹窗返回等同继续编辑，再次确认才允许退到权限页。 */
    @Test fun dirtySystemBackAndDialogBackKeepDraftUntilConfirmed() {
        show(OnboardingStep.API, dirty = true)
        val draft = state.settings.api
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.onNodeWithText("配置尚未保存").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithText("配置尚未保存").assertDoesNotExist()
        compose.runOnIdle { assertEquals(draft, state.settings.api); assertTrue(events.isEmpty()) }
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.onNodeWithText("放弃修改").performClick()
        compose.runOnIdle { assertEquals(listOf("放弃", "返回:API"), events) }
    }

    /** 测试不清除草稿标记；真实保存回填完成状态后才可无确认前进。 */
    @Test fun testingDoesNotSaveAndSavingAllowsNext() {
        show(OnboardingStep.API, dirty = true)
        scrollBodyTo("测试连接")
        compose.onNodeWithText("测试连接").performScrollTo().performClick()
        compose.onNodeWithText("下一步").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(state.settings.api.hasUnsavedChanges) }
        scrollBodyTo("保存翻译配置")
        compose.onNodeWithText("保存翻译配置").performScrollTo().performClick()
        compose.onNodeWithText("下一步").assertIsEnabled().performClick()
        compose.onNodeWithText("配置尚未保存").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("测试", "保存", "继续:API"), events) }
    }

    /** 未修改的接口配置允许直接返回和跳过，不额外确认或触发恢复。 */
    @Test fun cleanApiCanGoBackAndSkipWithoutConfirmation() {
        show(OnboardingStep.API)
        compose.onNodeWithText("上一步").performClick()
        compose.onNodeWithText("配置尚未保存").assertDoesNotExist()
        compose.onNodeWithText("第 1/3 步").assertIsDisplayed()
        compose.onNodeWithText("跳过这步").performClick()
        compose.onNodeWithText("跳过这步").performClick()
        compose.onNodeWithText("第 3/3 步").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("返回:API", "跳过:PERMISSIONS", "跳过:API"), events) }
    }

    /** 精简接口表单仍保留测试建议的显式确认，不收到建议就自动替换或保存。 */
    @Test fun suggestedEndpointRequiresExplicitConfirmation() {
        show(OnboardingStep.API, dirty = true)
        compose.runOnIdle {
            state = state.copy(settings = state.settings.copy(api = state.settings.api.copy(
                suggestedUrl = "https://example.com/v1/chat/completions")))
        }
        scrollBodyTo("替换并保存")
        compose.onNodeWithText("保持原地址").assertExists()
        compose.runOnIdle { assertTrue(events.isEmpty()); assertTrue(state.settings.api.hasUnsavedChanges) }
        compose.onNodeWithText("替换并保存").performScrollTo().performClick()
        compose.onNodeWithText("下一步").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("https://example.com/v1/chat/completions", state.settings.api.url)
            assertEquals(listOf("替换并保存", "继续:API"), events)
        }
    }

    /** 所有步骤的按钮始终位于首屏，正文滚动和大字号不会改变页脚位置。 */
    @Test fun fixedFooterRemainsReachableWhileBodyScrolls() {
        show(OnboardingStep.PERMISSIONS)
        for (step in OnboardingStep.entries) {
            compose.runOnIdle { state = state.copy(onboarding = OnboardingUiState(step = step)) }
            val footer = compose.onNodeWithTag("onboarding-footer").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val nextLabel = if (step == OnboardingStep.MODEL) "进入羽译" else "下一步"
            val buttons = listOf("跳过这步", nextLabel) + if (step != OnboardingStep.PERMISSIONS) listOf("上一步") else emptyList()
            buttons.forEach { label ->
                val bounds = compose.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue("按钮必须保持至少四十八单位触控高度", bounds.height >= 48f * renderDensity - 1f)
            }
            scrollBodyTo(when (step) {
                OnboardingStep.PERMISSIONS -> "系统屏幕共享会在每次开始同传时询问，这里无需提前授权。"
                OnboardingStep.API -> "保存翻译配置"
                OnboardingStep.MODEL -> "启用模型"
            })
            assertEquals(footer, compose.onNodeWithTag("onboarding-footer").fetchSemanticsNode().boundsInRoot)
        }
    }

    /** 以较低像素密度模拟平板空间，使设备测试无需修改真实系统显示配置。 */
    private val renderDensity: Float get() = if (tablet) 0.6f else 1f

    /** 构造无外部依赖的完整状态，所有权限、存储与下载回调只更新当前夹具。 */
    private fun show(step: OnboardingStep, dirty: Boolean = false) {
        state = SimulSubUiState(onboarding = OnboardingUiState(step = step),
            live = LiveUiState(permissions = listOf(
                PermissionUiState("必需权限", "显示悬浮字幕", "仅用于在其他应用上显示字幕。", false),
                PermissionUiState("可选权限", "可选通知权限", "不应该出现在引导。", false, required = false))),
            settings = SettingsUiState(api = if (dirty) savedApi.copy(model = "未保存模型", hasUnsavedChanges = true) else savedApi),
            models = ModelsUiState(items = listOf(
                model("甲", canDownload = true), model("乙", canActivate = true))))
        val actions = SimulSubActions(
            onOnboardingNext = { advance("继续") }, onOnboardingSkip = { advance("跳过") },
            onOnboardingBack = {
                val current = state.onboarding!!.step
                events += "返回:${current.name}"
                assertFalse("返回前必须恢复未保存草稿", state.settings.api.hasUnsavedChanges)
                state = state.copy(onboarding = state.onboarding!!.copy(step = OnboardingStep.entries[current.ordinal - 1]))
            },
            onDiscardApiDraft = { events += "放弃"; state = state.copy(settings = state.settings.copy(api = savedApi)) },
            onPermission = { id ->
                events += "权限:$id"
                state = state.copy(live = state.live.copy(permissions = state.live.permissions.map { if (it.id == id) it.copy(granted = true) else it }))
            },
            onApiDraftChange = { api -> state = state.copy(settings = state.settings.copy(api = api.copy(hasUnsavedChanges = true))) },
            onTestApi = { events += "测试" },
            onSaveApi = {
                events += "保存"
                state = state.copy(settings = state.settings.copy(api = state.settings.api.copy(hasUnsavedChanges = false)),
                    onboarding = state.onboarding!!.copy(apiReady = true))
            },
            onApplyTestedApiUrl = {
                events += "替换并保存"
                state = state.copy(settings = state.settings.copy(api = state.settings.api.copy(
                    url = state.settings.api.suggestedUrl, suggestedUrl = "", hasUnsavedChanges = false)),
                    onboarding = state.onboarding!!.copy(apiReady = true))
            },
            onDownloadModel = { id -> events += "下载:$id"; updateModel(id, ModelUiStatus.DOWNLOADING) },
            onCancelDownload = { id -> events += "取消:$id"; updateModel(id, ModelUiStatus.CANCELLED) },
            onActivateModel = { id ->
                events += "启用:$id"
                updateModel(id, ModelUiStatus.ACTIVE)
                state = state.copy(onboarding = state.onboarding!!.copy(modelReady = true))
            },
        )
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CompositionLocalProvider(LocalDensity provides Density(renderDensity, fontScale)) {
                Box(Modifier.requiredSize(if (tablet) 1040.dp else 320.dp, 640.dp)) { OnboardingScreen(state, actions) }
            }
        }
    }

    /** 跳过和完成均只推进纯内存步骤，最后一步退出引导。 */
    private fun advance(label: String) {
        val current = state.onboarding!!
        events += "$label:${current.step.name}"
        assertFalse("离开接口步骤前必须保存或恢复草稿", state.settings.api.hasUnsavedChanges)
        state = state.copy(onboarding = if (current.step == OnboardingStep.MODEL) null
            else current.copy(step = OnboardingStep.entries[current.step.ordinal + 1]))
    }

    /** 模型夹具包含容易被误带入引导的简介和删除能力，以检查精简界面。 */
    private fun model(id: String, canDownload: Boolean = false, canActivate: Boolean = false) = ModelUiState(
        id = id, name = "识别模型$id", description = "不应展示的模型简介", languages = "中英日", sizeLabel = "100 MB",
        license = "测试许可", sourceLabel = "测试来源", canDownload = canDownload, canActivate = canActivate,
        status = if (canActivate) ModelUiStatus.READY else ModelUiStatus.NOT_INSTALLED, canDelete = true)

    /** 模拟下载、取消和启用状态，不访问下载器或模型目录。 */
    private fun updateModel(id: String, status: ModelUiStatus) {
        state = state.copy(models = state.models.copy(items = state.models.items.map {
            if (it.id != id) it else it.copy(status = status, progress = 0.25f,
                canDownload = status == ModelUiStatus.CANCELLED, canCancel = status == ModelUiStatus.DOWNLOADING, canActivate = false)
        }))
    }

    /** 选择含目标内容的最后一列，兼容接口表单的宽屏双列布局。 */
    private fun scrollBodyTo(text: String) {
        val lists = compose.onAllNodes(hasScrollToIndexAction())
        val target = if (state.onboarding?.step == OnboardingStep.API && text != "服务预设") lists.onLast() else lists.onFirst()
        target.performScrollToNode(hasText(text))
    }

    /** 主题、字幕与场景不属于首次引导中的任何一步。 */
    private fun assertNoLaterSettings() {
        listOf("外观", "字幕样式", "场景术语", "高级设置", "使用记录").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
    }
}
