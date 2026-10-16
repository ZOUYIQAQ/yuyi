package io.github.korenkrita.simulsub

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout

/** 固定显示表面承载所有过渡；透明触摸窗只覆盖实际字幕，不让透明留白拦截其他应用。 */
internal class CaptionWindowHost(
    context: Context,
    private val wm: WindowManager,
    private val caption: CaptionOverlayView,
    private val anchor: CaptionBaselineAnchor,
    private val onPlaced: (Int, Int, Int, Int) -> Unit,
) : FrameLayout(context), ViewTreeObserver.OnPreDrawListener {
    private var requestedX = 0
    private var requestedY = 0
    private var cardWidth = 1
    private var areaWidth = 1
    private var areaHeight = 1
    private var shown = false
    private val displayParams = WindowManager.LayoutParams(1, 1,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.START
        // 系统允许此透明度的非触摸悬浮窗透传点击，避免整块显示表面遮挡视频操作。
        alpha = captionWindowOpacity(context)
        disableCaptionWindowMoveAnimation()
    }
    private val touchParams = WindowManager.LayoutParams(1, 1,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; disableCaptionWindowMoveAnimation() }
    private val touch = object : View(context) {
        /** 保留屏幕坐标供拖动使用，局部坐标始终相对于实际字幕卡片。 */
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val forwarded = MotionEvent.obtain(event)
            return try { caption.dispatchTouchEvent(forwarded) } finally { forwarded.recycle() }
        }
    }

    init {
        clipChildren = true
        touch.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        addView(caption, LayoutParams(1, LayoutParams.WRAP_CONTENT))
        viewTreeObserver.addOnPreDrawListener(this)
    }

    /** 仅显式移动、旋转或尺寸配置改变请求位置；逐帧高度变化仍在固定显示表面内完成。 */
    fun place(x: Int, y: Int, width: Int, screenWidth: Int, screenHeight: Int) {
        requestedX = x; requestedY = y; cardWidth = width
        areaWidth = screenWidth; areaHeight = screenHeight
        val child = caption.layoutParams
        if (child.width != width) { child.width = width; caption.layoutParams = child }
        if (displayParams.width != screenWidth || displayParams.height != screenHeight) {
            displayParams.width = screenWidth; displayParams.height = screenHeight
            if (shown) wm.updateViewLayout(this, displayParams)
        }
        alignCaption()
        requestLayout()
    }

    /** 第一次附着时同时创建显示和触摸窗口，触摸窗透明因此尺寸变化不影响画面。 */
    fun show() {
        check(!shown)
        wm.addView(this, displayParams)
        try { wm.addView(touch, touchParams) }
        catch (error: RuntimeException) { wm.removeViewImmediate(this); throw error }
        shown = true
    }

    /** 仅在同一父布局内定位字幕，系统窗口的尺寸与位置始终不参与字幕动画。 */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        alignCaption()
    }

    /** 读取本帧已完成的真实排版，不在预绘制里再次触发测量。 */
    override fun onPreDraw(): Boolean { alignCaption(); return true }

    /** 同一固定表面内的位移和文字使用同一张缓冲区，触摸窗口只跟随卡片矩形。 */
    private fun alignCaption() {
        if (!caption.isLaidOut) return
        val baseline = caption.finalLineBaselineInWindowPx()
        val x = requestedX.coerceIn(0, (areaWidth - cardWidth).coerceAtLeast(0))
        val y = anchor.position(requestedY, baseline, areaHeight, caption.height)
        caption.offsetLeftAndRight(x - caption.left)
        caption.offsetTopAndBottom(y - caption.top)
        val changed = touchParams.x != x || touchParams.y != y || touchParams.width != cardWidth || touchParams.height != caption.height
        touchParams.x = x; touchParams.y = y; touchParams.width = cardWidth; touchParams.height = caption.height.coerceAtLeast(1)
        if (shown && changed) wm.updateViewLayout(touch, touchParams)
        if (changed) onPlaced(x, y, cardWidth, caption.height)
    }

    /** 停止服务时移除两个窗口，动画和位图由字幕视图的释放流程结束。 */
    fun dismiss() {
        if (!shown) return
        shown = false
        try { if (touch.isAttachedToWindow) wm.removeViewImmediate(touch) }
        finally { if (isAttachedToWindow) wm.removeViewImmediate(this) }
    }
}
