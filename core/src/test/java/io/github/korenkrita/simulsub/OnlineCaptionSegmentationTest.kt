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

    /** 原文快照不变而译文逐句增长时，旧尾片不能继续携带整段原文。 */
    @Test fun growingConfirmedTranslationNeverAccumulatesWholeSourceCopies() {
        val adapter = OnlineCaptionAdapter()
        val board = CaptionBoard().apply { setRetention(CaptionLimitMode.SENTENCES, 10, 1000) }
        val source = "最初の文です。次の文です。最後の文です。"
        deliver(adapter, board, LiveTranslationText("a", source, "第一句。", false, "第一句。"))
        deliver(adapter, board, LiveTranslationText("a", source, "第一句。第二句。", false, "第一句。第二句。"))
        assertEquals(source, board.snapshot().joinToString("") { it.ja })
        deliver(adapter, board, LiveTranslationText("a", source, "第一句。第二句。第三句。", false, "第一句。第二句。第三句。"))
        assertEquals(listOf("最初の文です。", "次の文です。", "最後の文です。"), board.snapshot().map { it.ja })
    }

    /** 流式确认前缀和预测尾部也按顺序分配已有原文，不等全文定稿才拆原文。 */
    @Test fun streamingSourceUsesOrderedPartsForConfirmedRowsAndDraft() {
        val parts = OnlineCaptionAdapter().acceptSegmented(
            LiveTranslationText("a", "最初です。次です。", "第一句。预测尾部", false, "第一句。"))
        assertEquals(listOf("最初です。", "次です。"), parts.map { it.ja })
        assertEquals(listOf(true, false), parts.map { it.isFinal })
    }

    /** 流式原文变长或被识别修正时，只更新原有字幕槽位且完整替换旧原文。 */
    @Test fun streamingSourceGrowthAndCorrectionKeepStableSlots() {
        val adapter = OnlineCaptionAdapter()
        val board = CaptionBoard().apply { setRetention(CaptionLimitMode.SENTENCES, 10, 1000) }
        val initial = LiveTranslationText("a", "最初です。", "第一句。预测", false, "第一句。")
        deliver(adapter, board, initial)
        val ids = board.snapshot().map { it.segmentId }
        for (source in listOf("最初です。次です。", "最初の修正です。予測も修正します。")) {
            deliver(adapter, board, initial.copy(sourceText = source))
            assertEquals(ids, board.snapshot().map { it.segmentId })
            assertEquals(source, board.snapshot().joinToString("") { it.ja })
            assertEquals(listOf("第一句。", "预测"), board.snapshot().map { it.zh })
            assertEquals(listOf(true, false), board.snapshot().map { it.isFinal })
        }
    }

    /** 缺少对应标点时只作顺序切分，不能把完整长原文塞入最后一片或丢掉文字。 */
    @Test fun unmatchedLongSourceIsPartitionedWithoutLoss() {
        val source = "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめも"
        val parts = OnlineCaptionAdapter().acceptSegmented(LiveTranslationText("a", source, "第一句。第二句。第三句。", true))
        assertEquals(source, parts.joinToString("") { it.ja })
        assertTrue(parts.all { it.ja.isNotEmpty() && it.ja != source })
    }

    /** 原译文句子数一致时先保住句末锚点，再处理句内长度差异，避免第二句原文跑到第一句。 */
    @Test fun sentenceAnchorsSurviveDifferentReadingChunkCounts() {
        val firstSource = "これは最初の長い説明であり途中に句読点がありません。"
        val secondSource = "次です。"
        val firstTranslation = "这".repeat(39) + "。"
        val parts = OnlineCaptionAdapter().acceptSegmented(
            LiveTranslationText("a", firstSource + secondSource, firstTranslation + "第二句。", true))
        assertEquals(firstTranslation, parts.dropLast(1).joinToString("") { it.zh })
        assertEquals(firstSource, parts.dropLast(1).joinToString("") { it.ja })
        assertEquals(secondSource, parts.last().ja)
    }

    /** 迟到原文切点不匹配时仍修订原编号，源文本变化也不能留下前一版片段。 */
    @Test fun lateUnmatchedSourceAndSourceCorrectionKeepExistingRows() {
        val adapter = OnlineCaptionAdapter()
        val board = CaptionBoard().apply { setRetention(CaptionLimitMode.SENTENCES, 10, 1000) }
        val translation = "第一句。第二句。第三句。"
        deliver(adapter, board, LiveTranslationText("a", "", translation, true))
        val ids = board.snapshot().map { it.segmentId }
        for (source in listOf("あいうえおかきくけこさしすせそ", "修正された原文には対応する句読点がありません")) {
            deliver(adapter, board, LiveTranslationText("a", source, translation, true))
            assertEquals(ids, board.snapshot().map { it.segmentId })
            assertEquals(source, board.snapshot().joinToString("") { it.ja })
            assertTrue(board.snapshot().all { it.ja.isNotEmpty() && it.ja != source })
        }
    }

    /** 预测尾部改写、撤回后重新定稿时，原文必须从当前快照重新分配且不残留旧片。 */
    @Test fun draftWithdrawalAndFinalRevisionPreserveSourceExactlyOnce() {
        val adapter = OnlineCaptionAdapter()
        val board = CaptionBoard().apply { setRetention(CaptionLimitMode.SENTENCES, 10, 1000) }
        val source = "最初です。次です。最後です。"
        for (text in listOf(
            LiveTranslationText("a", source, "第一句。预测", false, "第一句。"),
            LiveTranslationText("a", source, "第一句。新预测的句子。", false, "第一句。"),
            LiveTranslationText("a", source, "第一句。", false, "第一句。"),
            LiveTranslationText("a", source, "修订成一句。", true)
        )) {
            deliver(adapter, board, text)
            assertEquals(source, board.snapshot().joinToString("") { it.ja })
            assertEquals(text.translatedText, board.snapshot().joinToString("") { it.zh })
        }
    }

    /** 多片定稿合并为较少片段时，原文随修订重新分配，旧片撤回不能带走中间原文。 */
    @Test fun finalCorrectionRepartitionsSourceWhenRowsShrink() {
        val adapter = OnlineCaptionAdapter()
        val board = CaptionBoard().apply { setRetention(CaptionLimitMode.SENTENCES, 10, 1000) }
        val source = "最初です。次です。最後です。"
        deliver(adapter, board, LiveTranslationText("a", source, "第一句。第二句。第三句。", true))
        val id = board.snapshot().first().segmentId
        deliver(adapter, board, LiveTranslationText("a", source, "修订成一句。", true))
        assertEquals(id, board.snapshot().single().segmentId)
        assertEquals(source, board.snapshot().single().ja)
    }

    /** 原文不足以逐片分配时允许空片，但代理对、组合重音和表情连字不能拆开或复制。 */
    @Test fun shortAndCombinedSourceGlyphsAreNeverSplitOrDuplicated() {
        for (source in listOf("あ", "👨‍👩‍👧‍👦", "e\u0301", "🇯🇵", "中".repeat(17) + "👨‍👩‍👧‍👦" + "后".repeat(30))) {
            val parts = OnlineCaptionAdapter().acceptSegmented(LiveTranslationText("a", source, "一。二。三。四。", true))
            assertEquals(source, parts.joinToString("") { it.ja })
            if (source.length < 18) assertEquals(listOf(source), parts.map { it.ja }.filter { it.isNotEmpty() })
            else assertTrue(parts.any { it.ja.contains("👨‍👩‍👧‍👦") })
        }
    }

    /** 原文分配不能改变仅译文模式的段号、确认状态和修订次数。 */
    @Test fun sourcePartitioningLeavesTranslationOnlyBehaviorUnchanged() {
        val translatedOnly = OnlineCaptionAdapter()
        val bilingual = OnlineCaptionAdapter()
        for (text in listOf(
            LiveTranslationText("a", "", "第一句。预测", false, "第一句。"),
            LiveTranslationText("a", "", "第一句。第二句。新预测", false, "第一句。第二句。"),
            LiveTranslationText("a", "", "第一句。第二句。", true),
            LiveTranslationText("a", "", "修正的一句。", true)
        )) {
            val expected = translatedOnly.acceptSegmented(text)
            val actual = bilingual.acceptSegmented(text.copy(sourceText = "最初です。次です。最後です。"))
            // 原文独立修订可能多发旧片；对应的译文变更必须保持相同编号和修订次数。
            expected.forEach { old ->
                val updated = actual.single { it.segmentId == old.segmentId }
                assertEquals(old.zh, updated.zh)
                assertEquals(old.isFinal, updated.isFinal)
                assertEquals(old.revision, updated.revision)
            }
        }
    }

    /** 直接合并分段器快照，验证不同标点、字形、原文迟到和尾片复用时的完整性。 */
    @Test fun segmenterSnapshotsPreserveEverySourceCharacterAcrossRevisions() {
        val sources = listOf(
            "Hello Dr. Smith, the value is 3.14. We can read all these words without losing spaces.",
            "長い原文です。".repeat(15),
            "👨‍👩‍👧‍👦e\u0301🇯🇵".repeat(8)
        )
        for (source in sources) {
            val segmenter = OnlineCaptionSegmenter()
            val visible = linkedMapOf<String, LiveTranslationText>()
            for (text in listOf(
                LiveTranslationText("a", "", "一。予测", false, "一。"),
                LiveTranslationText("a", source, "一。予测", false, "一。"),
                LiveTranslationText("a", source, "一。二。予测", false, "一。二。"),
                LiveTranslationText("a", source, "一。二。", false, "一。二。"),
                LiveTranslationText("a", source, "一。二。三。", true),
                LiveTranslationText("a", source, "最终修订。", true)
            )) {
                segmenter.accept(text).forEach { change ->
                    if (change.text.translatedText.isEmpty()) visible.remove(change.text.segmentId)
                    else visible[change.text.segmentId] = change.text
                }
                val parts = visible.values.sortedBy { it.segmentId.substringAfterLast('/').toInt() }
                assertEquals(text.sourceText, parts.joinToString("") { it.sourceText })
                assertEquals(text.translatedText, parts.joinToString("") { it.translatedText })
                assertTrue(segmenter.accept(text).isEmpty())
            }
        }
    }

    /** 模拟生产在线路径：新段进入字幕板，既有定稿只允许原位校正。 */
    private fun deliver(adapter: OnlineCaptionAdapter, board: CaptionBoard, text: LiveTranslationText) {
        adapter.acceptSegmented(text).forEach { if (!board.accept(it)) board.updateOnlineFinal(it) }
    }
}
