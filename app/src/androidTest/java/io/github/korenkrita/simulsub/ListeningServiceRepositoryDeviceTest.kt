package io.github.korenkrita.simulsub

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.online.CloudAsrConfig
import io.github.korenkrita.simulsub.online.LiveTranslateConfig
import io.github.korenkrita.simulsub.online.LiveTranslateModels
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** 每个测试使用独立偏好命名空间，验证服务目录与旧配置隔离。 */
class ListeningServiceRepositoryDeviceTest {
    /** 包装传入上下文，避免访问设备已有服务。 */
    private fun context(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "听写服务-${UUID.randomUUID()}"
        return object : ContextWrapper(base) {
            /** 所有偏好都落到本测试专属文件。 */
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$namespace-$name", mode)
        }
    }

    /** 同厂商不同端点是独立服务；选择和重建仓库不覆盖其连接字段。 */
    @Test fun sameVendorEndpointsRoundTripAndSelection() {
        val context = context()
        val repo = ListeningServiceRepository(context)
        val first = ListeningServiceProfile(ListeningServiceProfile.newId(), "  主听写  ", ListeningMode.CLOUD_ASR,
            CloudAsrConfig.DEFAULT_ENDPOINT, CloudAsrConfig.DEFAULT_MODEL)
        val secondEndpoint = "wss://workspace.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference"
        val second = first.copy(id = ListeningServiceProfile.newId(), name = "备用听写", endpoint = secondEndpoint)
        repo.save(first)
        repo.save(second)
        assertNull(repo.activeId)
        repo.select(second.id)
        val recreated = ListeningServiceRepository(context)
        assertEquals(second.id, recreated.activeId)
        assertEquals(listOf(first.copy(name = "主听写"), second), recreated.profiles)
        recreated.save(first.copy(name = "主听写新版"))
        assertEquals(second.id, recreated.activeId)
        assertEquals(secondEndpoint, recreated.profiles[1].endpoint)
        recreated.select(null)
        assertNull(ListeningServiceRepository(context).activeId)
    }

    /** 新安装仅预制在线听写与实时听译；目录只保存公开连接字段。 */
    @Test fun freshInstallAndStoredFieldsContainNoCredentials() {
        val context = context()
        val prefs = Prefs(context)
        val repo = ListeningServiceRepository(context)
        assertTrue(repo.profiles.isEmpty())
        repo.migrateLegacy(prefs)
        val profiles = ListeningServiceRepository(context).profiles
        assertEquals(2, profiles.size)
        assertEquals(listOf(CloudAsrConfig.DEFAULT_MODEL, LiveTranslateModels.REALTIME_ID), profiles.map { it.model })
        assertNull(repo.activeId)
        val anotherContext = context()
        val anotherRepo = ListeningServiceRepository(anotherContext)
        anotherRepo.ensureBuiltIns()
        assertEquals(profiles.map { it.id }, anotherRepo.profiles.map { it.id })
        val stored = context.getSharedPreferences("listening_services", 0).all
        assertEquals(setOf("legacyMigratedV1", "builtInsSeededV1", "profiles"), stored.keys)
        val serialized = stored["profiles"] as String
        val array = org.json.JSONArray(serialized)
        assertEquals(2, array.length())
        for (index in 0 until array.length()) {
            val fields = array.getJSONObject(index)
            assertEquals(5, fields.length())
            assertEquals(setOf("id", "name", "mode", "endpoint", "model"), fields.keys().asSequence().toSet())
            assertEquals(profiles[index].endpoint, fields.getString("endpoint"))
            assertEquals(profiles[index].model, fields.getString("model"))
        }
        assertFalse(serialized.contains("apiKey"))
        assertFalse(serialized.contains("platformKey"))
    }

    /** 仅首次复制已经保存且就绪的两路旧配置，不改变旧字段与密文。 */
    @Test fun migrationIsIdempotentAndSelectsCurrentMode() {
        val context = context()
        val prefs = Prefs(context)
        prefs.saveCloudAsr(CloudAsrConfig.DEFAULT_ENDPOINT, CloudAsrConfig.DEFAULT_MODEL, "设备测试密钥")
        prefs.saveOnline(LiveTranslateConfig.DEFAULT_ENDPOINT, LiveTranslateConfig.DEFAULT_MODEL, "设备测试密钥")
        prefs.listeningMode = ListeningMode.ONLINE
        val old = context.getSharedPreferences("jpsub", 0).all.toMap()
        val repo = ListeningServiceRepository(context)
        repo.migrateLegacy(prefs)
        assertEquals(2, repo.profiles.size)
        assertEquals(ListeningMode.ONLINE, repo.profiles.single { it.id == repo.activeId }.mode)
        assertEquals(old, context.getSharedPreferences("jpsub", 0).all)
        val firstIds = repo.profiles.map { it.id }
        repo.migrateLegacy(Prefs(context))
        assertEquals(firstIds, ListeningServiceRepository(context).profiles.map { it.id })
        assertFalse(context.getSharedPreferences("listening_services", 0).all.toString().contains("设备测试密钥"))
    }

    /** 旧用户只保存中央平台密钥时，默认端点与模型仍应迁移为两套服务。 */
    @Test fun keyOnlyLegacyDefaultsAreMigrated() {
        val context = context()
        val prefs = Prefs(context)
        prefs.savePlatformKey(PlatformCredentials.QWEN.id, "仅密钥旧用户")
        val old = context.getSharedPreferences("jpsub", 0).all.toMap()
        assertFalse(old.containsKey("cloudAsrEndpoint"))
        assertFalse(old.containsKey("onlineEndpoint"))
        assertTrue(prefs.cloudAsrReady)
        assertTrue(prefs.onlineReady)
        val repo = ListeningServiceRepository(context)
        repo.migrateLegacy(prefs)
        assertEquals(2, repo.profiles.size)
        assertEquals(CloudAsrConfig.DEFAULT_ENDPOINT, repo.profiles.single { it.mode == ListeningMode.CLOUD_ASR }.endpoint)
        assertEquals(LiveTranslateConfig.DEFAULT_ENDPOINT, repo.profiles.single { it.model == LiveTranslateConfig.DEFAULT_MODEL }.endpoint)
        assertEquals(old, context.getSharedPreferences("jpsub", 0).all)
    }

    /** 旧目录的同配置条目保留原标识、名称和活动选择，只补缺失预制。 */
    @Test fun existingConnectionIsNotDuplicatedByMigrationOrSeeding() {
        val context = context()
        val prefs = Prefs(context)
        prefs.saveCloudAsr(CloudAsrConfig.DEFAULT_ENDPOINT, CloudAsrConfig.DEFAULT_MODEL, "设备测试密钥")
        prefs.listeningMode = ListeningMode.CLOUD_ASR
        val repo = ListeningServiceRepository(context)
        val existing = ListeningServiceProfile(ListeningServiceProfile.newId(), "原有名称", ListeningMode.CLOUD_ASR,
            CloudAsrConfig.DEFAULT_ENDPOINT, CloudAsrConfig.DEFAULT_MODEL)
        repo.save(existing)
        repo.select(existing.id)
        repo.migrateLegacy(prefs)
        assertEquals(existing.id, repo.activeId)
        assertEquals(existing, repo.profiles.single { it.mode == ListeningMode.CLOUD_ASR })
        assertEquals(2, repo.profiles.size)
        val firstIds = repo.profiles.map { it.id }
        repo.migrateLegacy(Prefs(context))
        repo.ensureBuiltIns()
        assertEquals(firstIds, ListeningServiceRepository(context).profiles.map { it.id })
    }

    /** 旧实时听译与预制项同配置时保留旧名称及活动标识，重建也不重复播种。 */
    @Test fun existingOnlineConnectionKeepsIdentityAfterSeedingAndRestart() {
        val context = context()
        val repo = ListeningServiceRepository(context)
        val existing = ListeningServiceProfile(ListeningServiceProfile.newId(), "旧实时服务", ListeningMode.ONLINE,
            LiveTranslateModels.REALTIME_ENDPOINT, LiveTranslateModels.REALTIME_ID)
        repo.save(existing)
        repo.select(existing.id)
        repo.ensureBuiltIns()
        assertEquals(existing.id, repo.activeId)
        assertEquals(existing, repo.profiles.single { it.model == LiveTranslateModels.REALTIME_ID })
        assertEquals(2, repo.profiles.size)
        val first = repo.profiles
        val restarted = ListeningServiceRepository(context)
        restarted.ensureBuiltIns()
        assertEquals(first, restarted.profiles)
        assertEquals(existing.id, restarted.activeId)
    }

    /** 一次性播种不会把用户随后编辑过的预制连接恢复成额外副本。 */
    @Test fun seededConnectionIsNotReinsertedAfterEdit() {
        val repo = ListeningServiceRepository(context())
        repo.ensureBuiltIns()
        val builtIn = repo.profiles.single { it.mode == ListeningMode.CLOUD_ASR }
        repo.save(builtIn.copy(endpoint = "wss://workspace.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference"))
        repo.ensureBuiltIns()
        assertEquals(2, repo.profiles.size)
        assertEquals(1, repo.profiles.count { it.mode == ListeningMode.CLOUD_ASR })
    }

    /** 文字翻译逐套读取已保存字段，空默认项可见但未配置，查询不切换当前项。 */
    @Test fun translationSummaryUsesEachSavedConfigurationWithoutSwitching() {
        val context = context()
        val prefs = Prefs(context)
        val repo = ListeningServiceRepository(context)
        assertEquals(listOf(TranslationServiceSummary("", "默认", "", "", false)), repo.translationServices(prefs))
        prefs.saveByok("https://api.deepseek.com/v1", "deepseek-chat", null)
        val second = prefs.addByokProfile("备用")
        prefs.saveByok("http://127.0.0.1:8000/v1", "本地模型", null)
        val before = context.getSharedPreferences("jpsub", 0).all.toMap()
        val summaries = repo.translationServices(prefs)
        assertEquals(listOf("", second.id), summaries.map { it.id })
        assertEquals(listOf(true, true), summaries.map { it.configured })
        assertEquals("https://api.deepseek.com/v1", summaries[0].url)
        assertEquals("http://127.0.0.1:8000/v1", summaries[1].url)
        assertEquals(before, context.getSharedPreferences("jpsub", 0).all)
        assertEquals(second.id, prefs.byokProfile.id)
    }
}
