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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 使用真实设置导航与隔离内存状态，不启动服务、网络、控制器或持久化业务配置。 */
@RunWith(Parameterized::class)
class SettingsScrollPositionDeviceTest(private val tablet: Boolean) {
    @get:Rule val compose = createComposeRule()
    private val restoration = StateRestorationTester(compose)
    private var state by mutableStateOf(SimulSubUiState(destination = UiDestination.SETTINGS))
    private var resized by mutableStateOf(false)
    private lateinit var backDispatcher: OnBackPressedDispatcher
    private lateinit var savedStateRegistry: SaveableStateRegistry
    private var discarded = 0

    companion object {
        /** 手机单列和平板双列执行相同的返回及状态重建回归。 */
        @JvmStatic @Parameterized.Parameters(name = "平板={0}")
        fun cases(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }

    /** 顶栏返回后逐列校验实际滚动值和下方入口位置，不能靠再次滚动使断言通过。 */
    @Test fun topBarBackRestoresScrollPosition() {
        show()
        val before = scrollHome()
        openAbout()
        compose.onNodeWithContentDescription("返回设置").performClick()
        assertPosition(before)
    }

    /** 系统返回走生产返回分发器，左右两列均保留各自位置。 */
    @Test fun systemBackRestoresScrollPosition() {
        show()
        val before = scrollHome()
        openAbout()
        systemBack()
        assertPosition(before)
    }

    /** 首页可保存状态重建后保留条目内偏移，再次进入详情和返回也不能归零。 */
    @Test fun recreationOnHomeRestoresPosition() {
        show()
        val before = scrollHome()
        restoration.emulateSavedInstanceStateRestore()
        assertPosition(before)
        openAbout()
        systemBack()
        assertPosition(before)
    }

    /** 首页已退出组合时也必须保存位置；重建后分别验证两种返回方式。 */
    @Test fun recreationInDetailRestoresPositionForBothBackPaths() {
        show()
        val before = scrollHome()
        for (topBar in listOf(true, false)) {
            openAbout()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithContentDescription("返回设置").assertIsDisplayed()
            if (topBar) compose.onNodeWithContentDescription("返回设置").performClick() else systemBack()
            assertPosition(before)
        }
    }

    /** 模拟详情期间旋转改变窗口并重建；恢复原窗口后检查同布局的原始位置。 */
    @Test fun resizingAndRecreationInDetailKeepHomeAnchor() {
        show()
        val before = scrollHome()
        openAbout()
        compose.runOnIdle { resized = true }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { resized = false }
        systemBack()
        assertPosition(before)
    }

    /** 接口未保存确认保持现有语义，取消不放弃，确认后仍回到进入前的位置。 */
    @Test fun unsavedApiExitStillRequiresConfirmation() {
        show()
        pageLists()[0].performScrollToNode(hasText("翻译 API"))
        compose.onNodeWithText("翻译 API").performScrollTo()
        val before = scrollPosition(listOf("翻译 API"))
        compose.onNodeWithText("翻译 API").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            state = state.copy(settings = state.settings.copy(api = state.settings.api.copy(
                model = "未保存的测试模型", hasUnsavedChanges = true)))
        }
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.onNodeWithText("配置尚未保存").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.runOnIdle { assertTrue(state.settings.api.hasUnsavedChanges); assertEquals(0, discarded) }
        systemBack()
        compose.onNodeWithText("放弃修改").performClick()
        assertPosition(before)
        compose.runOnIdle { assertFalse(state.settings.api.hasUnsavedChanges); assertEquals(1, discarded) }
    }

    /** 三个主页面反复回切并重建，位置必须分别恢复，不能共享或仅保留最近一页。 */
    @Test fun mainDestinationsKeepIndependentPositionsAcrossRecreation() {
        show()
        val settingsPosition = scrollHome()
        navigate("同传")
        scrollTo("仅显示定稿", 0)
        val livePosition = scrollPosition(listOf("仅显示定稿"))
        assertTrue("同传页必须实际滚动", livePosition.scroll.any { it > 0f })
        navigate("字幕")
        compose.onNodeWithContentDescription("暂时收起字幕预览").performClick()
        scrollTo("字幕保留方式", if (tablet) 1 else 0)
        val subtitlePosition = scrollPosition(listOf("字幕保留方式"))
        assertTrue("字幕页必须实际滚动", subtitlePosition.scroll.any { it > 0f })
        restoration.emulateSavedInstanceStateRestore()
        assertPosition(subtitlePosition)
        repeat(2) {
            navigate("同传")
            assertPosition(livePosition)
            navigate("设置")
            assertPosition(settingsPosition)
            navigate("字幕")
            assertPosition(subtitlePosition)
        }
    }

    /** 设置首页和每个具体设置使用独立位置，再次进入同一详情仍停在上次阅读处。 */
    @Test fun settingsDetailKeepsItsOwnPositionAfterReturningHome() {
        show()
        scrollTo("外观", 0)
        val home = scrollPosition(listOf("外观"))
        compose.onNodeWithText("外观").performClick()
        scrollTo("动态取色", if (tablet) 1 else 0)
        val detail = scrollPosition(listOf("动态取色"))
        assertTrue("外观详情必须实际滚动", detail.scroll.any { it > 0f })
        compose.onNodeWithContentDescription("返回设置").performClick()
        assertPosition(home)
        compose.onNodeWithText("外观").performClick()
        assertPosition(detail)
        systemBack()
        assertPosition(home)
    }

    /** 模型独立列表离开路由后保留位置，返回设置入口也不重置首页。 */
    @Test fun modelListSurvivesOtherRoutesAndRecreation() {
        show()
        scrollTo("模型库", 0)
        val settingsPosition = scrollPosition(listOf("模型库"))
        compose.onNodeWithText("模型库").performClick()
        compose.waitForIdle()
        scrollTo("测试模型 10", 0)
        val modelPosition = scrollPosition(listOf("测试模型 10"))
        assertTrue("模型列表必须实际滚动", modelPosition.scroll.single() > 0f)
        navigate("同传")
        navigate("设置")
        assertPosition(settingsPosition)
        compose.onNodeWithText("模型库").performClick()
        assertPosition(modelPosition)
        restoration.emulateSavedInstanceStateRestore()
        assertPosition(modelPosition)
        compose.onNodeWithContentDescription("返回设置").performClick()
        assertPosition(settingsPosition)
    }

    /** 手机列表到编辑器和平板右侧编辑均不污染列表位置，覆盖顶栏及系统关闭。 */
    @Test fun sceneListRestoresAfterEditorAndRouteChanges() {
        show()
        scrollTo("场景与术语", 0)
        compose.onNodeWithText("场景与术语").performClick()
        compose.waitForIdle()
        scrollTo("测试场景 12", 0)
        compose.onAllNodesWithText("编辑场景").onLast().performScrollTo()
        val scenePosition = scrollPosition(listOf("测试场景 12"))
        assertTrue("场景列表必须实际滚动", scenePosition.scroll.first() > 0f)
        for (topBar in listOf(true, false)) {
            compose.onAllNodesWithText("编辑场景").onLast().performClick()
            compose.waitForIdle()
            scrollTo("关闭编辑", if (tablet) 1 else 0)
            restoration.emulateSavedInstanceStateRestore()
            if (topBar) compose.onNodeWithContentDescription("返回场景列表").performClick() else systemBack()
            assertPosition(scenePosition)
        }
        navigate("同传")
        navigate("设置")
        compose.onNodeWithText("场景与术语").performClick()
        assertPosition(scenePosition)
    }

    /** 保存状态只包含导航及滚动元数据，连已进入组合的 API 和场景输入框原文也不能被收集。 */
    @Test fun savedStateExcludesKeysAndBusinessDrafts() {
        show()
        scrollTo("翻译 API", 0)
        compose.onNodeWithText("翻译 API").performClick()
        compose.runOnIdle {
            state = state.copy(settings = state.settings.copy(api = state.settings.api.copy(
                key = "禁止持久化的测试密钥", model = "禁止持久化的测试模型", url = "https://draft.example/v1")))
        }
        scrollTo("API 密钥", if (tablet) 1 else 0)
        assertSavedStateExcludes("禁止持久化的测试密钥", "禁止持久化的测试模型", "https://draft.example/v1")
        systemBack()
        scrollTo("场景与术语", 0)
        compose.onNodeWithText("场景与术语").performClick()
        compose.runOnIdle {
            state = state.copy(scenes = state.scenes.copy(editor = SceneEditorUiState(
                name = "禁止持久化的场景草稿", background = "禁止持久化的背景草稿")))
        }
        scrollTo("场景名称", if (tablet) 1 else 0)
        assertSavedStateExcludes("禁止持久化的场景草稿", "禁止持久化的背景草稿")
    }

    /** 以自适应像素密度在任意测试设备提供指定逻辑窗口，不更改设备显示或方向设置。 */
    private fun show() {
        state = state.copy(
            models = ModelsUiState(items = (1..10).map {
                ModelUiState("测试模型-$it", "测试模型 $it", "隔离模型说明", "中文", "1 MB", "测试许可", "内存目录")
            }),
            scenes = ScenesUiState(items = (1..12).map { SceneUiState("测试场景-$it", "测试场景 $it", "测试背景", 0, "中文") }),
        )
        val actions = SimulSubActions(
            onNavigate = { state = state.copy(destination = it) },
            onEditScene = { id ->
                state = state.copy(scenes = state.scenes.copy(editor = SceneEditorUiState(id = id, name = "编辑测试场景",
                    terms = (1..12).map { TermUiState("测试术语-$it", "测试原文 $it", "测试译文 $it") })))
            },
            onCloseSceneEditor = { state = state.copy(scenes = state.scenes.copy(editor = null)) },
            onDiscardApiDraft = {
                discarded++
                state = state.copy(settings = state.settings.copy(api = TranslationApiUiState()))
            },
        )
        restoration.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            savedStateRegistry = LocalSaveableStateRegistry.current!!
            BoxWithConstraints {
                val size = if (tablet) {
                    if (resized) DpSize(960.dp, 400.dp) else DpSize(1040.dp, 440.dp)
                } else {
                    if (resized) DpSize(420.dp, 500.dp) else DpSize(360.dp, 560.dp)
                }
                val density = minOf(constraints.maxWidth / size.width.value, constraints.maxHeight / size.height.value)
                CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                    Box(Modifier.requiredSize(size.width, size.height)) { SimulSubApp(state, actions) }
                }
            }
        }
        compose.waitForIdle()
        pageLists().assertCountEquals(if (tablet) 2 else 1)
        compose.onAllNodes(isSelectable() and (hasText("同传") or hasText("字幕") or hasText("设置"))).assertCountEquals(3)
    }

    /** 滚到真实下方入口并略微回移，覆盖条目内部偏移和双列独立状态。 */
    private fun scrollHome(): ScrollPosition {
        val labels = if (tablet) listOf("外观", "关于") else listOf("关于")
        labels.forEachIndexed { index, label ->
            pageLists()[index].performScrollToNode(hasText(label))
            compose.onNodeWithText(label).performScrollTo()
            val adjustment = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.height / 4f
            pageLists()[index].performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -adjustment) }
            compose.onNodeWithText(label).assertIsDisplayed()
        }
        val position = scrollPosition(labels)
        assertTrue("每个受测列表必须确实离开顶部", position.scroll.all { it > 0f })
        return position
    }

    /** 等待淡出完成，确保测试包含首页真正退出组合后的状态生命周期。 */
    private fun openAbout() {
        compose.onNodeWithText("关于").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("返回设置").assertIsDisplayed()
        compose.onNodeWithText("首次配置引导").assertDoesNotExist()
    }

    /** 调用 Activity 实际分发器，沿用根组件注册的系统返回处理。 */
    private fun systemBack() {
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** 只选择真实懒列表，避免把平板导航侧栏当成设置正文。 */
    private fun pageLists() = compose.onAllNodes(hasScrollToIndexAction())

    /** 使用实际懒列表语义定位目标，列号由页面布局决定。 */
    private fun scrollTo(label: String, column: Int) {
        compose.waitForIdle()
        pageLists()[column].performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
    }

    /** 点击生产主导航并等待退出动画完成。 */
    private fun navigate(label: String) {
        compose.onNode(isSelectable() and hasText(label)).performClick()
        compose.waitForIdle()
    }

    /** 检查真实根注册表导出的嵌套保存值，而非只检查控制器或测试夹具。 */
    private fun assertSavedStateExcludes(vararg drafts: String) {
        val saved = compose.runOnIdle { savedStateRegistry.performSave().toString() }
        drafts.forEach { assertFalse("业务原文不能进入保存状态：$it", saved.contains(it)) }
    }

    /** 同时记录列表滚动语义和实际入口边界，避免只验证进入的页码或列表索引。 */
    private fun scrollPosition(labels: List<String>): ScrollPosition {
        compose.waitForIdle()
        return ScrollPosition(
            pageLists().fetchSemanticsNodes().map { it.config[SemanticsProperties.VerticalScrollAxisRange].value() },
            labels.associateWith { compose.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot },
        )
    }

    /** 返回后直接断言，不执行任何定位滚动；允许一像素的布局舍入误差。 */
    private fun assertPosition(expected: ScrollPosition) {
        compose.waitForIdle()
        pageLists().assertCountEquals(expected.scroll.size)
        val actual = scrollPosition(expected.anchors.keys.toList())
        expected.scroll.zip(actual.scroll).forEach { (before, after) ->
            assertEquals("返回后实际滚动值必须保持", before, after, 1f)
        }
        expected.anchors.forEach { (label, before) ->
            val after = actual.anchors.getValue(label)
            assertEquals("入口纵向位置必须保持：$label", before.top, after.top, 1f)
            assertEquals("入口横向位置必须保持：$label", before.left, after.left, 1f)
        }
    }

    /** 快照仅用于断言，既不回写 UI，也不进入应用保存状态。 */
    private data class ScrollPosition(val scroll: List<Float>, val anchors: Map<String, Rect>)
}
