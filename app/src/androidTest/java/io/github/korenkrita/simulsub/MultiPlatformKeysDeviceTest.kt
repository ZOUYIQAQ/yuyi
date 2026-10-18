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

/** 使用设备 Keystore 与隔离偏好验证命名凭据、绑定和运行时读取。 */
class MultiPlatformKeysDeviceTest {
    /** 每个用例只读写自己的偏好文件，不接触用户现有密钥。 */
    private fun isolated(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "多平台凭据-${UUID.randomUUID()}"
        return object : ContextWrapper(base) {
            /** 复用真实加密环境，但隔离所有偏好名。 */
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$namespace-$name", mode)
        }
    }

    /** 旧中央密文直接成为默认项，重建与新增备用密钥均不改写旧密文。 */
    @Test fun legacyDefaultCiphertextRemainsUntouched() {
        val context = isolated()
        val prefs = Prefs(context)
        val raw = "  旧中央密钥\n原文  "
        prefs.savePlatformKey("qwen", raw)
        val stored = context.getSharedPreferences("jpsub", 0)
        val oldCipher = stored.getString("platformKey:qwen", null)
        val another = UUID.randomUUID().toString()
        val reopened = Prefs(context)
        assertEquals("default", reopened.defaultPlatformCredentialId("qwen"))
        assertEquals(PlatformCredential("default", "默认密钥", raw), reopened.platformCredentials("qwen").first())
        assertNull(reopened.selectedCredentialId("qwen", "translation:"))
        reopened.savePlatformCredential("qwen", another, "  备用账号  ", "备用密钥")
        assertEquals(oldCipher, stored.getString("platformKey:qwen", null))
        assertEquals(listOf("default", another), Prefs(context).platformCredentials("qwen").map { it.id })
        assertEquals("备用账号", Prefs(context).platformCredentials("qwen")[1].label)
        assertEquals(raw, Prefs(context).platformKey("qwen"))
    }

    /** 同平台音频两套连接可绑定不同凭据，保存一个槽位不改写另一个。 */
    @Test fun qwenAudioSlotsKeepKeysSeparateAcrossRestart() {
        val context = isolated()
        val prefs = Prefs(context)
        val alternate = UUID.randomUUID().toString()
        val asrSlot = listeningCredentialSlot(ListeningMode.CLOUD_ASR,
            CloudAsrConfig.DEFAULT_ENDPOINT, CloudAsrConfig.DEFAULT_MODEL)
        val directSlot = listeningCredentialSlot(ListeningMode.ONLINE,
            LiveTranslateModels.REALTIME_ENDPOINT, LiveTranslateModels.REALTIME_ID)
        prefs.savePlatformKey("qwen", "默认千问密钥")
        prefs.savePlatformCredential("qwen", alternate, "听写专用", "专用千问密钥")
        prefs.selectCredential("qwen", asrSlot, alternate)
        assertEquals(alternate, prefs.selectedCredentialId("qwen", asrSlot))
        assertNull(prefs.selectedCredentialId("qwen", directSlot))
        assertEquals("专用千问密钥", prefs.keyForService(CloudAsrConfig.DEFAULT_ENDPOINT, asrSlot))
        assertEquals("默认千问密钥", prefs.keyForService(LiveTranslateModels.REALTIME_ENDPOINT, directSlot))
        prefs.saveCloudAsr(CloudAsrConfig.DEFAULT_ENDPOINT, CloudAsrConfig.DEFAULT_MODEL, "更新听写密钥")
        prefs.saveOnline(LiveTranslateModels.REALTIME_ENDPOINT, LiveTranslateModels.REALTIME_ID, "更新直译密钥")
        val restarted = Prefs(context)
        assertEquals("更新听写密钥", restarted.cloudAsrKey)
        assertEquals("更新听写密钥", restarted.cloudAsrConfig().apiKey)
        assertEquals("更新直译密钥", restarted.onlineKey)
        assertEquals("更新直译密钥", restarted.onlineConfig().apiKey)
        assertEquals("更新直译密钥", restarted.platformKey("qwen"))
        assertEquals("更新听写密钥", restarted.credentialFor("qwen", asrSlot)?.key)
        restarted.selectCredential("qwen", asrSlot, null)
        assertEquals("更新直译密钥", restarted.cloudAsrConfig().apiKey)
    }

    /** 翻译运行快照按目标配置绑定读取；同源另一配置与默认密钥均不被覆盖。 */
    @Test fun translationProfilesReadTheirOwnBoundKeys() {
        val context = isolated()
        val prefs = Prefs(context)
        val url = "https://api.deepseek.com/v1/chat/completions"
        val alternate = UUID.randomUUID().toString()
        prefs.savePlatformKey("deepseek", "默认 DeepSeek 密钥")
        prefs.saveByok(url, "deepseek-chat", null)
        val first = prefs.byokProfile
        val second = prefs.addByokProfile("第二套翻译", select = false)
        prefs.saveByok(url, "deepseek-flash", null, profile = second)
        prefs.savePlatformCredential("deepseek", alternate, "第二账号", "第二账号密钥")
        prefs.selectCredential("deepseek", translationCredentialSlot(second.id), alternate)
        assertEquals("默认 DeepSeek 密钥", prefs.byokService(first)?.key)
        assertEquals("第二账号密钥", prefs.byokService(second)?.key)
        assertTrue(prefs.byokReady(first))
        assertTrue(prefs.byokReady(second))
        prefs.saveByok(url, "deepseek-flash", "更新第二账号密钥", profile = second)
        val restarted = Prefs(context)
        assertEquals("更新第二账号密钥", restarted.byokService(second)?.key)
        assertEquals("默认 DeepSeek 密钥", restarted.byokService(first)?.key)
        assertEquals("默认 DeepSeek 密钥", restarted.platformKey("deepseek"))
        assertEquals("更新第二账号密钥", restarted.byokKey(second))
        assertEquals(first.id, restarted.byokProfile.id)
    }

    /** 绑定指向不存在的凭据时拒绝借用默认密钥，运行就绪也随之关闭。 */
    @Test fun brokenExplicitBindingFailsClosed() {
        val context = isolated()
        val prefs = Prefs(context)
        val slot = listeningCredentialSlot(ListeningMode.CLOUD_ASR,
            CloudAsrConfig.DEFAULT_ENDPOINT, CloudAsrConfig.DEFAULT_MODEL)
        prefs.savePlatformKey("qwen", "可用默认密钥")
        val encodedPlatform = encoded("qwen")
        val encodedSlot = encoded(slot)
        context.getSharedPreferences("jpsub", 0).edit()
            .putString("platformCredentialBinding:$encodedPlatform:$encodedSlot", UUID.randomUUID().toString()).commit()
        assertNull(prefs.credentialFor("qwen", slot))
        assertTrue(runCatching { prefs.selectedCredentialId("qwen", slot) }.exceptionOrNull()
            is IllegalArgumentException)
        assertEquals("", prefs.cloudAsrKey)
        assertFalse(prefs.cloudAsrReady)
        assertEquals("可用默认密钥", prefs.platformKey("qwen"))
        prefs.savePlatformKey("deepseek", "DeepSeek 默认密钥")
        prefs.saveByok("https://api.deepseek.com/v1/chat/completions", "deepseek-chat", null)
        val translationSlot = translationCredentialSlot(prefs.byokProfile.id)
        context.getSharedPreferences("jpsub", 0).edit()
            .putString("platformCredentialBinding:${encoded("deepseek")}:${encoded(translationSlot)}",
                UUID.randomUUID().toString()).commit()
        assertNull(prefs.byokService())
        assertFalse(prefs.byokReady)
    }

    /** 显式绑定空密钥不回退默认；非法名称或跨平台凭据不能写入。 */
    @Test fun emptySelectedKeyDoesNotBorrowDefault() {
        val context = isolated()
        val prefs = Prefs(context)
        val alternate = UUID.randomUUID().toString()
        val slot = listeningCredentialSlot(ListeningMode.CLOUD_ASR,
            CloudAsrConfig.DEFAULT_ENDPOINT, CloudAsrConfig.DEFAULT_MODEL)
        prefs.savePlatformKey("qwen", "默认千问密钥")
        prefs.savePlatformCredential("qwen", alternate, "空凭据", "")
        prefs.selectCredential("qwen", slot, alternate)
        assertEquals("", prefs.keyForService(CloudAsrConfig.DEFAULT_ENDPOINT, slot))
        assertFalse(prefs.cloudAsrReady)
        assertEquals("默认千问密钥", prefs.platformKey("qwen"))
        assertEquals("", prefs.keyForService("wss://other.example/api-ws/v1/inference", slot))
        try {
            prefs.savePlatformCredential("qwen", UUID.randomUUID().toString(), "  ", "不会保存")
            fail("空名称不应保存")
        } catch (_: IllegalArgumentException) {
            assertEquals(listOf("default", alternate), prefs.platformCredentials("qwen").map { it.id })
        }
        try {
            prefs.selectCredential("deepseek", slot, alternate)
            fail("其他平台的凭据不应可选")
        } catch (_: IllegalArgumentException) {
            assertNull(prefs.selectedCredentialId("deepseek", slot))
        }
    }

    /** Jev 选中备用凭据后读取与保存都只针对该槽位，其他来源互不借用。 */
    @Test fun jevProviderUsesSelectedCredential() {
        val context = isolated()
        val prefs = Prefs(context)
        val provider = JevProvider.OFFICIAL
        val platformId = requireNotNull(PlatformCredentials.forUrl(provider.url)).id
        val alternate = UUID.randomUUID().toString()
        prefs.savePlatformKey(platformId, "TypeSafe 默认密钥")
        prefs.savePlatformCredential(platformId, alternate, "Jev 专用", "TypeSafe 备用密钥")
        prefs.selectCredential(platformId, jevCredentialSlot(provider), alternate)
        assertEquals("TypeSafe 备用密钥", prefs.jevKey(provider))
        prefs.setJevKey(provider, "更新 Jev 专用密钥")
        val restarted = Prefs(context)
        assertEquals("更新 Jev 专用密钥", restarted.jevKey(provider))
        assertEquals("TypeSafe 默认密钥", restarted.platformKey(platformId))
        assertNull(restarted.jevKey(JevProvider.ZEN))
        assertNull(restarted.jevKey(JevProvider.OPENROUTER))
    }

    /** 测试构造与正式存储相同的 URL 安全键片段。 */
    private fun encoded(value: String): String = Base64.encodeToString(value.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
