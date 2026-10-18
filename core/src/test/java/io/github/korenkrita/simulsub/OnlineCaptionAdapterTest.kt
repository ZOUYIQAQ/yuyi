package io.github.korenkrita.simulsub

import io.github.korenkrita.simulsub.online.LiveTranslationText
import org.junit.Assert.*
import org.junit.Test

/** 验证云端多次更新接入现有字幕板后不会重复计句或丢失乱序完成的定稿。 */
class OnlineCaptionAdapterTest {
    /** 临时内容只更新同一槽位，完成一次计一句，迟到原文不能生成重复定稿。 */
    @Test fun draftsAndLateSourceDoNotOccupyAdditionalFinalSlots() {
        val adapter = OnlineCaptionAdapter()
        val board = CaptionBoard().apply { setRetention(CaptionLimitMode.SENTENCES, 3, 120) }
        board.accept(requireNotNull(adapter.accept(LiveTranslationText("a", "原文", "临时", false))))
        assertEquals(1, board.snapshot().size)
        board.accept(requireNotNull(adapter.accept(LiveTranslationText("a", "原文", "定稿", true))))
        val sourceUpdate = requireNotNull(adapter.accept(LiveTranslationText("a", "补全原文", "定稿", true)))
        assertFalse(board.accept(sourceUpdate))
        assertTrue(board.updateOriginal(sourceUpdate.segmentId, sourceUpdate.ja))
        assertEquals(listOf("定稿"), board.snapshot().map { it.zh })
        assertEquals("补全原文", board.snapshot().single().ja)
        assertNull(adapter.accept(LiveTranslationText("a", "补全原文", "定稿", true)))
        assertTrue(board.snapshot().single().isFinal)
    }

    /** 两段交错时按完成顺序分配递增编号，不因较早开始的段较晚完成而被字幕板丢弃。 */
    @Test fun overlappingResponsesKeepBothFinals() {
        val adapter = OnlineCaptionAdapter()
        val board = CaptionBoard()
        listOf(
            LiveTranslationText("a", "", "一临时", false), LiveTranslationText("b", "", "二临时", false),
            LiveTranslationText("b", "", "二定稿", true), LiveTranslationText("a", "", "一定稿", true),
        ).forEach { board.accept(requireNotNull(adapter.accept(it))) }
        assertEquals(listOf("二定稿", "一定稿"), board.snapshot().map { it.zh })
    }

    /** 空原文事件不能显示空字幕，连续相同草稿也不能重启动画。 */
    @Test fun emptyAndUnchangedSnapshotsAreIgnored() {
        val adapter = OnlineCaptionAdapter()
        assertNull(adapter.accept(LiveTranslationText("a", "有原文", "", false)))
        val draft = LiveTranslationText("a", "有原文", "译文", false)
        assertNotNull(adapter.accept(draft))
        assertNull(adapter.accept(draft))
    }

    /** 云端撤回预测并返回空定稿时，字幕板必须收到结束事件，旧临时字幕不能残留。 */
    @Test fun emptyFinalClearsExistingDraft() {
        val adapter = OnlineCaptionAdapter()
        val board = CaptionBoard()
        board.accept(requireNotNull(adapter.accept(LiveTranslationText("a", "", "预测内容", false))))
        board.accept(requireNotNull(adapter.accept(LiveTranslationText("a", "", "", true))))
        assertTrue(board.snapshot().isEmpty())
    }

    /** 较早响应的撤回不能通过重编号误删较新的临时字幕。 */
    @Test fun lateEmptyFinalDoesNotClearAnotherSegmentsDraft() {
        val adapter = OnlineCaptionAdapter()
        val board = CaptionBoard()
        listOf(LiveTranslationText("a", "", "旧草稿", false),
            LiveTranslationText("b", "", "已定稿", true),
            LiveTranslationText("c", "", "新草稿", false),
            LiveTranslationText("a", "", "", true)).forEach { text -> adapter.accept(text)?.let { board.accept(it) } }
        assertEquals(listOf("已定稿", "新草稿"), board.snapshot().map { it.zh })
        assertFalse(board.snapshot().last().isFinal)
    }

    /** 补原文只能更新原有一行，编号、译文、修订及数量均保持不变，不复活已裁剪句子。 */
    @Test fun originalCorrectionDoesNotRestartFinalAnimationOrReviveOldLines() {
        val board = CaptionBoard().apply { setRetention(CaptionLimitMode.SENTENCES, 1, 120) }
        val timing = Timing(0, 0, 0, 0, 0, 0, 0)
        board.accept(Caption(1, 1, true, "", "第一句", timing))
        board.accept(Caption(2, 3, true, "原文草稿", "第二句", timing))
        assertFalse(board.updateOriginal(1, "迟到旧句原文"))
        assertTrue(board.updateOriginal(2, "第二句完整原文"))
        val current = board.snapshot().single()
        assertEquals(2L, current.segmentId)
        assertEquals(3, current.revision)
        assertEquals("第二句", current.zh)
        assertEquals("第二句完整原文", current.ja)
    }
}
