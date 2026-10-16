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
    private var screenCase = "normal"
    /** 真实前台窗口隐藏系统栏后，字幕也必须到屏幕底部，且草稿与工具栏不能恢复抖动。 */
    @Test fun immersiveOverlayReachesScreenBottomWithoutJitter() {
        screenCase = "immersive"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wm = context.getSystemService(WindowManager::class.java)
        androidx.test.core.app.ActivityScenario.launch(androidx.activity.ComponentActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    activity.window.decorView.setBackgroundColor(0xFF2D3436.toInt())
                    activity.window.insetsController!!.hide(android.view.WindowInsets.Type.systemBars())
                }
                dismissImmersiveEducation()
                awaitStableArea(wm, false)
                assertEquals("隐藏系统栏后可用区域必须抵达物理屏幕底边",
                    wm.currentWindowMetrics.bounds.bottom, captionWindowArea(wm.currentWindowMetrics).bottom)
                verifyBottomOverlayNeverDrawsShiftedFinalBaseline()
            } finally {
                scenario.onActivity { it.window.insetsController!!.show(android.view.WindowInsets.Type.systemBars()) }
            }
        }
    }

    /** 首次全屏会出现系统教学并给整屏加暗；只确认已观察到的系统按钮，避免把它当作字幕颜色变化。 */
    private fun dismissImmersiveEducation() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val end = System.currentTimeMillis() + 5_000
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try {
            while (System.currentTimeMillis() < end) {
                val roots = listOfNotNull(automation.rootInActiveWindow) + automation.windows.mapNotNull { it.root }
                for (root in roots.filter { it.packageName?.toString() in listOf("android", "com.android.systemui") }) {
                    val button = listOf("Got it", "知道了").asSequence()
                        .flatMap { root.findAccessibilityNodeInfosByText(it).asSequence() }
                        .firstOrNull { it.isClickable }
                    if (button != null) {
                        assertTrue("全屏教学确认按钮应可操作", button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                        return
                    }
                }
                Thread.sleep(100)
            }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
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
        lateinit var toolbar: android.view.View
        val toolbarDraws = java.util.concurrent.atomic.AtomicInteger()
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
                toolbar = view.takeToolbarForWindow()
                toolbar.viewTreeObserver.addOnDrawListener { toolbarDraws.incrementAndGet() }
                toolbarWindow = CaptionToolbarWindow(wm, toolbar)
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
                    view.setReserveDraftSpace(index % 2 == 0)
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
                    screenshot("window-$index")
                }
            }
            assertTrue(frames.size >= examples.size + 1)
            // 阶段标记可能先记录上一份排版的绘制；长文换短文后还须等待系统应用新窗口位置。
            // 每一帧基线断言仍保留，不能把尚未完成的文本重排误算为点击工具栏造成的位移。
            val stableWindow = awaitStableWindow(view, params, areaTop, expected)
            screenshot("window-before-toolbar")
            val stablePixels = captionPixels(view)
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
                if (click == 0) {
                    awaitToolbar(toolbar, toolbarDraws)
                    Thread.sleep(400)

                }
                instrumentation.runOnMainSync {
                    view.getLocationOnScreen(screenLocation)
                    assertEquals("点击展开／收起只能改变工具栏自己的窗口", stableWindow,
                        listOf(screenLocation[0], screenLocation[1], view.width, view.height))
                }
                assertTrue("点击字幕不能绘制偏移帧：$frames", frames.all { it.second == expected })
                if (click == 0) {
                    screenshot("window-toolbar-expanded")
                    val after = captionPixels(view, stablePixels.first)
                    val beforeGlyph = glyphMask(stablePixels.second)
                    val afterGlyph = glyphMask(after.second)
                    assertEquals("工具栏展开不能移动正式字幕字形边界",
                        glyphBounds(beforeGlyph, stablePixels.first.width()), glyphBounds(afterGlyph, stablePixels.first.width()))
                    // 独立浮层不会经过字幕根视图，按住工具栏超过隐藏周期仍应保持可见。
                    instrumentation.runOnMainSync {
                        val now = android.os.SystemClock.uptimeMillis()
                        val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, 10f, 10f, 0)
                        toolbar.dispatchTouchEvent(down); down.recycle()
                    }
                    Thread.sleep(4_200)
                    instrumentation.runOnMainSync {
                        assertTrue("手指按住独立工具栏时不能自动隐藏", toolbar.isShown)
                        val now = android.os.SystemClock.uptimeMillis()
                        val cancel = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_CANCEL, 10f, 10f, 0)
                        toolbar.dispatchTouchEvent(cancel); cancel.recycle()
                    }
                }
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

    /** 独立工具栏必须实际绘制为可触控窗口，不能仅凭字幕角标判定它已经出现。 */
    private fun awaitToolbar(toolbar: android.view.View, draws: java.util.concurrent.atomic.AtomicInteger) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val end = System.currentTimeMillis() + 3_000
        var visible = false
        var detail = ""
        while (!visible && System.currentTimeMillis() < end) {
            instrumentation.runOnMainSync {
                val area = android.graphics.Rect()
                val minimum = (48 * toolbar.resources.displayMetrics.density).toInt()
                visible = toolbar.isAttachedToWindow && toolbar.isShown && toolbar.getGlobalVisibleRect(area) &&
                    area.width() >= minimum && area.height() >= minimum && draws.get() > 0
                detail = "测量=${toolbar.measuredWidth}x${toolbar.measuredHeight}，实际=${toolbar.width}x${toolbar.height}，显示=$area，附着=${toolbar.isAttachedToWindow}，绘制=${draws.get()}，可见=${toolbar.visibility}，透明=${toolbar.alpha}，坐标=${toolbar.left},${toolbar.top}，平移=${toolbar.translationX},${toolbar.translationY}，裁剪=${toolbar.clipBounds}"
            }
            if (!visible) Thread.sleep(16)
        }
        assertTrue("独立字幕工具栏未实际可见：$detail", visible)
        val position = IntArray(2)
        instrumentation.runOnMainSync { toolbar.getLocationOnScreen(position) }
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "工具栏运行测量：$detail；屏幕位置=${position.toList()}；请求=${toolbar.layoutParams}\n")
        })
    }

    /** 提取正式白字核心，缩放角标及阴影的合法变化不属于文字移动。 */
    private fun glyphMask(pixels: IntArray): IntArray {
        val result = IntArray(pixels.size) { index ->
            val color = pixels[index]
            if (android.graphics.Color.red(color) >= 200 && android.graphics.Color.green(color) >= 200 &&
                android.graphics.Color.blue(color) >= 200) 1 else 0
        }
        assertTrue("字形比较区域必须包含正式白字", result.count { it == 1 } > 100)
        return result
    }

    /** 字形边界必须逐像素相等，独立于合成器对抗锯齿边缘的颜色取整。 */
    private fun glyphBounds(pixels: IntArray, width: Int): android.graphics.Rect {
        val result = android.graphics.Rect(width, pixels.size / width, 0, 0)
        pixels.forEachIndexed { index, value ->
            if (value == 1) {
                val x = index % width
                val y = index / width
                result.left = minOf(result.left, x); result.top = minOf(result.top, y)
                result.right = maxOf(result.right, x + 1); result.bottom = maxOf(result.bottom, y + 1)
            }
        }
        return result
    }

    /** 在同一屏幕矩形读取正文像素，防止坐标缓存正确但合成层文字实际移动。 */
    private fun captionPixels(view: CaptionOverlayView, fixed: android.graphics.Rect? = null): Pair<android.graphics.Rect, IntArray> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bounds = android.graphics.Rect()
        instrumentation.runOnMainSync {
            val text = ((view.getChildAt(0) as FrameLayout).getChildAt(0) as LinearLayout).getChildAt(0) as TextView
            val location = IntArray(2)
            text.getLocationOnScreen(location)
            bounds.set(fixed ?: android.graphics.Rect(location[0], location[1], location[0] + text.width, location[1] + text.height))
        }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val pixels = IntArray(bounds.width() * bounds.height())
            bitmap.getPixels(pixels, 0, bounds.width(), bounds.left, bounds.top, bounds.width(), bounds.height())
            return bounds to pixels
        } finally { bitmap.recycle() }
    }

    /** 导出同一正式末行在工具栏开合前后的实际画面，避免只依赖窗口坐标。 */
    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "qa-first-release-fixes/$screenCase/$name.png")
        file.parentFile!!.mkdirs()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    /** 只在当前基线和请求坐标均已落到屏幕、且连续八次不变后记录点击前窗口；错误绘制仍由全帧断言捕获。 */
    private fun awaitStableWindow(view: CaptionOverlayView, params: WindowManager.LayoutParams,
                                  areaTop: Int, expectedBaseline: Int): List<Int> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val end = System.currentTimeMillis() + 5_000
        var previous: List<Int>? = null
        var stable = 0
        var current = emptyList<Int>()
        while (stable < 8) {
            instrumentation.runOnMainSync {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                current = listOf(location[0], location[1], view.width, view.height)
                val aligned = location[1] == areaTop + params.y &&
                    location[1] + view.finalLineBaselineInWindowPx() == expectedBaseline
                stable = if (aligned && current == previous) stable + 1 else 0
                previous = current
            }
            assertTrue("重排后窗口仍未定位完成：实际=$current，请求Y=${params.y}，目标基线=$expectedBaseline",
                System.currentTimeMillis() < end)
            Thread.sleep(16)
        }
        android.util.Log.i("CaptionWindowAnchorTest", "点击前字幕窗口已稳定：$current；正式末行基线=$expectedBaseline")
        return current
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
