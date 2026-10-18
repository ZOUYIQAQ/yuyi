package io.github.korenkrita.simulsub

private val captionEndMarks = setOf('。', '.', '，', ',', '？', '?', '！', '!', '；', ';', '：', ':', '…', '～', '~')
private val captionClosers = setOf('”', '’', '"', '\'', '）', ')', '】', ']', '》', '〉', '」', '』', '〕', '｝', '}')

/** 只加工悬浮字幕的显示副本；逐段清理句尾符号，保留原文、句中符号和内部换行。 */
fun captionDisplayText(value: String): String {
    val input = value.trimEnd()
    if (input.isEmpty()) return ""
    val display = StringBuilder(input.length)
    var start = 0
    while (start < input.length) {
        val newline = input.indexOf('\n', start).let { if (it < 0) input.length else it }
        val hasCarriageReturn = newline < input.length && newline > start && input[newline - 1] == '\r'
        val end = if (hasCarriageReturn) newline - 1 else newline
        display.append(trimCaptionSegment(input.substring(start, end)))
        if (newline < input.length) display.append(if (hasCarriageReturn) "\r\n" else "\n")
        start = newline + 1
    }
    return display.toString().takeIf { text ->
        text.any { !it.isWhitespace() && it !in captionEndMarks && it !in captionClosers }
    } ?: ""
}

/** 从一段末尾向前清理标点及其前后的闭合符号，闭合符号自身按原序保留。 */
private fun trimCaptionSegment(segment: String): String {
    var end = segment.length
    val closers = StringBuilder()
    while (end > 0) {
        when (val char = segment[end - 1]) {
            in captionEndMarks -> end--
            in captionClosers -> { closers.append(char); end-- }
            else -> if (char.isWhitespace()) end-- else break
        }
    }
    return segment.substring(0, end) + closers.reverse()
}
