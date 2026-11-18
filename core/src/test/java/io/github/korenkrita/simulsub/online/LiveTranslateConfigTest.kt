package io.github.korenkrita.simulsub.online

import org.junit.Assert.*
import org.junit.Test

class LiveTranslateConfigTest {
    /** 默认大陆地址、模型及现有四种语种保持稳定。 */
    @Test fun defaultsAndLanguages() {
        val config = LiveTranslateConfig(apiKey = "test-key")
        assertNull(config.validationError())
        assertEquals("https://dashscope.aliyuncs.com/api-ws/v1/realtime?model=qwen3.5-livetranslate-flash-realtime", config.requestUrl().toString())
        assertEquals(setOf("zh", "en", "ja", "ko"), LiveTranslateConfig.SUPPORTED_TARGET_LANGUAGES)
    }

    /** 支持业务空间地址和 HTTPS 输入，模型通过查询构建器独立设置。 */
    @Test fun secureWorkspaceEndpoint() {
        val config = LiveTranslateConfig("https://workspace.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime", "qwen3.5-livetranslate-flash-realtime-2026-05-19", "key", "ja", "ko")
        assertNull(config.validationError())
        assertEquals(config.model, config.requestUrl().queryParameter("model"))
    }

    /** 本实现只支持 3.5 协议，3.8 及其他模型必须在联网前明确拒绝。 */
    @Test fun rejectsModelsWithDifferentProtocols() {
        for (model in listOf("qwen3.8-livetranslate-flash-realtime", "qwen3-livetranslate-flash-realtime", "qwen3.5-omni-plus-realtime", "qwen3.5-livetranslate-flash-realtime-other")) {
            val error = LiveTranslateConfig(apiKey = "key", model = model).validationError()
            assertNotNull(error)
            assertTrue(error!!.contains("接入"))
        }
    }

    /** 旧分段稳定版和快照均不能通过校验或被当成实时模型。 */
    @Test fun retiredEconomyModelsAreRejected() {
        val config = LiveTranslateConfig(endpoint = LiveTranslateModels.ECONOMY_ENDPOINT,
            model = LiveTranslateModels.ECONOMY_ID, apiKey = "key", sourceLang = "ja")
        assertNotNull(config.validationError())
        assertFalse(config.isRealtime)
        assertNotNull(config.copy(model = "${LiveTranslateModels.ECONOMY_ID}-2025-12-01").validationError())
        assertNotNull(config.copy(endpoint = "wss://example.com/realtime").validationError())
        assertNotNull(config.copy(endpoint = "https://example.com/api-ws/v1/realtime").validationError())
        assertNotNull(config.copy(model = "qwen3-livetranslate-flash-2027-01-01").validationError())
    }

    /** 无效端点和密钥只产生固定说明，不在错误或配置展示中展开凭据。 */
    @Test fun rejectsUnsafeEndpointsAndInvalidValues() {
        for (endpoint in listOf("http://example.com", "ws://example.com", "wss://user:password@example.com", "wss://example.com?token=secret", "wss://example.com#secret", "wss://example.com\\secret")) {
            val config = LiveTranslateConfig(endpoint = endpoint, apiKey = "private-key")
            assertNotNull(config.validationError())
            assertFalse(config.toString().contains(endpoint))
            assertFalse(config.toString().contains("private-key"))
        }
        assertNull(LiveTranslateConfig(apiKey = "key\r\nInjected: value").validationError())
        assertNotNull(LiveTranslateConfig(apiKey = "key", targetLang = "unknown").validationError())
        assertNotNull(LiveTranslateConfig(apiKey = "key", sourceLang = "").validationError())
        assertNotNull(LiveTranslateConfig(apiKey = "key", model = "model?token=secret").validationError())
    }
}
