package io.github.korenkrita.simulsub

/** 显示限制互斥选择；句数为默认，字数只统计译文的Unicode码点。 */
enum class CaptionLimitMode(val title: String) { SENTENCES("按句数"), CHARACTERS("按字数") }

/** 只裁剪显示快照，不改动原始定稿、会话记录或翻译上下文。 */
object CaptionRetention {
    /** 句数包含实时草稿；字数保留最新译文，超长单句也按码点安全截取尾部。 */
    fun apply(captions: List<Caption>, mode: CaptionLimitMode, sentences: Int, chars: Int): List<Caption> {
        if (mode == CaptionLimitMode.SENTENCES) return captions.takeLast(sentences.coerceAtLeast(1))
        var remaining = chars.coerceAtLeast(1)
        val visible = mutableListOf<Caption>()
        for (caption in captions.asReversed()) {
            if (remaining <= 0) break
            val count = caption.zh.codePointCount(0, caption.zh.length)
            if (count <= remaining) {
                visible += caption
                remaining -= count
            } else {
                val start = caption.zh.offsetByCodePoints(0, count - remaining)
                visible += caption.copy(zh = caption.zh.substring(start))
                remaining = 0
            }
        }
        return visible.asReversed()
    }
}
