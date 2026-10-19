package io.github.korenkrita.simulsub

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/** 工具栏使用自己的系统浮层，显隐和重新定位不改变字幕窗口的尺寸或位置。 */
internal class CaptionToolbarWindow(
    private val wm: WindowManager,
    private val toolbar: View,
    private val contentVersion: () -> Int = { 0 },
) {
    private var attached = false
    private var measuredScreenWidth = 0
    private var measuredScreenHeight = 0
    private var measuredVersion = -1
    private var width = 1
    private var height = 1
    // 根视图始终可见且尺寸固定，避免根显隐带来的渲染表面停启。
    private val surface = FrameLayout(toolbar.context).apply {
        visibility = View.VISIBLE
        addView(toolbar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START; alpha = 0f }

    /** 稳定帧提前准备工具栏；仅内容或可用区域改变时重测，隐藏窗口不拦截其他应用。 */
    fun prepare(screenWidth: Int, screenHeight: Int) {
        val version = contentVersion()
        if (attached && measuredScreenWidth == screenWidth && measuredScreenHeight == screenHeight && measuredVersion == version) return
        toolbar.measure(View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.AT_MOST))
        width = toolbar.measuredWidth.coerceIn(1, screenWidth.coerceAtLeast(1))
        height = toolbar.measuredHeight.coerceIn(1, screenHeight.coerceAtLeast(1))
        measuredScreenWidth = screenWidth; measuredScreenHeight = screenHeight; measuredVersion = version
        if (!attached) {
            params.width = width; params.height = height
            wm.addView(surface, params)
            attached = true
        }
    }

    /** 优先在字幕下方显示，空间不足时放上方，始终只修改工具栏自己的窗口参数。 */
    fun update(shown: Boolean, left: Int, top: Int, cardWidth: Int, reservedHeight: Int, screenWidth: Int, screenHeight: Int) {
        if (!shown) {
            toolbar.visibility = View.GONE
            if (attached) applyWindowState(false, params.x, params.y, params.width, params.height)
            return
        }
        prepare(screenWidth, screenHeight)
        val gap = (CaptionOverlayView.TOOLBAR_GAP_DP * toolbar.resources.displayMetrics.density).toInt()
        val x = (left + (cardWidth - width) / 2).coerceIn(0, (screenWidth - width).coerceAtLeast(0))
        val below = top + reservedHeight + gap
        val y = (if (below + height <= screenHeight) below else top - gap - height)
            .coerceIn(0, (screenHeight - height).coerceAtLeast(0))
        applyWindowState(true, x, y, width, height)
        toolbar.visibility = View.VISIBLE
    }

    /** 透明度和触摸权限与几何一起提交；零透明度满足跨应用透传条件，不能只隐藏内部控件。 */
    private fun applyWindowState(shown: Boolean, x: Int, y: Int, width: Int, height: Int) {
        val alpha = if (shown) 1f else 0f
        val flags = if (shown) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (params.x == x && params.y == y && params.width == width && params.height == height &&
            params.alpha == alpha && params.flags == flags) return
        params.x = x; params.y = y; params.width = width; params.height = height
        params.alpha = alpha; params.flags = flags
        wm.updateViewLayout(surface, params)
    }

    /** 服务停止后释放独立工具栏，不触碰字幕内容或用户配置。 */
    fun dismiss() {
        if (attached) wm.removeViewImmediate(surface)
        attached = false
        toolbar.visibility = View.GONE
        params.alpha = 0f
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }
}
