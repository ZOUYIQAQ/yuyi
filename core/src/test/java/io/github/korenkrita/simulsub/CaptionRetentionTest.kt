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

    /** 一句上限只限制定稿，草稿更新和消失都不挤掉该定稿。 */
    @Test fun sentenceDraftHasIndependentSlotWithoutDeletingFinal() {
        val board=CaptionBoard().apply { setRetention(CaptionLimitMode.SENTENCES,1,1) }
        board.accept(cap(1,"定稿"));board.accept(cap(2,"草稿",false))
        assertEquals(listOf("定稿","草稿"),board.snapshot().map { it.zh })
        assertEquals("定稿",board.snapshot(false).single().zh)
        board.accept(cap(2,"草稿更新",false).copy(revision=2))
        assertEquals(listOf("定稿","草稿更新"),board.snapshot().map { it.zh })
        board.accept(cap(2,""))
        assertEquals("定稿",board.snapshot().single().zh)
        board.accept(cap(3,"下一句定稿"))
        assertEquals("下一句定稿",board.snapshot().single().zh)
    }

    /** 三句设置在草稿出现、更新、隐藏和消失期间始终保留最近三条完整定稿。 */
    @Test fun threeFinalsSurviveDraftLifecycleBeforeRetentionIsApplied() {
        val board=CaptionBoard().apply { setRetention(CaptionLimitMode.SENTENCES,3,1) }
        (1L..4L).forEach { board.accept(cap(it,"定稿$it")) }
        val finals=board.snapshot(false)
        assertEquals(listOf(2L,3L,4L),finals.map { it.segmentId })
        board.accept(cap(5,"草稿",false))
        assertEquals(finals + cap(5,"草稿",false),board.snapshot())
        board.accept(cap(5,"草稿更新",false).copy(revision=2))
        assertEquals(finals,board.snapshot().filter { it.isFinal })
        assertEquals("草稿更新",board.snapshot().last().zh)
        assertEquals(finals,board.snapshot(false))
        board.accept(cap(5,""))
        assertEquals(finals,board.snapshot())
        board.accept(cap(6,"下一句草稿",false))
        board.accept(cap(6,"下一句定稿").copy(revision=2))
        assertEquals(listOf(3L,4L,6L),board.snapshot().map { it.segmentId })
        assertTrue(board.snapshot().all { it.isFinal })
    }

    /** 多条草稿只取输入中最后一条，并保持与定稿的原始先后关系。 */
    @Test fun sentenceModeKeepsOnlyLatestDraftInInputOrder() {
        val captions=listOf(cap(9,"最早草稿",false),cap(8,"旧定稿"),
            cap(7,"草稿旧版",false),cap(7,"草稿新版",false).copy(revision=2),
            cap(6,"第二定稿"),cap(5,"最新定稿"))
        assertEquals(listOf(captions[3],captions[5]),
            CaptionRetention.apply(captions,CaptionLimitMode.SENTENCES,1,1))
        assertEquals(listOf(captions[1],captions[3],captions[4],captions[5]),
            CaptionRetention.apply(captions,CaptionLimitMode.SENTENCES,3,1))
    }

    /** 只有草稿时同样保留最新一条，不按分段编号或对象相等判断新旧。 */
    @Test fun sentenceModeWithOnlyDraftsKeepsLatestEntry() {
        val first=cap(8,"旧草稿",false)
        val latest=cap(2,"新草稿",false)
        assertEquals(listOf(latest),CaptionRetention.apply(listOf(first,latest),CaptionLimitMode.SENTENCES,3,1))
        assertEquals(listOf(first),CaptionRetention.apply(listOf(first,first),CaptionLimitMode.SENTENCES,1,1))
    }

    /** 只裁剪显示列表，原始字幕的文本、修订、时间和对象均不改动。 */
    @Test fun sentenceRetentionDoesNotMutateSourceCaptions() {
        val captions=mutableListOf(cap(1,"第一定稿"),cap(2,"第二定稿"),cap(3,"草稿",false))
        val original=captions.toList()
        val visible=CaptionRetention.apply(captions,CaptionLimitMode.SENTENCES,1,1)
        assertEquals(original,captions)
        assertSame(captions[1],visible[0])
        assertSame(captions[2],visible[1])
    }

    /** 空列表在两种显示策略和非正数额度下均安全返回空列表。 */
    @Test fun emptyInputRemainsEmptyInBothModes() {
        for (mode in CaptionLimitMode.values()) {
            assertTrue(CaptionRetention.apply(emptyList(),mode,3,100).isEmpty())
            assertTrue(CaptionRetention.apply(emptyList(),mode,0,0).isEmpty())
        }
        val board=CaptionBoard().apply { setRetention(CaptionLimitMode.SENTENCES,3,100) }
        assertTrue(board.snapshot().isEmpty())
        assertTrue(board.snapshot(false).isEmpty())
    }

    /** 无草稿时仍按原先规则保留最近定稿；非正数句数仍回退为一条定稿。 */
    @Test fun finalsOnlyAndInvalidSentenceCountsKeepExistingBehavior() {
        val captions=(1L..5L).map { cap(it,"定稿$it") }
        for (limit in listOf(-1,0,1,3,8)) {
            assertEquals(captions.takeLast(limit.coerceAtLeast(1)),
                CaptionRetention.apply(captions,CaptionLimitMode.SENTENCES,limit,1))
        }
        val draft=cap(6,"草稿",false)
        assertEquals(listOf(captions.last(),draft),
            CaptionRetention.apply(captions + draft,CaptionLimitMode.SENTENCES,0,1))
    }

    /** 旧范围接口继续让草稿占显示额度，切换新版策略后草稿获得独立槽位。 */
    @Test fun legacySnapshotLimitRemainsCompatibleUntilNewPolicyIsSelected() {
        val board=CaptionBoard(minFinals=1,maxChars=1000,maxFinals=3)
        (1L..3L).forEach { board.accept(cap(it,"定稿$it")) }
        board.accept(cap(4,"草稿",false))
        assertEquals(listOf(2L,3L,4L),board.snapshot().map { it.segmentId })
        assertEquals(listOf(1L,2L,3L),board.snapshot(false).map { it.segmentId })
        assertTrue(board.setRetention(CaptionLimitMode.SENTENCES,3,1))
        assertEquals(listOf(1L,2L,3L,4L),board.snapshot().map { it.segmentId })
        assertEquals(listOf(1L,2L,3L),board.snapshot(false).map { it.segmentId })
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
