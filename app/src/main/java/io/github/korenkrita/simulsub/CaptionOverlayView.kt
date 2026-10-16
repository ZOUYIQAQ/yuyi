package io.github.korenkrita.simulsub

import io.github.korenkrita.simulsub.ServiceStatus.Light
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * The subtitle card. Used twice: as the floating overlay (with [interactive] gestures) and as the live
 * preview in the settings screen, so what the user tunes is exactly what they get.
 *
 * Overlay gestures, unlocked: drag anywhere to move; a single tap shows the toolbar (font −/+, opacity −/+, mode, lock,
 * stop) together with a faint mark in each corner, and while they are shown a corner drag resizes width and height.
 * Locked: position and size are fixed and a tap only shows an unlock button in the bottom-right corner.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class CaptionOverlayView(ctx: Context, private val interactive: Boolean) : LinearLayout(ctx) {
    /** 将布局使用的逻辑尺寸换算成像素。 */
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val card = FrameLayout(ctx)
    private val text = CaptionTextView(ctx, animateChanges = true)
    private val stats = TextView(ctx)
    /** 草稿布局预留伸展空间，由统一背景包住可见文字，不改变定稿的位置。 */
    private val draftSlot = LinearLayout(ctx)
    private val draftSource = TextView(ctx)
    private val draftText = CaptionTextView(ctx)
    private var draftSlotEnabled = false
    private var reserveDraftSpace = false
    private var draftBackgroundFraction = 0f
    private var draftBackgroundTarget = false
    private var draftBackgroundAnimator: android.animation.ValueAnimator? = null
    private var draftOriginalShown = false
    /** resize marks, one per corner: top-left, top-right, bottom-right, bottom-left */
    private val corners = List(4) { android.widget.ImageView(ctx) }
    private val unlockBtn = ImageButton(ctx)
    private val toolbar = object : LinearLayout(ctx) {
        /** 独立工具栏不经过字幕根视图，按住时暂停隐藏，松开后重新计时。 */
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> this@CaptionOverlayView.removeCallbacks(hideRunnable)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (visibility == VISIBLE) bumpToolbarTimer()
            }
            return super.dispatchTouchEvent(event)
        }
    }
    private var externalToolbar = false
    private val bg = GradientDrawable().apply { cornerRadius = dp(12f) }

    var textSp = 13f; private set
    var bgAlpha = DEFAULT_ALPHA; private set
    var colors = PALETTES[0]; private set
    private var lastLines: List<Line> = emptyList()
    private var lastStats: String? = null
    var locked = false; private set

    /** Overlay host callbacks (unused for the preview). */
    var onMove: (dx: Int, dy: Int) -> Unit = { _, _ -> }
    var onMoveEnd: () -> Unit = {}
    /** A corner dragged by dx/dy; [left]/[top] say which corner, so the opposite edges stay put. */
    var onResize: (dx: Int, dy: Int, left: Boolean, top: Boolean) -> Unit = { _, _, _, _ -> }
    var onStyleChanged: () -> Unit = {}
    var onStop: () -> Unit = {}
    /** Mode button tapped: the host flips "only finished sentences" in prefs and calls [setFinalOnly]. */
    var onToggleMode: () -> Unit = {}
    private val modeBtn = TextView(ctx).apply {
        setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(6f).toInt(), 0, dp(6f).toInt(), 0)
        minWidth = dp(48f).toInt()
        setOnClickListener { onToggleMode(); bumpToolbarTimer() }
    }
    /** 同步工具栏与草稿伸展预算；仅定稿模式不占用草稿空间。 */
    fun setFinalOnly(v: Boolean) {
        setDraftSlotEnabled(!v)
        modeBtn.text = if (v) "说完再出" else "边听边出"
        modeBtn.contentDescription = if (v) "现在每句说完才显示，点一下改为边听边出" else "现在边听边出，点一下改为每句说完再显示"
    }
    /** 原文 / 用时 tapped: the host flips the pref; [setShown] shows the new state. */
    var onToggleJa: () -> Unit = {}
    var onToggleStats: () -> Unit = {}
    private fun switchBtn(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER; minWidth = dp(48f).toInt()
        compoundDrawablePadding = dp(4f).toInt()
        setPadding(dp(4f).toInt(), 0, dp(4f).toInt(), 0)
        setOnClickListener { onClick(); bumpToolbarTimer() }
    }
    private val jaBtn = switchBtn("原文") { onToggleJa() }
    private val statsBtn = switchBtn("用时") { onToggleStats() }
    /** A switch on the toolbar: a lit dot and white label when on, a hollow grey one when off. */
    private fun showSwitch(b: TextView, on: Boolean, what: String) {
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setSize(dp(7f).toInt(), dp(7f).toInt())
            if (on) setColor(0xFFA5B4FC.toInt()) else { setColor(0); setStroke(dp(1f).toInt().coerceAtLeast(1), 0xFF8B8B94.toInt()) }
        }
        b.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
        b.setTextColor(if (on) Color.WHITE else 0xFF8B8B94.toInt())
        b.contentDescription = if (on) "${what}：开，点一下关掉" else "${what}：关，点一下打开"
    }
    /** 同步原文和用时开关；临时原文与译文始终合并在同一行。 */
    fun setShown(ja: Boolean, stats: Boolean) {
        showSwitch(jaBtn, ja, "显示原文"); showSwitch(statsBtn, stats, "显示识别和翻译用时")
        if (draftOriginalShown != ja) {
            draftOriginalShown = ja
            updateDraftSlotHeight()
            setLines(lastLines, lastStats)
        }
    }

    /** 显式启用草稿伸展预算；不依赖当前有没有草稿，宿主预先避让下沿。 */
    fun setDraftSlotEnabled(enabled: Boolean) {
        if (draftSlotEnabled == enabled) return
        draftSlotEnabled = enabled
        updateDraftSlotHeight()
        setLines(lastLines, lastStats)
    }

    /** 仅改变临时区域是否持续绘制背景，不重测布局或改变正式末行锚点。 */
    fun setReserveDraftSpace(enabled: Boolean) {
        if (reserveDraftSpace == enabled) return
        reserveDraftSpace = enabled
        updateDraftBackground()
        invalidate()
    }

    /** 草稿对窗口增加的固定高度，扣除与正文底部共用的留白，不计工具栏。 */
    fun draftSlotHeightPx(): Int = if (draftSlotEnabled) {
        val params = draftSlot.layoutParams as LayoutParams
        (params.height + params.topMargin + params.bottomMargin).coerceAtLeast(0)
    } else 0

    /** 返回正文与草稿预留的固定总高度，供窗口贴屏和工具栏避让使用。 */
    fun measureCaptionBlockHeight(widthPx: Int): Int = measureCardHeight(widthPx) + draftSlotHeightPx()

    private var windowHeightLimit = Int.MAX_VALUE
    private var requestedCaptionMax = Int.MAX_VALUE
    private var requestedStatusMax = Int.MAX_VALUE

    /** 用整个可用窗口作总预算，正文、诊断和透明草稿不能分别占满同一空间。 */
    fun setWindowHeightLimit(heightPx: Int) {
        if (windowHeightLimit == heightPx.coerceAtLeast(0)) return
        windowHeightLimit = heightPx.coerceAtLeast(0)
        updateDraftSlotHeight()
    }

    /** 先扣除草稿伸展预算与内部边距，诊断区只能使用一行正文之外的剩余预算。 */
    private fun applyWindowHeightBudget() {
        val column = card.getChildAt(0)
        val padding = (column?.paddingTop ?: 0) + (column?.paddingBottom ?: 0)
        val room = if (windowHeightLimit == Int.MAX_VALUE) Int.MAX_VALUE else
            (windowHeightLimit - draftSlotHeightPx() - padding).coerceAtLeast(0)
        val statusCap = if (room == Int.MAX_VALUE) requestedStatusMax else
            minOf(requestedStatusMax, (room - minCaptionPx()).coerceAtLeast(0))
        if (stats.maxHeight != statusCap) stats.maxHeight = statusCap
        captionMaxPx = if (room == Int.MAX_VALUE) requestedCaptionMax else minOf(requestedCaptionMax, room - statusCap)
        text.animationHeightLimit = captionMaxPx.coerceAtLeast(0)
        applyCaptionHeight()
    }

    /** 固定预留一行临时字幕；原文开启时合并在同一行，内容变化不改变窗口高度。 */
    private fun updateDraftSlotHeight() {
        val gap = dp(DRAFT_GAP_DP).toInt()
        val bottom = dp(10f).toInt()
        // 中日文字体回退的真实行高可能大于TextView的主字体lineHeight，不能把唯一一行误裁为空。
        val sample = "国あAg🙂"
        val sampleLayout = android.text.StaticLayout.Builder.obtain(sample, 0, sample.length, draftText.paint, 10000)
            .setIncludePad(false).setUseLineSpacingFromFallbacks(true)
            .setLineSpacing(draftText.lineSpacingExtra, draftText.lineSpacingMultiplier).build()
        val singleLineHeight = maxOf(draftText.lineHeight, sampleLayout.height)
        val natural = gap + bottom + singleLineHeight
        val budget = if (windowHeightLimit == Int.MAX_VALUE) natural else minOf(natural, (windowHeightLimit * 0.4f).toInt())
        val sourceHeight = 0
        val translatedHeight = minOf(singleLineHeight, (budget - gap - bottom).coerceAtLeast(0))
        draftText.layoutParams?.let { lp ->
            if (lp.height != translatedHeight) { lp.height = translatedHeight; draftText.layoutParams = lp }
        }
        draftSource.layoutParams?.let { lp ->
            if (lp.height != sourceHeight) { lp.height = sourceHeight; draftSource.layoutParams = lp }
        }
        draftSource.visibility = if (sourceHeight > 0) VISIBLE else GONE
        draftSlot.layoutParams?.let { lp ->
            val height = minOf(budget, gap + bottom + translatedHeight + sourceHeight)
            if (lp.height != height) { lp.height = height; draftSlot.layoutParams = lp }
        }
        draftSlot.visibility = if (draftSlotEnabled) VISIBLE else GONE
        applyWindowHeightBudget()
    }

    /** 临时背景单独过渡，不改变草稿布局预算或正式文字位置。 */
    private fun updateDraftBackground() {
        val target = draftSlotEnabled && (reserveDraftSpace || draftText.visibility == VISIBLE)
        if (draftBackgroundTarget == target) return
        draftBackgroundTarget = target
        val from = draftBackgroundFraction
        draftBackgroundAnimator?.cancel()
        draftBackgroundAnimator = null
        val to = if (target) 1f else 0f
        if (!isAttachedToWindow || !isLaidOut || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
            draftBackgroundFraction = to
            return
        }
        draftBackgroundAnimator = android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = 200L
            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { draftBackgroundFraction = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** 统一绘制连续圆角背景；正文由视口高度过渡，草稿只改变背景下沿。 */
    override fun dispatchDraw(canvas: Canvas) {
        val extension = if (draftSlotEnabled) (draftSlot.bottom - card.bottom).coerceAtLeast(0) else 0
        val bottom = card.bottom + (extension * draftBackgroundFraction).toInt()
        bg.setBounds(card.left, card.top, card.right, bottom)
        bg.draw(canvas)
        super.dispatchDraw(canvas)
    }

    /** 伸展中的背景没有覆盖到的草稿先裁切，文字始终位于黑框内，工具栏保持独立。 */
    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (child !== draftSlot) return super.drawChild(canvas, child, drawingTime)
        val save = canvas.save()
        canvas.clipRect(bg.bounds)
        val drawn = super.drawChild(canvas, child, drawingTime)
        canvas.restoreToCount(save)
        return drawn
    }

    /** 离开页面或停止服务立即停止背景回调，重新附着时显示已选择的最终状态。 */
    override fun onDetachedFromWindow() {
        draftBackgroundAnimator?.cancel(); draftBackgroundAnimator = null
        draftBackgroundFraction = if (draftBackgroundTarget) 1f else 0f
        super.onDetachedFromWindow()
    }

    /** 返回最后一行正式字幕的基线偏移，草稿与耗时信息不参与定位。 */
    fun finalLineBaselinePx(): Int {
        val placed = text.layout
        val lines = placed?.lineCount ?: 0
        val baseline = if (placed != null && lines > 0) {
            text.measuredHeight - text.extendedPaddingBottom - (placed.height - placed.getLineBaseline(lines - 1))
        } else text.baseline
        return (card.getChildAt(0) as LinearLayout).paddingTop + baseline
    }

    /** 基线在整个悬浮窗口中的位置，包含工具栏或宿主布局引入的偏移。 */
    internal fun finalLineBaselineInWindowPx(): Int = card.top + finalLineBaselinePx()
    private fun lightView() = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setTextColor(0xFFDADCE0.toInt())
        gravity = Gravity.CENTER_VERTICAL; compoundDrawablePadding = dp(4f).toInt()
        setPadding(dp(8f).toInt(), 0, dp(8f).toInt(), 0)
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val mtLight = lightView()
    private val jevLight = lightView()
    private fun showLight(t: TextView, name: String, l: Light, why: String?) {
        val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setSize(dp(7f).toInt(), dp(7f).toInt()); setColor(l.color) }
        t.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
        // every state has a word (a light is never told by its colour alone); grey says "未开启" for a switched-off
        // service; the full reason of a fault is in the warning line
        val word = if (l == Light.GREY && why == "未开启") why else l.word
        t.text = "$name $word"
        t.contentDescription = "$name $word${why?.takeIf { it != word }?.let { "，$it" } ?: ""}"
    }
    /** The toolbar's service row: the translator and Jev, each with a light and, when not fine, why. */
    fun setServices(mt: Light, jev: Light, mtWhy: String?, jevWhy: String?) {
        showLight(mtLight, "翻译", mt, mtWhy); showLight(jevLight, "Jev", jev, jevWhy)
    }
    /** 通知宿主切换工具栏独立浮层，不调整字幕窗口本身。 */
    var onToolbarShown: (Boolean) -> Unit = {}

    /** 实际服务将工具栏交给独立浮层；预览与独立视图测试保留原有内部宿主。 */
    internal fun takeToolbarForWindow(): View {
        if (!externalToolbar) {
            removeView(toolbar)
            externalToolbar = true
        }
        return toolbar
    }

    /** 同步黑框和草稿槽的像素宽度，工具栏独立控制自身宽度。 */
    fun setCardWidth(px: Int) {
        val lp = card.layoutParams
        if (lp.width != px) { lp.width = px; card.layoutParams = lp }
        val draftLp = draftSlot.layoutParams
        if (draftLp.width != px) { draftLp.width = px; draftSlot.layoutParams = draftLp }
    }
    /** 同步黑框与草稿槽的横向偏移，工具栏放在整个字幕块的上方或下方，并按宿主计算的重叠避让。 */
    fun placeCard(cardOffset: Int, toolbarOffset: Int, toolbarAbove: Boolean, overlapPx: Int) {
        val clp = card.layoutParams as LayoutParams
        if (clp.marginStart != cardOffset) { clp.marginStart = cardOffset; card.layoutParams = clp }
        val dlp = draftSlot.layoutParams as LayoutParams
        if (dlp.marginStart != cardOffset) { dlp.marginStart = cardOffset; draftSlot.layoutParams = dlp }
        if (externalToolbar) return
        val m = dp(TOOLBAR_GAP_DP).toInt() - overlapPx
        val tlp = toolbar.layoutParams as LayoutParams
        val top = if (toolbarAbove) 0 else m; val bottom = if (toolbarAbove) m else 0
        if ((indexOfChild(toolbar) == 0) != toolbarAbove) {
            removeView(toolbar)
            tlp.topMargin = top; tlp.bottomMargin = bottom; tlp.marginStart = toolbarOffset
            addView(toolbar, if (toolbarAbove) 0 else childCount, tlp)
        } else if (tlp.marginStart != toolbarOffset || tlp.topMargin != top || tlp.bottomMargin != bottom) {
            tlp.topMargin = top; tlp.bottomMargin = bottom; tlp.marginStart = toolbarOffset; toolbar.layoutParams = tlp
        }
    }
    /** Caption card height in px at [widthPx], with the current text and caption height (measured, not laid out). */
    fun measureCardHeight(widthPx: Int): Int {
        card.measure(MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return card.measuredHeight
    }
    /** Height the open toolbar adds to the window, gap included. */
    fun toolbarHeightPx(): Int {
        toolbar.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return toolbar.measuredHeight + dp(TOOLBAR_GAP_DP).toInt()
    }

    /**
     * Caps caption and status heights so the toolbar keeps room on screen. Both are bottom-aligned, so when
     * they overflow (narrow card, big font) TextView itself keeps the newest lines in view, also after a
     * font or width change.
     */
    fun setMaxContentHeight(captionPx: Int, statusPx: Int) {
        requestedCaptionMax = captionPx.coerceAtLeast(0)
        requestedStatusMax = statusPx.coerceAtLeast(0)
        applyWindowHeightBudget()
    }

    private var captionMaxPx = Int.MAX_VALUE
    private var captionFixedPx = 0
    /** 设置原文和译文合计的实际排版行上限；零保持不限行，其他值约束在一到二十。 */
    fun setMaxCaptionLines(value: Int) {
        val limit = value.coerceIn(0, 20)
        if (text.captionLineLimit == limit) return
        text.captionLineLimit = limit
        applyCaptionHeight()
        text.requestLayout()
    }

    /** 固定正文高度，零为自动；超出后显示最新尾部，手指缩放可关闭补间以保持跟手。 */
    fun setCaptionHeight(px: Int, animate: Boolean = true) {
        if (!animate) text.followResizeGesture()
        captionFixedPx = px; applyCaptionHeight()
    }
    /** 限行时严格遵守高度上限；不限行时保留原有的最小一行及固定高度行为。 */
    private fun applyCaptionHeight() {
        if (text.captionLineLimit > 0) {
            val cap = captionMaxPx.coerceAtLeast(0)
            val fixed = if (captionFixedPx > 0) captionFixedPx.coerceAtMost(cap) else 0
            text.minHeight = fixed
            text.maxHeight = if (captionFixedPx > 0) fixed else cap
            return
        }
        val minimum = if (windowHeightLimit == Int.MAX_VALUE) minCaptionPx() else minOf(minCaptionPx(), captionMaxPx)
        val want = if (captionFixedPx > 0) captionFixedPx.coerceIn(minimum, captionMaxPx.coerceAtLeast(minimum)) else 0
        if (want > 0) { if (text.minHeight != want) text.minHeight = want; if (text.maxHeight != want) text.maxHeight = want }
        else { if (text.minHeight != 0) text.minHeight = 0; if (text.maxHeight != captionMaxPx) text.maxHeight = captionMaxPx }
    }
    /** one caption line: the area never gets smaller than that */
    fun minCaptionPx() = text.lineHeight + text.paddingTop + text.paddingBottom
    /** Current caption area height (for starting a resize drag from what is on screen). */
    fun captionHeightPx() = text.height

    /** Natural width of the toolbar, independent of the current window width. */
    fun toolbarWidthPx(): Int {
        toolbar.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return toolbar.measuredWidth
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false

        text.apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            setShadowLayer(dp(3f), 0f, dp(1f), 0xCC000000.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(dp(2f), 1f)
        }
        stats.apply {
            setTextColor(0xFFA5B4FC.toInt())
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            visibility = GONE
        }
        val column = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(dp(18f).toInt(), dp(10f).toInt(), dp(18f).toInt(), dp(10f).toInt())
            addView(text, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(stats, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        card.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        // resize marks: a faint "›" in each corner, pointing out of the card, in the caption colour. Only drawn;
        // the card's touch listener finds the corner (see installGestures).
        val markRot = floatArrayOf(225f, 315f, 45f, 135f)
        val markGrav = intArrayOf(Gravity.TOP or Gravity.START, Gravity.TOP or Gravity.END, Gravity.BOTTOM or Gravity.END, Gravity.BOTTOM or Gravity.START)
        corners.forEachIndexed { k, c ->
            c.visibility = GONE
            c.setImageResource(R.drawable.ic_chevron_right); c.rotation = markRot[k]; c.alpha = 0.5f
            card.addView(c, FrameLayout.LayoutParams(dp(14f).toInt(), dp(14f).toInt(), markGrav[k]).apply {
                val m = dp(3f).toInt(); setMargins(m, m, m, m)
            })
        }
        // locked: a tap shows this in the bottom-right corner for a few seconds; tapping it unlocks
        unlockBtn.apply {
            setImageResource(R.drawable.ic_lock_open); contentDescription = "解锁位置和大小"
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE626262B.toInt()); setStroke(dp(0.5f).toInt().coerceAtLeast(1), 0x33FFFFFF) }
            val p = dp(8f).toInt(); setPadding(p, p, p, p)
            visibility = GONE
            setOnClickListener { setLocked(false) }
        }
        card.addView(unlockBtn, FrameLayout.LayoutParams(dp(48f).toInt(), dp(48f).toInt(), Gravity.BOTTOM or Gravity.END).apply {
            val m = dp(3f).toInt(); setMargins(m, m, m, m)
        })
        // the card keeps its own place inside the window (see placeCard), and so does the toolbar
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.START })

        draftText.apply {
            includeFontPadding = false
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            typeface = this@CaptionOverlayView.text.typeface
            setLineSpacing(dp(2f), 1f)
            captionLineLimit = 1
        }
        draftSource.apply {
            includeFontPadding = false
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        draftSlot.apply {
            orientation = VERTICAL
            clipChildren = true
            setPadding(dp(18f).toInt(), dp(DRAFT_GAP_DP).toInt(), dp(18f).toInt(), dp(10f).toInt())
            addView(draftSource, LayoutParams(LayoutParams.MATCH_PARENT, 0))
            addView(draftText, LayoutParams(LayoutParams.MATCH_PARENT, 0))
            visibility = GONE
        }
        addView(draftSlot, LayoutParams(LayoutParams.MATCH_PARENT, 0).apply {
            gravity = Gravity.START
            // 在真实布局中共用正文底部留白，不能只移动绘制位置而留下透明尾部。
            topMargin = -dp(10f).toInt()
        })

        buildToolbar()
        addView(toolbar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.START; topMargin = dp(TOOLBAR_GAP_DP).toInt() })
        if (interactive) installGestures()
        applyStyle(textSp, bgAlpha)
    }

    /** 应用字号和背景样式，同时重建原文的绝对字号 Span 以便重新排版。 */
    fun applyStyle(sizeSp: Float, alpha: Float) {
        if (textSp != sizeSp) text.finishTransitions()
        text.refreshSpanStyle()
        draftText.refreshSpanStyle()
        textSp = sizeSp; bgAlpha = alpha
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        draftText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp * 0.85f)
        draftSource.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp * 0.62f)
        draftText.setTextColor(colors.draft)
        draftSource.setTextColor(colors.ja)
        // 草稿与定稿共用背景；浅色配色不绘制深色阴影。
        val draftShadow = if (colors.shadow) dp(3f) else 0f
        draftText.setShadowLayer(draftShadow, 0f, dp(1f), 0xEE000000.toInt())
        draftSource.setShadowLayer(draftShadow, 0f, dp(1f), 0xEE000000.toInt())
        updateDraftSlotHeight()
        applyCaptionHeight() // a fixed height is clamped to one line, which depends on the font size
        bg.setColor(colors.bg and 0x00FFFFFF or ((alpha * 255).toInt().coerceIn(0, 255) shl 24))
        // with a fully transparent card the text keeps a stronger shadow so it stays readable on video;
        // dark text on a light card has none (a black shadow would only blur it)
        if (colors.shadow) text.setShadowLayer(dp(if (alpha < 0.2f) 4f else 3f), 0f, dp(1f), 0xEE000000.toInt())
        else text.setShadowLayer(0f, 0f, 0f, 0)
        // magpie's hairline edge on a dark card, so it stands off a dark video; none once the card is nearly clear
        bg.setStroke(dp(0.5f).toInt().coerceAtLeast(1), if (colors.shadow && alpha >= 0.2f) 0x1AFFFFFF else 0)
        corners.forEach { it.setColorFilter(colors.zh) }
        setLines(lastLines, lastStats)
    }

    /** Text / background colours of the captions; the background keeps the user's opacity. */
    class Palette(val id: String, val label: String, val zh: Int, val ja: Int, val draft: Int, val stats: Int, val bg: Int, val shadow: Boolean = true)

    /** 切换字幕配色，并同步正式正文与临时区域的样式。 */
    fun setColors(p: Palette) {
        if (p === colors) return
        text.finishTransitions()
        colors = p
        text.setTextColor(p.zh); stats.setTextColor(p.stats)
        applyStyle(textSp, bgAlpha)
    }

    /** 一条字幕包含译文、可选原文、定稿标记与稳定段编号；长句仍按原生规则换行。 */
    class Line(val zh: String, val ja: String?, val isFinal: Boolean, val key: Long? = null)

    /** 草稿使用独立布局与统一背景；保留传入快照的显示裁剪结果，不修改字幕板。 */
    fun setLines(lines: List<Line>, statsLine: String?) {
        val before = lastLines.filter { it.isFinal }
        val after = lines.filter { it.isFinal }
        val newTail = after.lastOrNull()
        val priorTail = before.lastOrNull()
        val added = newTail != null && (priorTail == null || if (newTail.key != null && priorTail.key != null)
            newTail.key != priorTail.key else before.any { old -> after.any { it.zh == old.zh } } && newTail.zh != priorTail.zh)
        lastLines = lines; lastStats = statsLine
        var enteringOffset: Int? = null
        val sb = SpannableStringBuilder()
        val jaPx = (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSp * 0.62f, resources.displayMetrics)).toInt()
        for (l in lines) {
            if (draftSlotEnabled && !l.isFinal) continue
            if (sb.isNotEmpty()) sb.append('\n')
            if (added && l === newTail) enteringOffset = sb.length
            if (!l.ja.isNullOrEmpty()) {
                val s = sb.length
                sb.append(l.ja).append('\n')
                sb.setSpan(AbsoluteSizeSpan(jaPx), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(colors.ja), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val s = sb.length
            // the draft line is visibly separate from the finished sentences above: dimmer, smaller, marked "…"
            if (!l.isFinal) sb.append("… ")
            sb.append(l.zh)
            if (!l.isFinal) {
                sb.setSpan(ForegroundColorSpan(colors.draft), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(android.text.style.RelativeSizeSpan(0.85f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        text.setCaption(sb, enteringOffset)
        val draft = if (draftSlotEnabled) lines.lastOrNull { !it.isFinal } else null
        draftSource.text = draft?.ja ?: ""
        val draftCaption = SpannableStringBuilder()
        if (draft != null) {
            if (draftOriginalShown && !draft.ja.isNullOrEmpty()) {
                draftCaption.append(draft.ja.replace('\n', ' ')).append(" · ")
                draftCaption.setSpan(ForegroundColorSpan(colors.ja), 0, draftCaption.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            draftCaption.append("… ").append(draft.zh.replace('\n', ' '))
        }
        draftText.setCaption(draftCaption)
        // 空槽仍参与测量，只隐藏字形；不能用 GONE 折叠整个草稿区。
        draftText.visibility = if (draft == null) INVISIBLE else VISIBLE
        updateDraftBackground()
        stats.text = statsLine ?: ""
        stats.visibility = if (statsLine.isNullOrEmpty()) GONE else VISIBLE
        invalidate()
    }

    /**
     * Two rows, both as wide as the wider one, every cell sharing that width (no gaps):
     *   字号  A−  A+   │  背景  −  +
     *   边听边出 │ ● 原文 │ ● 用时 │ 🔒 │ ✕
     *   ● 翻译 │ ● Jev 受限     (service lights, read only; the reason is in the warning line)
     * The two switches carry a dot that is lit when on, so they read as switches. As small as a finger allows.
     */
    private fun buildToolbar() {
        toolbar.apply {
            orientation = VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(10f); setColor(0xF526262B.toInt()); setStroke(dp(0.5f).toInt().coerceAtLeast(1), 0x1AFFFFFF) }
            setPadding(dp(4f).toInt(), dp(2f).toInt(), dp(4f).toInt(), dp(2f).toInt())
            elevation = dp(6f)
            // the hairline between the rows: a divider, not a MATCH_PARENT child, which would stretch the toolbar
            // to the whole window (as wide as the caption card) instead of its buttons
            dividerDrawable = GradientDrawable().apply { setColor(0x1AFFFFFF); setSize(0, dp(0.5f).toInt().coerceAtLeast(1)) }
            showDividers = SHOW_DIVIDER_MIDDLE
            visibility = GONE
        }
        val size = dp(48f).toInt() // 所有可点按工具至少提供 48dp 触控区域
        // a cell takes its share of the row (weight); its natural size is the minimum
        fun cell(weight: Float = 1f) = LayoutParams(LayoutParams.WRAP_CONTENT, size, weight)
        fun btn(icon: Int, label: String, onClick: () -> Unit) = ImageButton(context).apply {
            setImageResource(icon); contentDescription = label
            background = null; val p = dp(9f).toInt(); setPadding(p, p, p, p)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            minimumWidth = size
            layoutParams = cell()
            setOnClickListener { onClick(); bumpToolbarTimer() }
        }
        fun tag(t: String) = TextView(context).apply {
            text = t; setTextColor(0xFF8B8B94.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER; setPadding(dp(6f).toInt(), 0, dp(2f).toInt(), 0)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, size)
        }
        fun sep() = View(context).apply { setBackgroundColor(0x1AFFFFFF); layoutParams = LayoutParams(dp(1f).toInt(), dp(16f).toInt()).apply { marginStart = dp(2f).toInt(); marginEnd = dp(2f).toInt() } }
        fun tbtn(label: String, desc: String, onClick: () -> Unit) = TextView(context).apply {
            text = label; contentDescription = desc
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; minWidth = size
            setPadding(dp(2f).toInt(), 0, dp(2f).toInt(), 0)
            layoutParams = cell()
            setOnClickListener { onClick(); bumpToolbarTimer() }
        }
        fun row() = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val full = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        // two halves, each a label with its −/+ kept together (the spare width goes around a group, not inside it)
        fun group(label: String, minus: View, plus: View) = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER
            addView(tag(label)); addView(minus.apply { layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, size) })
            addView(plus.apply { layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, size) })
        }
        toolbar.addView(row().apply {
            addView(group("字号", tbtn("A−", "字号减小") { setSize(textSp - 2) }, tbtn("A+", "字号增大") { setSize(textSp + 2) }), cell())
            addView(sep())
            addView(group("背景", tbtn("−", "背景更透明") { setAlphaStep(bgAlpha - 0.15f) }, tbtn("+", "背景更不透明") { setAlphaStep(bgAlpha + 0.15f) }), cell())
        }, full)
        toolbar.addView(row().apply {
            modeBtn.layoutParams = cell(1.4f); addView(modeBtn)
            addView(sep())
            jaBtn.layoutParams = cell(); addView(jaBtn)
            statsBtn.layoutParams = cell(); addView(statsBtn)
            addView(sep())
            addView(btn(R.drawable.ic_lock, "锁定位置和大小") { toggleLock() })
            addView(btn(R.drawable.ic_close, "停止字幕") { onStop() }.apply { setColorFilter(0xFFF28B82.toInt()) })
        }, full)
        // read only: each half shares the width; short words only, so the row never widens the toolbar
        toolbar.addView(row().apply {
            addView(mtLight, LayoutParams(0, dp(28f).toInt(), 1f))
            addView(sep())
            addView(jevLight, LayoutParams(0, dp(28f).toInt(), 1f))
        }, full)
        setServices(Light.GREY, Light.GREY, null, null)
    }

    private fun setSize(v: Float) { applyStyle(v.coerceIn(MIN_SP, MAX_SP), bgAlpha); onStyleChanged() }
    private fun setAlphaStep(v: Float) { applyStyle(textSp, v.coerceIn(0f, 1f)); onStyleChanged() }

    fun setLocked(v: Boolean) {
        locked = v
        hideUnlock()
        showCorners()
    }
    private fun toggleLock() { setLocked(!locked); if (locked) hideToolbar() }
    /** the resize marks go with the toolbar: shown (and draggable) only while it is open and the card is unlocked */
    private fun showCorners() {
        val on = interactive && !locked && toolbar.visibility == VISIBLE
        corners.forEach { it.visibility = if (on) VISIBLE else GONE }
    }

    private val hideRunnable = Runnable { hideToolbar() }
    private fun bumpToolbarTimer() { removeCallbacks(hideRunnable); postDelayed(hideRunnable, 4_000) }
    private fun showToolbar() {
        toolbar.visibility = VISIBLE
        showCorners()
        bumpToolbarTimer()
        onToolbarShown(true)
    }
    private fun hideToolbar() {
        if (toolbar.visibility == GONE) return
        toolbar.visibility = GONE
        showCorners()
        onToolbarShown(false)
    }
    private val hideUnlockRunnable = Runnable { unlockBtn.visibility = GONE }
    private fun hideUnlock() { removeCallbacks(hideUnlockRunnable); unlockBtn.visibility = GONE }
    private fun showUnlock() { unlockBtn.visibility = VISIBLE; removeCallbacks(hideUnlockRunnable); postDelayed(hideUnlockRunnable, 3_000) }

    /** A tap on the card: the toolbar when unlocked; when locked only the unlock button. */
    private fun tap() {
        if (locked) { if (unlockBtn.visibility == VISIBLE) hideUnlock() else showUnlock(); return }
        if (toolbar.visibility == VISIBLE) hideToolbar() else showToolbar()
    }

    // the toolbar stays while a finger is down anywhere on the overlay (card or toolbar button), and its timer
    // restarts when the finger lifts
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (interactive) when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> removeCallbacks(hideRunnable)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (toolbar.visibility == VISIBLE) bumpToolbarTimer()
        }
        return super.dispatchTouchEvent(e)
    }
    private fun installGestures() {
        val slop = dp(8f)
        val grab = dp(28f)
        var downX = 0f; var downY = 0f; var lastX = 0f; var lastY = 0f; var moved = false
        // the corner under the finger at ACTION_DOWN (-1: none); only while the toolbar (and so the marks) is shown.
        // One listener on the card picks it, so the four grab areas never overlap: each gets at most half the card.
        var corner = -1
        card.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; lastX = downX; lastY = downY; moved = false
                    val gx = minOf(grab, card.width / 2f); val gy = minOf(grab, card.height / 2f)
                    val l = e.x < gx; val r = e.x > card.width - gx; val t = e.y < gy; val b = e.y > card.height - gy
                    corner = if (locked || toolbar.visibility != VISIBLE) -1 else when { t && l -> 0; t && r -> 1; b && r -> 2; b && l -> 3; else -> -1 }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) moved = true
                    if (moved && !locked) {
                        // whole pixels only: keep the fraction for the next move, or slow drags would not move at all
                        val dx = (e.rawX - lastX).toInt(); val dy = (e.rawY - lastY).toInt()
                        if (dx != 0 || dy != 0) {
                            if (corner >= 0) onResize(dx, dy, corner == 0 || corner == 3, corner <= 1) else onMove(dx, dy)
                            lastX += dx; lastY += dy
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) tap() else if (!locked) onMoveEnd()
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (moved && !locked) onMoveEnd()
                }
            }
            true
        }
    }


    companion object {
        /** Caption size range, shared with the settings slider. */
        const val MIN_SP = 12f
        const val MAX_SP = 36f
        /** Card opacity when the user has not set one: magpie's ink card at 78%. */
        const val DEFAULT_ALPHA = 0.78f
        /** Space between the caption card and the toolbar. */
        const val TOOLBAR_GAP_DP = 6f
        /** 正式正文与临时字幕之间的小间距。 */
        const val DRAFT_GAP_DP = 2f

        /** Caption colour schemes; the first is the default. ids are stored in prefs, keep them stable. */
        val PALETTES = listOf(
            // magpie's ink card (#1a1a1e) with its quiet greys and indigo; the default since 0.6
            Palette("ink", "墨色", 0xFFFFFFFF.toInt(), 0xCCDCDCE4.toInt(), 0xFFB9B9C1.toInt(), 0xFFA5B4FC.toInt(), 0xFF1A1A1E.toInt()),
            Palette("white", "经典白", 0xFFFFFFFF.toInt(), 0xCCDCD8F0.toInt(), 0xFFB9B5C9.toInt(), 0xFFB4ACFA.toInt(), 0xFF100E1A.toInt()),
            Palette("yellow", "影院黄", 0xFFFFE066.toInt(), 0xCCFFF3C4.toInt(), 0xFFCFC48F.toInt(), 0xFFFFC94D.toInt(), 0xFF000000.toInt()),
            Palette("mint", "薄荷绿", 0xFFA7F3D0.toInt(), 0xCCD1FAE5.toInt(), 0xFF8CBBA6.toInt(), 0xFF6EE7B7.toInt(), 0xFF0B1F1A.toInt()),
            Palette("sky", "天空蓝", 0xFFBAE6FD.toInt(), 0xCCE0F2FE.toInt(), 0xFF93B4C9.toInt(), 0xFF7DD3FC.toInt(), 0xFF0B1726.toInt()),
            Palette("sakura", "樱花粉", 0xFFFBCFE8.toInt(), 0xCCFCE7F3.toInt(), 0xFFC9A3B8.toInt(), 0xFFF9A8D4.toInt(), 0xFF24101C.toInt()),
            Palette("paper", "白底黑字", 0xFF16151C.toInt(), 0xCC3D3B48.toInt(), 0xFF6E6C7A.toInt(), 0xFF5B4BD6.toInt(), 0xFFFFFFFF.toInt(), shadow = false),
        )
        fun palette(id: String?) = PALETTES.firstOrNull { it.id == id } ?: PALETTES[0]
    }
}
