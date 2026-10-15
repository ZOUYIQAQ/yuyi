package io.github.korenkrita.simulsub

import android.view.ViewTreeObserver

/** 在绘制前同步文字基线和窗口位置，禁止先画出偏移帧再异步纠正。 */
internal class CaptionLayoutGuard(
    private val baselineOffset: () -> Int,
    private val reservedHeight: () -> Int,
    private val position: () -> Unit,
    private val aligned: () -> Boolean = { true },
) : ViewTreeObserver.OnPreDrawListener {
    private var positionedBaseline = Int.MIN_VALUE
    private var positionedHeight = Int.MIN_VALUE

    /** 记录宿主刚刚使用的布局数据，不改写用户锚点。 */
    fun positioned(baseline: Int, height: Int) {
        positionedBaseline = baseline
        positionedHeight = height
    }

    /** 相同外框高度中的文字也可能重排，因此不能只监听窗口高变化。 */
    override fun onPreDraw(): Boolean {
        if (reservedHeight() == positionedHeight && baselineOffset() == positionedBaseline && aligned()) return true
        position()
        return false
    }
}
