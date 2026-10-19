package io.github.korenkrita.simulsub

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.online.CloudAsrConfig
import io.github.korenkrita.simulsub.online.LiveTranslateModels
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** 仅验证隔离偏好的连接迁移和真实密钥绑定，不启动音频、控制器或云端请求。 */
class RetiredListeningConfigurationDeviceTest {
    /** 为本用例创建独立偏好命名空间，避免读写用户已有数据。 */
    private fun context(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "停用分段听译-${UUID.randomUUID()}"
        return object : ContextWrapper(base) {
            /** 所有偏好文件均使用同一隔离前缀。 */
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$namespace-$name", mode)
        }
    }

    /** 复现旧版本直接持久化连接的结果，不借用已禁止旧模型的新保存入口。 */
    private fun seedCurrent(context: Context, endpoint: String = LiveTranslateModels.ECONOMY_ENDPOINT,
        model: String = LiveTranslateModels.ECONOMY_ID) {
        assertTrue(context.getSharedPreferences("jpsub", 0).edit().putString("onlineEndpoint", endpoint)
            .putString("onlineModel", model).putString("listeningMode", ListeningMode.ONLINE.name).commit())
    }

    /** 写入旧服务原记录和活动标识，模拟已经完成旧版预制播种的安装。 */
    private fun seedProfiles(context: Context, vararg profiles: ListeningServiceProfile) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(JSONObject().put("id", profile.id).put("name", profile.name).put("mode", profile.mode.name)
                .put("endpoint", profile.endpoint).put("model", profile.model).put("旧扩展字段", "保留原文"))
        }
        assertTrue(context.getSharedPreferences("listening_services", 0).edit().putString("profiles", array.toString())
            .putString("activeId", profiles.first().id).putBoolean("builtInsSeededV1", true)
            .putBoolean("legacyMigratedV1", true).commit())
    }

    /** 统一产生旧协议槽位，显式绑定和跟随默认不能被迁移混为一谈。 */
    private fun oldSlot(endpoint: String = LiveTranslateModels.ECONOMY_ENDPOINT,
        model: String = LiveTranslateModels.ECONOMY_ID): String = listeningCredentialSlot(ListeningMode.ONLINE, endpoint, model)

    /** 实时槽位与生产配置使用相同端点和模型。 */
    private fun realtimeSlot(): String = listeningCredentialSlot(ListeningMode.ONLINE,
        LiveTranslateModels.REALTIME_ENDPOINT, LiveTranslateModels.REALTIME_ID)

    /** 旧活动连接等待明确重选，备用绑定和原密文保留，重新初始化不再产生写入。 */
    @Test fun activeConnectionWaitsForExplicitSelectionAndKeepsOriginalCiphertext() {
        val context = context()
        val old = Prefs(context)
        val alternate = UUID.randomUUID().toString()
        old.savePlatformKey("qwen", "中央原文")
        old.savePlatformCredential("qwen", alternate, "备用", "  备用原文\n ")
        old.selectCredential("qwen", oldSlot(), alternate)
        seedCurrent(context)
        val stored = context.getSharedPreferences("jpsub", 0)
        val before = stored.all
        val migrated = Prefs(context)
        assertEquals(ListeningMode.ONLINE, migrated.listeningMode)
        assertEquals(LiveTranslateModels.ECONOMY_ENDPOINT, migrated.onlineEndpoint)
        assertEquals(LiveTranslateModels.ECONOMY_ID, migrated.onlineModel)
        assertEquals("  备用原文\n ", migrated.onlineConfig().apiKey)
        assertFalse(migrated.onlineReady)
        assertEquals(alternate, migrated.selectedCredentialId("qwen", oldSlot()))
        assertEquals(alternate, migrated.selectedCredentialId("qwen", realtimeSlot()))
        val untouched = before
        assertEquals(untouched, stored.all.filterKeys { it in untouched.keys })
        val backup = JSONObject(stored.getString("retiredOnlineConnectionV1", null)!!)
        assertEquals(LiveTranslateModels.ECONOMY_ID, backup.getString("model"))
        val once = stored.all
        Prefs(context)
        assertEquals(once, stored.all)
        migrated.saveOnline(LiveTranslateModels.REALTIME_ENDPOINT, LiveTranslateModels.REALTIME_ID,
            migrated.keyForService(LiveTranslateModels.REALTIME_ENDPOINT, realtimeSlot()))
        assertEquals(LiveTranslateModels.REALTIME_ID, migrated.onlineModel)
        assertTrue(migrated.onlineReady)
        assertEquals(alternate, migrated.selectedCredentialId("qwen", realtimeSlot()))
        val explicitlySelected = stored.all
        assertThrows(IllegalArgumentException::class.java) {
            migrated.saveOnline(LiveTranslateModels.ECONOMY_ENDPOINT, LiveTranslateModels.ECONOMY_ID, "不能保存")
        }
        assertEquals(explicitlySelected, stored.all)
    }

    /** 目录迁移保留原标识、活动项、业务空间与扩展字段，原记录有备份且重复读取稳定。 */
    @Test fun directoryMigrationKeepsIdentityOriginAndBackup() {
        val context = context()
        val prefs = Prefs(context)
        prefs.savePlatformKey("qwen", "目录密钥")
        val endpoint = "https://workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions"
        val old = ListeningServiceProfile(ListeningServiceProfile.newId(), "用户的服务", ListeningMode.ONLINE,
            endpoint, "${LiveTranslateModels.ECONOMY_ID}-2025-12-01")
        prefs.selectCredential("qwen", oldSlot(endpoint, old.model), "default")
        seedProfiles(context, old)
        val repo = ListeningServiceRepository(context)
        val expected = requireNotNull(old.migratedRealtime())
        assertEquals(listOf(expected), repo.profiles)
        assertNull(repo.activeId)
        assertEquals("default", prefs.selectedCredentialId("qwen", listeningCredentialSlot(
            ListeningMode.ONLINE, expected.endpoint, expected.model)))
        assertEquals("目录密钥", prefs.keyForService(expected.endpoint,
            listeningCredentialSlot(ListeningMode.ONLINE, expected.endpoint, expected.model)))
        val stored = context.getSharedPreferences("listening_services", 0)
        val current = JSONArray(stored.getString("profiles", null)).getJSONObject(0)
        assertEquals("保留原文", current.getString("旧扩展字段"))
        val backup = JSONObject(stored.getString("retiredOnlineProfileV1:${old.id}", null)!!)
        assertEquals(endpoint, backup.getString("endpoint"))
        assertEquals(old.model, backup.getString("model"))
        val once = stored.all
        assertEquals(repo.profiles, ListeningServiceRepository(context).profiles)
        assertEquals(once, stored.all)
        repo.select(old.id)
        assertEquals(old.id, repo.activeId)
    }

    /** 目标已有不同绑定时保持双方原文，旧项不再可选，保存其他服务也不能丢掉旧记录。 */
    @Test fun bindingConflictNeverOverwritesOrDeletesRetiredData() {
        val context = context()
        val prefs = Prefs(context)
        val alternate = UUID.randomUUID().toString()
        prefs.savePlatformKey("qwen", "中央")
        prefs.savePlatformCredential("qwen", alternate, "备用", "备用")
        prefs.selectCredential("qwen", oldSlot(), alternate)
        prefs.selectCredential("qwen", realtimeSlot(), "default")
        seedCurrent(context)
        val old = ListeningServiceProfile(ListeningServiceProfile.newId(), "不能覆盖的旧项", ListeningMode.ONLINE,
            LiveTranslateModels.ECONOMY_ENDPOINT, LiveTranslateModels.ECONOMY_ID)
        seedProfiles(context, old)
        val before = context.getSharedPreferences("jpsub", 0).all
        val migrated = Prefs(context)
        val repo = ListeningServiceRepository(context)
        assertEquals(before, context.getSharedPreferences("jpsub", 0).all)
        assertFalse(migrated.onlineReady)
        assertTrue(repo.profiles.isEmpty())
        assertNull(repo.activeId)
        assertThrows(IllegalArgumentException::class.java) { repo.select(old.id) }
        assertThrows(IllegalArgumentException::class.java) { repo.save(old) }
        val asr = ListeningServiceProfile(ListeningServiceProfile.newId(), "新听写", ListeningMode.CLOUD_ASR,
            CloudAsrConfig.DEFAULT_ENDPOINT, CloudAsrConfig.DEFAULT_MODEL)
        repo.save(asr)
        repo.ensureBuiltIns()
        val array = JSONArray(context.getSharedPreferences("listening_services", 0).getString("profiles", null))
        assertTrue((0 until array.length()).any { array.getJSONObject(it).optString("id") == old.id &&
            array.getJSONObject(it).optString("model") == old.model })
        assertEquals(alternate, migrated.selectedCredentialId("qwen", oldSlot()))
        assertEquals("default", migrated.selectedCredentialId("qwen", realtimeSlot()))
    }

    /** 已有实时服务的隐式默认绑定同样受保护，不能被旧服务的备用密钥替换。 */
    @Test fun existingRealtimeFollowerIsNotRepinnedByMigration() {
        val context = context()
        val prefs = Prefs(context)
        val alternate = UUID.randomUUID().toString()
        prefs.savePlatformKey("qwen", "默认")
        prefs.savePlatformCredential("qwen", alternate, "备用", "备用")
        prefs.selectCredential("qwen", oldSlot(), alternate)
        val realtime = ListeningServiceProfile(ListeningServiceProfile.newId(), "已有实时", ListeningMode.ONLINE,
            LiveTranslateModels.REALTIME_ENDPOINT, LiveTranslateModels.REALTIME_ID)
        val old = realtime.copy(id = ListeningServiceProfile.newId(), name = "旧分段",
            endpoint = LiveTranslateModels.ECONOMY_ENDPOINT, model = LiveTranslateModels.ECONOMY_ID)
        seedProfiles(context, old, realtime)
        seedCurrent(context)
        val before = context.getSharedPreferences("jpsub", 0).all
        assertEquals(listOf(realtime), ListeningServiceRepository(context).profiles)
        assertEquals(before, context.getSharedPreferences("jpsub", 0).all)
        assertNull(prefs.selectedCredentialId("qwen", realtimeSlot()))
        assertEquals("默认", prefs.keyForService(realtime.endpoint, realtimeSlot()))
    }

    /** 跟随默认的空密钥仍为空，不因协议升级借用其他命名项。 */
    @Test fun emptyDefaultAndFollowingSemanticsSurviveMigration() {
        val context = context()
        val prefs = Prefs(context)
        prefs.savePlatformKey("qwen", "")
        prefs.savePlatformCredential("qwen", UUID.randomUUID().toString(), "不能借用", "其他密钥")
        seedCurrent(context)
        val migrated = Prefs(context)
        assertEquals(LiveTranslateModels.ECONOMY_ID, migrated.onlineModel)
        assertEquals("", migrated.onlineKey)
        assertFalse(migrated.onlineReady)
        assertNull(migrated.selectedCredentialId("qwen", realtimeSlot()))
        migrated.savePlatformKey("qwen", "后来补齐")
        assertEquals("后来补齐", migrated.onlineKey)
    }

    /** 自定义旧路由不能猜测成另一来源；原值保留，旧模型被校验和目录共同拒绝。 */
    @Test fun customRetiredRouteIsPreservedButCannotBeSelected() {
        val context = context()
        Prefs(context)
        val old = ListeningServiceProfile(ListeningServiceProfile.newId(), "私有旧路由", ListeningMode.ONLINE,
            "https://private.example/custom", LiveTranslateModels.ECONOMY_ID)
        seedProfiles(context, old)
        seedCurrent(context, old.endpoint, old.model)
        val before = context.getSharedPreferences("jpsub", 0).all
        assertFalse(Prefs(context).onlineReady)
        assertTrue(ListeningServiceRepository(context).profiles.isEmpty())
        assertEquals(before, context.getSharedPreferences("jpsub", 0).all)
        assertEquals(old.model, JSONArray(context.getSharedPreferences("listening_services", 0)
            .getString("profiles", null)).getJSONObject(0).getString("model"))
    }

    /** 损坏绑定不能静默转成默认密钥，迁移失败保留全部原始字段。 */
    @Test fun corruptBindingNeverFallsBackDuringMigration() {
        val context = context()
        val prefs = Prefs(context)
        prefs.savePlatformKey("qwen", "不能借用")
        prefs.selectCredential("qwen", oldSlot(), "default")
        val stored = context.getSharedPreferences("jpsub", 0)
        val bindingKey = stored.all.keys.single { it.startsWith("platformCredentialBinding:") }
        assertTrue(stored.edit().putInt(bindingKey, 7).commit())
        seedCurrent(context)
        val before = stored.all
        val migrated = Prefs(context)
        assertEquals(before, stored.all)
        assertEquals("", migrated.onlineKey)
        assertFalse(migrated.onlineReady)
        assertNull(migrated.selectedCredentialId("qwen", realtimeSlot()))
    }

    /** 已支持的实时快照与在线听写不改模式、不改密文、不改目录或活动标识。 */
    @Test fun supportedRealtimeSnapshotAndAsrRemainUnchanged() {
        val context = context()
        val prefs = Prefs(context)
        val snapshotModel = "${LiveTranslateModels.REALTIME_ID}-2026-05-19"
        prefs.saveOnline(LiveTranslateModels.REALTIME_ENDPOINT, snapshotModel, "实时快照密钥")
        prefs.saveCloudAsr(CloudAsrConfig.DEFAULT_ENDPOINT, CloudAsrConfig.DEFAULT_MODEL, "实时快照密钥")
        prefs.listeningMode = ListeningMode.CLOUD_ASR
        val repo = ListeningServiceRepository(context)
        val realtime = ListeningServiceProfile(ListeningServiceProfile.newId(), "现有实时快照", ListeningMode.ONLINE,
            LiveTranslateModels.REALTIME_ENDPOINT, snapshotModel)
        repo.save(realtime)
        repo.select(realtime.id)
        val beforePrefs = context.getSharedPreferences("jpsub", 0).all
        val beforeServices = context.getSharedPreferences("listening_services", 0).all
        val reopened = Prefs(context)
        val restored = ListeningServiceRepository(context)
        assertEquals(ListeningMode.CLOUD_ASR, reopened.listeningMode)
        assertEquals(snapshotModel, reopened.onlineModel)
        assertTrue(reopened.onlineReady)
        assertTrue(reopened.cloudAsrReady)
        assertEquals(listOf(realtime), restored.profiles)
        assertEquals(realtime.id, restored.activeId)
        assertEquals(beforePrefs, context.getSharedPreferences("jpsub", 0).all)
        assertEquals(beforeServices, context.getSharedPreferences("listening_services", 0).all)
    }

    /** 真实旧经济预制项升级后只展示原生实时项，实时活动选择、原始备份和凭据全部保留。 */
    @Test fun migratedEconomyDuplicateKeepsExistingRealtimeActiveAndCompleteBackup() {
        val context = context()
        val prefs = Prefs(context)
        prefs.saveOnline(LiveTranslateModels.REALTIME_ENDPOINT, LiveTranslateModels.REALTIME_ID, "原实时密钥")
        prefs.listeningMode = ListeningMode.ONLINE
        prefs.selectCredential("qwen", oldSlot(), "default")
        prefs.selectCredential("qwen", realtimeSlot(), "default")
        val realtime = ListeningServiceProfile("a73b1888-6539-47b5-82f8-3b10347f3e9a", "千问实时听译",
            ListeningMode.ONLINE, LiveTranslateModels.REALTIME_ENDPOINT, LiveTranslateModels.REALTIME_ID)
        val economy = ListeningServiceProfile("f51c5a87-d9d8-4723-a517-55200dbe37e7", "千问分段听译",
            ListeningMode.ONLINE, LiveTranslateModels.ECONOMY_ENDPOINT, LiveTranslateModels.ECONOMY_ID)
        seedProfiles(context, realtime, economy)
        val stored = context.getSharedPreferences("listening_services", 0)
        val originalEconomy = JSONArray(stored.getString("profiles", null)).getJSONObject(1).toString()
        val originalPrefs = context.getSharedPreferences("jpsub", 0).all
        val repo = ListeningServiceRepository(context)
        assertEquals(listOf(realtime), repo.profiles)
        assertEquals(realtime.id, repo.activeId)
        assertEquals(realtime.id, stored.getString("activeId", null))
        assertEquals(originalEconomy, stored.getString("retiredOnlineProfileV1:${economy.id}", null))
        assertEquals(2, JSONArray(stored.getString("profiles", null)).length())
        assertEquals(originalPrefs, context.getSharedPreferences("jpsub", 0).all)
        assertEquals("default", prefs.selectedCredentialId("qwen", oldSlot()))
        assertEquals("default", prefs.selectedCredentialId("qwen", realtimeSlot()))
        assertEquals("原实时密钥", prefs.onlineKey)
        val migrated = stored.all
        assertEquals(listOf(realtime), ListeningServiceRepository(context).profiles)
        assertEquals(migrated, stored.all)
        // 原经济项若曾被选中，即使其副本被隐藏也不能悄悄转选现有实时项。
        assertTrue(stored.edit().putString("activeId", economy.id)
            .putString("retiredActiveIdV1", economy.id).commit())
        assertNull(ListeningServiceRepository(context).activeId)
        assertEquals(economy.id, stored.getString("activeId", null))
    }

    /** 原生重名同连接、不同端点和实时快照均保留，只有带迁移标记的精确副本隐藏。 */
    @Test fun duplicateFilteringDoesNotMergeNativeServicesEndpointsOrSnapshots() {
        val context = context()
        val original = ListeningServiceProfile(ListeningServiceProfile.newId(), "同名服务", ListeningMode.ONLINE,
            LiveTranslateModels.REALTIME_ENDPOINT, LiveTranslateModels.REALTIME_ID)
        val nativeDuplicate = original.copy(id = ListeningServiceProfile.newId())
        val migratedDuplicate = original.copy(id = ListeningServiceProfile.newId())
        val workspace = original.copy(id = ListeningServiceProfile.newId(),
            endpoint = "wss://workspace.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime")
        val realtimeSnapshot = original.copy(id = ListeningServiceProfile.newId(),
            model = "${LiveTranslateModels.REALTIME_ID}-2026-05-19")
        seedProfiles(context, original, nativeDuplicate, migratedDuplicate, workspace, realtimeSnapshot)
        val stored = context.getSharedPreferences("listening_services", 0)
        val editor = stored.edit()
        listOf(migratedDuplicate, workspace, realtimeSnapshot).forEach { profile ->
            editor.putString("retiredOnlineProfileV1:${profile.id}", JSONObject().put("id", profile.id)
                .put("model", LiveTranslateModels.ECONOMY_ID).put("endpoint", LiveTranslateModels.ECONOMY_ENDPOINT).toString())
        }
        assertTrue(editor.commit())
        val before = stored.all
        val repo = ListeningServiceRepository(context)
        assertEquals(listOf(original, nativeDuplicate, workspace, realtimeSnapshot), repo.profiles)
        assertEquals(original.id, repo.activeId)
        assertEquals(before, stored.all)
    }
}
