package io.github.korenkrita.simulsub

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.korenkrita.simulsub.online.CloudAsrConfig
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 路由级回归只使用内存状态；不访问偏好、网络、服务、通知或真实设备配置。 */
@RunWith(Parameterized::class)
class RouteScrollRegressionDeviceTest(private val tablet: Boolean) {
    @get:Rule val compose = createComposeRule()
    private val restoration = StateRestorationTester(compose)
    private var state by mutableStateOf(SimulSubUiState(destination = UiDestination.SETTINGS))
    private var alternateWidth by mutableStateOf(false)
    private lateinit var back: OnBackPressedDispatcher
    private lateinit var registry: SaveableStateRegistry
    private var discarded = 0
    private val privateUrl = "https://draft-private.example/v1"
    private val privateModel = "不应进入保存状态的模型草稿"
    private val privateKey = "不应进入保存状态的平台密钥"

    companion object {
        /** 手机单列和平板独立双列使用同一套真实路由与返回动作。 */
        @JvmStatic @Parameterized.Parameters(name = "平板={0}")
        fun cases(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }

    /** 听写列表滚到两栏底部后反复编辑与返回，顶栏和系统返回均不能重置列表位置。 */
    @Test fun listeningListKeepsBothColumnOffsetsAcrossEditorsAndBackActions() {
        show()
        clickText("听写服务")
        val position = scrollProfiles("asr")
        repeat(2) { index ->
            profileAction("asr-20", "编辑")
            reveal(hasText("保存听写服务"))
            compose.onNodeWithText("保存听写服务").assertIsDisplayed()
            if (index == 0) compose.onNodeWithContentDescription("返回听写服务列表").performClick() else systemBack()
            assertPosition(position)
        }
    }

    /** 翻译列表、具体接口编辑器和密钥页各有独立位置，重建后逐层返回必须原位恢复。 */
    @Test fun translationEditorKeysAndRecreationRestoreEveryLayer() {
        show()
        clickText("翻译服务")
        val listPosition = scrollProfiles("api")
        profileAction("api-20", "编辑")
        reveal(hasText("添加请求头"))
        val editorBottom = position(listOf(hasText("添加请求头")))
        assertTrue("接口编辑器必须实际滚动", editorBottom.scroll.any { it > 0f })
        reveal(hasText("管理平台密钥"))
        val editorPosition = position(listOf(hasText("管理平台密钥")))
        compose.onNodeWithText("管理平台密钥").performClick()
        val editorBeforeKeys = state.settings.api
        reveal(hasTestTag("platform-key-platform-12:default"))
        val keysPosition = position(listOf(hasTestTag("platform-key-platform-12:default")))
        assertTrue("密钥页必须实际滚动", keysPosition.scroll.any { it > 0f })
        restoration.emulateSavedInstanceStateRestore()
        assertPosition(keysPosition)
        compose.onNodeWithContentDescription("返回翻译服务").performClick()
        compose.runOnIdle { assertEquals(editorBeforeKeys, state.settings.api) }
        assertPosition(editorPosition)
        reveal(hasText("添加请求头"))
        val editedPosition = position(listOf(hasText("添加请求头")))
        compose.onNodeWithContentDescription("返回翻译服务列表").performClick()
        assertPosition(listPosition)
        profileAction("api-20", "编辑")
        assertPosition(editedPosition)
        systemBack()
        assertPosition(listPosition)
    }

    /** 主导航切走、改变布局并重建后，恢复原宽度仍保留服务列表各栏原始位置。 */
    @Test fun mainNavigationResizeAndRecreationKeepServiceListPosition() {
        show()
        clickText("听写服务")
        val before = scrollProfiles("asr")
        navigate("同传")
        compose.runOnIdle { alternateWidth = true }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { alternateWidth = false }
        navigate("设置")
        clickText("听写服务")
        assertPosition(before)
        compose.onNodeWithContentDescription("返回设置").performClick()
        clickText("听写服务")
        assertPosition(before)
    }

    /** 未保存确认继续保护业务草稿；真实根注册表只保存导航和滚动元数据。 */
    @Test fun unsavedProtectionAndSavedStateExcludeBusinessDrafts() {
        show()
        clickText("翻译服务")
        val before = scrollProfiles("api")
        profileAction("api-20", "编辑")
        reveal(hasText("模型 ID"))
        compose.onNode(hasSetTextAction() and hasText("模型 ID", substring = true)).performTextReplacement(privateModel)
        clickText("管理平台密钥")
        reveal(hasTestTag("platform-key-platform-12:default"))
        compose.onNodeWithTag("platform-key-platform-12:default").performTextReplacement(privateKey)
        assertSavedStateIsPrivate()
        systemBack()
        compose.onNodeWithText("配置尚未保存").assertIsDisplayed()
        compose.onNodeWithText("放弃修改").performClick()
        compose.runOnIdle { assertEquals(privateModel, state.settings.api.model) }
        systemBack()
        compose.onNodeWithText("继续编辑").performClick()
        compose.runOnIdle { assertEquals(0, discarded); assertTrue(state.settings.api.hasUnsavedChanges) }
        assertSavedStateIsPrivate()
        systemBack()
        compose.onNodeWithText("放弃修改").performClick()
        compose.runOnIdle { assertEquals(1, discarded) }
        assertPosition(before)
    }

    /** 费用入口位于资源分组，进入并返回仍保留设置首页位置。 */
    @Test fun costsBelongToResourcesAndKeepSettingsHomePosition() {
        show()
        reveal(hasText("用量与费用"))
        compose.onNode(hasText("用量与费用") and hasAnyAncestor(hasTestTag("settings-application-group"))).assertDoesNotExist()
        val before = position(listOf(hasText("用量与费用")))
        compose.onNodeWithText("用量与费用").performClick()
        compose.onNodeWithContentDescription("返回设置").performClick()
        assertPosition(before)
    }

    /** 引导选择后进入配置与密钥子页，逐层返回时选择列表和配置页各自保持位置。 */
    @Test fun onboardingListEditorAndKeysKeepIndependentPositions() {
        show(onboarding = true)
        val before = scrollProfiles("asr", "onboarding-service-")
        compose.onNode(hasText("选择并继续") and hasAnyAncestor(hasTestTag("onboarding-service-asr-20"))).performClick()
        reveal(hasText("管理平台密钥"))
        val editor = position(listOf(hasText("管理平台密钥")))
        compose.onNodeWithText("管理平台密钥").performClick()
        reveal(hasTestTag("platform-key-platform-12:default"))
        compose.onNodeWithText("返回当前引导步骤").performClick()
        assertPosition(editor)
        compose.onNodeWithText("上一步").performClick()
        assertPosition(before)
    }

    /** 多条技术接口和密钥只供滚动及编辑夹具使用，不初始化真实控制器或平台配置。 */
    private fun show(onboarding: Boolean = false) {
        state = SimulSubUiState(destination = UiDestination.SETTINGS,
            onboarding = if (onboarding) OnboardingUiState(step = OnboardingStep.MODE) else null,
            settings = SettingsUiState(
            listeningProfiles = (1..20).map { ServiceProfileUiState("asr-$it", "千问 Streaming 接口 $it", CloudAsrConfig.DEFAULT_MODEL, kind = "asr", builtIn = false) },
            translationProfiles = (1..20).map { ServiceProfileUiState("api-$it", "DeepSeek 接口 $it", "deepseek-flash", builtIn = false) },
            platformKeys = (1..12).map { PlatformKeyUiState("platform-$it", "平台 $it") }))
        val actions = SimulSubActions(onNavigate = { state = state.copy(destination = it) },
            onChooseOnboardingListeningService = { state = state.copy(
                onboarding = state.onboarding?.copy(step = OnboardingStep.ONLINE, listeningMode = ListeningMode.CLOUD_ASR),
                settings = state.settings.copy(listeningMode = ListeningMode.CLOUD_ASR)) },
            onOnboardingBack = { state = state.copy(onboarding = state.onboarding?.copy(step = OnboardingStep.MODE)) },
            onEditListeningService = { id -> state = state.copy(settings = state.settings.copy(
                listeningEditor = ListeningEditorUiState(id, "千问 Streaming", ListeningMode.CLOUD_ASR))) },
            onCloseListeningServiceEditor = { state = state.copy(settings = state.settings.copy(listeningEditor = null)) },
            onEditTranslationService = { id -> state = state.copy(settings = state.settings.copy(translationEditorId = id,
                api = TranslationApiUiState(profileId = id, url = privateUrl, model = "deepseek-flash",
                    headers = (1..8).map { HeaderUiState("header-$it", "X-Test-$it", "测试参数$it") }))) },
            onApiDraftChange = { state = state.copy(settings = state.settings.copy(api = it.copy(hasUnsavedChanges = true))) },
            onAddHeader = {},
            onCloseTranslationServiceEditor = { state = state.copy(settings = state.settings.copy(translationEditorId = null)) },
            onDiscardApiDraft = { discarded++; state = state.copy(settings = state.settings.copy(api = state.settings.api.copy(hasUnsavedChanges = false))) },
            onPlatformCredentialChange = { id, credentialId, value -> state = state.copy(settings = state.settings.copy(platformKeys = state.settings.platformKeys.map {
                if (it.id == id && it.credentialId == credentialId) it.copy(key = value, hasUnsavedChanges = true) else it
            })) },
            onPlatformKeyLabelChange = { id, credentialId, label -> state = state.copy(settings = state.settings.copy(platformKeys = state.settings.platformKeys.map {
                if (it.id == id && it.credentialId == credentialId) it.copy(credentialLabel = label, hasUnsavedChanges = true) else it
            })) },
            onSavePlatformCredential = { id, credentialId -> state = state.copy(settings = state.settings.copy(platformKeys = state.settings.platformKeys.map {
                if (it.id == id && it.credentialId == credentialId) it.copy(hasUnsavedChanges = false, hasSavedKey = true) else it
            })) },
            onDiscardPlatformKeys = { state = state.copy(settings = state.settings.copy(platformKeys = state.settings.platformKeys.map {
                it.copy(key = "", hasUnsavedChanges = false)
            })) })
        restoration.setContent {
            registry = LocalSaveableStateRegistry.current!!
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            BoxWithConstraints {
                val width = if (alternateWidth) { if (tablet) 540.dp else 1040.dp } else if (tablet) 1040.dp else 360.dp
                val height = 640.dp
                val density = minOf(constraints.maxWidth / width.value, constraints.maxHeight / height.value)
                CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                    Box(Modifier.requiredSize(width, height)) { SimulSubApp(state, actions) }
                }
            }
        }
        compose.waitForIdle()
    }

    /** 两栏分别滚到底并保留少量条目内偏移，避免只验证路由标识或单侧列表。 */
    private fun scrollProfiles(prefix: String, tagPrefix: String = "service-profile-"): Position {
        val ids = if (tablet) listOf("$prefix-19", "$prefix-20") else listOf("$prefix-20")
        ids.forEachIndexed { column, id ->
            lists()[column].performScrollToNode(hasTestTag("$tagPrefix$id"))
            compose.onNodeWithTag("$tagPrefix$id").performScrollTo()
            lists()[column].performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -18f) }
        }
        val result = position(ids.map { hasTestTag("$tagPrefix$it") })
        assertTrue("受测服务列表必须全部离开顶部", result.scroll.all { it > 0f })
        return result
    }

    /** 保存真实列表滚动语义与目标边界，断言恢复时不再主动滚动找回目标。 */
    private fun position(anchors: List<SemanticsMatcher>): Position {
        compose.waitForIdle()
        return Position(lists().fetchSemanticsNodes().map { it.config[SemanticsProperties.VerticalScrollAxisRange].value() },
            anchors.map { it to compose.onNode(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot })
    }

    /** 返回及重建后直接比对全部列表和锚点，允许一像素舍入，不通过再次定位掩盖重置。 */
    private fun assertPosition(expected: Position) {
        val actual = position(expected.anchors.map { it.first })
        assertEquals(expected.scroll.size, actual.scroll.size)
        expected.scroll.zip(actual.scroll).forEach { (before, after) -> assertEquals("滚动值必须恢复", before, after, 1f) }
        expected.anchors.zip(actual.anchors).forEach { (before, after) ->
            assertEquals("锚点纵坐标必须恢复", before.second.top, after.second.top, 1f)
            assertEquals("锚点横坐标必须恢复", before.second.left, after.second.left, 1f)
        }
    }

    /** 只读根注册表导出，已访问和已离开页面的业务原文均不可写入保存状态。 */
    private fun assertSavedStateIsPrivate() {
        val saved = compose.runOnIdle { registry.performSave().toString() }
        listOf(privateUrl, privateModel, privateKey).forEach { assertFalse("草稿不能进入保存状态：$it", saved.contains(it)) }
    }

    /** 只枚举正文懒列表，避免导航栏自己的滚动语义干扰位置比较。 */
    private fun lists() = compose.onAllNodes(hasScrollToIndexAction())

    /** 定位真实可见控件，兼容固定头部和双列正文。 */
    private fun reveal(matcher: SemanticsMatcher) {
        compose.waitForIdle()
        try { compose.onNode(matcher).assertIsDisplayed(); return } catch (_: AssertionError) { }
        var failure: AssertionError? = null
        for (index in lists().fetchSemanticsNodes().indices) {
            try {
                lists()[index].performScrollToNode(matcher)
                compose.onNode(matcher).performScrollTo().assertIsDisplayed()
                return
            } catch (error: AssertionError) { failure = error }
        }
        throw failure ?: AssertionError("未找到路由测试目标：$matcher")
    }

    /** 入口点击仍走真实组合事件，不直接修改设置内部路由。 */
    private fun clickText(text: String) { reveal(hasText(text)); compose.onNodeWithText(text).performClick(); compose.waitForIdle() }

    /** 按稳定服务标识点击卡片，多个同名编辑按钮不会误命中其他服务。 */
    private fun profileAction(id: String, text: String) {
        val matcher = hasText(text) and hasAnyAncestor(hasTestTag("service-profile-$id"))
        reveal(matcher)
        compose.onNode(matcher).performClick()
        compose.waitForIdle()
    }

    /** 使用 Activity 实际系统返回分发器，确保覆盖生产返回保护。 */
    private fun systemBack() { compose.runOnIdle { back.onBackPressed() }; compose.waitForIdle() }

    /** 主导航点击后等待动画移除旧页面，再读取新页面滚动状态。 */
    private fun navigate(label: String) { compose.onNode(isSelectable() and hasText(label)).performClick(); compose.waitForIdle() }

    /** 测试快照只在测试内存中比较，不进入任何页面或业务存储。 */
    private data class Position(val scroll: List<Float>, val anchors: List<Pair<SemanticsMatcher, Rect>>)
}
