package io.github.korenkrita.simulsub.online

import org.junit.Assert.*
import org.junit.Test

/** 密钥保存与连接校验分离，不把某一供应商当前格式写死为输入规则。 */
class PlatformKeyInputTest {
    /** 长密钥、标点、空格及Unicode均可传入配置，是否可鉴权交给网络请求。 */
    @Test fun configurationDoesNotImposeKeyFormat() {
        val keys = listOf("x", "sk-ws-H." + "PYDLPID_+-/=".repeat(90), " 带空格的凭据 ", "key\r\nvalue")
        keys.forEach { key ->
            assertNull(LiveTranslateConfig(apiKey = key).validationError())
            assertTrue(CloudAsrConfig(apiKey = key).validationErrors().isEmpty())
        }
    }

    /** 未填写密钥可以保存草稿，但无法启动需要鉴权的在线会话。 */
    @Test fun missingCredentialStillReportsConfigurationNeeded() {
        assertNotNull(LiveTranslateConfig(apiKey = "").validationError())
        assertFalse(CloudAsrConfig(apiKey = "").validationErrors().isEmpty())
    }
}
