package io.github.korenkrita.simulsub.ui

import org.junit.Assert.*
import org.junit.Test

/** 预设只帮助填写连接信息，平台凭据由控制器按地址绑定。 */
class TranslationPresetsTest {
    /** 换平台保留中央密钥的绑定职责，同时清空不适用的自定义请求参数。 */
    @Test fun changingEndpointDoesNotCarryCredentials() {
        val old = TranslationApiUiState(url = "https://old.example/v1", model = "旧模型", key = null, hasSavedKey = true,
            headers = listOf(HeaderUiState("1", "X-Key", "旧服务凭据")), userAgent = "旧服务要求")
        val applied = TranslationPresets.apply(old, "deepseek")
        assertEquals("https://api.deepseek.com/v1/chat/completions", applied.url)
        assertEquals("deepseek-flash", applied.model)
        assertNull(applied.key)
        assertTrue(applied.hasSavedKey)
        assertTrue(applied.headers.isEmpty())
        assertEquals("", applied.userAgent)
        assertEquals("https://old.example/v1", old.url)
        assertEquals("旧服务凭据", old.headers.single().value)
    }

    /** 同一规范化端点再次套用模板时保留原平台绑定和请求参数。 */
    @Test fun sameEndpointKeepsItsOwnCredentials() {
        val old = TranslationApiUiState(url = "https://api.deepseek.com/v1/chat/completions", hasSavedKey = true)
        val applied = TranslationPresets.apply(old, "deepseek")
        assertNull(applied.key)
        assertTrue(applied.hasSavedKey)
        assertEquals("https://api.deepseek.com/v1/chat/completions", applied.url)
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
    /** 各模板的版本化基础路径也能识别；手动模型与地址继续自由编辑。 */
    @Test fun versionedVendorBaseUrlsMatchTheirTemplates() {
        TranslationPresets.entries.forEach { preset ->
            assertEquals(preset.id, TranslationPresets.match(preset.url.removeSuffix("/chat/completions")))
            val draft = TranslationPresets.apply(TranslationApiUiState(), preset.id)
            assertEquals(preset.url, draft.url)
            assertTrue(io.github.korenkrita.simulsub.TextServiceProviders.requiresKey(
                io.github.korenkrita.simulsub.PlatformCredentials.forUrl(draft.url)?.id))
        }
    }

    /** 已有未知模型原样出现在首项；选择供应商不会夹带别家预设。 */
    @Test fun modelSuggestionsKeepCustomIdsAndProviderBoundaries() {
        val customId = "MyDeployment/Case-Sensitive-v9"
        val deepseek = TranslationPresets.modelsFor("https://api.deepseek.com", customId)
        assertEquals(customId, deepseek.first().id)
        assertTrue(deepseek.any { it.id == "deepseek-flash" })
        assertFalse(deepseek.any { it.id.startsWith("qwen") })
        assertEquals(1, TranslationPresets.modelsFor("https://api.deepseek.com", "deepseek-flash")
            .count { it.id == "deepseek-flash" })
        assertEquals(listOf(UiOption(customId, customId)),
            TranslationPresets.modelsFor("https://myrelay.example/v1", customId))
        assertTrue(TranslationPresets.modelsFor("https://myrelay.example/v1", "").isEmpty())
    }

    /** 手动部署名和账户接入点仅作为当前值保留，不改写输入或自动保存。 */
    @Test fun modelSuggestionsDoNotAlterTheConnectionDraft() {
        val draft = TranslationApiUiState(url = "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
            model = "ep-MyPrivateEndpoint", credentialId = "backup", headers = listOf(HeaderUiState("1", "X-Route", "value")))
        assertEquals(draft.model, TranslationPresets.modelsFor(draft.url, draft.model).first().id)
        assertEquals("ep-MyPrivateEndpoint", draft.model)
        assertEquals("backup", draft.credentialId)
        assertEquals("value", draft.headers.single().value)
    }
}
