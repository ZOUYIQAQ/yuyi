package io.github.korenkrita.simulsub

import org.junit.Assert.*
import org.junit.Test

/** 模板必须直接得到官方聊天地址，不能为不同版本路径多拼一层 v1。 */
class TextServiceProvidersTest {
    /** 目录包含国内外常用平台，稳定标识唯一，每条均链接官方文档。 */
    @Test fun catalogContainsMajorProvidersWithUniqueIdentities() {
        val entries = TextServiceProviders.entries
        assertEquals(24, entries.size)
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        assertTrue(entries.map { it.id }.containsAll(listOf("openai", "gemini", "deepseek", "qwen", "zhipu", "moonshot",
            "minimax", "doubao", "baidu", "hunyuan", "groq", "mistral", "openrouter")))
        entries.forEach { provider ->
            assertTrue(provider.docsUrl.startsWith("https://"))
            assertEquals(provider.platformId, PlatformCredentials.forUrl(provider.url)?.id)
            assertTrue(TextServiceProviders.requiresKey(provider.platformId))
        }
    }

    /** 非 v1 前缀明确提供完整地址，运行时无需探测就能访问正确路径。 */
    @Test fun vendorSpecificPathsDoNotGainAnExtraV1() {
        val expected = mapOf(
            "zhipu" to "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            "doubao" to "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
            "baidu" to "https://qianfan.baidubce.com/v2/chat/completions",
            "gemini" to "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            "deepinfra" to "https://api.deepinfra.com/v1/openai/chat/completions",
            "zai" to "https://api.z.ai/api/paas/v4/chat/completions",
        )
        expected.forEach { (id, url) ->
            val template = TextServiceProviders.entries.single { it.id == id }
            assertEquals(url, ByokTranslator.chatUrl(template.url).second)
        }
    }

    /** 相似主机、HTTP 和非标准端口仍与官方凭据隔离，自建免密接口保持自由配置。 */
    @Test fun templatesNeverExpandCredentialTrustBoundaries() {
        TextServiceProviders.entries.forEach { provider ->
            val host = java.net.URI(provider.url).host
            assertNotEquals(provider.platformId, PlatformCredentials.forUrl("https://$host.invalid/v1")?.id)
            assertNotEquals(provider.platformId, PlatformCredentials.forUrl("http://$host/v1")?.id)
            assertNotEquals(provider.platformId, PlatformCredentials.forUrl("https://$host:9443/v1")?.id)
        }
        assertFalse(TextServiceProviders.requiresKey("origin:https://custom.example"))
    }
}
