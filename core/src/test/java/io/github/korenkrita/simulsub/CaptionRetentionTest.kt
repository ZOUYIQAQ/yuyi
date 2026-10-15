package io.github.korenkrita.simulsub

import org.junit.Assert.*
import org.junit.Test

/** 验证新版显示策略真正互斥，独立于历史兼容范围接口。 */
class CaptionRetentionTest {
    /** 构造真实定稿结构，不调用外部服务。 */
    private fun cap(id: Long, text: String, final: Boolean = true) = Caption(id,1,final,"原文$id",text,Timing(0,0,0,0,0,0,0))

    /** 默认句数模式不再受短字数阈值影响。 */
    @Test fun sentenceModeIgnoresCharacterThreshold() {
        val board=CaptionBoard().apply { setRetention(CaptionLimitMode.SENTENCES,3,1) }
        (1L..5L).forEach { board.accept(cap(it,"译文".repeat(100))) }
        assertEquals(listOf(3L,4L,5L),board.snapshot().map { it.segmentId })
        assertTrue(board.snapshot().all { it.zh.length == 200 })
    }

    /** 字数模式不再暗中使用曾设置的一句上限。 */
    @Test fun characterModeIgnoresSentenceCount() {
        val board=CaptionBoard().apply { setRetention(CaptionLimitMode.CHARACTERS,1,100) }
        (1L..12L).forEach { board.accept(cap(it,"字")) }
        assertEquals(12,board.snapshot().size)
    }

    /** 过长的单句只显示最新字符，不会突破字数上限或拆开补充平面字符。 */
    @Test fun longSentenceKeepsUnicodeTailWithoutMutatingSource() {
        val original=cap(1,"开头😀山茶🌸结尾")
        val clipped=CaptionRetention.apply(listOf(original),CaptionLimitMode.CHARACTERS,1,4).single()
        assertEquals("茶🌸结尾",clipped.zh)
        assertEquals("开头😀山茶🌸结尾",original.zh)
        assertEquals(original.ja,clipped.ja)
        assertEquals(4,clipped.zh.codePointCount(0,clipped.zh.length))
    }

    /** 字数边界跨过两条定稿时，仍须保留预算以内的上一句尾部。 */
    @Test fun characterBudgetKeepsTailAcrossFinalBoundaries() {
        val board=CaptionBoard().apply { setRetention(CaptionLimitMode.CHARACTERS,1,10) }
        board.accept(cap(1,"12345678"));board.accept(cap(2,"甲乙丙丁戊己庚辛"))
        assertEquals("78甲乙丙丁戊己庚辛",board.snapshot().joinToString("") { it.zh })
        board.accept(cap(3,"😀😀😀😀😀😀😀😀"))
        assertEquals("庚辛😀😀😀😀😀😀😀😀",board.snapshot().joinToString("") { it.zh })
        assertEquals(10,board.snapshot().sumOf { it.zh.codePointCount(0,it.zh.length) })
        assertFalse(board.accept(cap(1,"迟到定稿")))
    }

    /** 实时草稿参与同一个字数预算，隐藏草稿后按定稿重新计算。 */
    @Test fun draftSharesCharacterBudgetAndFinalOnlyIsIndependent() {
        val board=CaptionBoard().apply { setRetention(CaptionLimitMode.CHARACTERS,1,5) }
        board.accept(cap(1,"12345"));board.accept(cap(2,"甲乙丙丁",false))
        assertEquals("5甲乙丙丁",board.snapshot().joinToString("") { it.zh })
        assertEquals("12345",board.snapshot(false).single().zh)
        board.accept(cap(2,"甲乙丙丁戊己",false).copy(revision=2))
        assertEquals("乙丙丁戊己",board.snapshot().single().zh)
    }

    /** 模式和额度变更立即生效，迟到的旧字幕仍不能复活。 */
    @Test fun switchingPolicyImmediatelyUpdatesSnapshot() {
        val board=CaptionBoard().apply { setRetention(CaptionLimitMode.SENTENCES,4,1) }
        (1L..4L).forEach { board.accept(cap(it,"字幕$it")) }
        assertTrue(board.setRetention(CaptionLimitMode.CHARACTERS,1,2))
        assertTrue(board.snapshot().sumOf { it.zh.codePointCount(0,it.zh.length) } <= 2)
        assertFalse(board.accept(cap(1,"迟到字幕")))
        assertTrue(board.setRetention(CaptionLimitMode.SENTENCES,2,1))
        board.accept(cap(5,"很长的译文不因字数阈值消失"))
        assertEquals(2,board.snapshot().size)
    }

    /** 仅按句数时草稿占额度，却不销毁仅定稿模式下的最近定稿。 */
    @Test fun sentenceDraftUsesDisplaySlotWithoutDeletingFinal() {
        val board=CaptionBoard().apply { setRetention(CaptionLimitMode.SENTENCES,1,1) }
        board.accept(cap(1,"定稿"));board.accept(cap(2,"草稿",false))
        assertEquals("草稿",board.snapshot().single().zh)
        assertEquals("定稿",board.snapshot(false).single().zh)
    }

    /** 完全相同设置不报告虚假变化，非正数额度安全回退。 */
    @Test fun unchangedPolicyIsStableAndInvalidCapsAreBounded() {
        val board=CaptionBoard().apply { setRetention(CaptionLimitMode.SENTENCES,2,100) }
        board.accept(cap(1,"字幕"))
        assertFalse(board.setRetention(CaptionLimitMode.SENTENCES,2,100))
        assertEquals(1,CaptionRetention.apply(listOf(cap(1,"一"),cap(2,"二")),CaptionLimitMode.SENTENCES,0,0).size)
        assertEquals("🌸",CaptionRetention.apply(listOf(cap(1,"一🌸")),CaptionLimitMode.CHARACTERS,0,0).single().zh)
    }
}
