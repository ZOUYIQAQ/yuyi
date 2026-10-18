package io.github.korenkrita.simulsub

import io.github.korenkrita.simulsub.online.LiveTranslationText

/** 将云端消息映射为现有字幕板编号，复用句数、草稿、防抖与动画规则。 */
class OnlineCaptionAdapter {
    private data class Entry(var id: Long, var revision: Int = 0, var last: LiveTranslationText? = null)
    private val entries = linkedMapOf<String, Entry>()
    private val finished = linkedMapOf<String, Caption>()
    private var sequence = 0L
    private var lastFinal = 0L
    private val segmenter = OnlineCaptionSegmenter()

    /** 把一次云端响应拆成可阅读的连续字幕，继续复用相同的草稿、定稿和原文补全规则。 */
    @Synchronized
    fun acceptSegmented(text: LiveTranslationText): List<Caption> = segmenter.accept(text).mapNotNull {
        accept(it.text, it.correctionOnly)
    }

    /** 完成顺序决定正式编号，空文本、重放和已完成消息不能再次占用句数。 */
    @Synchronized
    fun accept(text: LiveTranslationText, correctionOnly: Boolean = false): Caption? {
        finished[text.segmentId]?.let { old ->
            if (!text.isFinal || (text.translatedText == old.zh && text.sourceText == old.ja)) return null
            return old.copy(ja = text.sourceText, zh = text.translatedText,
                revision = old.revision + if (old.zh == text.translatedText) 0 else 1)
                .also { finished[text.segmentId] = it }
        }
        if (correctionOnly) return null
        if (text.translatedText.isBlank() && !(text.isFinal && text.segmentId in entries)) return null
        val entry = entries.getOrPut(text.segmentId) { Entry(++sequence) }
        if (entry.last == text) return null
        // 空定稿只是撤回自己的草稿，必须保留原编号，不能误清更新的其他响应。
        if (entry.id <= lastFinal && text.translatedText.isNotBlank()) entry.id = ++sequence
        entry.last = text
        entry.revision++
        val caption = Caption(entry.id, entry.revision, text.isFinal, text.sourceText, text.translatedText,
            Timing(0, 0, 0, 0, 0, 0, 0), timeEstimated = true)
        if (text.isFinal) {
            lastFinal = maxOf(lastFinal, entry.id)
            finished[text.segmentId] = caption
            entries.remove(text.segmentId)
        }
        while (entries.size > 64) entries.remove(entries.keys.first())
        while (finished.size > 512) finished.remove(finished.keys.first())
        return caption
    }
}
