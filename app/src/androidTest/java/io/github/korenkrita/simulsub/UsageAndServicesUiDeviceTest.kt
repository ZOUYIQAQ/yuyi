package io.github.korenkrita.simulsub

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.online.CloudAsrConfig
import io.github.korenkrita.simulsub.online.LiveTranslateModels
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/** 纯内存夹具覆盖手机、平板和两倍字号，不调用控制器、网络、采音或通知服务。 */
@RunWith(Parameterized::class)
class UsageAndServicesUiDeviceTest(private val tablet: Boolean, private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(SimulSubUiState())
    private val events = mutableListOf<String>()
    private lateinit var back: OnBackPressedDispatcher
    private val asrId = "profile:fixture-asr"
    private val directId = "profile:fixture-realtime"
    private val segmentedId = "profile:fixture-segmented"
    private val services = listOf(
        UiOption("local:fixture-model", "本地 · 已安装模型"),
        UiOption(asrId, "千问 · 在线听写"),
        UiOption(directId, "千问 · 实时直译"),
        UiOption(segmentedId, "千问 · 分段直译"),
        UiOption("catalog", "下载更多本地模型"),
    )
    private val translations = listOf(UiOption("", "默认翻译"), UiOption("profile-deepseek", "已保存 · DeepSeek"),
        UiOption("manage", "管理翻译配置"))
    private val listeningProfiles = listOf(
        ServiceProfileUiState("local:fixture-model", "本地 · 已安装模型", "本地识别模型", kind = "local", selected = true),
        ServiceProfileUiState("fixture-asr", "千问 · 在线听写", CloudAsrConfig.DEFAULT_MODEL, kind = "asr"),
        ServiceProfileUiState("fixture-realtime", "千问 · 实时直译", LiveTranslateModels.REALTIME_ID, kind = "realtime"),
        ServiceProfileUiState("fixture-segmented", "千问 · 分段直译", LiveTranslateModels.ECONOMY_ID, kind = "segmented"),
    )
    private val costs = UsageCostUiState(todayAmount = "¥123456789.1234", lastAmount = "¥9876543.2100",
        currentAmount = "¥12345.6789", todayDetail = "今日音频 120 秒，文字输入 1234 个令牌。",
        lastDetail = "上次会话音频 60 秒。", currentDetail = "本次会话音频 30 秒。",
        breakdown = listOf(UiOption("asr", "听写 · ¥0.1200", "音频输入 120 秒"),
            UiOption("translation", "翻译 · ¥0.0345", "输入与输出分别计量")),
        unpricedModels = listOf("自定义翻译模型 fixture-unpriced"), todayPartial = true, currentPartial = true)

    companion object {
        /** 每个交互同时覆盖两种屏幕和正常、两倍字号。 */
        @JvmStatic @Parameterized.Parameters(name = "平板={0}，字号={1}")
        fun cases(): List<Array<Any>> = listOf(false, true).flatMap { tablet ->
            listOf(1f, 2f).map { arrayOf<Any>(tablet, it) }
        }
    }

    /** 首页通过真实下拉选择服务，验证完整标识回调与状态回填，不允许三种模式按钮回归。 */
    @Test fun twoDropdownsDispatchCompleteServiceAndProfileIds() {
        show()
        compose.onNodeWithTag("live-primary-action").assertIsDisplayed()
        select("listening-service-picker", "千问 · 在线听写")
        select("translation-service-picker", "已保存 · DeepSeek")
        compose.runOnIdle {
            assertEquals(listOf("听写:$asrId", "翻译:profile-deepseek"), events)
            assertEquals(asrId, state.live.listeningServiceId)
            assertEquals("profile-deepseek", state.live.translationServiceId)
            assertEquals(ListeningMode.CLOUD_ASR, state.live.listeningMode)
        }
        ListeningMode.entries.forEach { compose.onNodeWithTag("listening-mode-${it.name}").assertDoesNotExist() }
        reveal(hasTestTag("service-selection"))
        screenshot("首页两步选择")
        select("translation-service-picker", "默认翻译")
        compose.runOnIdle { assertEquals("翻译:", events.last()); assertEquals("", state.live.translationServiceId) }
    }

    /** 直译只显示已包含翻译，切回听写后仍保留之前选中的翻译配置。 */
    @Test fun directServiceHidesTranslationSelectorAndPreservesProfile() {
        show()
        select("translation-service-picker", "已保存 · DeepSeek")
        select("listening-service-picker", "千问 · 实时直译")
        reveal(hasTestTag("translation-included"))
        compose.onNodeWithTag("translation-included").assertIsDisplayed()
        compose.onNodeWithTag("translation-service-picker").assertDoesNotExist()
        compose.onNodeWithText("管理翻译").assertDoesNotExist()
        screenshot("直译已包含翻译")
        select("listening-service-picker", "千问 · 分段直译")
        compose.runOnIdle { assertEquals(segmentedId, state.live.listeningServiceId) }
        select("listening-service-picker", "本地 · 已安装模型")
        reveal(hasTestTag("translation-service-picker"))
        compose.onNodeWithText("已保存 · DeepSeek").assertIsDisplayed()
        compose.runOnIdle { assertEquals("profile-deepseek", state.live.translationServiceId) }
        reveal(hasText("管理翻译"))
        compose.onNodeWithText("管理翻译").assertIsDisplayed().performClick()
        reveal(hasTestTag("create-translation-service"))
        compose.onNodeWithTag("create-translation-service").assertIsDisplayed()
        reveal(hasTestTag("service-profile-profile-deepseek"))
        compose.onNodeWithTag("service-profile-profile-deepseek").assertIsDisplayed()
        compose.onNodeWithText("当前配置").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回同传").performClick()
        compose.runOnIdle { assertEquals("profile-deepseek", state.live.translationServiceId) }
    }

    /** 首页费用入口进入详情，顶栏和系统返回都回到同传，不中断进行中的会话。 */
    @Test fun homeCostEntryAndBothBackActionsKeepRunningSession() {
        show(running = true)
        openCostsFromHome()
        reveal(hasText(costs.currentDetail))
        compose.onNodeWithText(costs.currentDetail).assertIsDisplayed()
        compose.onNodeWithContentDescription("返回同传").performClick()
        compose.runOnIdle { assertEquals(UiDestination.LIVE, state.destination); assertTrue(state.live.running) }
        openCostsFromHome()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithTag("live-primary-action").assertExists()
        compose.runOnIdle { assertEquals(UiDestination.LIVE, state.destination); assertTrue(state.live.running); assertTrue(events.none { it == "停止" }) }
    }

    /** 设置列表可独立进入费用页并返回设置首页，未计价服务有明确说明。 */
    @Test fun settingsCostEntryReturnsToSettingsAndShowsUnpricedService() {
        show(destination = UiDestination.SETTINGS)
        reveal(hasText("用量与费用"))
        compose.onNodeWithText("用量与费用").performClick()
        reveal(hasTestTag("cost-today"))
        compose.onNode(hasText("存在未计价服务，金额可能不完整") and
            hasAnyAncestor(hasTestTag("cost-today"))).assertIsDisplayed()
        screenshot("费用金额优先")
        reveal(hasText("听写 · ¥0.1200"))
        assertFullAmount(hasText("听写 · ¥0.1200"))
        reveal(hasText(costs.unpricedModels.single()))
        compose.onNodeWithText(costs.unpricedModels.single()).assertIsDisplayed()
        compose.onNodeWithText("未计价", useUnmergedTree = true).assertIsDisplayed()
        screenshot("分类成本与未计价")
        reveal(hasText(costs.priceNote, substring = true))
        compose.onNodeWithText(costs.priceNote, substring = true).assertIsDisplayed()
        compose.onNodeWithText("单价日期：${costs.ratesUpdated}", substring = true).assertIsDisplayed()
        screenshot("费用末尾计价说明")
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.runOnIdle { assertEquals(UiDestination.SETTINGS, state.destination) }
        reveal(hasText("听写服务"))
        compose.onNodeWithText("听写服务").assertIsDisplayed()
    }

    /** 用真实文字排版结果检查大金额没有截断，同时覆盖首页、今日、上次和本次明细卡片。 */
    @Test fun largeAmountsRemainCompleteInSummaryAndCostDetails() {
        show()
        reveal(hasTestTag("live-cost-summary"))
        assertFullAmount(hasText(costs.todayAmount))
        assertFullAmount(hasText(costs.lastAmount))
        screenshot("首页金额完整")
        compose.runOnIdle { state = state.copy(live = state.live.copy(running = true)) }
        reveal(hasTestTag("live-cost-summary"))
        compose.onNodeWithText("本次同传", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("上次同传", useUnmergedTree = true).assertDoesNotExist()
        assertFullAmount(hasText(costs.currentAmount))
        screenshot("运行中本次费用")
        openCostsFromHome()
        listOf("cost-today" to costs.todayAmount, "cost-last" to costs.lastAmount, "cost-current" to costs.currentAmount).forEach { (tag, amount) ->
            reveal(hasTestTag(tag))
            assertFullAmount(hasText(amount))
            screenshot("费用金额-$tag")
        }
    }

    /** 全部未知单价时保留控制器给出的待计价文字，不能回退为默认零金额。 */
    @Test fun entirelyUnpricedUsageShowsPendingPricingInsteadOfZero() {
        show()
        compose.runOnIdle {
            val pending = costs.copy(todayAmount = "待计价", lastAmount = "待计价", currentAmount = "待计价",
                todayPartial = true, lastPartial = true, currentPartial = true,
                priceNote = "按接口用量与公开单价估算；DeepSeek 节假日与优惠时段说明以服务商当日公布的价格和实际账单为准，优惠调整可能影响估算。")
            state = state.copy(live = state.live.copy(costs = pending), settings = state.settings.copy(costs = pending))
        }
        reveal(hasTestTag("live-cost-summary"))
        compose.onAllNodesWithText("待计价", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithText("¥0.0000", useUnmergedTree = true).assertDoesNotExist()
        openCostsFromHome()
        reveal(hasText(state.settings.costs.priceNote, substring = true))
        assertFullAmount(hasText(state.settings.costs.priceNote, substring = true))
        screenshot("全部待计价与长单价说明")
    }

    /** 沿用真实设备完整窗口验证费用，不使用合成窗口尺寸，供实际手机和平板视觉验收。 */
    @Test fun actualDeviceWindowShowsFullCostPage() {
        show(actualWindow = true)
        reveal(hasTestTag("live-cost-summary"))
        screenshot("实际设备首页全窗口")
        openCostsFromHome()
        listOf("cost-today" to costs.todayAmount, "cost-last" to costs.lastAmount, "cost-current" to costs.currentAmount).forEach { (tag, amount) ->
            reveal(hasTestTag(tag))
            assertFullAmount(hasText(amount))
            screenshot("实际设备费用-$tag")
        }
    }

    /** 没有当前会话时不制造空卡片，首屏展示金额，计价和保存说明仍能从底部访问。 */
    @Test fun inactiveSessionKeepsAmountsFirstAndExplanationAtEnd() {
        show()
        compose.runOnIdle {
            val idle = costs.copy(currentAmount = "", currentDetail = "")
            state = state.copy(live = state.live.copy(costs = idle), settings = state.settings.copy(costs = idle))
        }
        openCostsFromHome()
        compose.onNodeWithTag("cost-today").assertIsDisplayed()
        compose.onNodeWithTag("cost-current").assertDoesNotExist()
        assertFullAmount(hasText(costs.todayAmount))
        screenshot("费用首屏金额优先")
        reveal(hasText("仅保存用量与费用统计，不保存字幕内容。"))
        compose.onNodeWithTag("cost-current").assertDoesNotExist()
        screenshot("费用末尾保存说明")
    }

    /** 未计价提示按今日、上次、本次分别判断，不能因其他会话未知而污染当前首页。 */
    @Test fun partialPricingWarningsFollowTheirOwnTimeScope() {
        show()
        compose.runOnIdle {
            val scoped = costs.copy(todayPartial = false, lastPartial = true, currentPartial = false)
            state = state.copy(live = state.live.copy(costs = scoped), settings = state.settings.copy(costs = scoped))
        }
        reveal(hasTestTag("live-cost-summary"))
        compose.onNodeWithText("含未计价服务 · 金额可能不完整 · 查看明细 ›", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { state = state.copy(live = state.live.copy(running = true)) }
        reveal(hasTestTag("live-cost-summary"))
        compose.onNodeWithText("含未计价服务 · 金额可能不完整 · 查看明细 ›", useUnmergedTree = true).assertDoesNotExist()
        openCostsFromHome()
        listOf("cost-today" to false, "cost-last" to true, "cost-current" to false).forEach { (tag, partial) ->
            reveal(hasTestTag(tag))
            val warning = compose.onNode(hasText("存在未计价服务，金额可能不完整") and hasAnyAncestor(hasTestTag(tag)))
            if (partial) warning.assertIsDisplayed() else warning.assertDoesNotExist()
        }
    }

    /** 各种当前模式都能访问全部服务；仅编辑所选云服务时出现对应表单，本地编辑进入模型库。 */
    @Test fun everyModeKeepsBothConnectionFormsAndLocalEntry() {
        show(destination = UiDestination.SETTINGS)
        compose.onNodeWithText("听写服务").performClick()
        listOf("local:fixture-model", "fixture-asr", "fixture-realtime", "fixture-segmented").forEach { activeId ->
            val active = state.settings.listeningProfiles.single { it.id == activeId }
            if (!active.selected) profileAction(activeId, "使用")
            val selectedId = if (active.kind == "local") activeId else "profile:$activeId"
            compose.runOnIdle { assertEquals(selectedId, state.live.listeningServiceId) }
            state.settings.listeningProfiles.forEach { profile ->
                reveal(hasTestTag("service-profile-${profile.id}"))
                compose.onNode(hasText(profile.name) and hasAnyAncestor(hasTestTag("service-profile-${profile.id}"))).assertIsDisplayed()
            }
            compose.onNodeWithTag("listening-service-picker").assertDoesNotExist()
            compose.onNodeWithTag("cloud-asr-endpoint").assertDoesNotExist()
            compose.onNodeWithTag("online-endpoint").assertDoesNotExist()
            listeningProfiles.filter { it.kind != "local" }.forEach { profile ->
                profileAction(profile.id, "编辑")
                val expectedTag = if (profile.kind == "asr") "cloud-asr-endpoint" else "online-endpoint"
                val otherTag = if (profile.kind == "asr") "online-endpoint" else "cloud-asr-endpoint"
                reveal(hasTestTag("listening-service-name"))
                compose.onNodeWithTag("listening-service-name").assertTextContains(profile.name).assertIsEnabled()
                reveal(hasTestTag(expectedTag))
                compose.onNodeWithTag(expectedTag).assertIsDisplayed().assertIsEnabled()
                compose.onNodeWithTag(otherTag).assertDoesNotExist()
                compose.runOnIdle {
                    assertEquals(profile.id, state.settings.listeningEditor?.id)
                    assertEquals(selectedId, state.live.listeningServiceId)
                    val draft = if (profile.kind == "asr") state.settings.cloudAsr else state.settings.online
                    assertEquals(profile.detail, draft.model)
                }
                reveal(hasText("保存听写服务"))
                compose.onNodeWithText("保存听写服务").assertIsEnabled()
                compose.onNodeWithContentDescription("返回听写服务列表").performClick()
                compose.runOnIdle { assertNull(state.settings.listeningEditor); assertEquals(selectedId, state.live.listeningServiceId) }
                reveal(hasTestTag("create-listening-service"))
                compose.onNodeWithTag("cloud-asr-endpoint").assertDoesNotExist()
                compose.onNodeWithTag("online-endpoint").assertDoesNotExist()
            }
            profileAction("local:fixture-model", "编辑")
            compose.runOnIdle { assertEquals(UiDestination.MODELS, state.destination) }
            compose.onNodeWithContentDescription("返回设置").performClick()
            reveal(hasTestTag("create-listening-service"))
        }
        screenshot("所有具名服务均可访问")
    }

    /** 首页缺权限时第一项必须是权限提示，处理权限后主操作继续可达。 */
    @Test fun missingPermissionWarningPrecedesSession() {
        show(missingPermission = true)
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(0)
        compose.onNodeWithTag("live-permission-warning").assertIsDisplayed()
        val warningTop = compose.onNodeWithTag("live-permission-warning").getUnclippedBoundsInRoot().top
        if (compose.onAllNodesWithTag("live-control-panel").fetchSemanticsNodes().isNotEmpty()) {
            val sessionTop = compose.onNodeWithTag("live-control-panel").getUnclippedBoundsInRoot().top
            assertTrue("权限提示须位于会话卡片之前或平板左栏顶部", warningTop <= sessionTop)
        }
        screenshot("首页权限优先")
        reveal(hasText("去允许"))
        compose.onNodeWithText("去允许").performClick()
        compose.onNodeWithTag("live-permission-warning").assertDoesNotExist()
        reveal(hasTestTag("live-primary-action"))
        compose.onNodeWithTag("live-primary-action").assertIsDisplayed()
    }

    /** 所有状态与动作仅在内存回填；按实际宿主尺寸缩放窗口，不修改设备显示设置。 */
    private fun show(running: Boolean = false, destination: UiDestination = UiDestination.LIVE,
        missingPermission: Boolean = false, actualWindow: Boolean = false) {
        state = SimulSubUiState(destination = destination,
            live = LiveUiState(running = running, canStart = true, statusTitle = "已准备好", statusDetail = "",
                modelLabel = "本地 · 已安装模型", translationLabel = "默认翻译", translationReady = true, cloudReady = true,
                listeningServiceId = "local:fixture-model", listeningServices = services, translationServices = translations, costs = costs,
                sourceOptions = listOf(UiOption("ja", "日语")), targetOptions = listOf(UiOption("zh", "中文")),
                sceneOptions = listOf(UiOption("", "通用场景")), permissions = listOf(
                    PermissionUiState("audio", "录制播放音频", "允许识别媒体声音。", !missingPermission),
                    PermissionUiState("overlay", "显示悬浮字幕", "允许显示字幕。", true))),
            settings = SettingsUiState(listeningServiceId = "local:fixture-model", listeningServices = services,
                translationServices = translations, costs = costs, listeningProfiles = listeningProfiles,
                translationProfiles = listOf(ServiceProfileUiState("", "默认翻译", "已保存的默认文字翻译"),
                    ServiceProfileUiState("profile-deepseek", "已保存 · DeepSeek", "DeepSeek · deepseek-chat"))))
        val actions = SimulSubActions(onNavigate = { state = state.copy(destination = it) },
            onSelectListeningService = { id ->
                events += "听写:$id"
                val mode = when {
                    id == directId || id == segmentedId -> ListeningMode.ONLINE
                    id == asrId -> ListeningMode.CLOUD_ASR
                    else -> ListeningMode.LOCAL
                }
                state = state.copy(live = state.live.copy(listeningMode = mode, listeningServiceId = id),
                    settings = state.settings.copy(listeningMode = mode, listeningServiceId = id,
                        listeningProfiles = state.settings.listeningProfiles.map { profile ->
                            profile.copy(selected = profile.id == id.removePrefix("profile:"))
                        }))
            }, onEditListeningService = { id ->
                val profile = state.settings.listeningProfiles.single { it.id == id }
                val asr = profile.kind == "asr"
                val draft = OnlineListeningUiState(endpoint = when (profile.kind) {
                    "asr" -> CloudAsrConfig.DEFAULT_ENDPOINT
                    "segmented" -> LiveTranslateModels.ECONOMY_ENDPOINT
                    else -> LiveTranslateModels.REALTIME_ENDPOINT
                }, model = profile.detail, hasSavedConfiguration = true)
                state = state.copy(settings = state.settings.copy(
                    listeningEditor = ListeningEditorUiState(id, profile.name, if (asr) ListeningMode.CLOUD_ASR else ListeningMode.ONLINE),
                    cloudAsr = if (asr) draft else state.settings.cloudAsr,
                    online = if (asr) state.settings.online else draft))
            }, onCloseListeningServiceEditor = {
                state = state.copy(settings = state.settings.copy(listeningEditor = null))
            }, onSelectTranslationService = { id ->
                events += "翻译:$id"
                state = state.copy(live = state.live.copy(translationServiceId = id), settings = state.settings.copy(translationServiceId = id))
            }, onPermission = { id ->
                state = state.copy(live = state.live.copy(permissions = state.live.permissions.map {
                    if (it.id == id || id == "all") it.copy(granted = true) else it
                }))
            }, onStop = { events += "停止" })
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            val width = if (tablet) 1280f else 360f
            val height = if (tablet) 900f else 780f
            val window = LocalWindowInfo.current.containerSize
            val density = if (actualWindow) LocalDensity.current.density
                else minOf(LocalDensity.current.density, window.width / width, window.height / height)
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                val viewport = if (actualWindow) Modifier.fillMaxSize() else Modifier.requiredSize(width.dp, height.dp)
                Box(viewport.testTag("usage-services-viewport")) { SimulSubApp(state, actions) }
            }
        }
    }

    /** 两步选择必须真实打开下拉并点击选项，不能直接调用业务回调冒充交互。 */
    private fun select(tag: String, label: String) {
        reveal(hasTestTag(tag))
        // 标签文字与点击语义分属不同节点，选择稳定容器内真正可点击的下拉锚点。
        compose.onNode(hasAnyAncestor(hasTestTag(tag)) and hasClickAction()).performClick()
        compose.onNode(isPopup()).assertExists()
        compose.onNode(hasText(label) and hasAnyAncestor(isPopup())).performScrollTo().performClick()
    }

    /** 按服务稳定标识选择真实卡片按钮，避免重复的编辑与使用文字命中其他配置。 */
    private fun profileAction(id: String, label: String) {
        val matcher = hasText(label) and hasAnyAncestor(hasTestTag("service-profile-$id"))
        reveal(matcher)
        compose.onNode(matcher).performClick()
    }

    /** 从真实首页摘要进入费用详情，不直接写入设置私有分区状态。 */
    private fun openCostsFromHome() {
        reveal(hasTestTag("live-cost-summary"))
        compose.onNodeWithTag("live-cost-summary").performClick()
        compose.onNodeWithContentDescription("返回同传").assertIsDisplayed()
    }

    /** 逐列滚动寻找目标，兼容手机单列、平板双列与页面退出动画。 */
    private fun reveal(matcher: SemanticsMatcher) {
        compose.waitForIdle()
        try { compose.onNode(matcher).assertIsDisplayed(); return } catch (_: AssertionError) { }
        var failure: AssertionError? = null
        val lists = compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().size
        for (index in 0 until lists) {
            try {
                compose.onAllNodes(hasScrollToIndexAction())[index].performScrollToNode(matcher)
                compose.onNode(matcher).performScrollTo().assertIsDisplayed()
                return
            } catch (error: AssertionError) { failure = error }
        }
        throw failure ?: AssertionError("未找到可滚动的页面内容：$matcher")
    }

    /** 按真实行宽和段高检查裁切，仅容许浮点排版落到整数像素时产生的一像素误差。 */
    private fun assertFullAmount(matcher: SemanticsMatcher) {
        val node = compose.onNode(matcher, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue("金额必须提供实际排版结果", results.isNotEmpty())
        results.forEach { result ->
            val diagnostic = "文字=${result.layoutInput.text.text}；布局尺寸=${result.size}；段高=${result.multiParagraph.height}；行数=${result.lineCount}"
            assertFalse("不得超出最大行数：$diagnostic", result.multiParagraph.didExceedMaxLines)
            assertTrue("文字段落高度不得被裁切：$diagnostic", result.multiParagraph.height <= result.size.height + 1f)
            repeat(result.lineCount) { line ->
                assertFalse("金额行不应省略：$diagnostic", result.isLineEllipsized(line))
                assertTrue("文字行左侧不得越界：$diagnostic", result.getLineLeft(line) >= -1f)
                assertTrue("文字行右侧不得越界：$diagnostic", result.getLineRight(line) <= result.size.width + 1f)
            }
            assertEquals("最后一行须覆盖完整文本：$diagnostic", result.layoutInput.text.length,
                result.getLineEnd(result.lineCount - 1, visibleEnd = false))
        }
    }

    /** 按屏幕和字号独立保存截图到应用外部文件目录，不覆盖其他测试的验收材料。 */
    private fun screenshot(label: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(checkNotNull(context.getExternalFilesDir(null)) { "无法取得应用外部文件目录" }, ".qa/usage-costs/screens")
        check(directory.isDirectory || directory.mkdirs()) { "无法创建用量费用截图目录：$directory" }
        val prefix = "$label-${if (tablet) "平板" else "手机"}-字号$fontScale"
        val fullWindow = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) { "无法截取完整设备窗口" }
        try {
            val file = File(directory, "$prefix-全窗口.png")
            file.outputStream().use { check(fullWindow.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) { "全窗口截图保存失败" } }
            android.util.Log.i("用量费用界面验收", "全窗口截图已保存：${file.absolutePath}")
        } finally { fullWindow.recycle() }
        val bitmap = compose.onNodeWithTag("usage-services-viewport").captureToImage().asAndroidBitmap()
        val file = File(directory, "$prefix-组件.png")
        file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) { "组件截图保存失败" } }
    }
}
