package io.github.korenkrita.simulsub

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Debug
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** 真实系统窗口、生产服务回调与手势的性能回归；配置全在内存，不启动音频、网络或通知。 */
class CaptionInteractionPerformanceDeviceTest {
    /** 拖动只应移动坐标：记录真实输入与绘制耗时，并在独立追踪轮统计实际测量入口。 */
    @Test fun dragUsesCoordinatesWithoutRemeasuringCaptions() {
        withOverlay { fixture ->
            fixture.reset()
            fixture.drag(48)
            fixture.report("拖动正常轮")
            val normal = fixture.windows.snapshot()
            val changedPreferences = fixture.preferenceChanges.toList()
            val callbacks = fixture.samples.filter { it.action == MotionEvent.ACTION_MOVE }
            val measures = fixture.traceMeasurements("拖动") { fixture.drag(16) }
            assertTrue("必须实际收到连续拖动回调", callbacks.size >= 40)
            assertEquals("纯拖动不能重新测量字幕：$measures", 0, measures.card)
            assertEquals("纯拖动不能重新测量字幕块：$measures", 0, measures.block)
            assertEquals("纯拖动不能重新排版正文或草稿：$measures", 0, measures.textMeasure)
            assertEquals("拖动不能调整显示表面的系统窗口：$normal", 0, normal.displayUpdates)
            assertEquals("拖动不能重复提交相同系统窗口参数：$normal", 0, normal.identicalUpdates)
            assertTrue("拖动应复用显示区域，不能每个触控读取系统窗口：$normal", normal.metricsReads <= 1)
            assertTrue("每个移动至多一次触摸窗更新：$normal", normal.touchUpdates <= callbacks.size + 2)
            assertTrue("纯拖动只能保存位置和基线：$changedPreferences", changedPreferences.all { it in setOf(
                "overlayX", "overlayY", "overlayBaselineY", "overlayBaselineAreaWidth", "overlayBaselineAreaHeight") })
            assertTrue("拖动后的真实位置必须保存", "overlayX" in changedPreferences)
            assertTrue("必须记录至少一帧真实绘制", callbacks.any { it.drawNs > 0 })
            assertTrue("触控回调P95不能超过两帧：${fixture.summary(callbacks)}",
                fixture.percentile(callbacks.map { it.durationNs }, 95) < fixture.frameBudgetNs * 2)
            assertTrue("输入到绘制P95不能超过四帧：${fixture.summary(callbacks)}",
                fixture.percentile(callbacks.filter { it.drawNs > 0 }.map {
                    it.queueNs + it.durationNs + it.drawNs - it.completedNs
                }, 95) < fixture.frameBudgetNs * 4)
        }
    }

    /** 首次展开与复用展开分别记录，点击不能重测字幕或重复提交相同工具栏窗口参数。 */
    @Test fun toolbarOpeningReusesCaptionGeometryAndWindow() {
        withOverlay { fixture ->
            fixture.reset()
            fixture.tap()
            fixture.assertToolbarSurface(true)
            fixture.report("工具栏首次展开")
            fixture.tap()
            fixture.assertToolbarSurface(false)
            fixture.reset()
            repeat(6) {
                fixture.tap(); fixture.assertToolbarSurface(true)
                fixture.tap(); fixture.assertToolbarSurface(false)
            }
            fixture.report("工具栏复用展开关闭六轮")
            val normal = fixture.windows.snapshot()
            val releases = fixture.samples.filter { it.action == MotionEvent.ACTION_UP }
            val measures = fixture.traceMeasurements("工具栏") { repeat(3) { fixture.tap(); fixture.tap() } }
            assertEquals("点击不能重测字幕：$measures", 0, measures.card)
            assertEquals("点击不能重测字幕块：$measures", 0, measures.block)
            assertEquals("点击不能重新排版正文或草稿：$measures", 0, measures.textMeasure)
            assertEquals("复用工具栏不能重新添加系统窗口：$normal", 0, normal.adds)
            assertEquals("六轮显隐应各合并成一次真实窗口状态更新：$normal", 12, normal.toolbarUpdates)
            assertEquals("相同位置展开不能重复更新系统窗口：$normal", 0, normal.identicalUpdates)
            assertEquals("工具栏不能调整字幕显示窗口：$normal", 0, normal.displayUpdates)
            assertEquals("工具栏不能调整字幕触摸窗口：$normal", 0, normal.touchUpdates)
            assertTrue("必须收到全部真实点击回调", releases.size == 12)
            assertTrue("每次展开应取得工具栏真实绘制", releases.count { it.drawNs > 0 } >= 6)
            assertTrue("点击回调P95不能超过两帧：${fixture.summary(releases)}",
                fixture.percentile(releases.map { it.durationNs }, 95) < fixture.frameBudgetNs * 2)
            assertTrue("点击到绘制P95不能超过四帧：${fixture.summary(releases)}",
                fixture.percentile(releases.filter { it.drawNs > 0 }.map {
                    it.queueNs + it.durationNs + it.drawNs - it.completedNs
                }, 95) < fixture.frameBudgetNs * 4)
        }
    }

    /** 持续字幕重排期间连续高速拖动与展开，允许系统合并历史点但必须准确呈现末点。 */
    @Test fun rapidGesturesStayResponsiveWhileCaptionsStream() {
        withOverlay { fixture -> fixture.rapidGesturesWithStreamingCaptions() }
    }

    /** 已显示再隐藏的窗口必须允许真实点击另一UID的应用，不能只检查透明度字段。 */
    @Test fun hiddenToolbarSurfacePassesTouchesToAnotherApplication() {
        withOverlay { fixture -> fixture.verifyHiddenToolbarPassThrough() }
    }

    /** 仅反射调用生产建窗入口；不启动服务生命周期，确保不会录音、翻译或写业务状态。 */
    private fun withOverlay(block: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { fixture.open(); block(fixture) } finally { fixture.close() }
    }

    /** 窗口代理只统计并转发真实系统调用；绝不以假窗口替代生产窗口。 */
    private class WindowCalls(private val real: WindowManager) {
        val views = CopyOnWriteArrayList<View>()
        private val previous = IdentityHashMap<View, WindowManager.LayoutParams>()
        private var counts = Counts()
        val manager = Proxy.newProxyInstance(WindowManager::class.java.classLoader,
            arrayOf(WindowManager::class.java)) { _, method, args ->
            when (method.name) {
                "getCurrentWindowMetrics", "getMaximumWindowMetrics" -> counts.metricsReads++
                "addView" -> {
                    counts.adds++
                    val view = args!![0] as View
                    views.add(view)
                    previous[view] = WindowManager.LayoutParams().apply { copyFrom(args[1] as WindowManager.LayoutParams) }
                }
                "updateViewLayout" -> {
                    val view = args!![0] as View
                    val params = args[1] as WindowManager.LayoutParams
                    when (view) {
                        is CaptionWindowHost -> counts.displayUpdates++
                        views.getOrNull(1) -> counts.touchUpdates++
                        else -> counts.toolbarUpdates++
                    }
                    val old = previous[view]
                    if (old != null && old.copyFrom(params) == 0) counts.identicalUpdates++
                    previous[view] = WindowManager.LayoutParams().apply { copyFrom(params) }
                }
            }
            try { method.invoke(real, *(args ?: emptyArray())) }
            catch (error: InvocationTargetException) { throw error.targetException }
        } as WindowManager

        /** 稳定后清空计数，初次建窗不计入交互轮。 */
        fun reset() { counts = Counts() }
        /** 复制统计避免后续追踪轮覆盖正常轮。 */
        fun snapshot() = counts.copy()
    }

    private data class Counts(var metricsReads: Int = 0, var adds: Int = 0,
        var displayUpdates: Int = 0, var touchUpdates: Int = 0,
        var toolbarUpdates: Int = 0, var identicalUpdates: Int = 0)
    private data class Sample(val action: Int, val queueNs: Long, val durationNs: Long,
        val completedNs: Long, @Volatile var drawNs: Long = 0, val historySize: Int = 0,
        val oldestQueueNs: Long = queueNs, val downTimeMs: Long = 0,
        @Volatile var drawnCenterX: Float = Float.NaN)
    private data class Measurements(val card: Int, val block: Int, val viewMeasure: Int, val textMeasure: Int)

    /** 每轮独立创建服务对象和内存配置，测试结束只释放本轮窗口与回调。 */
    private class Fixture {
        private val inst = InstrumentationRegistry.getInstrumentation()
        private val base = inst.targetContext
        val windows = WindowCalls(base.getSystemService(WindowManager::class.java))
        val samples = CopyOnWriteArrayList<Sample>()
        val preferenceChanges = CopyOnWriteArrayList<String>()
        private lateinit var service: CaptionService
        private lateinit var caption: CaptionOverlayView
        private lateinit var touch: View
        private lateinit var host: CaptionWindowHost
        private lateinit var toolbar: View
        private lateinit var toolbarSurface: View
        private var lastDown = 0L
        private var pointerDown = false
        private var pointerDownTime = 0L
        private var pointerX = 0f
        private var pointerY = 0f
        private val pending = CopyOnWriteArrayList<Pair<Sample, View>>()
        private val drawing = IdentityHashMap<View, ViewTreeObserver.OnDrawListener>()
        val frameBudgetNs: Long
            get() = (1_000_000_000.0 / (host.display?.refreshRate ?: 60f)).toLong()

        /** 生产服务读取内存偏好和计数窗口管理器，其余只读系统能力沿用应用上下文。 */
        fun open() {
            assertTrue("主代理需在唯一测试设备预先授权悬浮窗", Settings.canDrawOverlays(base))
            releasePointer()
            val targetPackage = inst.context.packageName
            assertTrue("测试承载Activity必须属于测试APK独立UID", inst.context.applicationInfo.uid != base.applicationInfo.uid)
            base.startActivity(android.content.Intent().setComponent(android.content.ComponentName(
                targetPackage, CaptionTouchTargetActivity::class.java.name))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await("测试APK原生承载按钮真实就绪", 15_000) {
                inst.uiAutomation.rootInActiveWindow?.takeIf { it.packageName?.toString() == targetPackage }
                    ?.findAccessibilityNodeInfosByText(CaptionTouchTargetActivity.READY_TEXT)
                    ?.any { it.isVisibleToUser && it.isClickable } == true
            }
            inst.uiAutomation.waitForIdle(300, 5_000)
            assertEquals("建窗前前台必须仍为测试APK原生Activity", targetPackage,
                inst.uiAutomation.rootInActiveWindow?.packageName?.toString())
            val preferences = memoryPreferences { preferenceChanges.add(it) }
            val context = object : ContextWrapper(base) {
                /** 只替换窗口调用入口，真实窗口仍交给系统管理器。 */
                override fun getSystemService(name: String): Any? =
                    if (name == Context.WINDOW_SERVICE) windows.manager else super.getSystemService(name)
                /** 所有配置读写只停留在本轮内存。 */
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
            }
            inst.runOnMainSync {
                service = CaptionService()
                ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                    .apply { isAccessible = true }.invoke(service, context)
                CaptionService::class.java.getDeclaredMethod("showOverlay").apply { isAccessible = true }.invoke(service)
                // 不消费服务初始化排队的业务渲染，避免修改全局会话快照。
                (field("main") as Handler).removeCallbacksAndMessages(null)
                caption = field("overlay") as CaptionOverlayView
                host = field("overlayWindowHost") as CaptionWindowHost
                touch = windows.views[1]
                toolbar = caption.takeToolbarForWindow()
                toolbarSurface = toolbar.rootView
                caption.setLines(listOf(
                    CaptionOverlayView.Line("字幕交互性能测试第一句。", "字幕操作の性能を測定します。", true, 1),
                    CaptionOverlayView.Line("保持生产排版、正式末行定位与原有动画。", null, true, 2),
                    CaptionOverlayView.Line("临时译文持续显示。", null, false, 3)), null)
                listOf<View>(host, toolbarSurface).forEach { view ->
                    val listener = ViewTreeObserver.OnDrawListener {
                        val now = SystemClock.elapsedRealtimeNanos()
                        val location = IntArray(2)
                        caption.getChildAt(0).getLocationOnScreen(location)
                        pending.filter { it.second === view }.forEach { (sample, _) ->
                            sample.drawnCenterX = location[0] + caption.width / 2f
                            sample.drawNs = now
                        }
                        pending.removeAll { it.second === view }
                    }
                    drawing[view] = listener
                    view.viewTreeObserver.addOnDrawListener(listener)
                }
                touch.setOnTouchListener { target, event ->
                    val start = SystemClock.elapsedRealtimeNanos()
                    val receivedUptime = SystemClock.uptimeMillis()
                    val queue = (receivedUptime - event.eventTime).coerceAtLeast(0) * 1_000_000
                    val handled = target.onTouchEvent(event)
                    val end = SystemClock.elapsedRealtimeNanos()
                    val oldest = if (event.historySize > 0) event.getHistoricalEventTime(0) else event.eventTime
                    val sample = Sample(event.actionMasked, queue, end - start, end,
                        historySize = event.historySize,
                        oldestQueueNs = (receivedUptime - oldest).coerceAtLeast(0) * 1_000_000,
                        downTimeMs = event.downTime)
                    samples.add(sample)
                    if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP)
                        pending.add(sample to if (toolbar.visibility == View.VISIBLE &&
                            event.actionMasked == MotionEvent.ACTION_UP) toolbarSurface else host)
                    handled
                }
            }
            await("生产字幕首帧") { caption.isLaidOut && caption.height > 0 }
            inst.waitForIdleSync()
            // 等生产高度过渡自行结束，不关闭动画或修改系统动画倍率。
            SystemClock.sleep(500)
        }

        /** 将计数重置限定在主线程，避免窗口代理与测试线程并发修改。 */
        fun reset() { inst.runOnMainSync { windows.reset(); samples.clear(); pending.clear(); preferenceChanges.clear() } }

        /** 通过系统输入分发真实触摸窗，再进入生产手势和生产服务回调。 */
        fun drag(moves: Int) {
            val point = center()
            inject(MotionEvent.ACTION_DOWN, point.first, point.second)
            var x = point.first
            repeat(moves) { index ->
                x = point.first + (if (index % 16 < 8) 24 + index % 8 * 2 else 36 - index % 8 * 2) * base.resources.displayMetrics.density
                inject(MotionEvent.ACTION_MOVE, x, point.second)
                // 每次等待一次真实绘制机会，保留触控事件与呈现帧之间的关联。
                await("拖动后实际绘制") { (samples.lastOrNull()?.drawNs ?: 0L) > 0 }
            }
            inject(MotionEvent.ACTION_UP, x, point.second)
            inst.waitForIdleSync()
        }

        /** 点击卡片中央，避免命中缩放角和工具栏内部按钮。 */
        fun tap() {
            val point = center()
            inject(MotionEvent.ACTION_DOWN, point.first, point.second)
            inject(MotionEvent.ACTION_UP, point.first, point.second)
            await("点击后实际绘制") { (samples.lastOrNull()?.drawNs ?: 0L) > 0 }
            inst.waitForIdleSync()
        }

        /** 校验真实窗口根一直可见，显隐只改变窗口透明度、触摸权限与内部内容。 */
        fun assertToolbarSurface(shown: Boolean) {
            inst.runOnMainSync {
                assertTrue("工具栏表面必须保持附着", toolbarSurface.isAttachedToWindow)
                assertEquals("工具栏根不能随内容停启", View.VISIBLE, toolbarSurface.visibility)
                assertEquals("内部工具栏显隐应同步", if (shown) View.VISIBLE else View.GONE, toolbar.visibility)
                val params = toolbarSurface.layoutParams as WindowManager.LayoutParams
                assertEquals("隐藏表面的窗口透明度必须为零", if (shown) 1f else 0f, params.alpha, 0f)
                assertEquals("隐藏表面必须关闭触摸", !shown,
                    params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            }
        }

        /** 用测试APK独立UID的原生按钮验证透传，先证明该前台没有强制隐藏可见工具栏。 */
        fun verifyHiddenToolbarPassThrough() {
            tap(); tap()
            assertToolbarSurface(false)
            val automation = inst.uiAutomation
            val originalFlags = automation.serviceInfo.flags
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            try {
                val targetPackage = inst.context.packageName
                val targetUid = inst.context.applicationInfo.uid
                assertTrue("承载页面必须属于另一个普通UID", targetUid != base.applicationInfo.uid &&
                    targetUid != android.os.Process.SYSTEM_UID)
                assertEquals("承载App不能拥有强制隐藏overlay的权限", android.content.pm.PackageManager.PERMISSION_DENIED,
                    base.packageManager.checkPermission("android.permission.HIDE_OVERLAY_WINDOWS", targetPackage))
                /** 每次读取当前前台应用，不复用切页前的节点。 */
                fun targetRoot(): android.view.accessibility.AccessibilityNodeInfo? {
                    val roots = listOfNotNull(automation.rootInActiveWindow) + automation.windows.mapNotNull { it.root }
                    return roots.firstOrNull { it.packageName?.toString() == targetPackage && it.isVisibleToUser }
                }
                await("普通UID承载页面真实就绪", 15_000) { targetRoot() != null }
                automation.waitForIdle(300, 5_000)
                val button = checkNotNull(targetRoot()?.findAccessibilityNodeInfosByText(CaptionTouchTargetActivity.READY_TEXT)
                    ?.firstOrNull { it.isVisibleToUser && it.isClickable }) { "专用承载按钮必须保持初始就绪文字" }
                val target = android.graphics.Rect().also { button.getBoundsInScreen(it) }
                val wm = base.getSystemService(WindowManager::class.java)
                val area = captionWindowArea(wm.currentWindowMetrics)
                inst.runOnMainSync {
                    // 先把字幕触摸窗移开，避免误把它的拦截归到已经隐藏的工具栏。
                    caption.onMove(0, area.height() * 2 / 3 - caption.top)
                    caption.onMoveEnd()
                    val gap = (CaptionOverlayView.TOOLBAR_GAP_DP * base.resources.displayMetrics.density).toInt()
                    val window = field("overlayToolbarWindow") as CaptionToolbarWindow
                    val desiredTop = (target.centerY() - area.top - toolbarSurface.height / 2)
                        .coerceIn(0, (area.height() - toolbarSurface.height).coerceAtLeast(0))
                    window.update(true, target.centerX() - caption.width / 2,
                        desiredTop - gap,
                        caption.width, 0, area.width(), area.height())
                }
                inst.waitForIdleSync()
                assertToolbarSurface(true)
                // 系统若强制隐藏overlay，此可见性正控制必须失败，不能继续把隐藏当成透传。
                await("承载App前台上真实工具栏仍可见") {
                    automation.windows.mapNotNull { it.root }.any { root ->
                        root.packageName?.toString() == base.packageName &&
                            root.findAccessibilityNodeInfosByText("字号").any { it.isVisibleToUser }
                    }
                }
                inst.runOnMainSync {
                    val location = IntArray(2)
                    toolbarSurface.getLocationOnScreen(location)
                    val covered = android.graphics.Rect(location[0], location[1],
                        location[0] + toolbarSurface.width, location[1] + toolbarSurface.height)
                    assertTrue("真实按钮点击点必须处于工具栏窗口内：$covered，目标=$target",
                        covered.contains(target.centerX(), target.centerY()))
                    (field("overlayToolbarWindow") as CaptionToolbarWindow)
                        .update(false, 0, 0, caption.width, 0, area.width(), area.height())
                }
                inst.waitForIdleSync()
                assertToolbarSurface(false)
                val down = SystemClock.uptimeMillis()
                injectAsync(MotionEvent.ACTION_DOWN, target.centerX().toFloat(), target.centerY().toFloat(), down)
                injectAsync(MotionEvent.ACTION_UP, target.centerX().toFloat(), target.centerY().toFloat(), down)
                await("隐藏窗口透传后独立UID按钮文字真实改变", 15_000) {
                    targetRoot()?.findAccessibilityNodeInfosByText(CaptionTouchTargetActivity.CLICKED_TEXT)
                        ?.any { it.isVisibleToUser } == true
                }
                Log.i(TAG, "测试APK独立UID=$targetUid，无隐藏overlay权限且可见性正控制通过；隐藏工具栏透传后原生按钮文字已改变")
            } finally {
                automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            }
        }

        /** 三轮约500毫秒高速手势穿插点击；输入线程按绝对8毫秒节拍发送，不等待MOVE处理或绘制。 */
        fun rapidGesturesWithStreamingCaptions() {
            reset()
            val handler = Handler(android.os.Looper.getMainLooper())
            val responses = CopyOnWriteArrayList<Long>()
            val revisions = java.util.concurrent.atomic.AtomicInteger()
            val finals = java.util.concurrent.atomic.AtomicInteger()
            val active = java.util.concurrent.atomic.AtomicBoolean(true)
            val started = SystemClock.uptimeMillis()
            var heartbeatDue = started + 16
            val heartbeat = object : Runnable {
                /** 响应探针只保留一个待执行任务，避免用测试自身制造主线程积压。 */
                override fun run() {
                    if (!active.get()) return
                    responses.add((SystemClock.uptimeMillis() - heartbeatDue).coerceAtLeast(0) * 1_000_000)
                    heartbeatDue = SystemClock.uptimeMillis() + 16
                    handler.postAtTime(this, heartbeatDue)
                }
            }
            val captions = object : Runnable {
                /** 使用生产视图更新字幕并保留全部动画；固定节奏更新草稿，每秒新增正式句。 */
                override fun run() {
                    if (!active.get()) return
                    val revision = revisions.incrementAndGet()
                    val finalCount = ((SystemClock.uptimeMillis() - started) / 1_000).toInt()
                    finals.set(finalCount)
                    caption.setLines(buildList {
                        add(CaptionOverlayView.Line("持续交互测试的初始正式字幕。", null, true, 100))
                        repeat(finalCount) { index ->
                            add(CaptionOverlayView.Line("每秒进入的新定稿第${index + 1}句，保持原有滚动和高度动画。", null, true, 101L + index))
                        }
                        add(CaptionOverlayView.Line("正在连续更新的临时译文，第${revision}次。".repeat(1 + revision % 3), null, false, 999))
                    }, null)
                    handler.postDelayed(this, 150)
                }
            }
            handler.postAtTime(heartbeat, heartbeatDue)
            handler.post(captions)
            try {
                repeat(3) { round ->
                    val point = center()
                    val down = SystemClock.uptimeMillis()
                    val delta = minOf(48f * base.resources.displayMetrics.density, caption.width * 0.12f) *
                        if (round % 2 == 0) 1 else -1
                    val endpoint = point.first + delta
                    injectAsync(MotionEvent.ACTION_DOWN, point.first, point.second, down)
                    repeat(60) { index ->
                        val due = down + (index + 1) * 8
                        val remaining = due - SystemClock.uptimeMillis()
                        if (remaining > 0) SystemClock.sleep(remaining)
                        // 往返轨迹最终落在明确的水平末点；不依赖每个MOVE都被回调。
                        val progress = (index + 1) / 60f
                        val offset = delta * (progress + 0.25f * kotlin.math.sin(progress * Math.PI * 4).toFloat())
                        injectAsync(MotionEvent.ACTION_MOVE, point.first + offset, point.second, down)
                    }
                    injectAsync(MotionEvent.ACTION_UP, endpoint, point.second, down)
                    await("高速手势UP送达") { samples.any { it.action == MotionEvent.ACTION_UP && it.downTimeMs == down } }
                    await("高速手势末点绘制") {
                        val last = samples.lastOrNull { it.action == MotionEvent.ACTION_MOVE && it.downTimeMs == down }
                        last != null && last.drawNs > 0 && kotlin.math.abs(last.drawnCenterX - endpoint) <= 2f
                    }
                    inst.runOnMainSync {
                        val location = IntArray(2)
                        caption.getChildAt(0).getLocationOnScreen(location)
                        assertTrue("高速拖动末点必须准确：轮=$round，目标中心=$endpoint，实际中心=${location[0] + caption.width / 2f}",
                            kotlin.math.abs(location[0] + caption.width / 2f - endpoint) <= 2f)
                    }
                    tap(); tap()
                }
                await("持续运行覆盖至少一次定稿") { finals.get() >= 1 && revisions.get() >= 7 }
            } finally {
                active.set(false)
                handler.removeCallbacks(heartbeat)
                handler.removeCallbacks(captions)
                // 旧二进制若在末点或UP处失败，也必须留下队列与主线程证据。
                report("高速拖动与持续字幕")
                val moves = samples.filter { it.action == MotionEvent.ACTION_MOVE }
                Log.i(TAG, "高速注入计划MOVE=180，实际MOVE回调=${moves.size}，合并历史点=${moves.sumOf { it.historySize }}，" +
                    "草稿更新=${revisions.get()}，新增定稿=${finals.get()}，主线程响应P95/最大纳秒=" +
                    "${percentile(responses, 95)}/${responses.maxOrNull() ?: 0}")
            }
            val moves = samples.filter { it.action == MotionEvent.ACTION_MOVE }
            assertTrue("高速注入必须有实际拖动与绘制", moves.isNotEmpty() && moves.any { it.drawNs > 0 })
            assertTrue("连续字幕必须实际更新", revisions.get() >= 7 && finals.get() >= 1)
            assertTrue("必须采集主线程响应探针", responses.isNotEmpty())
            assertTrue("高速触控队列P95应低于100毫秒：${summary(moves)}", percentile(moves.map { it.queueNs }, 95) < 100_000_000)
            assertTrue("高速触控队列最大值应低于250毫秒：${summary(moves)}", moves.maxOf { it.queueNs } < 250_000_000)
            assertTrue("合并历史点也不能掩盖超过250毫秒的输入积压：${summary(moves)}", moves.maxOf { it.oldestQueueNs } < 250_000_000)
            assertTrue("主线程响应P95应低于100毫秒：$responses", percentile(responses, 95) < 100_000_000)
            assertTrue("主线程响应最大值应低于250毫秒：$responses", responses.maxOrNull()!! < 250_000_000)
        }

        /** 异步输入不等待系统处理，事件时间使用实际提交时刻，积压由真实触控回调记录。 */
        private fun injectAsync(action: Int, x: Float, y: Float, down: Long) {
            assertTrue("系统必须接受高速输入", sendPointer(action, x, y, down, false))
        }

        /** 屏幕坐标来自真实卡片，不能使用宿主的整屏透明区域充当触摸目标。 */
        private fun center(): Pair<Float, Float> {
            var result = 0f to 0f
            inst.runOnMainSync {
                val card = caption.getChildAt(0)
                val location = IntArray(2)
                card.getLocationOnScreen(location)
                result = location[0] + card.width / 2f to location[1] + card.height / 2f
            }
            return result
        }

        /** 注入时间作为排队时延起点；回调内部单独计时，不能把注入成功当成交互性能。 */
        private fun inject(action: Int, x: Float, y: Float) {
            val expected = samples.size + 1
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) lastDown = now
            assertTrue("系统必须接受输入", sendPointer(action, x, y, lastDown, true))
            await("生产触摸窗回调") { samples.size >= expected }
        }

        /** 同步与异步输入共用手势生命周期，回调等待失败也保留可清理的原始DOWN。 */
        private fun sendPointer(action: Int, x: Float, y: Float, down: Long, sync: Boolean): Boolean {
            if (action == MotionEvent.ACTION_DOWN) { pointerDown = true; pointerDownTime = down }
            pointerX = x; pointerY = y
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            return try {
                inst.uiAutomation.injectInputEvent(event, sync).also { accepted ->
                    if (accepted && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) pointerDown = false
                }
            } finally { event.recycle() }
        }

        /** 建窗前与失败清理时取消遗留输入；仅已知手势的取消失败时才补UP。 */
        private fun releasePointer() {
            val active = pointerDown
            val down = if (active) pointerDownTime else SystemClock.uptimeMillis()
            try {
                if (!sendPointer(MotionEvent.ACTION_CANCEL, pointerX, pointerY, down, true) && active)
                    sendPointer(MotionEvent.ACTION_UP, pointerX, pointerY, down, true)
            } catch (error: Exception) { Log.w(TAG, "清理测试触摸手势失败", error) }
            finally { pointerDown = false }
        }

        /** 真实方法追踪单独执行，计数轮的插桩开销绝不混入正常轮耗时。 */
        fun traceMeasurements(label: String, interactions: () -> Unit): Measurements {
            // 独立正控制必须先证明四个入口均能采集，不能要求零测量轮也出现测量字典。
            val control = File(base.cacheDir, "字幕测量正控制-$label-${SystemClock.uptimeMillis()}.trace")
            Debug.startMethodTracing(control.absolutePath, 32 * 1024 * 1024, 0)
            try {
                inst.runOnMainSync {
                    CaptionOverlayView(base, false).measureCaptionBlockHeight(240)
                }
            } finally { Debug.stopMethodTracing() }
            val verified = readMeasurements(control)
            Log.i(TAG, "$label 独立测量正控制：$verified；追踪文件=${control.absolutePath}")
            assertTrue("测量正控制必须完整采集四个入口：$verified",
                verified.card > 0 && verified.block > 0 && verified.viewMeasure > 0 && verified.textMeasure > 0)
            val output = File(base.cacheDir, "字幕交互-$label-${SystemClock.uptimeMillis()}.trace")
            Debug.startMethodTracing(output.absolutePath, 32 * 1024 * 1024, 0)
            try { interactions() } finally { Debug.stopMethodTracing() }
            val result = readMeasurements(output)
            Log.i(TAG, "$label 独立方法计数轮：$result；追踪文件=${output.absolutePath}")
            return result
        }

        /** 同时输出排队、回调执行与回调后首帧，呈现时间不冒充显示屏实际扫描时间。 */
        fun report(label: String) {
            Log.i(TAG, "$label；${summary(samples)}；系统窗口=${windows.snapshot()}；帧预算=${frameBudgetNs / 1_000_000.0}毫秒")
            samples.forEachIndexed { index, sample ->
                Log.i(TAG, "$label 样本=$index 动作=${sample.action} 排队纳秒=${sample.queueNs} 最旧历史点排队纳秒=${sample.oldestQueueNs} " +
                    "历史点=${sample.historySize} 回调纳秒=${sample.durationNs} " +
                    "回调后绘制纳秒=${if (sample.drawNs > 0) sample.drawNs - sample.completedNs else -1}")
            }
        }

        /** 百分位包含冷样本，不通过去除最慢样本美化结果。 */
        fun summary(values: List<Sample>): String =
            "样本=${values.size}，回调P50/P95/最大纳秒=${percentile(values.map { it.durationNs }, 50)}/" +
                "${percentile(values.map { it.durationNs }, 95)}/${values.maxOfOrNull { it.durationNs } ?: 0}，" +
                "排队P95/最大纳秒=${percentile(values.map { it.queueNs }, 95)}/${values.maxOfOrNull { it.queueNs } ?: 0}，" +
                "最旧历史点排队P95/最大纳秒=${percentile(values.map { it.oldestQueueNs }, 95)}/${values.maxOfOrNull { it.oldestQueueNs } ?: 0}，" +
                "回调后绘制P95纳秒=${percentile(values.filter { it.drawNs > 0 }.map { it.drawNs - it.completedNs }, 95)}，" +
                "输入到绘制P95纳秒=${percentile(values.filter { it.drawNs > 0 }.map {
                    it.queueNs + it.durationNs + it.drawNs - it.completedNs
                }, 95)}"

        /** 采用向上取整的最近秩百分位。 */
        fun percentile(values: List<Long>, percent: Int): Long = if (values.isEmpty()) 0 else
            values.sorted()[((values.size * percent + 99) / 100 - 1).coerceAtLeast(0)]

        /** 所有等待都有期限；失败说明缺失的是触控回调还是实际绘制。 */
        private fun await(label: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (!condition()) {
                assertTrue("等待超时：$label；样本=${samples.size}", SystemClock.uptimeMillis() < deadline)
                SystemClock.sleep(8)
            }
        }

        /** 访问字段仅用于测试装配与释放，生产源码保持原样。 */
        private fun field(name: String): Any? = CaptionService::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(service)

        /** 只释放本轮创建的窗口和处理器，不调用会修改全局状态的服务销毁流程。 */
        fun close() {
            releasePointer()
            if (!::service.isInitialized) return
            inst.runOnMainSync {
                (field("main") as Handler).removeCallbacksAndMessages(null)
                drawing.forEach { (view, listener) -> if (view.viewTreeObserver.isAlive)
                    view.viewTreeObserver.removeOnDrawListener(listener) }
                (field("overlayToolbarWindow") as? CaptionToolbarWindow)?.dismiss()
                (field("overlayWindowHost") as? CaptionWindowHost)?.dismiss()
            }
        }
    }

    companion object {
        private const val TAG = "字幕交互性能"

        /** 读取ART完整方法追踪的进入记录，精确区分字幕测量入口与全部原生View测量入口。 */
        private fun readMeasurements(file: File): Measurements {
            val bytes = file.readBytes()
            val marker = "*end\n".toByteArray(Charsets.US_ASCII)
            val headerEnd = (0..bytes.size - marker.size).firstOrNull { offset ->
                marker.indices.all { bytes[offset + it] == marker[it] }
            }?.plus(marker.size) ?: error("方法追踪缺少文本头结束标记")
            val text = bytes.copyOfRange(0, headerEnd).toString(Charsets.UTF_8)
            check(!text.contains("data-file-overflow=true")) { "方法追踪缓冲区溢出，不能报告不完整计数" }
            val cardIds = HashSet<Int>(); val blockIds = HashSet<Int>(); val measureIds = HashSet<Int>(); val textIds = HashSet<Int>()
            text.lineSequence().forEach { line ->
                val parts = line.split('\t')
                if (parts.size >= 4 && parts[0].startsWith("0x")) {
                    val id = parts[0].removePrefix("0x").toLong(16).toInt()
                    if (parts[1].replace('/', '.').endsWith(".CaptionOverlayView")) {
                        if (parts[2] == "measureCardHeight") cardIds.add(id)
                        if (parts[2] == "measureCaptionBlockHeight") blockIds.add(id)
                    }
                    if (parts[1].replace('/', '.') == "android.view.View" && parts[2] == "measure") measureIds.add(id)
                    if (parts[1].replace('/', '.').endsWith(".CaptionTextView") && parts[2] == "onMeasure") textIds.add(id)
                }
            }
            val data = ByteBuffer.wrap(bytes, headerEnd, bytes.size - headerEnd).slice().order(ByteOrder.LITTLE_ENDIAN)
            check(data.int == 0x574f4c53) { "方法追踪魔数无效" }
            val version = data.short.toInt() and 0xffff
            val offset = data.short.toInt() and 0xffff
            check(version in 1..3) { "不支持的方法追踪版本：$version" }
            val recordSize = if (version == 3) data.getShort(16).toInt() and 0xffff else if (version == 1) 9 else 10
            check(recordSize >= 9) { "方法追踪记录长度无效：$recordSize" }
            var card = 0; var block = 0; var measure = 0; var textMeasure = 0
            var entered = 0
            var cursor = offset
            while (cursor + recordSize <= data.limit()) {
                val encoded = data.getInt(cursor + if (version == 1) 1 else 2)
                if (encoded and 3 == 0) {
                    entered++
                    val id = encoded and -4
                    if (id in cardIds) card++
                    if (id in blockIds) block++
                    if (id in measureIds) measure++
                    if (id in textIds) textMeasure++
                }
                cursor += recordSize
            }
            check(entered > 0) { "方法追踪没有任何进入记录，不能把空追踪报告成零测量" }
            return Measurements(card, block, measure, textMeasure)
        }

        /** 内存偏好实现真实编辑和监听语义，防止手势结束保存位置时接触用户数据。 */
        private fun memoryPreferences(onChanged: (String) -> Unit): SharedPreferences {
            val values = hashMapOf<String, Any>("overlayX" to 40, "overlayY" to 160,
                "widthFrac" to 0.65f, "textSp" to 18f)
            val listeners = HashSet<SharedPreferences.OnSharedPreferenceChangeListener>()
            lateinit var preferences: SharedPreferences
            preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java)) { _, method, args ->
                when (method.name) {
                    "getAll" -> values.toMap()
                    "contains" -> values.containsKey(args!![0])
                    "getString", "getStringSet", "getInt", "getLong", "getFloat", "getBoolean" -> values[args!![0]] ?: args[1]
                    "registerOnSharedPreferenceChangeListener" -> { listeners.add(args!![0] as SharedPreferences.OnSharedPreferenceChangeListener); null }
                    "unregisterOnSharedPreferenceChangeListener" -> { listeners.remove(args!![0] as SharedPreferences.OnSharedPreferenceChangeListener); null }
                    "edit" -> {
                        val edits = HashMap<String, Any?>()
                        var clear = false
                        lateinit var editor: SharedPreferences.Editor
                        editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                            arrayOf(SharedPreferences.Editor::class.java)) { _, edit, parameters ->
                            when (edit.name) {
                                "clear" -> { clear = true; editor }
                                "remove" -> { edits[parameters!![0] as String] = null; editor }
                                "apply", "commit" -> {
                                    if (clear) values.clear()
                                    val changed = edits.filter { (key, value) -> values[key] != value }.keys
                                    edits.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                                    changed.forEach { key ->
                                        onChanged(key)
                                        listeners.toList().forEach { it.onSharedPreferenceChanged(preferences, key) }
                                    }
                                    if (edit.name == "commit") true else null
                                }
                                else -> if (edit.name.startsWith("put")) {
                                    edits[parameters!![0] as String] = parameters[1]; editor
                                } else error("未支持的内存偏好编辑：${edit.name}")
                            }
                        } as SharedPreferences.Editor
                        editor
                    }
                    else -> error("未支持的内存偏好操作：${method.name}")
                }
            } as SharedPreferences
            return preferences
        }
    }
}
