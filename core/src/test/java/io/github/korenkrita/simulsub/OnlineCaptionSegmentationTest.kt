package io.github.korenkrita.simulsub

import io.github.korenkrita.simulsub.online.LiveTranslationText
import org.junit.Assert.*
import org.junit.Test

/** 用真实服务端形态的快照验证逐句出现、预测改写、限句和最终校正。 */
class OnlineCaptionSegmentationTest {
    /** 云端未结束响应时，已确认分句就应成为正式字幕，其余内容只占一个临时槽位。 */
    @Test fun confirmedClausesAppearBeforeTheWholeResponseFinishes() {
        val adapter = OnlineCaptionAdapter()
        val board = CaptionBoard().apply { setRetention(CaptionLimitMode.SENTENCES, 3, 100) }
        val prefix = "我们中学实行便当制，"
        deliver(adapter, board, LiveTranslationText("a", "", prefix + "如果没带", false, prefix))
        assertEquals(listOf(prefix), board.snapshot(false).map { it.zh })
        assertEquals("如果没带", board.snapshot().last().zh)
        deliver(adapter, board, LiveTranslationText("a", "", prefix + "如果没带便当的话，就买面包", false,
            prefix + "如果没带便当的话，"))
        assertEquals(listOf(prefix, "如果没带便当的话，"), board.snapshot(false).map { it.zh })
        assertEquals("就买面包", board.snapshot().last().zh)
        deliver(adapter, board, LiveTranslationText("a", "", prefix + "如果没带便当的话，就买面包。", true))
        assertEquals(listOf(prefix, "如果没带便当的话，", "就买面包。"), board.snapshot().map { it.zh })
        assertTrue(board.snapshot().all { it.isFinal })
    }

    /** 预测中的句号不代表已经确认，预测改写和撤回不能留下错误定稿。 */
    @Test fun punctuationInStashNeverBecomesFinal() {
        val adapter = OnlineCaptionAdapter()
        val board = CaptionBoard()
        deliver(adapter, board, LiveTranslationText("a", "", "预测句子。后面还有。", false))
        assertTrue(board.snapshot(false).isEmpty())
        deliver(adapter, board, LiveTranslationText("a", "", "修改后的预测", false))
        assertEquals(listOf("修改后的预测"), board.snapshot().map { it.zh })
        deliver(adapter, board, LiveTranslationText("a", "", "", true))
        assertTrue(board.snapshot().isEmpty())
    }

    /** 重发的已确认快照不重计句数，真正的叠词也应原样保留。 */
    @Test fun repeatedSnapshotsKeepStableRowsAndRealRepetitions() {
        val adapter = OnlineCaptionAdapter()
        val text = LiveTranslationText("a", "", "好好。后续", false, "好好。")
        val first = adapter.acceptSegmented(text)
        assertEquals(listOf(true, false), first.map { it.isFinal })
        assertTrue(adapter.acceptSegmented(text).isEmpty())
        val update = adapter.acceptSegmented(text.copy(translatedText = "好好。后续更新"))
        assertEquals(1, update.size)
        assertEquals(first.last().segmentId, update.single().segmentId)
        assertFalse(update.single().isFinal)
    }

    /** 即使可见文字没有变化，从预测转为已确认也必须提升为定稿，供仅定稿模式显示。 */
    @Test fun unchangedVisibleTextCanStillBecomeConfirmed() {
        val adapter = OnlineCaptionAdapter()
        val draft = adapter.acceptSegmented(LiveTranslationText("a", "", "确认这句。", false)).single()
        val final = adapter.acceptSegmented(LiveTranslationText("a", "", "确认这句。", false, "确认这句。")).single()
        assertFalse(draft.isFinal)
        assertTrue(final.isFinal)
        assertEquals(draft.segmentId, final.segmentId)
        assertTrue(adapter.acceptSegmented(LiveTranslationText("a", "", "确认这句。", true)).isEmpty())
    }

    /** 只有三句定稿占用默认额度，临时尾部出现、消失都不能挤走正式字幕。 */
    @Test fun finalOnlyAndSentenceLimitApplyToSubSentences() {
        val adapter = OnlineCaptionAdapter()
        val board = CaptionBoard().apply { setRetention(CaptionLimitMode.SENTENCES, 3, 100) }
        deliver(adapter, board, LiveTranslationText("a", "", "一。二。三。四。预测", false, "一。二。三。四。"))
        assertEquals(listOf("二。", "三。", "四。"), board.snapshot(false).map { it.zh })
        assertEquals(4, board.snapshot().size)
        deliver(adapter, board, LiveTranslationText("a", "", "一。二。三。四。", true))
        assertEquals(listOf("二。", "三。", "四。"), board.snapshot().map { it.zh })
    }

    /** 长篇中文、英文、表情和数字不能无限堆在同一行，也不能切断代理对或丢失文字。 */
    @Test fun longTextPreservesEveryCharacterAndEnglishWords() {
        val adapter = OnlineCaptionAdapter()
        val chinese = "今天一起看看直播里面发生了什么事情".repeat(10) + "😀3.14"
        val chineseParts = adapter.acceptSegmented(LiveTranslationText("a", "", chinese, true))
        assertTrue(chineseParts.size > 3)
        assertEquals(chinese, chineseParts.joinToString("") { it.zh })
        assertTrue(chineseParts.all { it.zh.codePointCount(0, it.zh.length) <= 19 })
        val english = "We watch the live stream and read subtitles together every evening. ".repeat(5)
        val englishParts = adapter.acceptSegmented(LiveTranslationText("b", "", english, true))
        assertEquals(english, englishParts.joinToString("") { it.zh })
        englishParts.dropLast(1).forEach { assertTrue(it.zh.last().isWhitespace() || it.zh.last() == '.') }
    }

    /** 服务端最终修改已确认句子时沿用已有位置，分段减少时同时撤回多余句子。 */
    @Test fun finalCorrectionUpdatesRowsInsteadOfDuplicatingOrLosingText() {
        val adapter = OnlineCaptionAdapter()
        val board = CaptionBoard().apply { setRetention(CaptionLimitMode.SENTENCES, 3, 100) }
        deliver(adapter, board, LiveTranslationText("a", "", "旧一。旧二。未完", false, "旧一。旧二。"))
        val firstId = board.snapshot().first().segmentId
        deliver(adapter, board, LiveTranslationText("a", "", "修正的一句。", true))
        assertEquals(listOf("修正的一句。"), board.snapshot().map { it.zh })
        assertEquals(firstId, board.snapshot().single().segmentId)
    }

    /** 原文比译文晚到时仍补回相同字幕，不重启滚动、不误把整段原文复制到每一句。 */
    @Test fun lateSourceUpdatesExistingRowsWithoutRepeatingTheWholeParagraph() {
        val adapter = OnlineCaptionAdapter()
        val board = CaptionBoard().apply { setRetention(CaptionLimitMode.SENTENCES, 3, 100) }
        deliver(adapter, board, LiveTranslationText("a", "", "第一句。第二句。", true))
        val ids = board.snapshot().map { it.segmentId }
        deliver(adapter, board, LiveTranslationText("a", "一文。二文。", "第一句。第二句。", true))
        assertEquals(ids, board.snapshot().map { it.segmentId })
        assertEquals(listOf("一文。", "二文。"), board.snapshot().map { it.ja })
        deliver(adapter, board, LiveTranslationText("a", "原文没有对应的分句标点", "第一句。第二句。", true))
        assertEquals("原文没有对应的分句标点", board.snapshot().joinToString("") { it.ja })
    }

    /** 已裁剪的句子即使收到全文修订，也不能重新出现并挤走新字幕。 */
    @Test fun correctionsNeverReviveTrimmedRows() {
        val adapter = OnlineCaptionAdapter()
        val board = CaptionBoard().apply { setRetention(CaptionLimitMode.SENTENCES, 1, 100) }
        deliver(adapter, board, LiveTranslationText("a", "", "旧一。旧二。", true))
        deliver(adapter, board, LiveTranslationText("b", "", "新句。", true))
        deliver(adapter, board, LiveTranslationText("a", "补原文", "旧一改。旧二改。", true))
        assertEquals(listOf("新句。"), board.snapshot().map { it.zh })
    }

    /** 极长单次响应超过定稿映射缓存后，修订最早的子段也不能把它当成新句追加。 */
    @Test fun evictedMappingsCannotTurnOldCorrectionsIntoNewCaptions() {
        val adapter = OnlineCaptionAdapter()
        val board = CaptionBoard().apply { setRetention(CaptionLimitMode.SENTENCES, 3, 100) }
        val paragraph = "甲。".repeat(513)
        deliver(adapter, board, LiveTranslationText("long", "", paragraph, false, paragraph))
        val ids = board.snapshot().map { it.segmentId }
        deliver(adapter, board, LiveTranslationText("long", "", "乙。" + paragraph.drop(2), true))
        assertEquals(ids, board.snapshot().map { it.segmentId })
        assertEquals(listOf("甲。", "甲。", "甲。"), board.snapshot().map { it.zh })
    }

    /** 英文完整句无需等整段结束，但小数和称谓缩写保持完整。 */
    @Test fun englishSentencesCanFinishWithoutMisreadingDecimalsAndTitles() {
        val adapter = OnlineCaptionAdapter()
        assertTrue(adapter.acceptSegmented(LiveTranslationText("a", "", "Hello.", false, "Hello.")).single().isFinal)
        assertFalse(adapter.acceptSegmented(LiveTranslationText("b", "", "3.", false, "3.")).single().isFinal)
        assertFalse(adapter.acceptSegmented(LiveTranslationText("c", "", "Dr.", false, "Dr.")).single().isFinal)
        assertEquals("3.14", adapter.acceptSegmented(LiveTranslationText("b", "", "3.14", true)).single().zh)
    }

    /** 长度切点落入表情连字、肤色或组合重音时，整组字形必须留在同一子段。 */
    @Test fun lengthLimitsKeepCombinedGlyphsTogether() {
        val adapter = OnlineCaptionAdapter()
        val family = "👨‍👩‍👧‍👦"
        val paragraph = "中".repeat(17) + family + "后".repeat(40)
        val parts = adapter.acceptSegmented(LiveTranslationText("emoji", "", paragraph, true))
        assertEquals(paragraph, parts.joinToString("") { it.zh })
        assertTrue(parts.first().zh.endsWith(family))
        val accent = "中".repeat(17) + "e\u0301" + "后".repeat(40)
        assertTrue(adapter.acceptSegmented(LiveTranslationText("accent", "", accent, true)).first().zh.endsWith("e\u0301"))
    }

    /** 模拟生产在线路径：新段进入字幕板，既有定稿只允许原位校正。 */
    private fun deliver(adapter: OnlineCaptionAdapter, board: CaptionBoard, text: LiveTranslationText) {
        adapter.acceptSegmented(text).forEach { if (!board.accept(it)) board.updateOnlineFinal(it) }
    }
}
