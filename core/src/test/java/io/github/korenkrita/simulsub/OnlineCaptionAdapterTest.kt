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

    /** 空定稿仅撤回草稿，同一消息重新生成时必须使用更新的显示编号并最终正常定稿。 */
    @Test fun withdrawnDraftCanReappearAndFinishUnderTheSameMessageId() {
        val adapter = OnlineCaptionAdapter()
        val board = CaptionBoard()
        val draft = LiveTranslationText("a", "原文", "旧预测", false)
        val first = requireNotNull(adapter.accept(draft))
        board.accept(first)
        val withdrawal = requireNotNull(adapter.accept(draft.copy(translatedText = "", isFinal = true)))
        assertEquals(first.segmentId, withdrawal.segmentId)
        assertTrue(board.accept(withdrawal))
        assertTrue(board.snapshot().isEmpty())
        assertNull(adapter.accept(draft.copy(translatedText = "", isFinal = true)))
        val resumed = requireNotNull(adapter.accept(draft.copy(translatedText = "新预测")))
        assertTrue(resumed.segmentId > withdrawal.segmentId)
        assertTrue(board.accept(resumed))
        val final = requireNotNull(adapter.accept(draft.copy(translatedText = "最终译文", isFinal = true)))
        assertEquals(resumed.segmentId, final.segmentId)
        assertTrue(board.accept(final))
        assertEquals(listOf("最终译文"), board.snapshot().map { it.zh })
        assertEquals("原文", board.snapshot().single().ja)
    }

    /** 撤回后跳过草稿直接收到定稿，也不能把它误当成已删除行的原位修订。 */
    @Test fun withdrawnDraftCanReappearDirectlyAsAFinal() {
        val adapter = OnlineCaptionAdapter()
        val board = CaptionBoard()
        val draft = LiveTranslationText("a", "", "预测", false)
        board.accept(requireNotNull(adapter.accept(draft)))
        val withdrawal = requireNotNull(adapter.accept(draft.copy(translatedText = "", isFinal = true)))
        board.accept(withdrawal)
        val final = requireNotNull(adapter.accept(draft.copy(translatedText = "新定稿", isFinal = true)))
        assertTrue(final.segmentId > withdrawal.segmentId)
        assertTrue(board.accept(final))
        assertEquals(listOf("新定稿"), board.snapshot().map { it.zh })
    }

    /** 撤回仍推进显示水位，较早草稿重新出现必须重编号，避免被字幕板按过期内容丢掉。 */
    @Test fun draftWithdrawalStillAdvancesLastFinalOrdering() {
        val adapter = OnlineCaptionAdapter()
        val board = CaptionBoard()
        val older = LiveTranslationText("a", "", "较早草稿", false)
        val newer = LiveTranslationText("b", "", "较新草稿", false)
        board.accept(requireNotNull(adapter.accept(older)))
        board.accept(requireNotNull(adapter.accept(newer)))
        val withdrawal = requireNotNull(adapter.accept(newer.copy(translatedText = "", isFinal = true)))
        board.accept(withdrawal)
        val resumed = requireNotNull(adapter.accept(older.copy(translatedText = "较早响应的新草稿")))
        assertTrue(resumed.segmentId > withdrawal.segmentId)
        assertTrue(board.accept(resumed))
        val final = requireNotNull(adapter.accept(older.copy(translatedText = "较早响应的定稿", isFinal = true)))
        assertEquals(resumed.segmentId, final.segmentId)
        assertTrue(board.accept(final))
        assertEquals(listOf("较早响应的定稿"), board.snapshot().map { it.zh })
    }

    /** 真定稿撤回仍属于原位校正，不能重新开放草稿，也不能误清另一响应的新草稿。 */
    @Test fun withdrawingTrueFinalKeepsItsFinishedIdentityAndDoesNotAdvancePastAnotherDraft() {
        val adapter = OnlineCaptionAdapter()
        val board = CaptionBoard()
        val text = LiveTranslationText("a", "原文", "真正定稿", true)
        val final = requireNotNull(adapter.accept(text))
        board.accept(final)
        val other = LiveTranslationText("b", "", "另一响应草稿", false)
        val otherDraft = requireNotNull(adapter.accept(other))
        board.accept(otherDraft)
        val withdrawal = requireNotNull(adapter.accept(text.copy(sourceText = "", translatedText = ""), correctionOnly = true))
        assertEquals(final.segmentId, withdrawal.segmentId)
        assertEquals(final.revision + 1, withdrawal.revision)
        assertFalse(board.accept(withdrawal))
        assertTrue(board.updateOnlineFinal(withdrawal))
        assertNull(adapter.accept(text.copy(translatedText = "迟到草稿", isFinal = false)))
        assertNull(adapter.accept(text.copy(sourceText = "", translatedText = "")))
        val otherUpdate = requireNotNull(adapter.accept(other.copy(translatedText = "另一响应更新")))
        assertEquals(otherDraft.segmentId, otherUpdate.segmentId)
        assertTrue(board.accept(otherUpdate))
        val correction = requireNotNull(adapter.accept(text.copy(translatedText = "迟到定稿修订"), correctionOnly = true))
        assertEquals(final.segmentId, correction.segmentId)
        assertFalse(board.accept(correction))
        assertFalse(board.updateOnlineFinal(correction))
        assertEquals(listOf("另一响应更新"), board.snapshot().map { it.zh })
    }

    /** 撤回草稿清理后仍遵守只修订模式，不能借补原文或历史校正重新创建字幕。 */
    @Test fun correctionOnlyCannotRecreateWithdrawnDraft() {
        val adapter = OnlineCaptionAdapter()
        val draft = LiveTranslationText("a", "原文", "预测", false)
        adapter.accept(draft)
        val withdrawal = requireNotNull(adapter.accept(draft.copy(translatedText = "", isFinal = true)))
        val final = draft.copy(translatedText = "新定稿", isFinal = true)
        assertNull(adapter.accept(final, correctionOnly = true))
        assertNull(adapter.accept(draft, correctionOnly = true))
        assertTrue(requireNotNull(adapter.accept(final)).segmentId > withdrawal.segmentId)
    }

    /** 生产分段路径撤回预测尾片后再次增长，原译文都必须完整出现，不复用已结束的显示编号。 */
    @Test fun segmentedDraftWithdrawalAndRegrowthPreserveAllText() {
        val adapter = OnlineCaptionAdapter()
        val board = CaptionBoard().apply { setRetention(CaptionLimitMode.SENTENCES, 10, 1000) }
        val source = "最初です。次です。最後です。"
        for (text in listOf(
            LiveTranslationText("a", source, "第一句。预测", false, "第一句。"),
            LiveTranslationText("a", source, "第一句。", false, "第一句。"),
            LiveTranslationText("a", source, "第一句。新预测", false, "第一句。"),
            LiveTranslationText("a", source, "第一句。第二句。第三句。", true)
        )) {
            adapter.acceptSegmented(text).forEach { if (!board.accept(it)) board.updateOnlineFinal(it) }
            assertEquals(text.translatedText, board.snapshot().joinToString("") { it.zh })
            assertEquals(source, board.snapshot().joinToString("") { it.ja })
        }
        assertTrue(board.snapshot().all { it.isFinal })
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
