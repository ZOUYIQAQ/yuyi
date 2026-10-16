package io.github.korenkrita.simulsub

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 约二十八秒的连续录屏场景，只驱动真实窗口内的生产字幕，不读取或保存用户配置。 */
@RunWith(AndroidJUnit4::class)
class CaptionAnimationShowcaseDeviceTest {
    /** 开头静止四秒，依次演示增句、滚入淘汰、黑框伸缩和一行草稿显隐。 */
    @Test fun showcaseProductionCaptionAnimations() {
        assertTrue("演示需要系统已启用动画；测试不会修改系统动画设置", ValueAnimator.areAnimatorsEnabled())
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var overlay: CaptionOverlayView
            lateinit var heading: TextView
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val host = FrameLayout(activity).apply { setBackgroundColor(0xFF344044.toInt()) }
                heading = TextView(activity).apply {
                    textSize = 22f
                    setTextColor(0xFFF5F1E8.toInt())
                    gravity = Gravity.CENTER
                    setPadding(12, 12, 12, 12)
                }
                host.addView(heading, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                ).apply { topMargin = px(host, 36) })
                overlay = CaptionOverlayView(activity, interactive = false).apply {
                    setFinalOnly(false)
                    setReserveDraftSpace(false)
                    setShown(false, false)
                    applyStyle(26f, 0.92f)
                    setWindowHeightLimit(activity.resources.displayMetrics.heightPixels)
                    setMaxContentHeight(px(this, 240), 0)
                    setCaptionHeight(0)
                    setLines(listOf(line(1)), null)
                }
                host.addView(overlay, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply {
                    val margin = px(host, 20)
                    setMargins(margin, 0, margin, px(host, 72))
                })
                activity.setContentView(host)
            }

            stage(heading, "准备录屏 · 正式字幕静止", 4_000) {}
            stage(heading, "增加第二句 · 黑框向上长高") {
                overlay.setLines(listOf(line(1), line(2)), null)
            }
            stage(heading, "增加第三句 · 黑框继续长高") {
                overlay.setLines(listOf(line(1), line(2), line(3)), null)
            }
            stage(heading, "第四句滚入 · 第一句退出") {
                overlay.setLines(listOf(line(2), line(3), line(4)), null)
            }
            stage(heading, "第五句滚入 · 第二句退出") {
                overlay.setLines(listOf(line(3), line(4), line(5)), null)
            }
            val shortHeight = onMain { overlay.captionHeightPx() }
            stage(heading, "黑框升高 · 正式字幕贴底") {
                overlay.setCaptionHeight(px(overlay, 220))
            }
            assertEquals("黑框应到达加高后的生产高度", onMain { px(overlay, 220) },
                onMain { overlay.captionHeightPx() })
            stage(heading, "黑框缩小 · 回到三句高度") {
                overlay.setCaptionHeight(0)
            }
            assertEquals("自动高度应恢复到三句的真实高度", shortHeight, onMain { overlay.captionHeightPx() })

            val formal = onMain { body(overlay) }
            val expectedBaseline = onMain { screenBaseline(formal) }
            val expectedPixels = onMain { capture(formal) }
            val baselines = java.util.concurrent.CopyOnWriteArrayList<Int>()
            val observer = ViewTreeObserver.OnDrawListener { baselines += screenBaseline(formal) }
            onMain { overlay.viewTreeObserver.addOnDrawListener(observer) }
            try {
                stage(heading, "临时字幕出现 · 正式文字不动") {
                    overlay.setLines(listOf(line(3), line(4), line(5),
                        CaptionOverlayView.Line("下一句正在听写", null, false)), null)
                }
                onMain {
                    val slot = overlay.getChildAt(1) as LinearLayout
                    val draft = slot.getChildAt(1) as CaptionTextView
                    assertEquals("临时字幕只占一行", 1, draft.lineCount)
                    assertEquals(View.VISIBLE, draft.visibility)
                }
                assertFormalPixels(formal, expectedPixels)
                stage(heading, "临时字幕消失 · 正式文字不动") {
                    overlay.setLines(listOf(line(3), line(4), line(5)), null)
                }
                assertFormalPixels(formal, expectedPixels)
                assertTrue("草稿显隐必须实际经过窗口绘制", baselines.isNotEmpty())
                assertTrue("草稿显隐所有绘制帧的正式末行都应保持原位：$baselines",
                    baselines.all { it == expectedBaseline })
                onMain {
                    val slot = overlay.getChildAt(1) as LinearLayout
                    assertEquals("草稿消失后隐藏字形但保留布局空间", View.INVISIBLE, slot.getChildAt(1).visibility)
                }
            } finally {
                onMain { overlay.viewTreeObserver.removeOnDrawListener(observer) }
                expectedPixels.recycle()
            }
        }
    }

    /** 标题只解释当前动作；等待主线程空闲后留出观看时间，不替代或放慢生产动画。 */
    private fun stage(heading: TextView, title: String, durationMs: Long = 3_000, update: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val start = SystemClock.uptimeMillis()
        onMain { heading.text = title; update() }
        // 生产滚动和高度动画约二百八十毫秒，等待真实窗口完成绘制后再进入静止观看段。
        Thread.sleep(650)
        instrumentation.waitForIdleSync()
        val remaining = durationMs - (SystemClock.uptimeMillis() - start)
        if (remaining > 0) Thread.sleep(remaining)
        instrumentation.waitForIdleSync()
    }

    /** 使用稳定片段编号与短句，窄屏放大字号时仍便于识别被淘汰的最老句。 */
    private fun line(id: Long) = CaptionOverlayView.Line(when (id) {
        1L -> "第一句：风吹过树梢"
        2L -> "第二句：阳光照进来"
        3L -> "第三句：我们向前走"
        4L -> "第四句：新风景到了"
        else -> "第五句：故事在继续"
    }, null, true, id)

    /** 读取黑框内真正参与生产滚动的正文控件，不创建替代字幕渲染器。 */
    private fun body(overlay: CaptionOverlayView): CaptionTextView =
        ((overlay.getChildAt(0) as FrameLayout).getChildAt(0) as LinearLayout).getChildAt(0) as CaptionTextView

    /** 以真实屏幕坐标核对最后一行字形基线，包括控件内部的原生滚动偏移。 */
    private fun screenBaseline(text: TextView): Int {
        val location = IntArray(2)
        text.getLocationOnScreen(location)
        val layout = checkNotNull(text.layout) { "正式字幕尚未完成排版" }
        return location[1] + text.baseline + layout.getLineBaseline(layout.lineCount - 1) -
            layout.getLineBaseline(0) - text.scrollY
    }

    /** 直接读取生产正文画面，只用于断言，不把位图显示在演示窗口。 */
    private fun capture(view: View): Bitmap =
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }

    /** 草稿变化后的正式字形应逐像素一致，防止基线没变但正文仍重播动画。 */
    private fun assertFormalPixels(formal: CaptionTextView, expected: Bitmap) {
        val actual = onMain { capture(formal) }
        try { assertTrue("临时字幕显隐不能改变正式字形", actual.sameAs(expected)) }
        finally { actual.recycle() }
    }

    /** 按当前窗口密度换算演示尺寸，不修改设备字号或用户配置。 */
    private fun px(view: View, dp: Int): Int = (view.resources.displayMetrics.density * dp).toInt()

    /** 主线程同步读写窗口状态，断言异常交回测试线程，避免测试制造并发更新。 */
    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
