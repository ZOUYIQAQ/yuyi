package io.github.korenkrita.simulsub

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** 使用真实系统悬浮窗口逐帧核对基线；不启动录音、不调用翻译、不改变业务配置。 */
class CaptionWindowAnchorDeviceTest {
    /** 真实前台窗口隐藏系统栏后，字幕也必须到屏幕底部，且草稿与工具栏不能恢复抖动。 */
    @Test fun immersiveOverlayReachesScreenBottomWithoutJitter() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wm = context.getSystemService(WindowManager::class.java)
        androidx.test.core.app.ActivityScenario.launch(androidx.activity.ComponentActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    activity.window.decorView.setBackgroundColor(0xFF2D3436.toInt())
                    activity.window.insetsController!!.hide(android.view.WindowInsets.Type.systemBars())
                }
                awaitStableArea(wm, false)
                assertEquals("隐藏系统栏后可用区域必须抵达物理屏幕底边",
                    wm.currentWindowMetrics.bounds.bottom, captionWindowArea(wm.currentWindowMetrics).bottom)
                verifyBottomOverlayNeverDrawsShiftedFinalBaseline()
            } finally {
                scenario.onActivity { it.window.insetsController!!.show(android.view.WindowInsets.Type.systemBars()) }
            }
        }
    }

    /** 底部放置后只更新字幕，生产绘制守卫必须在每一帧保持正式最后一行不动。 */
    @Test fun realBottomOverlayNeverDrawsShiftedFinalBaseline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wm = context.getSystemService(WindowManager::class.java)
        androidx.test.core.app.ActivityScenario.launch(androidx.activity.ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.window.insetsController!!.show(android.view.WindowInsets.Type.systemBars()) }
            awaitStableArea(wm, true)
            verifyBottomOverlayNeverDrawsShiftedFinalBaseline()
        }
    }

    /** 浮窗测量前等待前台宿主方向和系统栏稳定，不能继承上一测试的竖屏Activity过渡帧。 */
    private fun awaitStableArea(wm: WindowManager, barsVisible: Boolean) {
        val end = System.currentTimeMillis() + 5_000
        var previous: android.graphics.Rect? = null
        var stable = 0
        while (stable < 8) {
            val metrics = wm.currentWindowMetrics
            val area = captionWindowArea(metrics)
            val visible = metrics.windowInsets.isVisible(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
            stable = if (area == previous && visible == barsVisible) stable + 1 else 0
            previous = area
            assertTrue("前台方向与系统栏未稳定：$area", System.currentTimeMillis() < end)
            Thread.sleep(16)
        }
    }

    /** 复用同一逐帧和像素断言，分别在普通及沉浸前台宿主上验证生产定位器。 */
    private fun verifyBottomOverlayNeverDrawsShiftedFinalBaseline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("专用模拟器需已授权悬浮窗", Settings.canDrawOverlays(context))
        val wm = context.getSystemService(WindowManager::class.java)
        val frames = CopyOnWriteArrayList<Pair<Int, Int>>()
        val details = CopyOnWriteArrayList<String>()
        var phase = 0
        var attached = false
        lateinit var view: CaptionOverlayView
        lateinit var params: WindowManager.LayoutParams
        lateinit var guard: CaptionLayoutGuard
        lateinit var drawing: ViewTreeObserver.OnDrawListener
        lateinit var toolbarWindow: CaptionToolbarWindow
        val anchor = CaptionBaselineAnchor()
        lateinit var origin: CaptionWindowOrigin
        var availableHeight = 0
        var cardWidth = 0
        var areaTop = 0
        val screenLocation = IntArray(2)

        /** 只供真实窗口宿主应用生产定位器；每次记录同一份测量快照。 */
        fun position() {
            view.setCardWidth(cardWidth)
            val block = view.measureCaptionBlockHeight(cardWidth)
            val baseline = view.finalLineBaselinePx()
            params.y = anchor.position(availableHeight, baseline, availableHeight, block)
            guard.positioned(baseline, block)
            if (view.isAttachedToWindow) wm.updateViewLayout(view, params)
        }

        try {
            instrumentation.runOnMainSync {
                val metrics = wm.currentWindowMetrics
                val area = captionWindowArea(metrics)
                availableHeight = area.height()
                areaTop = area.top
                cardWidth = (metrics.bounds.width() * 0.85f).toInt()
                origin = CaptionWindowOrigin(areaTop)
                view = CaptionOverlayView(context, true).apply {
                    setFinalOnly(false); applyStyle(18f, 0.85f)
                    setWindowHeightLimit(availableHeight)
                    setMaxContentHeight(availableHeight * 2 / 5, availableHeight / 5)
                    setLines(listOf(CaptionOverlayView.Line("这个v是什么人？真是令人在意的v", null, true)), null)
                }
                params = WindowManager.LayoutParams(cardWidth, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
                toolbarWindow = CaptionToolbarWindow(wm, view.takeToolbarForWindow())
                view.onToolbarShown = { shown ->
                    toolbarWindow.update(shown, params.x, params.y, cardWidth,
                        view.measureCaptionBlockHeight(cardWidth), metrics.bounds.width(), availableHeight)
                }
                guard = CaptionLayoutGuard({ view.finalLineBaselinePx() }, { view.measureCaptionBlockHeight(cardWidth) }, ::position) {
                    view.getLocationOnScreen(screenLocation)
                    origin.aligned(screenLocation[1], params.y, view.finalLineBaselineInWindowPx(), anchor.baseline ?: 0)
                }
                drawing = ViewTreeObserver.OnDrawListener {
                    val text = ((view.getChildAt(0) as FrameLayout).getChildAt(0) as LinearLayout).getChildAt(0) as TextView
                    val location = IntArray(2)
                    text.getLocationOnScreen(location)
                    text.layout?.let { layout ->
                        frames += phase to (location[1] + text.baseline + layout.getLineBaseline(layout.lineCount - 1) -
                            layout.getLineBaseline(0) - text.scrollY)
                        details += "阶段=$phase，屏幕Y=${location[1]}，正文高=${text.height}/${text.measuredHeight}，排版高=${layout.height}，滚动=${text.scrollY}，首基线=${text.baseline}，末基线=${layout.getLineBaseline(layout.lineCount - 1)}，偏移=${view.finalLineBaselinePx()}，窗口Y=${params.y}，锚点=${anchor.baseline}"
                    }
                }
                view.viewTreeObserver.addOnPreDrawListener(guard)
                view.viewTreeObserver.addOnDrawListener(drawing)
                position()
                wm.addView(view, params)
                attached = true
            }
            awaitFrame(frames, 0)
            val expected = frames.first().second
            val examples = listOf(
                Triple("这个v是什么人？真是令人在意的v", "这个v是什么人？", null),
                Triple("这个v是什么人？真是令人在意的v", "临时译文继续更新。".repeat(50), null),
                Triple("这个v是什么人？真是令人在意的v", "", null),
                Triple("这个v是什么人？真是令人在意的v", "", "状态一行\n状态第二行"),
                Triple("较长正式字幕。".repeat(40), "", null),
                Triple("较长正式字幕。".repeat(40), "下句临时字幕", null),
                Triple("较长正式字幕。".repeat(40), "", null),
                Triple("短定稿", "", null),
            )
            examples.forEachIndexed { index, (final, draft, status) ->
                instrumentation.runOnMainSync {
                    phase = index + 1
                    view.setLines(buildList {
                        add(CaptionOverlayView.Line(final, null, true))
                        if (draft.isNotEmpty()) add(CaptionOverlayView.Line(draft, null, false))
                    }, status)
                }
                awaitFrame(frames, index + 1)
                Thread.sleep(80)
                assertTrue("底部悬浮窗出现偏移帧：${frames.toList()}；详情：$details", frames.all { it.second == expected })
                if (index in listOf(0, 1)) instrumentation.runOnMainSync {
                    view.getLocationOnScreen(screenLocation)
                    assertEquals("有草稿时字幕窗口必须真正贴到可用区域底部", areaTop + availableHeight,
                        screenLocation[1] + view.height)
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(android.graphics.Canvas(bitmap))
                    assertTrue("系统浮窗底部不能留下透明尾部",
                        android.graphics.Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height - 1)) > 0)
                }
                if (index in listOf(0, 1, 2)) {
                    val file = File(context.getExternalFilesDir(null), "qa-first-release-fixes/window-$index.png")
                    file.parentFile!!.mkdirs()
                    instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
            }
            assertTrue(frames.size >= examples.size + 1)
            var stableWindow: List<Int>? = null
            instrumentation.runOnMainSync {
                view.getLocationOnScreen(screenLocation)
                stableWindow = listOf(screenLocation[0], screenLocation[1], view.width, view.height)
            }
            repeat(6) { click ->
                instrumentation.runOnMainSync {
                    phase = examples.size + click + 1
                    val card = view.getChildAt(0)
                    val x = card.left + card.width / 2f
                    val y = card.top + card.height / 2f
                    val time = android.os.SystemClock.uptimeMillis()
                    val down = android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
                    val up = android.view.MotionEvent.obtain(time, time + 20, android.view.MotionEvent.ACTION_UP, x, y, 0)
                    view.dispatchTouchEvent(down); view.dispatchTouchEvent(up)
                    down.recycle(); up.recycle()
                    view.invalidate()
                }
                awaitFrame(frames, examples.size + click + 1)
                Thread.sleep(80)
                instrumentation.runOnMainSync {
                    view.getLocationOnScreen(screenLocation)
                    assertEquals("点击展开／收起只能改变工具栏自己的窗口", stableWindow,
                        listOf(screenLocation[0], screenLocation[1], view.width, view.height))
                }
                assertTrue("点击字幕不能绘制偏移帧：$frames", frames.all { it.second == expected })
            }
        } finally {
            instrumentation.runOnMainSync {
                if (attached && view.isAttachedToWindow) {
                    toolbarWindow.dismiss()
                    view.viewTreeObserver.removeOnPreDrawListener(guard)
                    view.viewTreeObserver.removeOnDrawListener(drawing)
                    wm.removeViewImmediate(view)
                }
            }
        }
    }

    /** 等待真实绘制回调而非固定睡眠猜测布局已完成，失败时有界退出。 */
    private fun awaitFrame(frames: List<Pair<Int, Int>>, phase: Int) {
        val end = System.currentTimeMillis() + 5_000
        while (frames.none { it.first == phase }) {
            assertTrue("等待第${phase}帧超时：$frames", System.currentTimeMillis() < end)
            Thread.sleep(16)
        }
    }
}
