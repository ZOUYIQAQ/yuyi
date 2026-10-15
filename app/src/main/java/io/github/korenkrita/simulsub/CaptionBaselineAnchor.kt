package io.github.korenkrita.simulsub

/** 用户放下字幕后固定最后一行定稿的基线；文字、诊断和草稿变化不重新贴边。 */
internal class CaptionBaselineAnchor(initialBaseline: Int? = null) {
    var baseline: Int? = initialBaseline
        private set
    private var fitPlacement = initialBaseline == null

    /** 仅未保存位置或显式重置后适配屏幕；恢复锚点和内容更新始终保持原基线。 */
    fun position(requestedTop: Int, baselineOffset: Int, availableHeight: Int, reservedHeight: Int): Int {
        val wanted = baseline?.minus(baselineOffset) ?: requestedTop
        if (!fitPlacement) return wanted
        val top = wanted.coerceIn(0, (availableHeight - reservedHeight).coerceAtLeast(0))
        baseline = top + baselineOffset
        fitPlacement = false
        return top
    }

    /** 拖动、缩放或显示区域改变才允许建立新锚点。 */
    fun reset() {
        baseline = null
        fitPlacement = true
    }
}
