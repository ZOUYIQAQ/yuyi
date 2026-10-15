package io.github.korenkrita.simulsub

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** 工具栏使用自己的系统浮层，显隐和重新定位不改变字幕窗口的尺寸或位置。 */
internal class CaptionToolbarWindow(private val wm: WindowManager, private val toolbar: View) {
    private var attached = false
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /** 优先在字幕下方显示，空间不足时放上方，始终只修改工具栏自己的窗口参数。 */
    fun update(shown: Boolean, left: Int, top: Int, cardWidth: Int, reservedHeight: Int, screenWidth: Int, screenHeight: Int) {
        toolbar.visibility = if (shown) View.VISIBLE else View.GONE
        if (!shown) return
        toolbar.measure(View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.AT_MOST))
        val width = toolbar.measuredWidth.coerceIn(1, screenWidth.coerceAtLeast(1))
        val height = toolbar.measuredHeight.coerceIn(1, screenHeight.coerceAtLeast(1))
        val gap = (CaptionOverlayView.TOOLBAR_GAP_DP * toolbar.resources.displayMetrics.density).toInt()
        params.width = width
        params.height = height
        params.x = (left + (cardWidth - width) / 2).coerceIn(0, (screenWidth - width).coerceAtLeast(0))
        val below = top + reservedHeight + gap
        params.y = (if (below + height <= screenHeight) below else top - gap - height)
            .coerceIn(0, (screenHeight - height).coerceAtLeast(0))
        if (attached) wm.updateViewLayout(toolbar, params)
        else { wm.addView(toolbar, params); attached = true }
    }

    /** 服务停止后释放独立工具栏，不触碰字幕内容或用户配置。 */
    fun dismiss() {
        if (attached) wm.removeViewImmediate(toolbar)
        attached = false
    }
}
