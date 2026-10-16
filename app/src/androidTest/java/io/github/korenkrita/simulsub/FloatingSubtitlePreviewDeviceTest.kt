package io.github.korenkrita.simulsub

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 使用独立页面和内存状态测试真实原生渲染，不启动服务、不写业务偏好。 */
class FloatingSubtitlePreviewDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** 开关在实时模式可操作，定稿模式置灰且不丢选择；实际预览立即更新空槽背景。 */
    @Test fun draftReservationSwitchIsDisabledForFinalOnlyAndUpdatesNativePreview() {
        var style by mutableStateOf(SubtitleStyleUiState(reserveDraftSpace = false))
        var finalOnly by mutableStateOf(false)
        compose.setContent {
            SimulSubTheme(SettingsUiState()) {
                SubtitleSettings(style, SimulSubActions(onSubtitleStyleChange = { style = it }), false, null, finalOnly)
            }
        }
        val stage = nativeStage()
        compose.onNodeWithContentDescription("暂时收起字幕预览").performClick()
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText("预留临时字幕空间"))
        val toggle = compose.onNode(hasText("预留临时字幕空间") and isToggleable())
        toggle.performScrollTo().assertIsEnabled().assertIsOff()
        captureReservationState("switch-off")
        toggle.performClick()
        captureReservationState("switch-on")
        compose.runOnIdle { assertTrue(style.reserveDraftSpace); finalOnly = true }
        toggle.assertIsNotEnabled().assertIsOn()
        compose.onNodeWithText("关闭「仅显示定稿」后可调整。").assertIsDisplayed()
        captureReservationState("switch-disabled")
        compose.runOnIdle { assertTrue(style.reserveDraftSpace); finalOnly = false }
        toggle.assertIsEnabled().assertIsOn()
        compose.onNodeWithTag("subtitle-preview-restore").performClick()
        compose.runOnIdle {
            val current = nativeStageOnMain()
            assertNotSame("恢复预览应创建新的渲染视图", stage, current)
            val caption = current.captionView
            caption.setLines(listOf(CaptionOverlayView.Line("正式字幕", null, true)), null)
            caption.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            caption.layout(0, 0, caption.measuredWidth, caption.measuredHeight)
            val bitmap = android.graphics.Bitmap.createBitmap(caption.width, caption.height, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                caption.draw(android.graphics.Canvas(bitmap))
                assertTrue("预览需真正绘制空槽背景", android.graphics.Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height - 1)) > 0)
            } finally { bitmap.recycle() }
        }
    }

    /** 保存真实组件的整屏像素，供手机和平板逐图核对触控区域和置灰说明。 */
    private fun captureReservationState(name: String) {
        compose.waitForIdle()
        Thread.sleep(250)
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val directory = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "qa-draft-reserve").apply { mkdirs() }
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "未能截取当前界面" }
        try {
            java.io.File(directory, "$name.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { screenshot.recycle() }
    }

    /** 滚动不能带走预览；拖动、收起、恢复和离页都必须作用于真实视图。 */
    @Test fun scrollingDraggingCollapseAndLeavingHaveRealLifecycle() {
        var state by mutableStateOf(SimulSubUiState(destination = UiDestination.SETTINGS))
        compose.setContent { SimulSubApp(state, SimulSubActions()) }
        compose.onNodeWithText("字幕样式").performClick()
        val first = nativeStage()
        val before = compose.onNodeWithTag("subtitle-floating-preview").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        // 宽屏右栏承载样式设置；窄屏只有一个列表，均选择最后一个真实容器。
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText("字幕保留方式"))
        assertEquals(before, compose.onNodeWithTag("subtitle-floating-preview").assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("subtitle-preview-handle").performTouchInput {
            down(center); moveBy(Offset(-100f, -200f)); up()
        }
        val moved = compose.onNodeWithTag("subtitle-floating-preview").fetchSemanticsNode().boundsInRoot
        assertTrue("拖动必须实际移动预览", moved.top < before.top)
        compose.onNodeWithContentDescription("暂时收起字幕预览").performClick()
        compose.onNodeWithTag("subtitle-native-preview").assertDoesNotExist()
        compose.runOnIdle { assertFalse("收起后原生预览应脱离窗口", first.isAttachedToWindow) }
        compose.onNodeWithTag("subtitle-preview-restore").assertIsDisplayed().performClick()
        val restored = nativeStage()
        assertNotSame("恢复时应创建新的原生预览", first, restored)
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.onNodeWithTag("subtitle-floating-preview").assertDoesNotExist()
        compose.runOnIdle { assertFalse("离页后预览不能留在窗口", restored.isAttachedToWindow) }
        compose.onNodeWithText("字幕样式").performClick()
        compose.onNodeWithText("竖屏示例").assertIsDisplayed()
        compose.onNodeWithTag("subtitle-native-preview").assertIsDisplayed()
        compose.runOnIdle { assertFalse("预览不能启动真实字幕服务", CaptionService.running) }
    }

    /** 方向切换必须改变画幅、虚拟字幕宽高和缩放，而不只是替换标题。 */
    @Test fun orientationChangesWatchingFrameAndNativeCaptionGeometry() {
        showSettings(SubtitleStyleUiState(widthFraction = 0.42f, heightFraction = 0.2f))
        val stage = nativeStage()
        val portrait = compose.onNodeWithTag("subtitle-native-preview").fetchSemanticsNode().boundsInRoot
        var oldWidth = 0
        var oldHeight = 0
        var captionWidth = 0
        var captionHeight = 0
        compose.runOnIdle {
            oldWidth = stage.watchingWidthPx
            oldHeight = stage.watchingHeightPx
            captionWidth = stage.captionView.measuredWidth
            captionHeight = stage.captionView.captionHeightPx()
            assertTrue(portrait.width < portrait.height)
            assertEquals(oldWidth * 0.42f, captionWidth.toFloat(), 1f)
        }
        compose.onNodeWithContentDescription("切换为横屏预览").performClick()
        val landscape = compose.onNodeWithTag("subtitle-native-preview").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            assertSame(stage, nativeStageOnMain())
            assertTrue("横屏必须实际变成宽画幅", landscape.width > landscape.height)
            assertEquals(oldHeight, stage.watchingWidthPx)
            assertEquals(oldWidth, stage.watchingHeightPx)
            assertEquals(oldHeight * 0.42f, stage.captionView.measuredWidth.toFloat(), 1f)
            assertEquals(oldWidth * 0.2f, stage.captionView.captionHeightPx().toFloat(), 1f)
            assertNotEquals(captionWidth, stage.captionView.measuredWidth)
            assertNotEquals(captionHeight, stage.captionView.captionHeightPx())
            assertEquals(stage.captionView.scaleX, stage.captionView.scaleY, 0.0001f)
            assertEquals(18f, stage.captionView.textSp, 0.001f)
        }
        compose.onNodeWithContentDescription("切换为竖屏预览").performClick()
        compose.runOnIdle { assertEquals(oldWidth, stage.watchingWidthPx) }
    }

    /** 同一真实字幕视图实时接收所有样式和互斥保留规则，旧行数和最少句数不能叠加。 */
    @Test fun nativePreviewUpdatesStyleAndExclusiveRetentionWithoutPersistence() {
        var style by mutableStateOf(SubtitleStyleUiState(widthFraction = 0.42f, heightFraction = 0f, maxSentences = 2))
        compose.setContent { SimulSubTheme(SettingsUiState()) { FloatingSubtitlePreview(style) } }
        val stage = nativeStage()
        compose.runOnIdle {
            assertFalse(style.showOriginal)
            assertFalse(style.showStats)
            assertFalse(caption(stage).text.toString().contains("このV何者気になるV、他とは違うVだから"))
            assertEquals(View.GONE, status(stage).visibility)
            assertEquals(2, subtitlePreviewLines(style).count { it.isFinal })
            assertEquals(1, subtitlePreviewLines(style).count { !it.isFinal })
            assertEquals("自动正文高度必须与服务的四成上限一致", (stage.watchingHeightPx * CaptionService.MAX_CAPTION_FRAC).toInt(), caption(stage).maxHeight)
            assertEquals("耗时区必须与服务的两成上限一致", (stage.watchingHeightPx * 0.2f).toInt(), status(stage).maxHeight)
            style = style.copy(textSp = 30f, backgroundAlpha = 0.2f, paletteId = "paper", widthFraction = 0.8f,
                heightFraction = 0.2f, showOriginal = true, showStats = true, limitMode = CaptionLimitMode.CHARACTERS,
                keepChars = 10, keepSentences = 10, maxLines = 1)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertSame(stage, nativeStageOnMain())
            assertEquals(30f, stage.captionView.textSp, 0.001f)
            assertEquals(0.2f, stage.captionView.bgAlpha, 0.001f)
            assertEquals("paper", stage.captionView.colors.id)
            assertEquals(stage.watchingWidthPx * 0.8f, stage.captionView.measuredWidth.toFloat(), 1f)
            assertEquals(stage.watchingHeightPx * 0.2f, stage.captionView.captionHeightPx().toFloat(), 1f)
            assertEquals(10, subtitlePreviewLines(style).sumOf { it.zh.codePointCount(0, it.zh.length) })
            assertTrue("字数额度只剩草稿时，原文应显示在背景内的临时区域", draftSource(stage).text.toString().contains("このV何者気になるV、他とは違うVだから"))
            assertEquals(View.VISIBLE, status(stage).visibility)
            style = style.copy(limitMode = CaptionLimitMode.SENTENCES, maxSentences = 5, keepChars = 10)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("句数模式只统计定稿且不叠加十字阈值", 5, subtitlePreviewLines(style).count { it.isFinal }) }
    }

    /** 真实预览切换仅定稿时移除外部槽，恢复后复用同一个原生视图和透明区域。 */
    @Test fun previewSharesDraftSlotAndFinalOnlyRules() {
        var finalOnly by mutableStateOf(false)
        val style = SubtitleStyleUiState(maxSentences = 2, heightFraction = 0.2f, showOriginal = true)
        compose.setContent { SimulSubTheme(SettingsUiState()) { FloatingSubtitlePreview(style, finalOnly) } }
        val stage = nativeStage()
        var slotHeight = 0
        var cardHeight = 0
        compose.runOnIdle {
            val view = stage.captionView
            slotHeight = view.draftSlotHeightPx()
            cardHeight = view.measureCardHeight(view.width)
            assertTrue(slotHeight > 0)
            assertEquals(cardHeight + slotHeight, view.height)
            assertEquals(view.getChildAt(0).bottom + (view.getChildAt(1).layoutParams as LinearLayout.LayoutParams).topMargin, view.getChildAt(1).top)
            assertNull(view.getChildAt(1).background)
            assertFalse("草稿不得混入正式正文", caption(stage).text.contains("… "))
            assertTrue(draftSource(stage).text.contains("このV何者気になるV、他とは違うVだから"))
            finalOnly = true
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertSame(stage, nativeStageOnMain())
            assertEquals(0, stage.captionView.draftSlotHeightPx())
            assertEquals(cardHeight, stage.captionView.height)
            assertTrue(subtitlePreviewLines(style, true).all { it.isFinal })
            finalOnly = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertSame(stage, nativeStageOnMain())
            assertEquals(slotHeight, stage.captionView.draftSlotHeightPx())
            assertEquals(cardHeight + slotHeight, stage.captionView.height)
        }
    }

    /** 放大仅原大裁切，不改变真实折行宽度、固定高度或字号，返回后画幅比例恢复。 */
    @Test fun enlargedCaptionKeepsWatchingLayoutAndFontUnchanged() {
        showSettings(SubtitleStyleUiState(widthFraction = 0.42f, heightFraction = 0.2f))
        val stage = nativeStage()
        val width = stage.captionView.measuredWidth
        val height = stage.captionView.captionHeightPx()
        val wholeScale = stage.captionView.scaleX
        compose.onNodeWithContentDescription("放大字幕局部").performClick()
        compose.runOnIdle {
            assertEquals(width, stage.captionView.measuredWidth)
            assertEquals(height, stage.captionView.captionHeightPx())
            assertEquals(18f, stage.captionView.textSp, 0.001f)
            assertEquals(1f, stage.captionView.scaleX, 0.001f)
            assertEquals(stage.captionView.scaleX, stage.captionView.scaleY, 0.001f)
            assertEquals("局部绘制不能越界遮住工具栏", android.graphics.Rect(0, 0, stage.width, stage.height), stage.clipBounds)
            assertTrue("原大观察应比完整画幅更清楚", stage.captionView.scaleX > wholeScale)
        }
        compose.onNodeWithContentDescription("返回完整观看画面").performClick()
        val frame = compose.onNodeWithTag("subtitle-native-preview").fetchSemanticsNode().boundsInRoot
        assertTrue(frame.width < frame.height)
        compose.runOnIdle { assertEquals(wholeScale, stage.captionView.scaleX, 0.001f) }
    }

    /** 手机展开默认可见，收起预览后所有保留设置依然可以操作。 */
    @Test fun phoneControlsRemainReachable() = verifyWindow(360, 640, 1f, false)

    /** 平板两栏保持独立滚动，预览仍限制在页面内且可以恢复。 */
    @Test fun tabletControlsRemainReachable() = verifyWindow(1000, 600, 1f, true)

    /** 两倍字号不能挤掉方向和收起入口，设置可以继续修改。 */
    @Test fun largeTextControlsRemainReachable() = verifyWindow(360, 640, 2f, false)

    /** 低矮横屏中，预览不占满视口且可以临时收起让出设置空间。 */
    @Test fun shortLandscapeControlsRemainReachable() = verifyWindow(640, 240, 2f, false)

    /** 未拖动时，窗口宽窄切换应重新选择默认角落，不能把默认位置当成用户位置。 */
    @Test fun untouchedPreviewFollowsDefaultCornerWhenWindowChanges() {
        var width by mutableStateOf(360)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.3f, 1f)) {
                SimulSubTheme(SettingsUiState()) {
                    Box(Modifier.requiredSize(width.dp, 740.dp).testTag("preview-test-page")) {
                        FloatingSubtitlePreview(SubtitleStyleUiState())
                    }
                }
            }
        }
        listOf(360, 1040, 360).forEach { windowWidth ->
            compose.runOnIdle { width = windowWidth }
            val page = compose.onNodeWithTag("preview-test-page").fetchSemanticsNode().boundsInRoot
            val panel = compose.onNodeWithTag("subtitle-floating-preview").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals("未自定义的位置应跟随窗口选择左右角落",
                if (windowWidth >= 600) page.left else page.right - panel.width, panel.left, 1f)
            assertEquals("尺寸改变后默认位置仍然贴底", page.bottom, panel.bottom, 1f)
        }
    }

    /** 真实拖动后不因切换宽窄屏恢复默认角落；缩窗收敛后的坐标在放大时继续保留。 */
    @Test fun draggedPreviewKeepsCustomPositionAndClampsOnResize() {
        var width by mutableStateOf(1040)
        var height by mutableStateOf(740)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.3f, 1f)) {
                SimulSubTheme(SettingsUiState()) {
                    Box(Modifier.requiredSize(width.dp, height.dp).testTag("preview-test-page")) {
                        FloatingSubtitlePreview(SubtitleStyleUiState())
                    }
                }
            }
        }
        val initial = previewRelativeOffset()
        compose.onNodeWithTag("subtitle-preview-handle").performTouchInput {
            down(center); moveBy(Offset(70f, -60f)); up()
        }
        val dragged = previewRelativeOffset()
        assertTrue("平板拖动应从左下向右上实际移动", dragged.x > initial.x && dragged.y < initial.y)
        compose.onNodeWithContentDescription("暂时收起字幕预览").performClick()
        assertEquals("收起不能重置自定义位置", dragged, previewRelativeOffset())
        compose.onNodeWithTag("subtitle-preview-restore").performClick()
        assertEquals("恢复不能重置自定义位置", dragged, previewRelativeOffset())
        compose.runOnIdle { width = 1200; height = 900 }
        assertEquals("可用空间增大时必须保留自定义坐标", dragged, previewRelativeOffset())
        compose.runOnIdle { width = 360; height = 300 }
        val page = compose.onNodeWithTag("preview-test-page").fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("subtitle-floating-preview").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val clamped = previewRelativeOffset()
        assertTrue("缩窗后整个面板必须处于页面内", panel.left >= page.left - 1f && panel.top >= page.top - 1f &&
            panel.right <= page.right + 1f && panel.bottom <= page.bottom + 1f)
        assertTrue("缩窗必须同时收敛横纵坐标", clamped.x < dragged.x && clamped.y < dragged.y)
        compose.runOnIdle { width = 1040; height = 740 }
        assertEquals("放大不能跳回旧的越界坐标或默认角落", clamped, previewRelativeOffset())
    }

    /** 比较相对页面的真实像素坐标，避免测试宿主居中引起根坐标变化。 */
    private fun previewRelativeOffset(): Offset {
        val page = compose.onNodeWithTag("preview-test-page").fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("subtitle-floating-preview").fetchSemanticsNode().boundsInRoot
        return panel.topLeft - page.topLeft
    }

    /** 在限定窗口内实测预览边界和实际点击，不用断言控件存在代替可达性。 */
    private fun verifyWindow(width: Int, height: Int, fontScale: Float, wide: Boolean) {
        var style by mutableStateOf(SubtitleStyleUiState())
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                SimulSubTheme(SettingsUiState()) {
                    Box(Modifier.requiredSize(width.dp, height.dp).testTag("preview-test-page")) {
                        SubtitleSettings(style, SimulSubActions(onSubtitleStyleChange = { style = it }), wide, null)
                    }
                }
            }
        }
        val page = compose.onNodeWithTag("preview-test-page").fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("subtitle-floating-preview").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("宽屏默认左下，手机默认右下", if (width >= 600) page.left else page.right - panel.width, panel.left, 1f)
        assertEquals("预览默认贴住页面底部", page.bottom, panel.bottom, 1f)
        assertTrue("预览必须留在页面横向边界内", panel.left >= page.left && panel.right <= page.right)
        assertTrue("低矮窗口仍应保留设置空间", panel.height < page.height)
        compose.onNodeWithContentDescription("切换为横屏预览").performClick()
        compose.onNodeWithContentDescription("暂时收起字幕预览").performClick()
        val moveToTop = compose.onNodeWithTag("subtitle-floating-preview").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].first { it.label == "移到顶部" }
        compose.runOnIdle { assertTrue(moveToTop.action()) }
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText("显示句数"))
        compose.onNodeWithText("显示句数").performScrollTo().performClick()
        compose.onNode(hasText("1 句") and isSelectable()).performClick()
        compose.runOnIdle { assertEquals(1, style.maxSentences) }
        compose.onNodeWithTag("subtitle-preview-restore").performClick()
        compose.onNodeWithTag("subtitle-native-preview").assertIsDisplayed()
        compose.onNodeWithContentDescription("切换为竖屏预览").assertIsDisplayed()
    }

    /** 不经过控制器，直接呈现独立字幕设置，避免测试触碰用户偏好。 */
    private fun showSettings(style: SubtitleStyleUiState) {
        compose.setContent { SimulSubTheme(SettingsUiState()) { SubtitleSettings(style, SimulSubActions(), false, null) } }
    }

    /** 等待组合完成后在主线程获取真正挂载的原生预览。 */
    private fun nativeStage(): SubtitlePreviewStage {
        compose.waitForIdle()
        var stage: SubtitlePreviewStage? = null
        compose.runOnIdle { stage = nativeStageOnMain() }
        return requireNotNull(stage)
    }

    /** 从当前测试 Activity 查找原生视图，不把 Compose 占位节点当成真实渲染。 */
    private fun nativeStageOnMain(): SubtitlePreviewStage = requireNotNull(findStage(compose.activity.window.decorView))

    /** 递归查找真实原生容器，保留对渲染对象及生命周期的检查。 */
    private fun findStage(view: View): SubtitlePreviewStage? {
        if (view is SubtitlePreviewStage) return view
        if (view is ViewGroup) repeat(view.childCount) { index -> findStage(view.getChildAt(index))?.let { return it } }
        return null
    }

    /** 读取真实字幕正文，校验默认原文开关和样式更新。 */
    private fun caption(stage: SubtitlePreviewStage): TextView =
        ((stage.captionView.getChildAt(0) as FrameLayout).getChildAt(0) as LinearLayout).getChildAt(0) as TextView

    /** 读取真实耗时视图，验证隐藏不是只改变说明文字。 */
    private fun status(stage: SubtitlePreviewStage): TextView =
        ((stage.captionView.getChildAt(0) as FrameLayout).getChildAt(0) as LinearLayout).getChildAt(1) as TextView

    /** 读取统一背景内的草稿原文视图，验证其不混入正式正文。 */
    private fun draftSource(stage: SubtitlePreviewStage): TextView =
        (stage.captionView.getChildAt(1) as LinearLayout).getChildAt(0) as TextView
}
