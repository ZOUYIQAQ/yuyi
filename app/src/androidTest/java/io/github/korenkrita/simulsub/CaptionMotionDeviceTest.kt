package io.github.korenkrita.simulsub

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 使用附着到真实窗口的原生字幕验证过渡帧，不启动录音或写入业务配置。 */
class CaptionMotionDeviceTest {
    /** 六行替换为五行，保留句在滚动起点和中间帧均不反向抖动。 */
    @Test fun wrappedSentencesShrinkWithoutReversingRetainedText() = verifyWrappedTransition(false, false)

    /** 五行替换为六行，增高不能使同一保留句先下跳。 */
    @Test fun wrappedSentencesGrowWithoutReversingRetainedText() = verifyWrappedTransition(false, true)

    /** 原文与译文混合字号时也按真实基线衔接。 */
    @Test fun wrappedOriginalsShrinkWithoutReversingRetainedText() = verifyWrappedTransition(true, false)

    /** 混合字号增高时，文字仍从上一帧原位进入滚动。 */
    @Test fun wrappedOriginalsGrowWithoutReversingRetainedText() = verifyWrappedTransition(true, true)

    /** 追踪同一保留句的可见像素，布局坐标正确不能替代真实画面的连续性。 */
    private fun verifyWrappedTransition(original: Boolean, growing: Boolean) = withOverlay { view ->
        val label = "变行-$original-$growing"
        val lines = listOf(if (growing) "旧短句" else "甲".repeat(20), "乙".repeat(20), "丙".repeat(20),
            if (growing) "丁".repeat(20) else "新的一句")
            .mapIndexed { index, value -> CaptionOverlayView.Line(value, if (original) "原文$index" else null, true, index.toLong()) }
        onMain { view.setShown(original, false); view.setCaptionHeight(0); view.setLines(lines.take(3), null) }
        Thread.sleep(600)
        val body = onMain { ((view.getChildAt(0) as android.widget.FrameLayout).getChildAt(0) as android.widget.LinearLayout).getChildAt(0) as android.widget.TextView }
        val initial = onMain {
            assertEquals("复现必须包含自然换行", (if (growing) 5 else 6) + if (original) 3 else 0, body.lineCount)
            formalCapture(view).also { save(view, "$label-起点", it) }
        }
        val templateLine = (if (growing) 1 else 2) + if (original) 2 else 0
        val templateTop = onMain { body.baseline + body.layout.getLineBaseline(templateLine) - body.layout.getLineBaseline(0) - body.scrollY - body.textSize.toInt() }
        val template = Bitmap.createBitmap(initial, 0, templateTop, initial.width, onMain { body.textSize.toInt() })
        val frames = java.util.concurrent.CopyOnWriteArrayList<Bitmap>()
        val initialPosition = locateWhiteTemplate(template, initial) - initial.height
        val observer = android.view.ViewTreeObserver.OnDrawListener { frames += formalCapture(view) }
        try {
            onMain {
                view.setLines(lines.drop(1), null)
                view.measure(View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                view.layout(view.left, view.top, view.right, view.top + view.measuredHeight)
                val first = formalCapture(view)
                try {
                    save(view, "$label-零进度", first)
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                        putString("stream", "$label：旧高=${initial.height}，新高=${first.height}，排版=${body.layout.height}，滚动=${body.scrollY}，基线=${body.baseline}\n")
                    })
                    assertEquals("滚动零进度必须与上一帧无缝衔接", initialPosition,
                        locateWhiteTemplate(template, first) - first.height)
                } finally { first.recycle() }
                view.viewTreeObserver.addOnDrawListener(observer)
            }
            Thread.sleep(800)
            onMain {
                view.viewTreeObserver.removeOnDrawListener(observer)
                assertEquals("新句替换后须保持真实换行", (if (growing) 6 else 5) + if (original) 3 else 0, body.lineCount)
            }
            val positions = listOf(initialPosition) + frames.mapIndexed { index, bitmap ->
                save(view, "$label-$index", bitmap)
                locateWhiteTemplate(template, bitmap) - bitmap.height
            }
            assertTrue("保留句必须有实际滚动帧：$positions", positions.size >= 3)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "$label 像素轨迹：$positions\n") })
            assertTrue("同一保留句只能向上移动，不能反向抖动：$positions", positions.zipWithNext().all { (a, b) -> b <= a + 1 })
        } finally {
            onMain { view.viewTreeObserver.removeOnDrawListener(observer) }
            frames.forEach { it.recycle() }; template.recycle(); initial.recycle()
        }
    }

    /** 用原始白字的像素模式追踪保留句，不把内部布局坐标误当真实可见位置。 */
    private fun locateWhiteTemplate(template: Bitmap, bitmap: Bitmap): Int {
        val probes = (0 until template.height step 3).flatMap { y ->
            (0 until template.width step 4).map { x -> Triple(x, y, android.graphics.Color.red(template.getPixel(x, y)) > 210) }
        }
        return (0..(bitmap.height - template.height).coerceAtLeast(0)).minBy { top ->
            probes.count { (x, y, white) -> (android.graphics.Color.red(bitmap.getPixel(x, top + y)) > 210) != white }
        }
    }
    /** 高度必须经过中间值，不能从旧固定高度直接跳到新固定高度。 */
    @Test fun fixedHeightChangesHaveIntermediateFrames() = withOverlay { view ->
        val initial = onMain { view.captionHeightPx() }
        val heights = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val observer = android.view.ViewTreeObserver.OnDrawListener { heights += view.captionHeightPx() }
        onMain { view.viewTreeObserver.addOnDrawListener(observer); view.setCaptionHeight(px(view, 180)) }
        awaitHeight(view, px(view, 180))
        val target = onMain { view.viewTreeObserver.removeOnDrawListener(observer); view.captionHeightPx() }
        assertTrue("高度变化需要真实中间帧：$initial → $heights → $target", heights.any { it > initial && it < target })
        assertEquals(px(view, 180), target)
    }

    /** 新句进入时必须存在旧画面与最终画面之间的滚动帧，草稿刷新不能再次移动正式文字。 */
    @Test fun retainedSentencesScrollAndDraftsDoNotRestartTheTransition() = withOverlay { view ->
        val before = onMain { capture(view) }
        onMain { view.setLines(listOf(line("第二句"), line("第三句"), line("第四句")), null) }
        val frames = (0 until 9).map { index ->
            Thread.sleep(30)
            onMain { capture(view).also { save(view, "滚动-$index", it) } }
        }
        Thread.sleep(200)
        val final = onMain { capture(view) }
        assertTrue("替换新句应有滚动过渡，不能直接切换整块文字", frames.any { !it.sameAs(before) && !it.sameAs(final) })
        val settled = onMain { formalCapture(view).also { save(view, "草稿前正式", it) } }
        for (draft in listOf("实时草稿", "实时草稿继续变化", "")) {
            onMain { view.setLines(listOf(line("第二句"), line("第三句"), line("第四句")) +
                if (draft.isEmpty()) emptyList() else listOf(CaptionOverlayView.Line(draft, null, false)), null) }
            Thread.sleep(40)
            val current = onMain { formalCapture(view).also { save(view, "草稿后正式-${draft.length}", it) } }
            assertTrue("只有草稿变化时不能重播正式字幕动画", current.sameAs(settled))
            current.recycle()
        }
        (frames + listOf(before, final, settled)).forEach { it.recycle() }
    }

    /** 自动高度同样平滑改变，超长句保持真实换行与物理上限。 */
    @Test fun automaticHeightAlsoAnimatesWithoutExceedingItsBudget() = withOverlay { view ->
        onMain { view.setCaptionHeight(0); view.setLines(listOf(line("一条短句")), null) }
        Thread.sleep(400)
        val initial = onMain { view.captionHeightPx() }
        onMain { view.setLines(listOf(line("一条短句"), line("这是新增的较长字幕，需要按观看宽度自然换行。".repeat(4))), null) }
        val heights = (0 until 12).map { Thread.sleep(25); onMain { view.captionHeightPx() } }
        val final = onMain { view.captionHeightPx() }
        assertTrue("自动高度变化也需中间帧：$heights", heights.any { it > initial && it < final })
        assertTrue("动画不得突破原有正文高度上限", heights.all { it <= px(view, 240) })
    }

    /** 有原文、换行和真实滚动偏移时仍有过渡，结束画面必须稳定，不能留下重影或空白。 */
    @Test fun wrappedOriginalAndTranslatedTextFinishAtNativeFinalFrame() = withOverlay { view ->
        onMain {
            view.setShown(true, false)
            view.setLines(listOf(CaptionOverlayView.Line("第一句较长译文。".repeat(10), "一番目の原文。".repeat(8), true, 1),
                CaptionOverlayView.Line("第二句较长译文。".repeat(10), "二番目の原文。".repeat(8), true, 2)), null)
        }
        Thread.sleep(450)
        val before = onMain { capture(view) }
        onMain { view.setLines(listOf(CaptionOverlayView.Line("第二句较长译文。".repeat(10), "二番目の原文。".repeat(8), true, 2),
            CaptionOverlayView.Line("花朵绽放出来吧", "花が咲くでしょう～", true, 3)), null) }
        val frames = (0 until 8).map { index -> Thread.sleep(30); onMain { capture(view).also { save(view, "原文滚动-$index", it) } } }
        Thread.sleep(300)
        val final = onMain { capture(view) }
        assertTrue("混合字号和原文换行也必须有滚动中间帧", frames.any { !it.sameAs(before) && !it.sameAs(final) })
        Thread.sleep(100)
        val settled = onMain { capture(view) }
        assertTrue("完成后应回到稳定的原生最终画面", settled.sameAs(final))
        (frames + listOf(before, final, settled)).forEach { it.recycle() }
    }

    /** 可用高度骤减时不得沿旧尺寸继续绘制，新的物理预算优先于过渡。 */
    @Test fun shrinkingHeightBudgetNeverDrawsBeyondTheNewLimit() = withOverlay { view ->
        onMain { view.setCaptionHeight(px(view, 180)) }
        awaitHeight(view, px(view, 180))
        val heights = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val observer = android.view.ViewTreeObserver.OnDrawListener { heights += view.captionHeightPx() }
        onMain {
            view.viewTreeObserver.addOnDrawListener(observer)
            view.setMaxContentHeight(px(view, 80), px(view, 40))
        }
        Thread.sleep(450)
        onMain { view.viewTreeObserver.removeOnDrawListener(observer) }
        assertTrue("收紧预算后必须有实际绘制帧", heights.isNotEmpty())
        assertTrue("收紧后不能再绘制超额高度：$heights", heights.all { it <= px(view, 80) })
    }

    /** 相同文字的不同新句仍应进入，有空余高度时旧句平滑淡出而非在结束瞬间消失。 */
    @Test fun repeatedSingleSentenceAlsoTransitionsWithSpareHeight() = withOverlay { view ->
        onMain { view.setCaptionHeight(px(view, 180)); view.setLines(listOf(CaptionOverlayView.Line("花朵绽放出来吧", null, true, 1)), null) }
        awaitHeight(view, px(view, 180))
        val before = onMain { capture(view) }
        onMain { view.setLines(listOf(CaptionOverlayView.Line("花朵绽放出来吧", null, true, 2)), null) }
        val frames = (0 until 9).map { index -> Thread.sleep(25); onMain { capture(view).also { save(view, "单句滚动-$index", it) } } }
        Thread.sleep(300)
        val final = onMain { capture(view) }
        assertTrue("相同字句的新片段也需滚动", frames.any { !it.sameAs(before) && !it.sameAs(final) })
        assertTrue("完成后应只有一份最终字形", before.sameAs(final))
        (frames + before + final).forEach { it.recycle() }
    }

    /** 手指连续拖动时每帧直接采用手势尺寸，停止拖动后普通高度配置仍恢复动画。 */
    @Test fun directResizeUsesFingerHeightAndLaterChangesStillAnimate() = withOverlay { view ->
        onMain { view.setCaptionHeight(px(view, 160), animate = false) }
        Thread.sleep(80)
        assertEquals("直接缩放不能滞后于手势尺寸", px(view, 160), onMain { view.captionHeightPx() })
        val heights = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val observer = android.view.ViewTreeObserver.OnDrawListener { heights += view.captionHeightPx() }
        onMain { view.viewTreeObserver.addOnDrawListener(observer); view.setCaptionHeight(px(view, 220)) }
        awaitHeight(view, px(view, 220))
        onMain { view.viewTreeObserver.removeOnDrawListener(observer) }
        assertTrue("手势结束后不能永久关闭高度过渡：$heights", heights.any { it > px(view, 160) && it < px(view, 220) })
    }

    /** 文本没有变化时切换浅色背景，草稿原文的富文本色也必须同步更新。 */
    @Test fun unchangedDraftOriginalUsesTheNewPaletteImmediately() = withOverlay { view ->
        onMain {
            view.setShown(true, false)
            view.setLines(listOf(CaptionOverlayView.Line("花朵绽放出来吧", "花が咲くでしょう～", false)), null)
            val paper = CaptionOverlayView.PALETTES.first { it.id == "paper" }
            view.setColors(paper)
            val draft = (view.getChildAt(1) as android.widget.LinearLayout).getChildAt(1) as android.widget.TextView
            val rich = draft.text as android.text.Spanned
            val span = rich.getSpans(0, rich.length, android.text.style.ForegroundColorSpan::class.java).first()
            assertEquals("不应因相同文字缓存而继续使用旧草稿原文色", paper.ja, span.foregroundColor)
        }
    }

    /** 创建已稳定排版的固定字幕，所有状态只存在当前测试窗口。 */
    private fun withOverlay(test: (CaptionOverlayView) -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var view: CaptionOverlayView
            scenario.onActivity { activity ->
                val host = FrameLayout(activity).apply { setBackgroundColor(0xFF344044.toInt()) }
                view = CaptionOverlayView(activity, false).apply {
                    setFinalOnly(false); setReserveDraftSpace(true); setShown(false, false)
                    applyStyle(18f, 0.85f)
                    setWindowHeightLimit(activity.resources.displayMetrics.heightPixels)
                    setMaxContentHeight(px(this, 240), px(this, 40))
                    setCaptionHeight(px(this, 100))
                    setLines(listOf(line("第一句"), line("第二句"), line("第三句")), null)
                }
                host.addView(view, FrameLayout.LayoutParams(px(view, 300), FrameLayout.LayoutParams.WRAP_CONTENT))
                activity.setContentView(host)
            }
            Thread.sleep(400)
            test(view)
        }
    }

    /** 测试句子使用稳定内容，可从头尾重叠识别滚动方向。 */
    private fun line(text: String) = CaptionOverlayView.Line(text, null, true)
    /** 统一按当前模拟器密度换算。 */
    private fun px(view: View, dp: Int) = (view.resources.displayMetrics.density * dp).toInt()
    /** 等待真实终帧而非猜测系统绘制速度；中间帧断言仍由实际绘制监听器收集。 */
    private fun awaitHeight(view: CaptionOverlayView, target: Int) {
        val deadline = System.currentTimeMillis() + 3_000
        while (onMain { view.captionHeightPx() } != target && System.currentTimeMillis() < deadline) Thread.sleep(16)
        assertEquals("高度过渡应在期限内到达终点", target, onMain { view.captionHeightPx() })
    }
    /** 在主线程读取布局和绘制，避免测试自身制造并发状态。 */
    private fun <T> onMain(block: () -> T): T {
        var value: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
    /** 保存真实渲染器的画面，不重画或模拟字幕。 */
    private fun capture(view: View): Bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    /** 只裁取正式文字区域，草稿内容的变化不能干扰防抖比较。 */
    private fun formalCapture(view: CaptionOverlayView): Bitmap {
        val bitmap = capture(view)
        val card = view.getChildAt(0) as FrameLayout
        val body = (card.getChildAt(0) as android.widget.LinearLayout).getChildAt(0)
        val rect = android.graphics.Rect(0, 0, body.width, body.height)
        view.offsetDescendantRectToMyCoords(body, rect)
        // 这里裁取的是控件视口，祖先换算会扣除正文自己的scrollY，需要还原这段内部偏移。
        rect.offset(body.scrollX, body.scrollY)
        // 草稿会共享黑框下沿留白，正式字形比较必须精确裁到正文，不能把新草稿算作位移。
        return Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height()).also { if (it !== bitmap) bitmap.recycle() }
    }
    /** 保存逐帧证据供手机和平板逐图验收。 */
    private fun save(view: View, name: String, bitmap: Bitmap) {
        val directory = File(view.context.getExternalFilesDir(null), "qa-caption-motion").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
