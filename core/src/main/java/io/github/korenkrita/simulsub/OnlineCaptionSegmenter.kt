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
        val originals = splitOriginal(text.sourceText, chunks.map { it.first })
        val parts = chunks.mapIndexed { index, (translated, final) ->
            // 每次完整快照统一分配原文；不能沿用旧尾片的全文，再把同一全文挂到新尾片。
            LiveTranslationText("${text.segmentId}/$index", originals[index], translated, final,
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

    /** 原文与译文句数一致时保留句末锚点，其余仅按顺序和长度分配，不推断跨语言语义。 */
    private fun splitOriginal(text: String, translated: List<String>): List<String> {
        if (translated.isEmpty()) return emptyList()
        if (text.isEmpty()) return List(translated.size) { "" }
        val translation = translated.joinToString("")
        val sourceEnds = sentenceEnds(text)
        val translatedEnds = sentenceEnds(translation)
        val chunkEnds = translated.runningFold(0) { offset, part -> offset + part.length }.drop(1)
        // 预测尾部可能含多个句子但仍只有一个草稿槽位，此时不能在槽位内部建立虚假的对应关系。
        val aligned = sourceEnds.size == translatedEnds.size && translatedEnds.all { chunkEnds.binarySearch(it) >= 0 }
        if (!aligned) return distributeOriginal(text, translated)
        val result = mutableListOf<String>()
        var sourceStart = 0
        var chunkStart = 0
        sourceEnds.forEachIndexed { index, sourceEnd ->
            val chunkEnd = chunkEnds.binarySearch(translatedEnds[index]) + 1
            result += distributeOriginal(text.substring(sourceStart, sourceEnd), translated.subList(chunkStart, chunkEnd))
            sourceStart = sourceEnd
            chunkStart = chunkEnd
        }
        return result
    }

    /** 收集真正句末及未结束尾部，排除阅读长度和逗号产生的人工切点。 */
    private fun sentenceEnds(text: String): List<Int> {
        val ends = boundaries(text, sentencesOnly = true).toMutableList()
        if (ends.lastOrNull() != text.length) ends += text.length
        return ends
    }

    /** 对无逐词对齐信息的区间按译文长度分配连续原文，邻近标点或空格优先且始终保留完整字形。 */
    private fun distributeOriginal(text: String, translated: List<String>): List<String> {
        if (translated.size == 1) return listOf(text)
        val glyphEnds = mutableListOf(0)
        val natural = mutableListOf<Int>()
        var at = 0
        while (at < text.length) {
            val cp = text.codePointAt(at)
            at = graphemeEnd(text, at + Character.charCount(cp), at)
            glyphEnds += at
            if (Character.isWhitespace(cp) || (cp <= 0xFFFF && cp.toChar() in "。！？!?；;\n，,、：:.")) {
                natural += glyphEnds.lastIndex
            }
        }
        val weights = translated.map { it.codePointCount(0, it.length).coerceAtLeast(1) }
        val total = weights.sum()
        val glyphs = glyphEnds.lastIndex
        var weight = 0L
        var start = 0
        return translated.indices.map { index ->
            weight += weights[index]
            var end = if (index == translated.lastIndex) glyphs else ((glyphs * weight + total / 2) / total).toInt()
            if (index != translated.lastIndex) {
                val found = natural.binarySearch(end)
                val insertion = if (found >= 0) found else -found - 1
                val nearby = listOfNotNull(natural.getOrNull(insertion - 1), natural.getOrNull(insertion))
                    .minByOrNull { kotlin.math.abs(it - end) }
                if (nearby != null && kotlin.math.abs(nearby - end) <= (glyphs / translated.size / 3).coerceAtLeast(1)) {
                    end = nearby
                }
                // 原文字形足够时每片至少分到一个；不足时允许空片，不能拆开或重复一个字形。
                val minimum = if (glyphs >= translated.size) start + 1 else start
                val maximum = if (glyphs >= translated.size) glyphs - (translated.lastIndex - index) else glyphs
                end = end.coerceIn(minimum, maximum)
            }
            text.substring(glyphEnds[start], glyphEnds[end]).also { start = end }
        }
    }

    /** 句末优先断开，较长分句可在逗号处断开；无标点长段按文字系统控制长度，英文优先保留整词。 */
    private fun boundaries(text: String, sentencesOnly: Boolean = false): List<Int> {
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
            if (sentence || (!sentencesOnly && (clause || full))) {
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
    private fun graphemeEnd(text: String, boundary: Int, glyphStart: Int = 0): Int {
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
                while (at > glyphStart && text.codePointBefore(at) in 0x1F1E6..0x1F1FF) {
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
