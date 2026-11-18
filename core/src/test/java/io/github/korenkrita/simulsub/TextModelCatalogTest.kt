package io.github.korenkrita.simulsub

import org.junit.Assert.*
import org.junit.Test

/** 预设属于平台建议而非模型白名单，不能串用模型标识或重复显示。 */
class TextModelCatalogTest {
    /** 每个供应商均有非空建议，现有默认模型保持可选，未知供应商不借用别家的模型。 */
    @Test fun everyProviderHasDistinctNonBlankSuggestions() {
        TextServiceProviders.entries.forEach { provider ->
            val models = TextModelCatalog.forProvider(provider.id)
            assertTrue(provider.id, models.isNotEmpty())
            assertTrue(provider.id, models.none { it.isBlank() || it != it.trim() })
            assertEquals(provider.id, models.size, models.distinct().size)
            if (provider.model.isNotBlank()) assertTrue(provider.id, provider.model in models)
        }
        assertTrue(TextModelCatalog.forProvider("custom").isEmpty())
        assertTrue(TextModelCatalog.forProvider("unknown-provider").isEmpty())
    }

    /** 平台自有模型与聚合平台的带命名空间模型分别维护，国内外 MiniMax 共用模型名称。 */
    @Test fun modelIdsRetainPlatformSpecificNamespacesAndCasing() {
        assertTrue("deepseek-flash" in TextModelCatalog.forProvider("deepseek"))
        assertTrue("deepseek-ai/DeepSeek-V4-Flash" in TextModelCatalog.forProvider("siliconflow"))
        assertFalse("deepseek-flash" in TextModelCatalog.forProvider("siliconflow"))
        assertEquals(TextModelCatalog.forProvider("minimax"), TextModelCatalog.forProvider("minimax-global"))
    }
}
