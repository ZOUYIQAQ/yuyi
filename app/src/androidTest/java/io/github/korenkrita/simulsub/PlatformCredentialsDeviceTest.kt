package io.github.korenkrita.simulsub

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** 用独立偏好检查平台密钥迁移、共享和来源隔离。 */
class PlatformCredentialsDeviceTest {
    /** 为每个测试建立独立偏好，避免接触用户凭据。 */
    private fun context(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "平台凭据-${UUID.randomUUID()}"
        return object : ContextWrapper(base) {
            /** 只替换偏好文件名，加密仍使用应用的真实 Keystore。 */
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$namespace-$name", mode)
        }
    }

    /** 千问三入口和 DeepSeek 官方入口分别共享，未知主机只使用自己的来源。 */
    @Test fun officialHostsAndOriginsHaveStableIdentity() {
        assertEquals(PlatformCredentials.QWEN, PlatformCredentials.forUrl("wss://dashscope.aliyuncs.com/api-ws/v1/realtime"))
        assertEquals(PlatformCredentials.QWEN, PlatformCredentials.forUrl("wss://dashscope.aliyuncs.com/api-ws/v1/inference"))
        assertEquals(PlatformCredentials.QWEN, PlatformCredentials.forUrl("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"))
        assertEquals(PlatformCredentials.DEEPSEEK, PlatformCredentials.forUrl("https://api.deepseek.com/v1"))
        assertEquals(PlatformCredentials.DEEPSEEK, PlatformCredentials.forUrl("https://api.deepseek.com/chat/completions"))
        val custom = PlatformCredentials.forUrl("HTTPS://Other.Example:443/v1")!!
        assertEquals("origin:https://other.example", custom.id)
        assertEquals(custom, PlatformCredentials.forUrl("https://other.example/another/path"))
        assertNotEquals(PlatformCredentials.QWEN, PlatformCredentials.forUrl("https://dashscope.aliyuncs.com.evil.example/v1"))
        assertNotEquals(PlatformCredentials.DEEPSEEK, PlatformCredentials.forUrl("https://api.deepseek.com.evil.example/v1"))
        assertEquals(PlatformCredentials.QWEN, PlatformCredentials.forUrl("https://dashscope.aliyuncs.com/v1"))
        assertNotEquals(PlatformCredentials.QWEN, PlatformCredentials.forUrl("http://dashscope.aliyuncs.com/v1"))
        assertNull(PlatformCredentials.forUrl("https://user:pass@dashscope.aliyuncs.com/v1"))
        assertEquals("origin:http://[::1]:8000", PlatformCredentials.forUrl("http://[::1]:8000/v1")?.id)
    }

    /** 只识别官方共享域名与已列地域的单层业务空间，其他阿里云域名保持隔离。 */
    @Test fun qwenWorkspaceHostsAreBounded() {
        for (host in listOf("llm-123.cn-beijing.maas.aliyuncs.com", "llm-456.cn-shanghai.maas.aliyuncs.com",
            "dashscope-intl.aliyuncs.com", "dashscope-us.aliyuncs.com",
            "cn-hongkong.dashscope.aliyuncs.com", "llm-789.ap-southeast-1.maas.aliyuncs.com")) {
            assertEquals(host, PlatformCredentials.QWEN, PlatformCredentials.forUrl("wss://$host/api-ws/v1/inference"))
        }
        for (host in listOf("maas.aliyuncs.com", "other.aliyuncs.com",
            "evil.llm-123.cn-beijing.maas.aliyuncs.com", "llm-123.cn-beijing.maas.aliyuncs.com.evil.example",
            "llm-123.unknown-region.maas.aliyuncs.com")) {
            assertNotEquals(host, PlatformCredentials.QWEN, PlatformCredentials.forUrl("https://$host/v1"))
        }
        assertNotEquals(PlatformCredentials.QWEN,
            PlatformCredentials.forUrl("https://llm-123.cn-beijing.maas.aliyuncs.com:8443/v1"))
    }

    /** 升级前普通来源槽位中的官方业务空间密钥迁入千问，未知工作区不借用。 */
    @Test fun oldWorkspaceOriginKeyMovesOnlyWhenQwenSlotIsAbsent() {
        val context = context()
        val prefs = Prefs(context)
        val host = "llm-123.cn-beijing.maas.aliyuncs.com"
        val oldId = "origin:https://$host"
        prefs.savePlatformKey("origin:https://fixture.example", "旧业务空间密钥")
        val stored = context.getSharedPreferences("jpsub", 0)
        val oldCipher = stored.getString("platformKey:origin:https://fixture.example", null)
        stored.edit().remove("platformQwenHostsMigratedV1")
            .putString("platformKey:$oldId", oldCipher)
            .putString("byokUrl", "https://$host/compatible-mode/v1").commit()
        val migrated = Prefs(context)
        assertEquals("旧业务空间密钥", migrated.onlineKey)
        assertEquals(oldCipher, stored.getString("platformKey:$oldId", null))
        migrated.saveByok("https://unrelated.aliyuncs.com/v1", "其他模型", null)
        assertEquals("", migrated.byokService()!!.key)
        migrated.savePlatformKey("qwen", "")
        stored.edit().remove("platformQwenHostsMigratedV1").commit()
        assertEquals("", Prefs(context).onlineKey)
    }

    /** 旧版业务空间来源槽位即使已被归入千问，也能留在列表中独立恢复与编辑。 */
    @Test fun savedLegacyWorkspaceOriginRemainsEditable() {
        val context = context()
        val prefs = Prefs(context)
        val id = "origin:https://llm-123.cn-beijing.maas.aliyuncs.com"
        val stored = context.getSharedPreferences("jpsub", 0)
        prefs.savePlatformKey("origin:https://fixture.example", "旧来源密钥")
        val cipher = stored.getString("platformKey:origin:https://fixture.example", null)
        stored.edit().putString("platformKey:$id", cipher).commit()
        val reopened = Prefs(context)
        assertTrue(reopened.credentialPlatforms.any { it.id == id })
        reopened.savePlatformKey(id, "更新后的旧来源密钥")
        assertEquals("更新后的旧来源密钥", reopened.platformKey(id))
    }

    /** 原文中的空格、非 ASCII 字符和换行可逆保存，空串不回退旧密钥。 */
    @Test fun rawKeyAndExplicitClearArePreserved() {
        val context = context()
        val prefs = Prefs(context)
        val raw = "  测试密钥\n前后空格  "
        prefs.saveOnline(prefs.onlineEndpoint, prefs.onlineModel, raw)
        assertEquals(raw, prefs.onlineKey)
        assertEquals(raw, prefs.cloudAsrKey)
        assertEquals(raw, prefs.onlineConfig().apiKey)
        assertEquals(raw, prefs.cloudAsrConfig().apiKey)
        val stored = context.getSharedPreferences("jpsub", 0)
        assertNotEquals(raw, stored.getString("platformKey:qwen", null))
        prefs.saveCloudAsr(prefs.cloudAsrEndpoint, prefs.cloudAsrModel, "")
        assertEquals("", Prefs(context).onlineKey)
        assertTrue(stored.contains("platformKey:qwen"))
    }

    /** 两路音频切到自定义主机时只读取该来源槽位，保存也不会改写千问密钥。 */
    @Test fun customAudioEndpointsNeverReadOfficialQwenKey() {
        val context = context()
        val prefs = Prefs(context)
        prefs.savePlatformKey(PlatformCredentials.QWEN.id, "官方千问密钥")
        val stored = context.getSharedPreferences("jpsub", 0)
        val live = "wss://live.example/realtime"
        val asr = "wss://asr.example/api-ws/v1/inference"
        stored.edit().putString("onlineEndpoint", live).putString("cloudAsrEndpoint", asr).commit()
        assertEquals("", prefs.onlineKey)
        assertEquals("", prefs.onlineConfig().apiKey)
        assertEquals("", prefs.cloudAsrKey)
        assertEquals("", prefs.cloudAsrConfig().apiKey)
        assertTrue(prefs.credentialPlatforms.any { it.id == "origin:https://live.example" })
        assertTrue(prefs.credentialPlatforms.any { it.id == "origin:https://asr.example" })
        prefs.saveOnline(live, prefs.onlineModel, "自定义听译密钥")
        prefs.saveCloudAsr(asr, prefs.cloudAsrModel, "自定义听写密钥")
        assertEquals("自定义听译密钥", prefs.onlineConfig().apiKey)
        assertEquals("自定义听写密钥", prefs.cloudAsrConfig().apiKey)
        assertEquals("官方千问密钥", prefs.platformKey(PlatformCredentials.QWEN.id))
        stored.edit().putString("onlineEndpoint", "wss://unknown.example/realtime")
            .putString("cloudAsrEndpoint", "wss://unknown.example/inference").commit()
        assertEquals("", prefs.onlineConfig().apiKey)
        assertEquals("", prefs.cloudAsrConfig().apiKey)
    }

    /** 官方业务空间音频端点与默认千问端点读取同一中央槽位。 */
    @Test fun officialWorkspaceAudioEndpointsShareQwenKey() {
        val context = context()
        val prefs = Prefs(context)
        val workspace = "llm-123.cn-beijing.maas.aliyuncs.com"
        val live = "wss://$workspace/api-ws/v1/realtime"
        val asr = "wss://$workspace/api-ws/v1/inference"
        prefs.saveOnline(live, prefs.onlineModel, "业务空间千问密钥")
        prefs.saveCloudAsr(asr, prefs.cloudAsrModel, "业务空间千问密钥")
        assertEquals("业务空间千问密钥", prefs.platformKey(PlatformCredentials.QWEN.id))
        assertEquals("业务空间千问密钥", prefs.onlineConfig().apiKey)
        assertEquals("业务空间千问密钥", prefs.cloudAsrConfig().apiKey)
        val stored = context.getSharedPreferences("jpsub", 0)
        stored.edit().putString("onlineEndpoint", "wss://dashscope.aliyuncs.com/api-ws/v1/realtime")
            .putString("cloudAsrEndpoint", "wss://dashscope.aliyuncs.com/api-ws/v1/inference").commit()
        assertEquals("业务空间千问密钥", prefs.onlineKey)
        assertEquals("业务空间千问密钥", prefs.cloudAsrKey)
    }

    /** 文字配置按来源共享密钥，换地址时 null 保留目标槽位而不带入旧来源。 */
    @Test fun profilesShareOnlyTheirOrigin() {
        val prefs = Prefs(context())
        prefs.saveByok("https://first.example/v1", "模型一", "first-key")
        val second = prefs.addByokProfile("同源")
        prefs.saveByok("https://FIRST.example:443/other", "模型二", null)
        assertEquals("first-key", prefs.byokKey)
        assertEquals("first-key", prefs.byokService()!!.key)
        prefs.saveByok("https://second.example/v1", "模型三", null)
        assertNull(prefs.byokKey)
        assertEquals("", prefs.byokService()!!.key)
        prefs.saveByok("https://second.example/v1", "模型三", "second-key")
        val raw = "  中文密钥\n "
        prefs.saveByok("https://second.example/v1", "模型三", raw)
        assertEquals(raw, prefs.byokKey)
        assertEquals(raw, prefs.byokService()!!.key)
        prefs.useByokProfile(LlmProfile.FIRST)
        assertEquals("first-key", prefs.byokKey)
        prefs.useByokProfile(second)
        prefs.saveByok("https://first.example/v1", "模型四", null)
        assertEquals("first-key", prefs.byokKey)
        assertEquals(raw, prefs.platformKey("origin:https://second.example"))
        prefs.saveByok("https://first.example/v1", "模型四", "")
        assertEquals("", prefs.byokKey)
        assertEquals("", prefs.platformKey("origin:https://first.example"))
        assertTrue(prefs.credentialPlatforms.containsAll(PlatformCredentials.defaults))
        assertTrue(prefs.credentialPlatforms.any { it.id == "origin:https://first.example" })
    }

    /** 仅保存平台密钥或删除唯一引用它的配置后，中央列表仍可找回并编辑来源槽位。 */
    @Test fun savedOriginRemainsListedWithoutAnApiProfile() {
        val context = context()
        val prefs = Prefs(context)
        val id = "origin:https://draft.example:8443"
        prefs.savePlatformKey(id, "草稿平台密钥")
        assertEquals(CredentialPlatform(id, "https://draft.example:8443"),
            prefs.credentialPlatforms.single { it.id == id })
        val profile = prefs.addByokProfile("临时配置")
        prefs.saveByok("https://draft.example:8443/v1", "测试模型", null)
        prefs.deleteByokProfile(profile)
        val reopened = Prefs(context)
        assertEquals(CredentialPlatform(id, "https://draft.example:8443"),
            reopened.credentialPlatforms.single { it.id == id })
        assertEquals("草稿平台密钥", reopened.platformKey(id))
        reopened.savePlatformKey(id, "更新后的平台密钥")
        assertEquals("更新后的平台密钥", Prefs(context).platformKey(id))
    }

    /** 官方文字接口分别读取自身平台密钥，陌生主机不会继承官方密钥。 */
    @Test fun officialTextKeysStaySeparatedFromUnknownHost() {
        val prefs = Prefs(context())
        prefs.savePlatformKey("qwen", "qwen-key")
        prefs.savePlatformKey("deepseek", "deepseek-key")
        prefs.saveByok("https://api.deepseek.com/v1", "deepseek-flash", null)
        assertEquals("deepseek-key", prefs.byokService()!!.key)
        prefs.saveByok("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", null)
        assertEquals("qwen-key", prefs.byokService()!!.key)
        prefs.saveByok("https://dashscope.aliyuncs.com.evil.example/v1", "other", null)
        assertEquals("", prefs.byokService()!!.key)
    }

    /** 四个已知官方文字平台只要求中央密钥非空，任意原文均可保存；自定义来源可免密。 */
    @Test fun knownOfficialByokRequiresCentralKeyButCustomDoesNot() {
        val prefs = Prefs(context())
        for ((url, id) in listOf(
            "https://api.deepseek.com/v1" to PlatformCredentials.DEEPSEEK.id,
            "https://llm-123.cn-beijing.maas.aliyuncs.com/compatible-mode/v1" to PlatformCredentials.QWEN.id,
            "https://api.siliconflow.cn/v1" to PlatformCredentials.SILICONFLOW.id,
            "https://openrouter.ai/api/v1" to PlatformCredentials.OPENROUTER.id,
        )) {
            prefs.saveByok(url, "测试模型", null)
            assertFalse(url, prefs.byokReady)
            assertNull(url, prefs.byokForSession(null))
            val raw = " 中文密钥\n "
            prefs.savePlatformKey(id, raw)
            assertTrue(url, prefs.byokReady)
            assertEquals(raw, prefs.byokForSession(null)!!.key)
            prefs.savePlatformKey(id, "")
            assertFalse(url, prefs.byokReady)
        }
        prefs.saveByok("https://private.example/v1", "测试模型", null)
        assertTrue(prefs.byokReady)
        assertEquals("", prefs.byokForSession(null)!!.key)
    }

    /** 切到官方空密钥配置时维持运行中服务，补齐中央密钥后才通知切换。 */
    @Test fun profileSwitchWaitsForOfficialCentralKey() {
        val context = context()
        val prefs = Prefs(context)
        prefs.saveByok("https://private.example/v1", "本地模型", null)
        val running = prefs.byokForSession(null)!!
        val official = prefs.addByokProfile("官方")
        prefs.saveByok("https://api.deepseek.com/v1", "deepseek-flash", null)
        val stored = context.getSharedPreferences("jpsub", 0)
        prefs.useByokProfile(LlmProfile.FIRST)
        val before = stored.getLong("byokSaved", 0)
        prefs.useByokProfile(official)
        assertEquals(before, stored.getLong("byokSaved", 0))
        assertFalse(prefs.byokReady)
        assertEquals(running, prefs.byokForSession(running))
        prefs.savePlatformKey("deepseek", "有效密钥")
        prefs.useByokProfile(official)
        assertNotEquals(before, stored.getLong("byokSaved", 0))
        assertEquals("有效密钥", prefs.byokForSession(running)!!.key)
    }

    /** 一次迁移全部旧槽位，当前文字千问优先；重复初始化不覆盖中央空串。 */
    @Test fun migrationKeepsLegacyCiphertextAndCentralValue() {
        val context = context()
        val prefs = Prefs(context)
        prefs.savePlatformKey("qwen", "旧文字千问")
        val stored = context.getSharedPreferences("jpsub", 0)
        val textCipher = stored.getString("platformKey:qwen", null)!!
        prefs.savePlatformKey("qwen", "旧云端千问")
        val onlineCipher = stored.getString("platformKey:qwen", null)!!
        prefs.savePlatformKey("origin:https://other.example", "旧其他平台")
        val otherCipher = stored.getString("platformKey:origin:https://other.example", null)!!
        stored.edit().remove("platformKey:qwen").remove("platformKey:origin:https://other.example")
            .remove("platformCredentialsMigratedV1")
            .putString("byokUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1")
            .putString("byokKey", textCipher).putString("onlineKey", onlineCipher)
            .putString("byokProfiles", LlmProfile.store(listOf(LlmProfile.FIRST, LlmProfile("p2", "其他"))))
            .putString("byokUrl:p2", "https://other.example/v1").putString("byokKey:p2", otherCipher)
            .putString("listeningMode", ListeningMode.LOCAL.name).commit()
        val migrated = Prefs(context)
        assertEquals("旧文字千问", migrated.onlineKey)
        assertEquals("旧其他平台", migrated.platformKey("origin:https://other.example"))
        assertEquals(textCipher, stored.getString("byokKey", null))
        assertEquals(onlineCipher, stored.getString("onlineKey", null))
        migrated.savePlatformKey("qwen", "")
        assertEquals("", Prefs(context).onlineKey)
        assertEquals("", Prefs(context).byokKey)
    }

    /** 两路旧自定义音频密钥迁到各自来源，不能被误迁为千问密钥。 */
    @Test fun legacyCustomAudioKeysMigrateToTheirOwnOrigins() {
        val context = context()
        val prefs = Prefs(context)
        val liveId = "origin:https://legacy-live.example"
        val asrId = "origin:https://legacy-asr.example"
        prefs.savePlatformKey(liveId, "旧听译密钥")
        prefs.savePlatformKey(asrId, "旧听写密钥")
        val stored = context.getSharedPreferences("jpsub", 0)
        val liveCipher = stored.getString("platformKey:$liveId", null)!!
        val asrCipher = stored.getString("platformKey:$asrId", null)!!
        stored.edit().remove("platformKey:$liveId").remove("platformKey:$asrId")
            .remove("platformCredentialsMigratedV1")
            .putString("onlineEndpoint", "wss://legacy-live.example/realtime")
            .putString("cloudAsrEndpoint", "wss://legacy-asr.example/api-ws/v1/inference")
            .putString("onlineKey", liveCipher).putString("cloudAsrKey", asrCipher)
            .putString("listeningMode", ListeningMode.CLOUD_ASR.name).commit()
        val migrated = Prefs(context)
        assertEquals("旧听译密钥", migrated.onlineKey)
        assertEquals("旧听写密钥", migrated.cloudAsrKey)
        assertEquals("旧听译密钥", migrated.onlineConfig().apiKey)
        assertEquals("旧听写密钥", migrated.cloudAsrConfig().apiKey)
        assertEquals("", migrated.platformKey(PlatformCredentials.QWEN.id))
        assertEquals(liveCipher, stored.getString("onlineKey", null))
        assertEquals(asrCipher, stored.getString("cloudAsrKey", null))
    }

    /** 云端模式迁移优先对应旧密钥，已有中央槽位绝不被旧数据覆盖。 */
    @Test fun migrationRespectsModeAndExistingCentralKey() {
        val context = context()
        val prefs = Prefs(context)
        prefs.savePlatformKey("qwen", "旧听译")
        val stored = context.getSharedPreferences("jpsub", 0)
        val onlineCipher = stored.getString("platformKey:qwen", null)!!
        prefs.savePlatformKey("qwen", "旧听写")
        val asrCipher = stored.getString("platformKey:qwen", null)!!
        stored.edit().remove("platformKey:qwen").remove("platformCredentialsMigratedV1")
            .putString("onlineKey", onlineCipher).putString("cloudAsrKey", asrCipher)
            .putString("listeningMode", ListeningMode.CLOUD_ASR.name).commit()
        assertEquals("旧听写", Prefs(context).cloudAsrKey)
        stored.edit().putString("platformKey:qwen", onlineCipher).remove("platformCredentialsMigratedV1").commit()
        assertEquals("旧听译", Prefs(context).onlineKey)
    }

    /** 当前听写是自定义来源时，它的旧密钥不能覆盖另一端点的千问旧密钥。 */
    @Test fun customLegacyAudioDoesNotWinQwenConflict() {
        val context = context()
        val prefs = Prefs(context)
        prefs.savePlatformKey("qwen", "旧官方听译密钥")
        val stored = context.getSharedPreferences("jpsub", 0)
        val officialCipher = stored.getString("platformKey:qwen", null)!!
        prefs.savePlatformKey("origin:https://legacy-asr.example", "旧自定义听写密钥")
        val customCipher = stored.getString("platformKey:origin:https://legacy-asr.example", null)!!
        stored.edit().remove("platformKey:qwen").remove("platformKey:origin:https://legacy-asr.example")
            .remove("platformCredentialsMigratedV1")
            .putString("onlineKey", officialCipher)
            .putString("cloudAsrKey", customCipher)
            .putString("cloudAsrEndpoint", "wss://legacy-asr.example/api-ws/v1/inference")
            .putString("listeningMode", ListeningMode.CLOUD_ASR.name).commit()
        val migrated = Prefs(context)
        assertEquals("旧官方听译密钥", migrated.platformKey("qwen"))
        assertEquals("旧自定义听写密钥", migrated.cloudAsrKey)
    }

    /** 旧官方文字密钥迁移至 DeepSeek 槽位，换成仿冒主机后不能读取。 */
    @Test fun deepSeekLegacyKeyDoesNotLeakToSimilarHost() {
        val context = context()
        val prefs = Prefs(context)
        prefs.savePlatformKey("deepseek", "旧 DeepSeek 密钥")
        val stored = context.getSharedPreferences("jpsub", 0)
        val cipher = stored.getString("platformKey:deepseek", null)!!
        stored.edit().remove("platformKey:deepseek").remove("platformCredentialsMigratedV1")
            .putString("byokUrl", "https://api.deepseek.com/v1").putString("byokModel", "deepseek-flash")
            .putString("byokKey", cipher).commit()
        val migrated = Prefs(context)
        assertEquals("旧 DeepSeek 密钥", migrated.byokService()!!.key)
        migrated.saveByok("https://api.deepseek.com.evil.example/v1", "other", null)
        assertEquals("", migrated.byokService()!!.key)
        assertEquals("旧 DeepSeek 密钥", migrated.platformKey("deepseek"))
    }

    /** Jev 官方服务与同源文字接口共享中央密钥，Zen 不使用中央槽位。 */
    @Test fun jevProvidersUseTheirOwnOrigin() {
        val context = context()
        val prefs = Prefs(context)
        val raw = "  TypeSafe 中文密钥\n "
        prefs.setJevKey(JevProvider.OFFICIAL, raw)
        prefs.setJevKey(JevProvider.OPENROUTER, "OpenRouter 密钥")
        assertEquals(raw, prefs.jevKey(JevProvider.OFFICIAL))
        assertEquals(raw, prefs.platformKey(PlatformCredentials.TYPESAFE.id))
        assertEquals("OpenRouter 密钥", prefs.platformKey(PlatformCredentials.OPENROUTER.id))
        assertTrue(context.getSharedPreferences("jpsub", 0).contains("jevKey:changed"))
        prefs.saveByok("https://openrouter.ai/api/v1", "测试模型", null)
        assertEquals("OpenRouter 密钥", prefs.byokService()!!.key)
        assertTrue(prefs.credentialPlatforms.contains(PlatformCredentials.TYPESAFE))
        assertNull(prefs.jevKey(JevProvider.ZEN))
        prefs.setJevKey(JevProvider.ZEN, "Zen 独立密钥")
        assertEquals("Zen 独立密钥", prefs.jevKey(JevProvider.ZEN))
        assertFalse(prefs.credentialPlatforms.any { it.id == "origin:https://opencode.ai" })
        prefs.setJevKey(JevProvider.OPENROUTER, "")
        assertEquals("", prefs.jevKey(JevProvider.OPENROUTER))
        assertEquals("", prefs.byokService()!!.key)
    }

    /** 第二阶段只迁移缺失的 Jev 来源槽位，保留旧密文与已有中央空串。 */
    @Test fun jevLegacyMigrationDoesNotOverrideCentralKeys() {
        val context = context()
        val prefs = Prefs(context)
        val stored = context.getSharedPreferences("jpsub", 0)
        prefs.savePlatformKey(PlatformCredentials.TYPESAFE.id, "旧 TypeSafe")
        val typeSafeCipher = stored.getString("platformKey:${PlatformCredentials.TYPESAFE.id}", null)!!
        prefs.savePlatformKey(PlatformCredentials.OPENROUTER.id, "旧 OpenRouter")
        val routerCipher = stored.getString("platformKey:${PlatformCredentials.OPENROUTER.id}", null)!!
        prefs.savePlatformKey(PlatformCredentials.OPENROUTER.id, "")
        stored.edit().remove("platformKey:${PlatformCredentials.TYPESAFE.id}")
            .remove("platformJevCredentialsMigratedV1")
            .putString("jevKey:OFFICIAL", typeSafeCipher)
            .putString("jevKey:OPENROUTER", routerCipher).commit()
        val migrated = Prefs(context)
        assertEquals("旧 TypeSafe", migrated.jevKey(JevProvider.OFFICIAL))
        assertEquals("", migrated.jevKey(JevProvider.OPENROUTER))
        assertEquals(typeSafeCipher, stored.getString("jevKey:OFFICIAL", null))
        assertEquals(routerCipher, stored.getString("jevKey:OPENROUTER", null))
        migrated.setJevKey(JevProvider.OFFICIAL, "")
        assertEquals("", Prefs(context).jevKey(JevProvider.OFFICIAL))
    }
}
