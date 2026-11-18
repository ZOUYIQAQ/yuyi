package io.github.korenkrita.simulsub.ui

import org.junit.Assert.*
import org.junit.Test

/** 列表首尾预览不改变原文，任何非空短密钥也不完整展示。 */
class KeyRingPreviewTest {
    /** 常见密钥首尾各四位，中间固定星号，长度不泄露。 */
    @Test fun longKeyUsesHeadAndTailOnly() {
        val raw = "sk-demo-1234567890-test"
        assertEquals("sk-d******test", keyRingPreview(raw))
        assertEquals("sk-demo-1234567890-test", raw)
    }

    /** 很短的密钥保留中间星号，一到两字全部隐藏。 */
    @Test fun shortKeysNeverShowTheWholeValue() {
        assertEquals("未填写", keyRingPreview(""))
        assertEquals("*", keyRingPreview("a"))
        assertEquals("**", keyRingPreview("ab"))
        assertEquals("a*c", keyRingPreview("abc"))
        assertEquals("a******h", keyRingPreview("abcdefgh"))
    }
}
