package io.github.korenkrita.simulsub.online

import org.junit.Assert.*
import org.junit.Test

/** 模型选项必须对应真实协议，切换时不把业务空间或用户自定义地址意外覆盖。 */
class LiveTranslateModelsTest {
    /** 新用户只看两种工作方式，旧快照仍可回填并继续使用。 */
    @Test fun knownModelsAndSavedSnapshotsRemainSelectable() {
        assertEquals(2, LiveTranslateModels.choices(LiveTranslateModels.REALTIME_ID).size)
        val snapshot = "qwen3.5-livetranslate-flash-realtime-2026-05-19"
        assertEquals(3, LiveTranslateModels.choices(snapshot).size)
        assertEquals(snapshot, LiveTranslateModels.choices(snapshot).last().id)
        assertNull(LiveTranslateModels.find("任意模型"))
        assertEquals(2, LiveTranslateModels.choices("未知旧模型").size)
    }

    /** 两种默认路由可双向切换，已有私有业务空间的域名保留。 */
    @Test fun switchesProtocolAndPreservesWorkspaceHost() {
        val models = LiveTranslateModels
        assertEquals(models.ECONOMY_ENDPOINT, models.endpointAfterSelection(models.REALTIME_ENDPOINT, models.REALTIME_ID, models.ECONOMY_ID))
        assertEquals(models.REALTIME_ENDPOINT, models.endpointAfterSelection(models.ECONOMY_ENDPOINT, models.ECONOMY_ID, models.REALTIME_ID))
        val workspace = "wss://workspace.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime"
        val http = "https://workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions"
        assertEquals(http, models.endpointAfterSelection(workspace, models.REALTIME_ID, models.ECONOMY_ID))
        assertEquals(workspace, models.endpointAfterSelection(http, models.ECONOMY_ID, models.REALTIME_ID))
        val custom = "https://custom.example.com/translate"
        assertEquals(custom, models.endpointAfterSelection(custom, models.REALTIME_ID, models.ECONOMY_ID))
    }
}
