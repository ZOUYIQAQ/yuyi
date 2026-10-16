package io.github.korenkrita.simulsub

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.view.Gravity
import android.view.animation.PathInterpolator
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.abs
import kotlin.math.roundToInt

/** 保留原生富文本排版和末行对齐，独立处理高度过渡与正文滚动，不移动系统窗口锚点。 */
internal class CaptionTextView(ctx: Context, private val animateChanges: Boolean = false) : AppCompatTextView(ctx) {
    var captionLineLimit = 0
    var animationHeightLimit = Int.MAX_VALUE
    private var fullCaption: CharSequence = ""
    private var shortened = false
    private var spanStyleDirty = false
    private var hasDrawn = false
    private var lastDrawnHeight = 0
    private var measuredWidthKey = 0
    private var naturalHeight = -1
    private var movingHeight: Float? = null
    private var heightStepPosted = false
    private var snapHeightChange = false
    private var heightFrom = 0f
    private var heightElapsed = 0L
    private var heightFrameTime = 0L
    /** 只有上一尺寸真正绘制后才推进，低帧率或定位守卫重排也不会吞掉全部中间帧。 */
    private val heightStep = Runnable {
        heightStepPosted = false
        if (movingHeight != null) {
            val now = android.os.SystemClock.uptimeMillis()
            heightElapsed = if (!ValueAnimator.areAnimatorsEnabled()) 280L else
                (heightElapsed + (now - heightFrameTime).coerceIn(16L, 64L)).coerceAtMost(280L)
            heightFrameTime = now
            movingHeight = if (heightElapsed >= 280L) null else
                heightFrom + (naturalHeight - heightFrom) * easing.getInterpolation(heightElapsed / 280f)
            requestLayout(); invalidate()
        }
    }
    private val outgoingPaint = Paint()
    private var scrollAnimator: ValueAnimator? = null
    private var previousFrame: Bitmap? = null
    private var incomingOffset: Int? = null
    private var scrollFraction = 1f
    private var scrollDistance = 0f
    private val easing = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** 换色或混合字号变化后重建富文本，不把相同文字误当新句重播。 */
    fun refreshSpanStyle() { spanStyleDirty = true }

    /** 手指直接缩放时跟随手势，不让补间动画追赶手指；下一次普通配置变化仍可过渡。 */
    fun followResizeGesture() { finishTransitions(); snapHeightChange = true }

    /** 保存完整富文本；只有新增正式句时快照当前画面，草稿刷新不重启动画。 */
    fun setCaption(value: CharSequence, enteringOffset: Int? = null) {
        val unchanged = TextUtils.equals(fullCaption, value)
        if (unchanged && enteringOffset == null && !spanStyleDirty) return
        if (enteringOffset != null && canAnimate() && width > 0 && height > 0) {
            val snapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(snapshot)
            canvas.translate(-scrollX.toFloat(), -scrollY.toFloat())
            draw(canvas)
            stopScroll()
            previousFrame = snapshot
            incomingOffset = enteringOffset
            scrollFraction = 0f
        } else if (!unchanged && enteringOffset == null) stopScroll()
        fullCaption = value
        shortened = false
        spanStyleDirty = false
        text = value
    }

    /** 限行规则沿用原生排版，高度只在目标变化时开始一次过渡，连续测量不会反复重启。 */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (shortened) { text = fullCaption; shortened = false }
        // 先独立取得自然目标，避免系统浮窗的上一帧尺寸反过来改写动画终点。
        val contentHeightSpec = if (animateChanges) MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED) else heightMeasureSpec
        super.onMeasure(widthMeasureSpec, contentHeightSpec)
        if (captionLineLimit > 0) {
            scrollTo(0, 0)
            while (text.isNotEmpty()) {
                val placed = layout ?: break
                val available = measuredHeight - compoundPaddingTop - compoundPaddingBottom
                val first = (placed.lineCount - captionLineLimit).coerceAtLeast(0)
                if (first == 0 && placed.height <= available) break
                val skip = first.coerceAtLeast(1)
                val start = if (skip < placed.lineCount) placed.getLineStart(skip) else text.length
                text = text.subSequence(start, text.length)
                shortened = true
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
        val target = measuredHeight.coerceAtMost(animationHeightLimit)
        val widthChanged = measuredWidthKey != measuredWidth
        measuredWidthKey = measuredWidth
        if (widthChanged) finishTransitions()
        if (naturalHeight != target) {
            // 预算收紧必须立即服从新上限，不能让收缩动画暂时越界。
            val bound = minOf(animationHeightLimit, if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED)
                Int.MAX_VALUE else MeasureSpec.getSize(heightMeasureSpec))
            // 连续调整只能从已经呈现的尺寸衔接，不能从尚待窗口定位的下一帧开始。
            val from = (lastDrawnHeight.takeIf { it > 0 } ?: height).toFloat().coerceAtMost(bound.toFloat())
            naturalHeight = target
            removeCallbacks(heightStep); heightStepPosted = false
            if (!snapHeightChange && !widthChanged && canAnimate() && from > 0 && abs(from - target) > 1f) {
                movingHeight = from
                heightFrom = from
                heightElapsed = 0L
                heightFrameTime = android.os.SystemClock.uptimeMillis()
            } else movingHeight = null
        }
        if (animateChanges) {
            val bound = minOf(animationHeightLimit, if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED)
                Int.MAX_VALUE else MeasureSpec.getSize(heightMeasureSpec)).coerceAtLeast(0)
            setMeasuredDimension(measuredWidth, (movingHeight?.roundToInt() ?: target).coerceIn(0, bound))
        }
        snapHeightChange = false
        // 定位守卫在预绘制末尾重新测量时，TextView可能清掉内部滚动。
        // 立即按当前真实视口恢复尾部，不能等下一次布局或预绘制再纠正一帧。
        keepLatestAtBottom()
    }

    /** 排版后确定真实新句高度，快照与新内容沿同一方向移动，分界裁切避免重叠重影。 */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        keepLatestAtBottom()
        startPendingScroll()
    }

    /** 相同总高度的新正文未必触发布局，因此也在预绘制时启动待处理滚动。 */
    private fun startPendingScroll() {
        val offset = incomingOffset ?: return
        val placed = layout ?: return
        incomingOffset = null
        val line = placed.getLineForOffset(offset.coerceIn(0, text.length))
        // 保留句从旧位置进入新排版时，位移是末行基线间距，包含上一行的行间距。
        // 末行矩形高度不含这段间距，用它代替会在零进度先跳几像素，再开始滚动。
        scrollDistance = (if (line > 0) placed.getLineBaseline(placed.lineCount - 1) - placed.getLineBaseline(line - 1)
            else placed.height).toFloat().coerceIn(1f, height.coerceAtLeast(1).toFloat())
        scrollAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280L
            interpolator = easing
            addUpdateListener { scrollFraction = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                /** 结束后仅绘制原生正文，及时释放旧帧。 */
                override fun onAnimationEnd(animation: Animator) {
                    previousFrame?.recycle(); previousFrame = null
                    scrollAnimator = null; scrollFraction = 1f; invalidate()
                }
            })
            start()
        }
    }

    /** 所有正文位移仅发生在内部绘制，临时字幕与工具栏不参与这条动画。 */
    override fun onDraw(canvas: Canvas) {
        hasDrawn = true
        if (canvas.isHardwareAccelerated || !isHardwareAccelerated) lastDrawnHeight = height
        if (movingHeight != null && !heightStepPosted) {
            heightStepPosted = true
            postOnAnimation(heightStep)
        }
        // 原生TextView在总高度相同且无需自动滚动时也可能不注册预绘制回调，绘制入口兜底一次。
        startPendingScroll()
        val old = previousFrame
        if (old == null || old.isRecycled) { super.onDraw(canvas); return }
        val progress = scrollFraction
        val shift = scrollDistance * (1f - progress)
        val placed = layout
        val room = height - compoundPaddingTop - compoundPaddingBottom
        val verticalOffset = if ((gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM)
            (room - (placed?.height ?: 0)).coerceAtLeast(0) else 0
        val incomingTop = compoundPaddingTop + verticalOffset - scrollY + shift
        val outer = canvas.save()
        canvas.translate(scrollX.toFloat(), scrollY.toFloat())
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        val oldSave = canvas.save()
        // 新画面包含保留句；旧帧只负责即将退出的顶部，固定框有留白时也平滑淡出。
        canvas.clipRect(0f, 0f, width.toFloat(), incomingTop.coerceAtLeast(0f))
        outgoingPaint.alpha = ((1f - progress) * 255).roundToInt()
        canvas.drawBitmap(old, 0f, height - old.height - scrollDistance * progress, outgoingPaint)
        canvas.restoreToCount(oldSave)
        if (placed != null) {
            // TextView会在内部再次裁到未平移的视口；增高时这会裁掉本应从旧位置进入的保留行。
            // 过渡期间直接绘制同一原生富文本Layout，裁切只交给外层固定视口，终帧恢复原生绘制。
            paint.color = currentTextColor
            paint.drawableState = drawableState
            canvas.translate(compoundPaddingLeft - scrollX.toFloat(), compoundPaddingTop + verticalOffset - scrollY + shift)
            placed.draw(canvas)
        }
        canvas.restoreToCount(outer)
    }

    /** 框架预绘制可能自行滚动，随后仍按固定视口显示最新尾部。 */
    override fun onPreDraw(): Boolean {
        val ready = super.onPreDraw()
        keepLatestAtBottom()
        startPendingScroll()
        return ready
    }

    /** 保持原生末行定位，动画不通过滚动宿主或移动窗口实现。 */
    private fun keepLatestAtBottom() {
        if (captionLineLimit == 0 && (gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM) {
            val room = height - compoundPaddingTop - compoundPaddingBottom
            scrollTo(0, ((layout?.height ?: 0) - room).coerceAtLeast(0))
        }
    }

    /** 无首帧或系统关闭动画时直接到最终状态，避免空白及违反系统减弱动态效果设置。 */
    private fun canAnimate() = animateChanges && isAttachedToWindow && hasDrawn && ValueAnimator.areAnimatorsEnabled()

    /** 切换宽度、字号或离开窗口时终止过期过渡，不保留位图或后台回调。 */
    fun finishTransitions() {
        removeCallbacks(heightStep); heightStepPosted = false; movingHeight = null
        stopScroll()
    }

    /** 取消旧滚动后才能创建下一张快照，结束监听不会误回收新画面。 */
    private fun stopScroll() {
        scrollAnimator?.cancel(); scrollAnimator = null
        previousFrame?.recycle(); previousFrame = null
        incomingOffset = null; scrollFraction = 1f
    }

    /** 视图释放时立即停止动画并回收旧帧，重新附着后从真实首帧开始。 */
    override fun onDetachedFromWindow() {
        finishTransitions(); hasDrawn = false; lastDrawnHeight = 0
        super.onDetachedFromWindow()
    }
}
