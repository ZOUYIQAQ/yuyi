package io.github.korenkrita.simulsub

import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** 直接在系统底部悬浮窗中观察换行数改变；可同时录屏，不使用应用偏好或联网服务。 */
class CaptionBottomMotionDeviceTest {
    /** 使用系统注入的真实屏幕点击及拖动，验证透明触摸窗能操作字幕而非只直接调用控件。 */
    @Test fun transparentTouchWindowForwardsTapDragAndOutsideClicks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val wm = context.getSystemService(WindowManager::class.java)
        val outsideClicks = java.util.concurrent.atomic.AtomicInteger()
        val shown = java.util.concurrent.atomic.AtomicBoolean()
        val moved = java.util.concurrent.atomic.AtomicInteger()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContentView(TextView(activity).apply {
                    text = "下面的页面仍然可点击"; gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    setPadding(0, 160, 0, 0); setOnClickListener { outsideClicks.incrementAndGet() }
                })
            }
            Thread.sleep(500)
            val area = captionWindowArea(wm.currentWindowMetrics)
            lateinit var overlay: CaptionOverlayView
            lateinit var host: CaptionWindowHost
            val anchor = CaptionBaselineAnchor()
            var x = 20; var y = area.height()
            val width = (area.width() * 0.7f).toInt()
            try {
                instrumentation.runOnMainSync {
                    overlay = CaptionOverlayView(context, true).apply {
                        setFinalOnly(false); setReserveDraftSpace(true); applyStyle(18f, 0.9f)
                        setCardWidth(width); setWindowHeightLimit(area.height()); setMaxContentHeight(area.height() * 2 / 5, 0)
                        setLines(listOf(CaptionOverlayView.Line("花朵绽放出来吧", null, true)), null)
                    }
                    overlay.takeToolbarForWindow()
                    overlay.onToolbarShown = { shown.set(it) }
                    host = CaptionWindowHost(context, wm, overlay, anchor) { px, py, _, _ -> x = px; y = py }
                    overlay.onMove = { dx, dy ->
                        moved.incrementAndGet(); x += dx; y += dy; anchor.reset()
                        host.place(x, y, width, area.width(), area.height())
                    }
                    host.place(x, y, width, area.width(), area.height()); host.show()
                }
                Thread.sleep(500)
                injectGesture(area.centerX().toFloat(), (area.top + 200).toFloat())
                assertTrue("显示表面的透明区不能拦住下面页面", outsideClicks.get() > 0)
                val position = IntArray(2)
                instrumentation.runOnMainSync { overlay.getLocationOnScreen(position) }
                injectGesture(position[0] + width / 2f, position[1] + 30f)
                assertTrue("点击透明触摸窗口应打开字幕工具栏", shown.get())
                injectGesture(position[0] + width / 2f, position[1] + 30f, 0f, -100f)
                assertTrue("系统触摸事件应能拖动字幕", moved.get() > 0)
                // 系统设置属于另一个UID，验证Android 12起的透明覆盖限制不会阻断其他应用。
                val automation = instrumentation.uiAutomation
                automation.serviceInfo = automation.serviceInfo.apply {
                    flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                }
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                val rootDeadline = System.currentTimeMillis() + 5_000
                var settingsRoot: android.view.accessibility.AccessibilityNodeInfo? = null
                while (settingsRoot == null && System.currentTimeMillis() < rootDeadline) {
                    settingsRoot = (listOfNotNull(automation.rootInActiveWindow) + automation.windows.mapNotNull { it.root })
                        .firstOrNull { it.packageName?.toString() == "com.android.settings" }
                    if (settingsRoot == null) Thread.sleep(100)
                }
                val root = checkNotNull(settingsRoot) { "系统设置页应在期限内就绪" }
                assertEquals("com.android.settings", root.packageName.toString())
                val search = root.findAccessibilityNodeInfosByText("Search settings").firstOrNull()
                    ?: root.findAccessibilityNodeInfosByText("搜索设置").firstOrNull()
                    ?: throw AssertionError("测试设备设置页应有搜索入口")
                val bounds = android.graphics.Rect(); search.getBoundsInScreen(bounds)
                injectGesture(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                val deadline = System.currentTimeMillis() + 3_000
                var searching = false
                while (!searching && System.currentTimeMillis() < deadline) {
                    searching = (listOfNotNull(automation.rootInActiveWindow) + automation.windows.mapNotNull { it.root })
                        .any { it.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)?.className?.toString() == "android.widget.EditText" }
                    if (!searching) Thread.sleep(100)
                }
                assertTrue("透明区域必须允许点击另一应用的搜索入口", searching)
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            } finally { instrumentation.runOnMainSync { host.dismiss() } }
        }
    }

    /** 通过系统输入分发器发送屏幕坐标事件，不能绕过悬浮窗输入或触摸遮挡检查。 */
    private fun injectGesture(x: Float, y: Float, dx: Float = 0f, dy: Float = 0f) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val start = android.os.SystemClock.uptimeMillis()
        val steps = if (dx == 0f && dy == 0f) 0 else 6
        for (index in 0..steps + 1) {
            val action = when (index) { 0 -> MotionEvent.ACTION_DOWN; steps + 1 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val fraction = if (steps == 0) 0f else index.coerceAtMost(steps) / steps.toFloat()
            val event = MotionEvent.obtain(start, android.os.SystemClock.uptimeMillis(), action, x + dx * fraction, y + dy * fraction, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            Thread.sleep(30)
        }
        Thread.sleep(300)
    }
    /** 连续六行、五行和更短正文来回切换，字幕必须贴底且正式末行锚点保持不变。 */
    @Test fun wrappedCaptionTransitionsAtScreenBottom() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val wm = context.getSystemService(WindowManager::class.java)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var heading: TextView
            scenario.onActivity { activity ->
                activity.window.decorView.setBackgroundColor(0xFF344044.toInt())
                heading = TextView(activity).apply {
                    text = "贴底字幕 · 自动高度"
                    textSize = 20f; setTextColor(0xFFF5F1E8.toInt())
                    gravity = Gravity.CENTER; setPadding(0, 100, 0, 0)
                }
                activity.setContentView(heading)
            }
            Thread.sleep(600)
            val area = captionWindowArea(wm.currentWindowMetrics)
            val width = minOf((300 * context.resources.displayMetrics.density).toInt(), (area.width() * 0.9f).toInt())
            val anchor = CaptionBaselineAnchor()
            val frames = CopyOnWriteArrayList<Int>()
            val geometry = CopyOnWriteArrayList<String>()
            lateinit var overlay: CaptionOverlayView
            lateinit var host: CaptionWindowHost
            var attached = false
            val lines = listOf("甲".repeat(20), "乙".repeat(20), "丙".repeat(20), "新的一句", "丁".repeat(20), "尾句")
                .mapIndexed { index, value -> CaptionOverlayView.Line(value, null, true, index.toLong()) }

            /** 使用与实际服务相同的测量、窗口锚点和绘制守卫，不能由替代渲染器掩盖问题。 */
            fun position() {
                host.place((area.width() - width) / 2, area.height(), width, area.width(), area.height())
            }

            try {
                instrumentation.runOnMainSync {
                    overlay = CaptionOverlayView(context, false).apply {
                        setFinalOnly(false); setReserveDraftSpace(true); setShown(false, false)
                        applyStyle(18f, 0.9f)
                        setWindowHeightLimit(area.height()); setMaxContentHeight(area.height() * 2 / 5, 0)
                        setCaptionHeight(0); setCardWidth(width); setLines(lines.take(3), null)
                    }
                    host = CaptionWindowHost(context, wm, overlay, anchor) { _, _, _, _ -> }
                    overlay.viewTreeObserver.addOnDrawListener {
                        val body = ((overlay.getChildAt(0) as FrameLayout).getChildAt(0) as LinearLayout).getChildAt(0) as TextView
                        val location = IntArray(2)
                        body.getLocationOnScreen(location)
                        frames += location[1] + body.baseline + body.layout.getLineBaseline(body.lineCount - 1) -
                            body.layout.getLineBaseline(0) - body.scrollY
                        val rootLocation = IntArray(2)
                        overlay.getLocationOnScreen(rootLocation)
                        geometry += "${android.os.SystemClock.uptimeMillis()},${rootLocation[1]},${overlay.height},${overlay.measuredHeight},${overlay.getChildAt(0).top},${overlay.getChildAt(0).height},${overlay.getChildAt(1).bottom},0,${overlay.isHardwareAccelerated}"
                    }
                    position(); host.show(); attached = true
                }
                Thread.sleep(2_500)
                for ((index, range) in listOf(1..3, 2..4, 3..5, 0..2, 1..3, 2..4).withIndex()) {
                    instrumentation.runOnMainSync {
                        heading.text = "贴底字幕 · 第${index + 1}次换句"
                        overlay.setLines(lines.slice(range) + CaptionOverlayView.Line("下一句正在听写", null, false), null)
                    }
                    Thread.sleep(1_500)
                    instrumentation.runOnMainSync { overlay.setLines(lines.slice(range), null) }
                    Thread.sleep(500)
                    val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try {
                        val directory = File(context.getExternalFilesDir(null), "qa-bottom-motion").apply { mkdirs() }
                        File(directory, "阶段-$index.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    } finally { image.recycle() }
                }
                assertTrue("必须采集真实窗口绘制帧", frames.size > 20)
                assertTrue("底部切换不能偏离正式末行锚点：$frames", frames.all { it == area.top + anchor.baseline!! })
            } finally {
                val directory = File(context.getExternalFilesDir(null), "qa-bottom-motion").apply { mkdirs() }
                File(directory, "逐帧布局.csv").writeText("毫秒,窗口顶,实际高,测量高,卡片顶,卡片高,临时区底,底偏移,硬件加速\n" + geometry.joinToString("\n"))
                instrumentation.runOnMainSync { if (attached) host.dismiss() }
            }
        }
    }
}
