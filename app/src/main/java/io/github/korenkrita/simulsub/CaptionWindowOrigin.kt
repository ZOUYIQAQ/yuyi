package io.github.korenkrita.simulsub

/** 将悬浮窗布局坐标转换为屏幕坐标；旧系统在首帧用真实位置确定系统栏偏移。 */
internal class CaptionWindowOrigin(private var origin: Int? = null) {
    private var lastMismatch: Int? = null
    private var repeatedMismatch = 0

    /** 显示区域改变后重新建立坐标原点，旧系统仍由首帧校准。 */
    fun reset(value: Int? = null) {
        origin = value
        lastMismatch = null
        repeatedMismatch = 0
    }

    /** 等待系统应用窗口位移；持续相同的系统约束最多等待八帧，避免永久取消绘制。 */
    fun aligned(actualTop: Int, requestedTop: Int, actualBaselineOffset: Int, baseline: Int): Boolean {
        if (origin == null) origin = actualTop - requestedTop
        val difference = actualTop + actualBaselineOffset - (origin!! + baseline)
        if (difference == 0) {
            lastMismatch = null
            repeatedMismatch = 0
            return true
        }
        repeatedMismatch = if (lastMismatch == difference) repeatedMismatch + 1 else 1
        lastMismatch = difference
        if (repeatedMismatch < 8) return false
        // 某些系统会给应用悬浮窗额外固定偏移，统一修正坐标原点而非移动用户锚点。
        origin = origin!! + difference
        lastMismatch = null
        repeatedMismatch = 0
        return true
    }
}
