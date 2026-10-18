package io.github.korenkrita.simulsub

import io.github.korenkrita.simulsub.online.CloudAsrConfig
import io.github.korenkrita.simulsub.online.LiveTranslateConfig
import io.github.korenkrita.simulsub.online.LiveTranslateModels
import org.junit.Assert.*
import org.junit.Test

/** 听写服务在进入持久化前必须经过现有协议校验。 */
class ListeningServiceProfileTest {
    /** 名称去空白并保留原始端点，标识由 UUID 生成。 */
    @Test fun validProfilesKeepOriginalEndpoint() {
        val endpoint = "wss://workspace.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference"
        val asr = ListeningServiceProfile(ListeningServiceProfile.newId(), "  工作听写  ", ListeningMode.CLOUD_ASR,
            endpoint, CloudAsrConfig.DEFAULT_MODEL).validated()
        assertEquals("工作听写", asr.name)
        assertEquals(endpoint, asr.endpoint)
        assertNotEquals(asr.id, ListeningServiceProfile.newId())
        assertEquals(LiveTranslateModels.ECONOMY_ENDPOINT,
            ListeningServiceProfile(ListeningServiceProfile.newId(), "省钱", ListeningMode.ONLINE,
                LiveTranslateModels.ECONOMY_ENDPOINT, LiveTranslateModels.ECONOMY_ID).validated().endpoint)
    }

    /** 不允许空名、超长名、本地模式或伪造标识进入目录。 */
    @Test fun rejectsInvalidIdentity() {
        val good = ListeningServiceProfile(ListeningServiceProfile.newId(), "听写", ListeningMode.CLOUD_ASR,
            CloudAsrConfig.DEFAULT_ENDPOINT, CloudAsrConfig.DEFAULT_MODEL)
        for (bad in listOf(good.copy(name = " \n "), good.copy(name = "字".repeat(41)),
            good.copy(mode = ListeningMode.LOCAL), good.copy(id = "cloud-asr"))) {
            try { bad.validated(); fail("应拒绝无效服务") } catch (_: IllegalArgumentException) { }
        }
    }

    /** 模型与路由必须匹配当前实现的两种在线协议。 */
    @Test fun rejectsUnsupportedModelAndRoute() {
        val base = ListeningServiceProfile(ListeningServiceProfile.newId(), "在线", ListeningMode.ONLINE,
            LiveTranslateConfig.DEFAULT_ENDPOINT, LiveTranslateConfig.DEFAULT_MODEL)
        for (bad in listOf(base.copy(model = "unknown"), base.copy(endpoint = "http://example.com"),
            base.copy(model = LiveTranslateModels.ECONOMY_ID),
            base.copy(mode = ListeningMode.CLOUD_ASR, model = CloudAsrConfig.DEFAULT_MODEL))) {
            try { bad.validated(); fail("应拒绝不匹配的模型或地址") } catch (_: IllegalArgumentException) { }
        }
    }
}
