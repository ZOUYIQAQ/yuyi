package io.github.korenkrita.simulsub.ui

import org.junit.Assert.*
import org.junit.Test

/** 预设只帮助填写，重点防止换供应商时凭据串用与覆盖手动输入。 */
class TranslationPresetsTest {
    /** 换地址必须清空旧密钥、请求头和User-Agent，不触发保存动作。 */
    @Test fun changingEndpointDoesNotCarryCredentials() {
        val old = TranslationApiUiState(url = "https://old.example/v1", model = "旧模型", key = null, hasSavedKey = true,
            headers = listOf(HeaderUiState("1", "X-Key", "旧服务凭据")), userAgent = "旧服务要求")
        val applied = TranslationPresets.apply(old, "deepseek")
        assertEquals("https://api.deepseek.com/v1", applied.url)
        assertEquals("deepseek-flash", applied.model)
        assertEquals("", applied.key)
        assertFalse(applied.hasSavedKey)
        assertTrue(applied.headers.isEmpty())
        assertEquals("", applied.userAgent)
        assertEquals("https://old.example/v1", old.url)
        assertEquals("旧服务凭据", old.headers.single().value)
    }

    /** 同一规范化端点再次套用模板时保留用户已保存密钥。 */
    @Test fun sameEndpointKeepsItsOwnCredentials() {
        val old = TranslationApiUiState(url = "https://api.deepseek.com/v1/chat/completions", hasSavedKey = true)
        val applied = TranslationPresets.apply(old, "deepseek")
        assertNull(applied.key)
        assertTrue(applied.hasSavedKey)
    }

    /** 手动填写不重置已有表单，模型与地址的自由修改不会被模板锁定。 */
    @Test fun customModeKeepsEditableValues() {
        val selected = TranslationPresets.apply(TranslationApiUiState(), "siliconflow")
        val edited = selected.copy(url = "https://my.example/relay/v1", model = "我自己的模型")
        val custom = TranslationPresets.apply(edited, "custom")
        assertEquals(edited.url, custom.url)
        assertEquals(edited.model, custom.model)
        assertEquals("custom", TranslationPresets.match(custom.url))
        assertEquals("deepseek", TranslationPresets.match("https://api.deepseek.com"))
    }

    /** 每个内置模板都能形成有效HTTPS聊天端点；未知模板不改变草稿。 */
    @Test fun presetsResolveAndUnknownIdIsIgnored() {
        TranslationPresets.entries.forEach { preset ->
            assertEquals(preset.id, TranslationPresets.match(preset.url))
            assertTrue(TranslationPresets.apply(TranslationApiUiState(), preset.id).resolvedUrl.endsWith("/chat/completions"))
        }
        val original = TranslationApiUiState(model = "保留")
        assertEquals(original, TranslationPresets.apply(original, "不存在"))
    }
}
