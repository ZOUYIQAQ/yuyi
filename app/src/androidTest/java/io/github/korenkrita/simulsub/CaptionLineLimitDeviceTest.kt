package io.github.korenkrita.simulsub

import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 使用设备上的真实 TextView 排版验证行数、字号和高度限制，不启动字幕服务。 */
@RunWith(AndroidJUnit4::class)
class CaptionLineLimitDeviceTest {
    /** 无显式换行的长句也必须按真实折行截取末尾，不能按句数或字符数限行。 */
    @Test fun longSentenceKeepsNewestWrappedLines() = onMain {
        val sentence = "这是一段没有显式换行而且需要在窄字幕卡片中自动折行的连续译文。".repeat(12) + "最新结尾"
        val full = overlay(sentence)
        measure(full, 220)
        val expected = tail(caption(full), 3)
        assertTrue("夹具必须产生多于三行的真实排版", caption(full).lineCount > 3)

        val limited = overlay(sentence)
        limited.setMaxCaptionLines(3)
        measure(limited, 220)
        assertEquals(expected, caption(limited).text.toString())
        assertTrue(caption(limited).text.endsWith("最新结尾"))
        assertFits(limited, 3)
    }

    /** 多次更新和显式空行共同限行，最新草稿仍位于显示末尾。 */
    @Test fun explicitNewlinesAndDraftKeepLatestContent() = onMain {
        val view = overlay("旧内容")
        view.setMaxCaptionLines(3)
        measure(view)
        view.setLines(listOf(
            CaptionOverlayView.Line("更旧的译文", null, true),
            CaptionOverlayView.Line("第一行\n\n第三行", null, true),
            CaptionOverlayView.Line("最新草稿", null, false),
        ), null)
        measure(view)
        assertEquals("\n第三行\n… 最新草稿", caption(view).text.toString())
        assertFits(view, 3)
    }

    /** 原文与译文共用额度，状态文本不占字幕行；保留原文的独立字号。 */
    @Test fun sourceAndTranslationShareBudgetButStatusDoesNot() = onMain {
        val view = overlay("")
        view.setLines(listOf(
            CaptionOverlayView.Line("旧译文", "旧原文", true),
            CaptionOverlayView.Line("最新译文", "最新原文", true),
        ), "诊断第一行\n诊断第二行\n诊断第三行")
        view.setMaxCaptionLines(2)
        measure(view)
        assertEquals("最新原文\n最新译文", caption(view).text.toString())
        assertEquals(3, status(view).lineCount)
        val spans = caption(view).text as Spanned
        assertTrue("原文的实际字号必须小于译文", spans.getSpans(0, 4, AbsoluteSizeSpan::class.java)
            .any { it.size < caption(view).textSize })
        assertFits(view, 2)

        view.setLines(listOf(CaptionOverlayView.Line("最新译文", null, true)), "仍有诊断")
        measure(view)
        assertEquals("最新译文", caption(view).text.toString())
        assertFits(view, 2)
    }

    /** 变窄后重新折行，变宽后必须重新显示之前隐藏的内容。 */
    @Test fun widthChangesReflowFromCompleteInput() = onMain {
        val sentence = "字幕宽度改变时需要重新排版并恢复之前隐藏的文字。".repeat(8) + "最新结尾"
        val view = overlay(sentence)
        view.setMaxCaptionLines(2)
        measure(view, 220)
        val narrow = caption(view).text.toString()
        measure(view, 440)
        val wide = caption(view).text.toString()
        val reference = overlay(sentence)
        measure(reference, 440)
        assertEquals(tail(caption(reference), 2), wide)
        assertTrue("变宽应能显示更多历史文字", wide.length > narrow.length)
        assertFits(view, 2)
        measure(view, 220)
        assertEquals(narrow, caption(view).text.toString())
        assertFits(view, 2)
    }

    /** 字号变化同时更新原文字号 Span，并以新字体宽度重新选择末尾行。 */
    @Test fun fontSizeChangesReflowTranslationAndSourceSpans() = onMain {
        val lines = listOf(CaptionOverlayView.Line(
            "字体变大后译文需要重新折行。".repeat(10) + "最新结尾",
            "原文字号也应随着设置同步变化。".repeat(6), true,
        ))
        val view = overlay("")
        view.setLines(lines, null)
        view.setMaxCaptionLines(2)
        measure(view, 300)
        val small = caption(view).text.toString()
        view.applyStyle(30f, CaptionOverlayView.DEFAULT_ALPHA)
        measure(view, 300)
        val reference = overlay("")
        reference.applyStyle(30f, CaptionOverlayView.DEFAULT_ALPHA)
        reference.setLines(lines, null)
        measure(reference, 300)
        assertEquals(tail(caption(reference), 2), caption(view).text.toString())
        assertTrue("大字号每行容纳的文字应减少", caption(view).text.length < small.length)
        assertFits(view, 2)

        view.setMaxCaptionLines(0)
        measure(view, 300)
        val actualSpans = (caption(view).text as Spanned).getSpans(0, lines[0].ja!!.length, AbsoluteSizeSpan::class.java)
        val expectedSpans = (caption(reference).text as Spanned).getSpans(0, lines[0].ja!!.length, AbsoluteSizeSpan::class.java)
        assertEquals(expectedSpans.single().size, actualSpans.single().size)
        view.applyStyle(13f, CaptionOverlayView.DEFAULT_ALPHA)
        view.setMaxCaptionLines(2)
        measure(view, 300)
        assertEquals(small, caption(view).text.toString())
    }

    /** 字体横向拉伸后也必须从完整内容重新排版，恢复字体宽度后恢复更多内容。 */
    @Test fun fontWidthChangesReflowFromCompleteInput() = onMain {
        val sentence = "字体实际宽度会改变每行能够容纳的文字。".repeat(10) + "最新结尾"
        val view = overlay(sentence)
        view.setMaxCaptionLines(3)
        measure(view, 300)
        val normal = caption(view).text.toString()
        caption(view).textScaleX = 1.8f
        measure(view, 300)
        val reference = overlay(sentence)
        caption(reference).textScaleX = 1.8f
        measure(reference, 300)
        assertEquals(tail(caption(reference), 3), caption(view).text.toString())
        assertTrue(caption(view).text.length < normal.length)
        assertFits(view, 3)
        caption(view).textScaleX = 1f
        measure(view, 300)
        assertEquals(normal, caption(view).text.toString())
    }

    /** 混合字号的两行使用实际高度，不能用译文行高乘以二来代替。 */
    @Test fun mixedSourceFontUsesActualLineHeights() = onMain {
        val latest = CaptionOverlayView.Line("最新译文", "最新原文", true)
        val reference = overlay("")
        reference.applyStyle(30f, CaptionOverlayView.DEFAULT_ALPHA)
        reference.setLines(listOf(latest), null)
        measure(reference)
        val view = overlay("")
        view.applyStyle(30f, CaptionOverlayView.DEFAULT_ALPHA)
        view.setLines(listOf(CaptionOverlayView.Line("较早译文", "较早原文", true), latest), null)
        view.setMaxCaptionLines(3)
        view.setCaptionHeight(caption(reference).height)
        measure(view)
        assertEquals("最新原文\n最新译文", caption(view).text.toString())
        assertFits(view, 2)
    }

    /** 固定高度和屏幕高度上限取更严格者，额外半行空间不能露出上一行。 */
    @Test fun fixedAndScreenHeightsOnlyShowWholeNewestLines() = onMain {
        val two = overlay("倒数第二行\n最后一行")
        measure(two)
        val twoHeight = caption(two).height
        val one = overlay("最后一行")
        measure(one)
        val oneHeight = caption(one).height
        val view = overlay("最早一行\n较早一行\n倒数第二行\n最后一行")
        view.setMaxCaptionLines(20)
        val fixed = twoHeight + caption(two).lineHeight / 2
        view.setCaptionHeight(fixed)
        measure(view)
        assertEquals(fixed, caption(view).height)
        assertEquals("倒数第二行\n最后一行", caption(view).text.toString())
        assertFits(view, 2)

        val screenCap = oneHeight + caption(one).lineHeight / 2
        view.setMaxContentHeight(screenCap, 200)
        measure(view)
        assertTrue("固定高度不能越过屏幕高度上限", caption(view).height <= screenCap)
        assertEquals("最后一行", caption(view).text.toString())
        assertFits(view, 1)

        view.setMaxContentHeight(Int.MAX_VALUE, 200)
        view.setCaptionHeight(0)
        measure(view)
        assertEquals(4, caption(view).lineCount)
        assertFits(view, 4)
    }

    /** 连一行都放不下时隐藏正文，避免留下半行字；高度恢复后可重新显示。 */
    @Test fun heightSmallerThanOneLineDoesNotShowPartialGlyphs() = onMain {
        val view = overlay("第一行\n最后一行")
        view.setMaxCaptionLines(2)
        view.setCaptionHeight(1)
        view.setMaxContentHeight(1, 100)
        measure(view)
        assertTrue("高度不足一行时正文应为空", caption(view).text.isEmpty())
        assertTrue(caption(view).height <= 1)
        view.setCaptionHeight(0)
        view.setMaxContentHeight(Int.MAX_VALUE, 100)
        measure(view)
        assertEquals("第一行\n最后一行", caption(view).text.toString())
        assertFits(view, 2)
    }

    /** 限行只影响显示，字幕板保留历史；恢复零值后完整还原旧行为。 */
    @Test fun zeroRestoresUnlimitedWithoutMutatingCaptionBoard() = onMain {
        val board = CaptionBoard(minFinals = 20, maxChars = Int.MAX_VALUE)
        repeat(6) { index ->
            board.accept(Caption(index.toLong(), 1, true, "原文$index", "译文$index", Timing(0, 0, 0, 0, 0, 0, 0)))
        }
        val snapshot = board.snapshot()
        val view = overlay("")
        view.setLines(snapshot.map { CaptionOverlayView.Line(it.zh, it.ja, it.isFinal) }, null)
        measure(view)
        val original = caption(view).text.toString()
        assertEquals(12, caption(view).lineCount)
        caption(view).scrollTo(0, 120)
        view.setMaxCaptionLines(1)
        measure(view)
        assertEquals("译文5", caption(view).text.toString())
        assertFits(view, 1)
        assertEquals(snapshot, board.snapshot())
        view.setMaxCaptionLines(0)
        measure(view)
        assertEquals(original, caption(view).text.toString())
        assertEquals(12, caption(view).lineCount)

        view.setCaptionHeight(view.minCaptionPx())
        measure(view)
        assertEquals("不限时固定高度仍保留旧版完整正文", original, caption(view).text.toString())
    }

    /** API 将超界值约束在零到二十，合法的上界确实只显示二十行。 */
    @Test fun publicLimitIsClampedToSupportedRange() = onMain {
        val sentence = (1..25).joinToString("\n") { "字幕第${it}行" }
        val view = overlay(sentence)
        view.setMaxCaptionLines(Int.MAX_VALUE)
        measure(view)
        assertEquals((6..25).joinToString("\n") { "字幕第${it}行" }, caption(view).text.toString())
        assertFits(view, 20)
        view.setMaxCaptionLines(-1)
        measure(view)
        assertEquals(sentence, caption(view).text.toString())
    }

    /** 在主线程执行真实视图操作，并把断言异常带回测试线程。 */
    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { block() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }

    /** 创建不绑定窗口、不启动服务的真实原生字幕视图。 */
    private fun overlay(sentence: String): CaptionOverlayView = CaptionOverlayView(
        InstrumentationRegistry.getInstrumentation().targetContext, false,
    ).apply { setLines(listOf(CaptionOverlayView.Line(sentence, null, true)), null) }

    /** 用明确的物理宽度测量并布局，同时验证宿主预先测量的卡片高度一致。 */
    private fun measure(view: CaptionOverlayView, widthDp: Int = 360) {
        val width = (widthDp * view.resources.displayMetrics.density).toInt()
        val expectedHeight = view.measureCardHeight(width)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, width, view.measuredHeight)
        assertEquals("预先测量和实际布局的卡片高度应一致", expectedHeight, view.getChildAt(0).height)
    }

    /** 读取卡片正文和诊断所在的现有原生列，不使用反射或模拟排版器。 */
    private fun column(view: CaptionOverlayView) = (view.getChildAt(0) as FrameLayout).getChildAt(0) as LinearLayout

    /** 获取实际负责字幕绘制的 TextView。 */
    private fun caption(view: CaptionOverlayView) = column(view).getChildAt(0) as TextView

    /** 获取独立诊断 TextView，以验证其不占正文额度。 */
    private fun status(view: CaptionOverlayView) = column(view).getChildAt(1) as TextView

    /** 从真实 TextView 的排版行起点提取预期末尾内容。 */
    private fun tail(text: TextView, count: Int): String = text.text.subSequence(
        text.layout.getLineStart((text.lineCount - count).coerceAtLeast(0)), text.text.length,
    ).toString()

    /** 正文自身排版不超过额度且完整装入可绘制高度，排除额外半行和省略号。 */
    private fun assertFits(view: CaptionOverlayView, count: Int) {
        val text = caption(view)
        assertTrue("正文实际排版不能超出$count 行：${text.text}", text.lineCount <= count)
        assertTrue("全部排版行必须完整容纳，不能裁掉半行", text.layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
        assertNull("保留最新正文而非添加省略号", text.ellipsize)
        assertEquals("限行正文不能沿用旧正文的滚动偏移", 0, text.scrollY)
    }
}
