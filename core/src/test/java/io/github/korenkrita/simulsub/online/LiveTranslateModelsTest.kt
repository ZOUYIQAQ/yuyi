package io.github.korenkrita.simulsub.online

import org.junit.Assert.*
import org.junit.Test

/** 模型选项必须对应真实协议，切换时不把业务空间或用户自定义地址意外覆盖。 */
class LiveTranslateModelsTest {
    /** 新用户只看实时听译，受支持的实时快照仍可回填。 */
    @Test fun knownModelsAndSavedSnapshotsRemainSelectable() {
        assertEquals(1, LiveTranslateModels.choices(LiveTranslateModels.REALTIME_ID).size)
        val snapshot = "qwen3.5-livetranslate-flash-realtime-2026-05-19"
        assertEquals(2, LiveTranslateModels.choices(snapshot).size)
        assertEquals(snapshot, LiveTranslateModels.choices(snapshot).last().id)
        assertNull(LiveTranslateModels.find("任意模型"))
        assertEquals(1, LiveTranslateModels.choices("未知旧模型").size)
        for (retired in listOf(LiveTranslateModels.ECONOMY_ID, "${LiveTranslateModels.ECONOMY_ID}-2025-12-01")) {
            assertTrue(LiveTranslateModels.isRetired(retired))
            assertNull(LiveTranslateModels.find(retired))
            assertEquals(listOf(LiveTranslateModels.realtime), LiveTranslateModels.choices(retired))
        }
    }

    /** 只允许从旧分段转向实时，已有私有业务空间域名和端口保留。 */
    @Test fun switchesProtocolAndPreservesWorkspaceHost() {
        val models = LiveTranslateModels
        assertEquals(models.REALTIME_ENDPOINT, models.endpointAfterSelection(models.REALTIME_ENDPOINT, models.REALTIME_ID, models.ECONOMY_ID))
        assertEquals(models.REALTIME_ENDPOINT, models.endpointAfterSelection(models.ECONOMY_ENDPOINT, models.ECONOMY_ID, models.REALTIME_ID))
        val workspace = "wss://workspace.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime"
        val http = "https://workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions"
        assertEquals(workspace, models.endpointAfterSelection(workspace, models.REALTIME_ID, models.ECONOMY_ID))
        assertEquals(workspace, models.endpointAfterSelection(http, models.ECONOMY_ID, models.REALTIME_ID))
        val custom = "https://custom.example.com/translate"
        assertEquals(custom, models.endpointAfterSelection(custom, models.REALTIME_ID, models.ECONOMY_ID))
    }

    /** 自动迁移只接受旧标准安全路由，未知模型、凭据、查询和自定义路由原样留存。 */
    @Test fun migrationOnlyConvertsKnownSecureConnections() {
        val models = LiveTranslateModels
        assertEquals(models.REALTIME_ENDPOINT to models.REALTIME_ID,
            models.migratedConnection(models.ECONOMY_ENDPOINT, models.ECONOMY_ID))
        assertEquals("wss://workspace.example:8443/api-ws/v1/realtime" to models.REALTIME_ID,
            models.migratedConnection("https://workspace.example:8443/compatible-mode/v1/chat/completions",
                "${models.ECONOMY_ID}-2025-12-01"))
        for (endpoint in listOf("http://example.com/compatible-mode/v1/chat/completions",
            "https://user:pass@example.com/compatible-mode/v1/chat/completions",
            "${models.ECONOMY_ENDPOINT}?x=1", "${models.ECONOMY_ENDPOINT}#x",
            "https://example.com/custom", " ${models.ECONOMY_ENDPOINT}")) {
            assertNull(endpoint, models.migratedConnection(endpoint, models.ECONOMY_ID))
        }
        assertNull(models.migratedConnection(models.ECONOMY_ENDPOINT, "${models.ECONOMY_ID}-unknown"))
        assertNull(models.migratedConnection(models.REALTIME_ENDPOINT, models.REALTIME_ID))
    }
}
