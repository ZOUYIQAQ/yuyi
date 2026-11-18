package io.github.korenkrita.simulsub

import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 只验证自动宽度的排版、预览和设置，不录音、不联网、不改用户业务数据。 */
class CaptionAutoWidthDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** 零宽度随最新内容收放，长句不越界，切回固定宽度后不再随内容变化。 */
    @Test fun zeroWidthFitsContentAndFixedWidthRemainsFixed() {
        compose.runOnIdle {
            val view = CaptionOverlayView(compose.activity, false)
            view.setFinalOnly(false)
            view.takeToolbarForWindow()
            view.applyStyle(18f, 0.8f)
            view.setCardWidth(0)
            view.setLines(listOf(CaptionOverlayView.Line("花开", null, true)), null)
            val short = measure(view, 900)
            assertTrue("短句不能占满可用宽度：$short", short in 1 until 900)
            view.setLines(listOf(CaptionOverlayView.Line("花朵绽放出来吧，这是更长的字幕", null, true)), null)
            val longer = measure(view, 900)
            assertTrue("内容变长时应展开：$short → $longer", longer > short)
            view.setLines(listOf(CaptionOverlayView.Line("超长字幕".repeat(50), null, true)), null)
            assertEquals("长句仍服从屏幕上限", 900, measure(view, 900))
            view.setLines(listOf(CaptionOverlayView.Line("花开", null, true)), null)
            assertEquals("旧长句不能让宽度一直撑开", short, measure(view, 900))
            view.setCardWidth(640)
            view.setLines(listOf(CaptionOverlayView.Line("短句", null, true)), null)
            assertEquals(640, measure(view, 900))
            view.setLines(listOf(CaptionOverlayView.Line("超长字幕".repeat(50), null, true)), null)
            assertEquals(640, measure(view, 900))
        }
    }

    /** 自动宽度必须计入小号原文、临时字幕和耗时信息，隐藏后可重新缩小。 */
    @Test fun bilingualDraftAndStatusParticipateWithoutStaleWidth() {
        compose.runOnIdle {
            val view = CaptionOverlayView(compose.activity, false)
            view.takeToolbarForWindow()
            view.applyStyle(18f, 0.8f)
            view.setCardWidth(0)
            view.setFinalOnly(false)
            view.setShown(true, true)
            val small = CaptionOverlayView.Line("花开", null, true)
            view.setLines(listOf(small), null)
            val base = measure(view, 900)
            view.setLines(listOf(CaptionOverlayView.Line("花开", "花が咲くでしょう～ほかとは違う花", true)), null)
            assertTrue("原文使用实际小字号参与测量", measure(view, 900) > base)
            view.setLines(listOf(small, CaptionOverlayView.Line("正在听到的临时字幕内容", null, false)), null)
            assertTrue("临时字幕也需包在背景内", measure(view, 900) > base)
            view.setLines(listOf(small), "识别 120ms · 翻译 320ms")
            assertTrue("耗时信息不能溢出窄黑框", measure(view, 900) > base)
            view.setLines(listOf(small), null)
            assertEquals("消失的原文、草稿和耗时不能留下宽度", base, measure(view, 900))
        }
    }

    /** 设置预览在横竖画幅里均复用真实字幕测量，固定比例仍保持原行为。 */
    @Test fun previewUsesTheSameAutomaticWidthAndOrientationBounds() {
        compose.runOnIdle {
            val stage = SubtitlePreviewStage(compose.activity)
            val style = SubtitleStyleUiState(widthFraction = 0f)
            stage.render(style, false, false)
            stage.captionView.setLines(listOf(CaptionOverlayView.Line("花开", null, true)), null)
            stage.measure(exact(600), exact(360))
            val portrait = stage.captionView.measuredWidth
            assertTrue("零值不能按10%固定宽度解释", portrait > 1)
            stage.captionView.setLines(listOf(CaptionOverlayView.Line("很长的预览字幕".repeat(80), null, true)), null)
            stage.measure(exact(600), exact(360))
            assertTrue("预览应随长句展开", stage.captionView.measuredWidth > portrait)
            val portraitMax = stage.captionView.measuredWidth
            stage.render(style, true, false)
            stage.captionView.setLines(listOf(CaptionOverlayView.Line("很长的预览字幕".repeat(80), null, true)), null)
            stage.measure(exact(600), exact(360))
            assertTrue("横屏画幅允许更宽字幕", stage.captionView.measuredWidth >= portraitMax)
            val landscapeMax = stage.captionView.measuredWidth
            stage.render(style.copy(widthFraction = 0.8f), true, false)
            stage.measure(exact(600), exact(360))
            assertEquals("固定比例仍按虚拟观看宽度排版", landscapeMax * 0.8f, stage.captionView.measuredWidth.toFloat(), 1f)
        }
    }

    /** 滑块真实可选零值，并显示明确的自动含义，再切回固定比例。 */
    @Test fun widthSliderCanSelectAutoAndReturnToFixed() {
        var style by mutableStateOf(SubtitleStyleUiState())
        compose.setContent { SimulSubTheme(SettingsUiState()) {
            SubtitleSettings(style, SimulSubActions(onSubtitleStyleChange = { style = it }), false, null)
        } }
        compose.onNodeWithContentDescription("暂时收起字幕预览").performClick()
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText("字幕宽度", substring = true))
        val slider = compose.onNodeWithContentDescription("字幕宽度").performScrollTo()
        slider.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0f) }
        compose.runOnIdle { assertEquals(0f, style.widthFraction, 0f) }
        slider.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "自动随内容变化"))
        slider.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.8f) }
        compose.runOnIdle { assertEquals(0.8f, style.widthFraction, 0.001f) }
    }

    /** 真正经控制器保存再重建，零值不能重新被旧的40%下限覆盖。 */
    @Test fun zeroWidthSurvivesControllerSaveAndReload() {
        val base = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "自动宽度-${java.util.UUID.randomUUID()}"
        val app = object : android.app.Application() {
            /** 只复用包名，系统查询不写入用户配置。 */
            override fun getPackageName(): String = base.packageName
            /** 单例仓库仅复用只读系统上下文。 */
            override fun getApplicationContext(): android.content.Context = base.applicationContext
            /** 用唯一前缀隔离所有偏好。 */
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                base.getSharedPreferences("$namespace-$name", mode)
            /** 日志路径使用独立缓存。 */
            override fun getExternalFilesDir(type: String?): java.io.File = java.io.File(base.cacheDir, namespace)
        }
        compose.runOnIdle {
            val store = androidx.lifecycle.ViewModelStore()
            try {
                val controller = AppController(app)
                store.put("自动宽度", controller)
                controller.actions({}, {}, {}, {}).onSubtitleStyleChange(controller.state.value.settings.subtitle.copy(widthFraction = 0f))
                assertEquals(0f, Prefs(app).widthFrac, 0f)
                store.clear()
                val reopened = AppController(app)
                store.put("自动宽度", reopened)
                assertEquals(0f, reopened.state.value.settings.subtitle.widthFraction, 0f)
            } finally { store.clear() }
        }
    }

    /** 真浮窗随长短句改变触摸矩形和宽度，底部定稿末行基线逐帧保持不变。 */
    @Test fun actualOverlayChangesWidthWithoutMovingFinalBaseline() {
        val frames = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val widths = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val growth = mutableListOf<Int>()
        val heights = java.util.concurrent.CopyOnWriteArrayList<Int>()
        lateinit var view: CaptionOverlayView
        var host: CaptionWindowHost? = null
        var collect = false
        var shortWidth = 0
        var originalCenter = 0
        val area = captionWindowArea(compose.activity.getSystemService(WindowManager::class.java).currentWindowMetrics)
        try {
            compose.runOnIdle {
                view = CaptionOverlayView(compose.activity, true)
                view.takeToolbarForWindow()
                view.setFinalOnly(false)
                view.setReserveDraftSpace(true)
                view.applyStyle(18f, 0.8f)
                view.setCardWidth(0)
                view.setWindowHeightLimit(area.height())
                view.setMaxContentHeight((area.height() * CaptionService.MAX_CAPTION_FRAC).toInt(), 0)
                view.setLines(listOf(CaptionOverlayView.Line("花朵绽放出来吧", null, true, 1)), null)
                host = CaptionWindowHost(compose.activity, compose.activity.getSystemService(WindowManager::class.java), view,
                    CaptionBaselineAnchor()) { _, _, width, _ -> assertEquals(view.width, width) }
                view.viewTreeObserver.addOnDrawListener {
                    if (collect) {
                        frames += view.top + view.finalLineBaselineInWindowPx()
                        widths += view.width
                        heights += view.height
                    }
                }
                requireNotNull(host).place((area.width() - view.contentWidthPx(area.width())) / 2, area.height() - 100, 0, area.width(), area.height())
                requireNotNull(host).show()
            }
            Thread.sleep(400)
            compose.runOnIdle {
                shortWidth = view.width
                originalCenter = view.left + view.width / 2
                frames += view.top + view.finalLineBaselineInWindowPx()
                widths += view.width
                heights += view.height
                collect = true
                view.setLines(listOf(CaptionOverlayView.Line("花朵绽放出来吧，这是一段需要占满可用宽度并且换行的字幕".repeat(3), null, true, 2)), null)
            }
            Thread.sleep(500)
            compose.runOnIdle {
                assertTrue("真实浮窗应扩展", view.width > shortWidth)
                assertTrue("不能超出观看区域", view.left >= 0 && view.right <= area.width())
                assertEquals("字幕横向中心不因自动宽度漂移", originalCenter.toFloat(), (view.left + view.width / 2).toFloat(), 1f)
                growth += widths
                assertTrue("宽度展开同时增加换行时，高度也需要过渡：$heights",
                    heights.filter { it > heights.first() && it < view.height }.distinct().size >= 3)
                widths.clear(); widths += view.width
                view.setLines(listOf(CaptionOverlayView.Line("花开", null, true, 3)), null)
            }
            Thread.sleep(500)
            compose.runOnIdle {
                assertTrue("真实浮窗应重新收窄", view.width < shortWidth)
                assertTrue("应采集到实际变化的绘制帧", frames.size >= 3)
                assertTrue("自动宽度换行不能改变定稿基线：$frames", frames.max() - frames.min() <= 1)
                assertTrue("展开必须画出多个中间宽度：$growth", growth.filter { it > shortWidth && it < growth.last() }.distinct().size >= 3)
                assertTrue("展开不能反向抽动：$growth", growth.zipWithNext().all { (a, b) -> b >= a - 1 })
                assertTrue("收窄必须画出多个中间宽度：$widths", widths.filter { it > view.width && it < widths.first() }.distinct().size >= 3)
                assertTrue("收窄不能反向抽动：$widths", widths.zipWithNext().all { (a, b) -> b <= a + 1 })
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "宽度展开轨迹：$growth\n宽度收窄轨迹：$widths\n定稿基线轨迹：$frames\n")
                })
                // 真实拖动后只改横向位置，后续内容仍围绕放下位置伸缩。
                requireNotNull(host).moveTo(0, view.top)
                view.setLines(listOf(CaptionOverlayView.Line("拖动后依然正常自动展开", null, true, 4)), null)
            }
            Thread.sleep(300)
            compose.runOnIdle { assertTrue(view.left >= 0 && view.right <= area.width()) }
        } finally {
            compose.runOnIdle { host?.dismiss() }
        }
    }

    /** 使用真实原生测量规格，不根据期望结果手工改写最终尺寸。 */
    private fun measure(view: CaptionOverlayView, maxWidth: Int): Int {
        view.measure(View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST), exact(1200))
        return view.measuredWidth
    }

    /** 生成固定像素规格供预览宿主和高度预算使用。 */
    private fun exact(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
