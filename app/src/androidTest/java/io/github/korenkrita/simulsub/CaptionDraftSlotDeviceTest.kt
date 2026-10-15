package io.github.korenkrita.simulsub

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 只测真实原生布局和像素，不启动服务、不请求悬浮窗权限、不修改用户数据。 */
@RunWith(AndroidJUnit4::class)
class CaptionDraftSlotDeviceTest {
    /** 低横屏与200%字号同时出现时，三块内容必须共享总高度预算，不把透明槽挤出屏幕。 */
    @Test fun shortLandscapeBudgetContainsCardStatusAndDraftAtLargeFont() = onMain {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = android.content.res.Configuration(context.resources.configuration).apply { fontScale = 2f }
        val large = context.createConfigurationContext(config)
        for (fixed in listOf(false, true)) for (original in listOf(false, true)) {
            val view = CaptionOverlayView(large, interactive = false)
            view.setFinalOnly(false); view.setShown(original, true)
            view.applyStyle(40f, 0.8f)
            val height = px(view, 240)
            val width = px(view, 640)
            view.setWindowHeightLimit(height)
            view.setMaxContentHeight((height * 0.4f).toInt(), (height * 0.2f).toInt())
            view.setCaptionHeight(if (fixed) height else 0)
            view.setLines(listOf(CaptionOverlayView.Line("定稿内容".repeat(80), if (original) "原文内容".repeat(80) else null, true),
                CaptionOverlayView.Line("实时译文".repeat(80), if (original) "实时原文" else null, false)), "诊断\n".repeat(12))
            layout(view, width)
            assertTrue("定稿、诊断、草稿不能超过横屏可用高度：${view.height}/$height", view.measureCaptionBlockHeight(width) <= height)
            assertTrue(view.height <= height)
            assertTrue("保留至少一行实时译文", view.draftSlotHeightPx() >= (view.getChildAt(1) as LinearLayout).getChildAt(1).let { (it as TextView).lineHeight })
            assertNull(view.getChildAt(1).background)
        }
    }

    /** 横竖宽度、自动和固定高度、原文和两种保留方式下，草稿不能改变黑框或窗口高度。 */
    @Test fun draftLifecycleKeepsCardAndWindowStable() = onMain {
        for (landscape in listOf(false, true)) for (fixed in listOf(false, true)) {
            for (original in listOf(false, true)) for (mode in CaptionLimitMode.entries) {
                val view = overlay(original)
                val width = px(view, if (landscape) 520 else 240)
                val screenHeight = px(view, if (landscape) 320 else 700)
                view.setMaxContentHeight((screenHeight * 0.4f).toInt(), screenHeight / 5)
                view.setCaptionHeight(if (fixed) screenHeight / 5 else 0)
                val final = caption(1, true, "已经定稿")
                val draft = caption(2, false, "实时译文需要在预留区域内折行显示。".repeat(8))
                val frames = listOf(listOf(final), listOf(final, draft),
                    listOf(caption(2, true, "新的定稿")), listOf(caption(2, true, "新的定稿"), draft),
                    listOf(caption(2, true, "新的定稿")))
                var expected: List<Int>? = null
                for (frame in frames) {
                    val retained = CaptionRetention.apply(frame, mode, 2, 300)
                    view.setLines(retained.map { CaptionOverlayView.Line(it.zh, if (original) it.ja else null, it.isFinal) }, null)
                    layout(view, width)
                    val card = view.getChildAt(0)
                    val slot = view.getChildAt(1)
                    val geometry = listOf(view.height, card.top, card.bottom, slot.top, slot.bottom)
                    if (expected == null) expected = geometry else assertEquals("草稿生命周期不应推动黑框：$landscape/$fixed/$original/$mode", expected, geometry)
                    assertEquals(card.bottom, slot.top)
                    assertEquals(view.measureCardHeight(width) + view.draftSlotHeightPx(), view.height)
                    assertEquals(view.measureCaptionBlockHeight(width), view.height)
                    assertNull("草稿区不得继承正式背景", slot.background)
                    assertFalse("草稿不得混入正式正文", body(view).text.contains("实时译文"))
                }
            }
        }
    }

    /** 真正增长的定稿向上扩展；使用底部锚点后，草稿区域和黑框底边始终固定。 */
    @Test fun bottomAnchoredHostKeepsCardBottomWhenFinalsGrow() = onMain {
        val view = overlay(false)
        val width = px(view, 280)
        val host = FrameLayout(view.context).apply {
            addView(view, FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
        val frames = listOf(
            listOf(CaptionOverlayView.Line("已有定稿", null, true)),
            listOf(CaptionOverlayView.Line("已有定稿", null, true), CaptionOverlayView.Line("草稿".repeat(50), null, false)),
            listOf(CaptionOverlayView.Line("已有定稿\n新定稿增加了正式正文高度", null, true)),
            listOf(CaptionOverlayView.Line("已有定稿\n新定稿增加了正式正文高度", null, true), CaptionOverlayView.Line("下一句草稿", null, false)),
            listOf(CaptionOverlayView.Line("已有定稿\n新定稿增加了正式正文高度", null, true)),
        )
        var bottom = -1
        for (frame in frames) {
            view.setLines(frame, null)
            host.measure(exact(width), exact(px(view, 700)))
            host.layout(0, 0, host.measuredWidth, host.measuredHeight)
            val actual = view.top + view.getChildAt(0).bottom
            if (bottom < 0) bottom = actual else assertEquals("正式内容增长不能移动黑框底部锚点", bottom, actual)
            assertEquals(host.height, view.bottom)
        }
    }

    /** 自动和固定高度均绘制到透明位图，验证预留区域透明且正式黑底没有加高。 */
    @Test fun reservedPixelsAreTransparentAndFinalOnlyHasNoSlot() = onMain {
        for (fixed in listOf(false, true)) for (original in listOf(false, true)) {
            val view = overlay(original)
            view.setCaptionHeight(if (fixed) px(view, 96) else 0)
            view.setLines(listOf(CaptionOverlayView.Line("定稿", null, true)), null)
            val width = px(view, 300)
            layout(view, width)
            val cardHeight = view.measureCardHeight(width)
            val reservedHeight = view.draftSlotHeightPx()
            assertTrue(reservedHeight > 0)
            val bitmap = Bitmap.createBitmap(width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            assertTrue("黑框内部仍应绘制正式背景", Color.alpha(bitmap.getPixel(width / 2, px(view, 4))) > 0)
            for (y in cardHeight until view.height) for (x in 0 until width) {
                assertEquals("黑框外预留像素必须透明：$fixed/$original/$x,$y", 0, Color.alpha(bitmap.getPixel(x, y)))
            }
            view.setFinalOnly(true)
            layout(view, width)
            assertEquals(0, view.draftSlotHeightPx())
            assertEquals("开启草稿槽不应加高正式黑底", cardHeight, view.measureCardHeight(width))
            assertEquals(cardHeight, view.height)
            view.setFinalOnly(false)
            layout(view, width)
            assertEquals(reservedHeight, view.draftSlotHeightPx())
        }
    }

    /** 超长草稿保留末尾完整行，原文有独立固定一行，字号变化重新计算预留。 */
    @Test fun longDraftStaysInsideSlotAndReflowsOnStyleChanges() = onMain {
        val view = overlay(true)
        view.setLines(listOf(CaptionOverlayView.Line("很长的草稿。".repeat(100) + "最新结尾", "原文".repeat(100), false)), null)
        for (font in listOf(13f, 36f)) for (widthDp in listOf(220, 520)) {
            view.applyStyle(font, 0.8f)
            layout(view, px(view, widthDp))
            val slot = view.getChildAt(1) as LinearLayout
            val source = slot.getChildAt(0) as TextView
            val draft = slot.getChildAt(1) as TextView
            assertTrue(source.height > 0)
            assertTrue(draft.text.endsWith("最新结尾"))
            assertTrue(draft.lineCount <= 2)
            assertTrue("草稿必须整行容纳在透明区域内", draft.layout.height <= draft.height)
            assertTrue(draft.bottom <= slot.height)
            assertEquals(View.VISIBLE, draft.visibility)
            assertEquals(0, draft.scrollY)
        }
    }

    /** 工具栏在上下切换和避让时，草稿槽始终紧跟黑框且与黑框共享宽度、水平偏移。 */
    @Test fun toolbarPlacementKeepsTransparentSlotWithCard() = onMain {
        val view = overlay(false)
        view.setLines(listOf(CaptionOverlayView.Line("定稿", null, true)), null)
        val cardWidth = px(view, 240)
        view.setCardWidth(cardWidth)
        val card = view.getChildAt(0)
        val slot = view.getChildAt(1)
        val toolbar = view.getChildAt(2)
        toolbar.visibility = View.VISIBLE
        for (above in listOf(false, true, false)) {
            view.placeCard(px(view, 30), 0, above, 0)
            layout(view, px(view, 640))
            assertEquals(card.left, slot.left)
            assertEquals(card.right, slot.right)
            assertEquals(cardWidth, slot.width)
            assertEquals(card.bottom, slot.top)
            if (above) assertTrue(toolbar.bottom <= card.top) else assertTrue(toolbar.top >= slot.bottom)
            assertEquals(view.measureCaptionBlockHeight(cardWidth) + view.toolbarHeightPx(), view.height)
        }
    }

    /** 创建启用草稿槽的真实视图，显式同步原文开关。 */
    private fun overlay(original: Boolean) = CaptionOverlayView(InstrumentationRegistry.getInstrumentation().targetContext, false).apply {
        setFinalOnly(false)
        setShown(original, false)
    }

    /** 构造不可变字幕样本，保留策略调用生产实现。 */
    private fun caption(id: Long, final: Boolean, text: String) = Caption(id, 1, final, "样例原文", text, Timing(0, 0, 0, 0, 0, 0, 0))

    /** 读取正式黑框中的正文，不把草稿当作正式内容。 */
    private fun body(view: CaptionOverlayView) = ((view.getChildAt(0) as FrameLayout).getChildAt(0) as LinearLayout).getChildAt(0) as TextView

    /** 将测试尺寸换算成当前设备像素。 */
    private fun px(view: View, dp: Int) = (dp * view.resources.displayMetrics.density).toInt()

    /** 构造精确测量约束。 */
    private fun exact(px: Int) = View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY)

    /** 同步执行真实原生测量和布局。 */
    private fun layout(view: CaptionOverlayView, width: Int) {
        view.measure(exact(width), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, width, view.measuredHeight)
    }

    /** 在主线程执行布局并向测试线程传回断言失败。 */
    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { block() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }
}
