package io.github.korenkrita.simulsub

import io.github.korenkrita.simulsub.online.LiveTranslationText

/** 在不切断云端音频上下文的前提下，把已确认的译文逐句送入字幕板，预测尾部始终保持临时状态。 */
internal class OnlineCaptionSegmenter {
    internal data class Change(val text: LiveTranslationText, val correctionOnly: Boolean)
    private data class Entry(var last: LiveTranslationText, var parts: List<LiveTranslationText>)
    private val entries = linkedMapOf<String, Entry>()

    /** 同一响应内使用稳定子段号；定稿校正、迟到原文和撤回都只更新对应子段。 */
    fun accept(text: LiveTranslationText): List<Change> {
        val previous = entries[text.segmentId]
        if (previous?.last == text || (previous?.last?.isFinal == true && !text.isFinal)) return emptyList()
        val confirmed = if (text.isFinal) text.translatedText else text.confirmedText
        require(text.translatedText.startsWith(confirmed)) { "听译已确认内容必须是字幕前缀" }
        val ends = boundaries(confirmed)
        val chunks = mutableListOf<Pair<String, Boolean>>()
        var start = 0
        for (end in ends) {
            chunks += text.translatedText.substring(start, end) to true
            start = end
        }
        if (start < text.translatedText.length) chunks += text.translatedText.substring(start) to text.isFinal
        val originals = if (text.isFinal) splitOriginal(text.sourceText) else emptyList()
        val parts = chunks.mapIndexed { index, (translated, final) ->
            // 服务端没有逐词对齐信息：无法一一对应时，整段原文仅附在最后一段，避免伪造对齐或重复显示。
            val original = when {
                originals.size == chunks.size -> originals[index]
                index == chunks.lastIndex -> text.sourceText
                text.isFinal -> ""
                else -> previous?.parts?.getOrNull(index)?.sourceText.orEmpty()
            }
            LiveTranslationText("${text.segmentId}/$index", original, translated, final,
                if (final) translated else confirmed.substring(start))
        }
        val changed = mutableListOf<Change>()
        // 全文校正使分段变少时，先撤掉多余的旧段，不能把过期预测或错误定稿留在字幕板中。
        previous?.parts?.drop(parts.size)?.forEach { old ->
            changed += Change(old.copy(sourceText = "", translatedText = "", isFinal = true, confirmedText = ""), old.isFinal)
        }
        parts.forEachIndexed { index, part ->
            val old = previous?.parts?.getOrNull(index)
            if (old != part) changed += Change(part, old?.isFinal == true)
        }
        entries[text.segmentId] = Entry(text, parts)
        while (entries.size > 256) {
            val oldest = entries.entries.firstOrNull { it.value.last.isFinal }
                ?: error("听译并发响应过多")
            entries.remove(oldest.key)
        }
        return changed
    }

    /** 原文仅在确认整个响应后分块；分块数量不匹配时由调用方保留完整原文。 */
    private fun splitOriginal(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val ends = boundaries(text).toMutableList()
        if (ends.lastOrNull() != text.length) ends += text.length
        var start = 0
        return ends.map { end -> text.substring(start, end).also { start = end } }
    }

    /** 句末优先断开，较长分句可在逗号处断开；无标点长段按文字系统控制长度，英文优先保留整词。 */
    private fun boundaries(text: String): List<Int> {
        val ends = mutableListOf<Int>()
        var start = 0
        var pos = 0
        var count = 0
        var cjk = false
        var space = -1
        while (pos < text.length) {
            val cp = text.codePointAt(pos)
            val next = pos + Character.charCount(cp)
            count++
            cjk = cjk || when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> true
                else -> false
            }
            if (Character.isWhitespace(cp)) space = next
            val decimal = cp == '.'.code && pos > 0 && next < text.length &&
                text[pos - 1].isDigit() && text[next].isDigit()
            val sentence = (cp <= 0xFFFF && cp.toChar() in "。！？!?；;\n") ||
                (cp == '.'.code && !decimal && periodEndsSentence(text, start, pos, next))
            val clause = cp <= 0xFFFF && cp.toChar() in "，,、：:" && count >= if (cjk) 6 else 24
            val full = count >= if (cjk) 18 else 60
            if (sentence || clause || full) {
                var end = if (full && !sentence && !clause && space > start + (next - start) / 2) space else next
                while (end < text.length && (text[end].isWhitespace() || text[end] in "。！？!?；;\"'”’」』）)")) end++
                end = graphemeEnd(text, end)
                ends += end
                start = end
                pos = end
                count = 0
                cjk = false
                space = -1
            } else pos = next
        }
        return ends
    }

    /** 英文完整句可以立即确认，但数字小数点和常见称谓缩写不能在后文到达前误切。 */
    private fun periodEndsSentence(text: String, start: Int, pos: Int, next: Int): Boolean {
        val word = text.substring(start, pos).takeLastWhile { it.isLetter() || it == '.' }.lowercase(java.util.Locale.ROOT)
        if (word.length <= 1 || word in ABBREVIATIONS) return false
        if (next == text.length) return pos > start && !text[pos - 1].isDigit()
        return text[next].isWhitespace() || text[next] in "\"'”’」』"
    }

    /** 长度边界后移到组合字形末尾，避免拆开连字表情、肤色、组合重音和国旗。 */
    private fun graphemeEnd(text: String, boundary: Int): Int {
        var end = boundary
        while (end < text.length) {
            val cp = text.codePointAt(end)
            val previous = text.codePointBefore(end)
            val extending = Character.getType(cp) in listOf(Character.NON_SPACING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt()) ||
                cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF || cp in 0x1F3FB..0x1F3FF
            var regionalPair = false
            if (cp in 0x1F1E6..0x1F1FF && previous in 0x1F1E6..0x1F1FF) {
                var count = 0
                var at = end
                while (at > 0 && text.codePointBefore(at) in 0x1F1E6..0x1F1FF) {
                    at -= Character.charCount(text.codePointBefore(at))
                    count++
                }
                regionalPair = count % 2 == 1
            }
            if (!extending && cp != 0x200D && previous != 0x200D && !regionalPair) break
            end += Character.charCount(cp)
        }
        return end
    }

    companion object {
        private val ABBREVIATIONS = setOf("mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "e.g", "i.e")
    }
}
