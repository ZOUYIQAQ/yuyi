package io.github.korenkrita.simulsub

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.online.CloudAsrConfig
import io.github.korenkrita.simulsub.online.LiveTranslateModels
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** 使用隔离偏好验证默认指针切换，不移动任何密钥或服务绑定。 */
class PlatformDefaultCredentialDeviceTest {
    /** 每个用例使用独立偏好，真实加密仍由设备 Keystore 提供。 */
    private fun isolated(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "平台默认切换-${UUID.randomUUID()}"
        return object : ContextWrapper(base) {
            /** 仅替换偏好文件名，避免接触用户已有数据。 */
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$namespace-$name", mode)
        }
    }

    /** 同平台三路跟随者即时读取新默认，已取得的会话快照仍保持旧密钥。 */
    @Test fun followersUseNewDefaultWithoutMutatingRunningSnapshots() {
        val context = isolated()
        val prefs = Prefs(context)
        val alternate = UUID.randomUUID().toString()
        val original = "  默认甲\n原文  "
        val replacement = "  备用乙\n原文  "
        prefs.savePlatformKey("qwen", original)
        prefs.savePlatformCredential("qwen", alternate, "备用乙", replacement)
        prefs.saveOnline(LiveTranslateModels.REALTIME_ENDPOINT, LiveTranslateModels.REALTIME_ID, original)
        prefs.saveByok("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", null)
        val oldOnline = prefs.onlineConfig()
        val oldAsr = prefs.cloudAsrConfig()
        val oldTranslation = requireNotNull(prefs.byokService())
        val stored = context.getSharedPreferences("jpsub", 0)
        val before = stored.all
        assertEquals("default", prefs.defaultPlatformCredentialId("qwen"))

        prefs.setDefaultPlatformCredential("qwen", alternate)

        assertEquals(alternate, prefs.defaultPlatformCredentialId("qwen"))
        assertEquals(replacement, prefs.platformKey("qwen"))
        assertEquals(replacement, prefs.onlineKey)
        assertEquals(replacement, prefs.onlineConfig().apiKey)
        assertEquals(replacement, prefs.cloudAsrKey)
        assertEquals(replacement, prefs.cloudAsrConfig().apiKey)
        assertEquals(replacement, prefs.byokKey)
        assertEquals(replacement, prefs.byokService()?.key)
        assertNull(prefs.selectedCredentialId("qwen", translationCredentialSlot(prefs.byokProfile.id)))
        assertEquals(original, oldOnline.apiKey)
        assertEquals(original, oldAsr.apiKey)
        assertEquals(original, oldTranslation.key)
        assertEquals(before["byokSaved"], stored.all["byokSaved"])
        assertEquals(before, stored.all.filterKeys { it != defaultStorageKey("qwen") })
        val restarted = Prefs(context)
        assertEquals(alternate, restarted.defaultPlatformCredentialId("qwen"))
        assertEquals(replacement, restarted.cloudAsrConfig().apiKey)
        assertEquals(replacement, restarted.byokService()?.key)
        assertEquals(listOf("default", alternate), restarted.platformCredentials("qwen").map { it.id })
        restarted.setDefaultPlatformCredential("qwen", "default")
        assertEquals(original, restarted.platformKey("qwen"))
        assertEquals(before, stored.all.filterKeys { it != defaultStorageKey("qwen") })
    }

    /** 显式绑定中央标识或其他 UUID 的服务，不随平台默认指针改变。 */
    @Test fun explicitDefaultAndUuidBindingsStayIndependent() {
        val context = isolated()
        val prefs = Prefs(context)
        val replacement = UUID.randomUUID().toString()
        val pinned = UUID.randomUUID().toString()
        val url = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        prefs.savePlatformKey("qwen", "中央甲")
        prefs.savePlatformCredential("qwen", replacement, "新默认乙", "密钥乙")
        prefs.savePlatformCredential("qwen", pinned, "独立丙", "密钥丙")
        prefs.saveByok(url, "qwen-plus", null)
        val centralProfile = prefs.byokProfile
        val pinnedProfile = prefs.addByokProfile("独立绑定", select = false)
        prefs.saveByok(url, "qwen-plus", null, profile = pinnedProfile)
        prefs.selectCredential("qwen", translationCredentialSlot(centralProfile.id), "default")
        prefs.selectCredential("qwen", translationCredentialSlot(pinnedProfile.id), pinned)
        val asrSlot = listeningCredentialSlot(ListeningMode.CLOUD_ASR,
            CloudAsrConfig.DEFAULT_ENDPOINT, CloudAsrConfig.DEFAULT_MODEL)
        prefs.selectCredential("qwen", asrSlot, "default")
        val stored = context.getSharedPreferences("jpsub", 0)
        val before = stored.all

        prefs.setDefaultPlatformCredential("qwen", replacement)

        val restarted = Prefs(context)
        assertEquals("中央甲", restarted.cloudAsrKey)
        assertEquals("中央甲", restarted.byokService(centralProfile)?.key)
        assertEquals("密钥丙", restarted.byokService(pinnedProfile)?.key)
        assertEquals("default", restarted.selectedCredentialId("qwen", asrSlot))
        assertEquals(pinned, restarted.selectedCredentialId("qwen", translationCredentialSlot(pinnedProfile.id)))
        assertEquals(before, stored.all.filterKeys { it != defaultStorageKey("qwen") })
        restarted.selectCredential("qwen", asrSlot, null)
        assertEquals("密钥乙", restarted.cloudAsrConfig().apiKey)
    }

    /** 旧入口和服务保存均写入当前默认，显式绑定则始终写自己的槽位。 */
    @Test fun legacyAndBoundWritesResolveTheSameCurrentDefault() {
        val context = isolated()
        val prefs = Prefs(context)
        val alternate = UUID.randomUUID().toString()
        prefs.savePlatformKey("qwen", "中央甲")
        prefs.savePlatformCredential("qwen", alternate, "默认乙", "乙")
        prefs.saveByok("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", null)
        prefs.setDefaultPlatformCredential("qwen", alternate)
        val stored = context.getSharedPreferences("jpsub", 0)
        val centralCipher = stored.getString("platformKey:qwen", null)
        prefs.savePlatformKey("qwen", "  旧入口写乙\n ")
        assertEquals("  旧入口写乙\n ", prefs.cloudAsrKey)
        prefs.saveOnline(LiveTranslateModels.REALTIME_ENDPOINT, LiveTranslateModels.REALTIME_ID, "  听译写乙\n ")
        assertEquals("  听译写乙\n ", prefs.platformKey("qwen"))
        prefs.saveCloudAsr(CloudAsrConfig.DEFAULT_ENDPOINT, CloudAsrConfig.DEFAULT_MODEL, "  听写写乙\n ")
        assertEquals("  听写写乙\n ", prefs.onlineConfig().apiKey)
        prefs.saveByok(prefs.byokUrl, prefs.byokModel, "  翻译写乙\n ")
        assertEquals("  翻译写乙\n ", prefs.cloudAsrConfig().apiKey)
        prefs.byokKey = "  属性写乙\n "
        assertEquals("  属性写乙\n ", Prefs(context).byokService()?.key)
        assertEquals(centralCipher, stored.getString("platformKey:qwen", null))
        val asrSlot = listeningCredentialSlot(ListeningMode.CLOUD_ASR,
            CloudAsrConfig.DEFAULT_ENDPOINT, CloudAsrConfig.DEFAULT_MODEL)
        prefs.selectCredential("qwen", asrSlot, "default")
        prefs.saveCloudAsr(CloudAsrConfig.DEFAULT_ENDPOINT, CloudAsrConfig.DEFAULT_MODEL, "只写中央甲")
        assertEquals("只写中央甲", prefs.platformCredentials("qwen").first().key)
        assertEquals("  属性写乙\n ", prefs.platformKey("qwen"))
    }

    /** 新默认即使为空也不借用中央项或其他平台，清空旧入口同样只清空当前默认。 */
    @Test fun emptyDefaultDoesNotBorrowAnotherCredentialOrPlatform() {
        val context = isolated()
        val prefs = Prefs(context)
        val alternate = UUID.randomUUID().toString()
        prefs.savePlatformKey("qwen", "中央甲")
        prefs.savePlatformKey("deepseek", "DeepSeek 甲")
        prefs.savePlatformCredential("qwen", alternate, "空默认", "")
        prefs.saveByok("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", null)
        prefs.setDefaultPlatformCredential("qwen", alternate)
        assertEquals("", prefs.platformKey("qwen"))
        assertEquals("", prefs.cloudAsrConfig().apiKey)
        assertEquals("", prefs.onlineConfig().apiKey)
        assertEquals("", prefs.byokService()?.key)
        assertFalse(prefs.cloudAsrReady)
        assertFalse(prefs.byokReady)
        assertEquals("DeepSeek 甲", prefs.platformKey("deepseek"))
        assertEquals("default", prefs.defaultPlatformCredentialId("deepseek"))
        prefs.savePlatformKey("qwen", "临时乙")
        prefs.savePlatformKey("qwen", "")
        assertEquals("中央甲", prefs.platformCredentials("qwen").first().key)
        assertEquals("", Prefs(context).platformKey("qwen"))
    }

    /** 两个需要密钥的 Jev 平台切换默认均通知变更，独立绑定仍读写中央旧项。 */
    @Test fun jevDefaultChangesNotifyAndKeepExplicitBindings() {
        for (provider in listOf(JevProvider.OFFICIAL, JevProvider.OPENROUTER)) {
            val context = isolated()
            val prefs = Prefs(context)
            val platform = requireNotNull(PlatformCredentials.forUrl(provider.url)).id
            val alternate = UUID.randomUUID().toString()
            prefs.savePlatformKey(platform, "Jev 甲")
            prefs.savePlatformCredential(platform, alternate, "Jev 乙", "  Jev 乙\n ")
            val stored = context.getSharedPreferences("jpsub", 0)
            val before = stored.all
            prefs.setDefaultPlatformCredential(platform, alternate)
            assertNotEquals(before["jevKey:changed"], stored.all["jevKey:changed"])
            assertEquals(before.filterKeys { it != "jevKey:changed" },
                stored.all.filterKeys { it != "jevKey:changed" && it != defaultStorageKey(platform) })
            assertEquals("  Jev 乙\n ", Prefs(context).jevKey(provider))
            prefs.setJevKey(provider, "更新乙")
            assertEquals("更新乙", prefs.platformKey(platform))
            assertEquals("Jev 甲", prefs.platformCredentials(platform).first().key)
            prefs.selectCredential(platform, jevCredentialSlot(provider), "default")
            prefs.setJevKey(provider, "更新甲")
            assertEquals("更新甲", prefs.jevKey(provider))
            assertEquals("更新乙", prefs.platformKey(platform))
            assertNull(prefs.jevKey(JevProvider.ZEN))
        }
    }

    /** 非规范、未知或跨平台标识全部拒绝，失败时偏好和通知标记均不改变。 */
    @Test fun defaultAndSelectionSettersRejectInvalidIdentifiersAtomically() {
        val context = isolated()
        val prefs = Prefs(context)
        val alternate = "abcdefab-1234-4234-8234-abcdefabcdef"
        prefs.savePlatformKey("qwen", "中央甲")
        prefs.savePlatformCredential("qwen", alternate, "乙", "乙")
        val slot = "translation:检查"
        val stored = context.getSharedPreferences("jpsub", 0)
        val before = stored.all
        for (invalid in listOf("", " default", "default ", "selecteddefault", "1-1-1-1-1",
            alternate.uppercase(), UUID.randomUUID().toString())) {
            rejected { prefs.setDefaultPlatformCredential("qwen", invalid) }
            rejected { prefs.selectCredential("qwen", slot, invalid) }
            assertEquals(before, stored.all)
        }
        rejected { prefs.setDefaultPlatformCredential("deepseek", alternate) }
        assertEquals(before, stored.all)
    }

    /** 损坏默认元数据使跟随读取关闭、写入失败，但显式中央绑定仍可独立读取。 */
    @Test fun corruptDefaultMetadataFailsClosedWithoutBreakingPinnedServices() {
        val context = isolated()
        val prefs = Prefs(context)
        prefs.savePlatformKey("qwen", "中央甲")
        prefs.saveByok("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", null)
        val asrSlot = listeningCredentialSlot(ListeningMode.CLOUD_ASR,
            CloudAsrConfig.DEFAULT_ENDPOINT, CloudAsrConfig.DEFAULT_MODEL)
        prefs.selectCredential("qwen", asrSlot, "default")
        val stored = context.getSharedPreferences("jpsub", 0)
        for (invalid in listOf("", " default", "selecteddefault", "1-1-1-1-1", UUID.randomUUID().toString(), 7, true)) {
            putInvalid(stored, defaultStorageKey("qwen"), invalid)
            val before = stored.all
            rejected { prefs.defaultPlatformCredentialId("qwen") }
            assertEquals("", prefs.platformKey("qwen"))
            assertEquals("", prefs.onlineConfig().apiKey)
            assertNull(prefs.byokService())
            assertFalse(prefs.byokReady)
            assertEquals("中央甲", prefs.cloudAsrConfig().apiKey)
            rejected { prefs.savePlatformKey("qwen", "禁止写入") }
            rejected { prefs.saveOnline(prefs.onlineEndpoint, prefs.onlineModel, "禁止写入") }
            rejected { prefs.saveByok(prefs.byokUrl, prefs.byokModel, "禁止写入") }
            assertEquals(before, stored.all)
        }
        prefs.setDefaultPlatformCredential("qwen", "default")
        assertEquals("中央甲", prefs.platformKey("qwen"))
    }

    /** 损坏显式绑定不伪装成跟随默认，也不能通过保存连接绕过校验。 */
    @Test fun corruptSelectedIdentifiersNeverFallBackOrWrite() {
        val context = isolated()
        val prefs = Prefs(context)
        val alternate = "abcdefab-1234-4234-8234-abcdefabcdef"
        prefs.savePlatformKey("qwen", "中央甲")
        prefs.savePlatformCredential("qwen", alternate, "乙", "乙")
        prefs.setDefaultPlatformCredential("qwen", alternate)
        val slot = listeningCredentialSlot(ListeningMode.CLOUD_ASR,
            CloudAsrConfig.DEFAULT_ENDPOINT, CloudAsrConfig.DEFAULT_MODEL)
        val stored = context.getSharedPreferences("jpsub", 0)
        for (invalid in listOf(" default", "selecteddefault", alternate.uppercase(), "1-1-1-1-1",
            UUID.randomUUID().toString(), 7, true)) {
            putInvalid(stored, "platformCredentialBinding:${encoded("qwen")}:${encoded(slot)}", invalid)
            val before = stored.all
            rejected { prefs.selectedCredentialId("qwen", slot) }
            assertNull(prefs.credentialFor("qwen", slot))
            assertEquals("", prefs.cloudAsrConfig().apiKey)
            assertFalse(prefs.cloudAsrReady)
            rejected { prefs.saveCloudAsr(prefs.cloudAsrEndpoint, prefs.cloudAsrModel, "禁止写入") }
            assertEquals(before, stored.all)
        }
        prefs.selectCredential("qwen", slot, null)
        assertNull(prefs.selectedCredentialId("qwen", slot))
        assertEquals("乙", prefs.cloudAsrKey)
    }

    /** 默认指针、目录和密文均从调用方给定快照解析，旧快照写入原目标。 */
    @Test fun snapshotResolutionAndBoundWriteStayAtomicAcrossDefaultChange() {
        val context = isolated()
        val stored = context.getSharedPreferences("独立存储", 0)
        val store = PlatformKeyStore(stored, { it }, { it })
        val alternate = UUID.randomUUID().toString()
        val slot = "translation:快照"
        store.saveDefault("qwen", "甲")
        store.save("qwen", alternate, "乙", "乙")
        val oldSnapshot = stored.all
        store.setDefault("qwen", alternate)
        val newSnapshot = stored.all
        assertEquals("default", store.defaultId(oldSnapshot, "qwen"))
        assertEquals(alternate, store.defaultId(newSnapshot, "qwen"))
        assertEquals("甲", store.selected(oldSnapshot, "qwen", slot)?.key)
        assertEquals("乙", store.selected(newSnapshot, "qwen", slot)?.key)
        val editor = stored.edit().putString("测试字段", "同次提交")
        store.putBoundKey(editor, oldSnapshot, "qwen", slot, "旧快照只写甲")
        assertTrue(editor.commit())
        assertEquals("旧快照只写甲", store.credentials(stored.all, "qwen").first().key)
        assertEquals("乙", store.selected(stored.all, "qwen", slot)?.key)
        assertEquals("甲", store.selected(oldSnapshot, "qwen", slot)?.key)
        assertEquals("同次提交", stored.getString("测试字段", null))
    }

    /** 目录项密文缺失或无法解密时不读取其他项，也不改变默认指针。 */
    @Test fun missingOrUnreadableDefaultCiphertextDoesNotBorrowCentralKey() {
        val context = isolated()
        val stored = context.getSharedPreferences("独立存储", 0)
        val store = PlatformKeyStore(stored, { it }, { check(it != "损坏密文") { "模拟解密失败" }; it })
        val alternate = UUID.randomUUID().toString()
        store.saveDefault("qwen", "甲")
        store.save("qwen", alternate, "乙", "乙")
        store.setDefault("qwen", alternate)
        val alternateStorage = "platformCredentialKey:${encoded("qwen")}:$alternate"
        val missing = stored.all.filterKeys { it != alternateStorage }
        val damaged = stored.all + (alternateStorage to "损坏密文")
        for (snapshot in listOf(missing, damaged)) {
            assertEquals(alternate, store.defaultId(snapshot, "qwen"))
            assertEquals("", store.selected(snapshot, "qwen", "translation:跟随")?.key)
            assertEquals("甲", store.credentials(snapshot, "qwen").first().key)
        }
    }

    /** 断言数据层以参数错误拒绝写入，而不是接受错误绑定。 */
    private fun rejected(action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull()
        assertTrue("应拒绝非法凭据，实际为 $failure", failure is IllegalArgumentException)
    }

    /** 构造存储损坏样本，不改写其他偏好键。 */
    private fun putInvalid(stored: SharedPreferences, key: String, value: Any) {
        val editor = stored.edit()
        when (value) {
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Boolean -> editor.putBoolean(key, value)
            else -> error("测试样本类型无效")
        }
        assertTrue(editor.commit())
    }

    /** 默认元数据独立于中央密文和备用目录保存。 */
    private fun defaultStorageKey(platformId: String): String = "platformCredentialDefault:${encoded(platformId)}"

    /** 与正式存储使用相同的 URL 安全编码构造故障样本。 */
    private fun encoded(value: String): String = Base64.encodeToString(value.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
