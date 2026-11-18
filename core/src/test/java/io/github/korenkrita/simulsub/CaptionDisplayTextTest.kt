package io.github.korenkrita.simulsub

import org.junit.Assert.assertEquals
import org.junit.Test

/** 验证显示文本仅去掉句尾符号，原始字幕仍可独立保存。 */
class CaptionDisplayTextTest {
    /** 中英文句末标点和空白可连续去除，句中标点保持原样。 */
    @Test fun removesOnlySentenceEndingMarks() {
        assertEquals("你好，世界", captionDisplayText("你好，世界？！。… ~  "))
        assertEquals("Wait, what", captionDisplayText("Wait, what?!;:...~  "))
        assertEquals("比例3.14，约1:2", captionDisplayText("比例3.14，约1:2。"))
    }

    /** 句尾引号和括号留在原位，内部标点仍只清理结尾。 */
    @Test fun keepsClosingQuotesAndBrackets() {
        assertEquals("他说“你好”", captionDisplayText("他说“你好。”"))
        assertEquals("示例（甲，乙）", captionDisplayText("示例（甲，乙！）  "))
        assertEquals("Hello\"", captionDisplayText("Hello!\""))
        assertEquals("他说“你好”", captionDisplayText("他说“你好”。"))
    }

    /** 内部段落及换行符不变，各段落独立去掉句尾符号。 */
    @Test fun preservesInternalParagraphs() {
        assertEquals("第一句\n\n第二句，继续", captionDisplayText("第一句。\n\n第二句，继续？！ \n"))
        assertEquals("第一句\r\n第二句", captionDisplayText("第一句。\r\n第二句！"))
    }

    /** 仅含末尾符号或空白的草稿没有可显示的文字。 */
    @Test fun punctuationOnlyHasNoDisplayText() {
        assertEquals("", captionDisplayText("…"))
        assertEquals("", captionDisplayText("？！ ~  "))
        assertEquals("", captionDisplayText("  "))
    }
}
