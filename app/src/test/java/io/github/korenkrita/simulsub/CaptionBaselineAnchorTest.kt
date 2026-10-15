package io.github.korenkrita.simulsub

import org.junit.Assert.*
import org.junit.Test

/** 定位算法不依赖Android，验证内容更新不能偷偷改写用户放下的位置。 */
class CaptionBaselineAnchorTest {
    /** 重启先测量空正文，即使占位高度触发边界限制，也不能覆盖保存的基线。 */
    @Test fun restoredBaselineSurvivesEmptyPlaceholderThenRealContent() {
        val anchor = CaptionBaselineAnchor(950)
        val emptyTop = anchor.position(900, 20, 1_000, 160)
        assertEquals(930, emptyTop)
        assertEquals(950, anchor.baseline)

        val contentTop = anchor.position(900, 120, 1_000, 240)
        assertEquals(830, contentTop)
        assertEquals(950, contentTop + 120)
        assertEquals(950, anchor.baseline)
    }

    /** 保存的基线靠近顶部时也直接恢复，只有显式重置才能重新贴边。 */
    @Test fun restoredBaselineOnlyRefitsAfterExplicitReset() {
        val anchor = CaptionBaselineAnchor(40)
        assertEquals(-80, anchor.position(900, 120, 800, 240))
        assertEquals(40, anchor.baseline)
        anchor.reset()
        assertEquals(560, anchor.position(900, 120, 800, 240))
        assertEquals(680, anchor.baseline)
    }

    /** 草稿和诊断改变总高度时，不能触发第二次贴底或抬高定稿。 */
    @Test fun transientHeightChangesNeverRewriteBaseline() {
        val anchor = CaptionBaselineAnchor()
        val top = anchor.position(900, 60, 1_000, 160)
        assertEquals(840, top)
        for (height in listOf(160, 220, 80, 300, 160)) {
            assertEquals(top, anchor.position(900, 60, 1_000, height))
            assertEquals(900, anchor.baseline)
        }
    }

    /** 定稿增加、换行与字号变化只改变向上的伸展，最后一行仍落在同一像素。 */
    @Test fun finalReflowKeepsLastBaselineEvenNearTopEdge() {
        val anchor = CaptionBaselineAnchor()
        anchor.position(10, 30, 1_000, 100)
        for (offset in listOf(30, 90, 280, 22, 160)) {
            val top = anchor.position(10, offset, 1_000, offset + 80)
            assertEquals(40, top + offset)
            assertEquals(40, anchor.baseline)
        }
    }

    /** 只有用户再次移动或屏幕改变后，才重新限制位置并建立锚点。 */
    @Test fun explicitPlacementCanEstablishAnotherAnchor() {
        val anchor = CaptionBaselineAnchor(600)
        assertEquals(550, anchor.position(20, 50, 800, 150))
        anchor.reset()
        assertEquals(650, anchor.position(900, 50, 800, 150))
        assertEquals(700, anchor.baseline)
        assertEquals(600, anchor.position(0, 100, 800, 240))
    }
}
