package io.github.korenkrita.simulsub

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.online.CloudAsrConfig
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 通过真实设备加密与隔离偏好，验证缓存不改变密文格式和服务快照语义。 */
class KeyBoxCacheDeviceTest {
    /** 仅替换偏好文件名，保留真实安卓密钥库且不接触已有偏好。 */
    private fun isolated(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "解密缓存-${UUID.randomUUID()}"
        return object : ContextWrapper(base) {
            /** 每个用例的所有偏好访问统一落到独立命名空间。 */
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$namespace-$name", mode)
        }
    }

    /** 独立按原持久化协议认证解密，确保测试确实经过真实 AES-GCM。 */
    private fun decrypt(ciphertext: String): String {
        val bytes = Base64.decode(ciphertext, Base64.NO_WRAP)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey("jpsub-jev", null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12))
        return String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }

    /** 三百次真实密文读取只执行一次认证解密；旧偏好快照仍按旧密文解析。 */
    @Test fun realCiphertextIsDecryptedOnceAndOldSnapshotsRemainReadable() {
        val context = isolated()
        val prefs = Prefs(context)
        val stored = context.getSharedPreferences("jpsub", 0)
        val oldKey = "  旧密钥\n原文  "
        val newKey = "  新密钥\n原文  "
        prefs.savePlatformKey("qwen", oldKey)
        val oldSnapshot = stored.all
        val oldCipher = oldSnapshot["platformKey:qwen"] as String
        val calls = AtomicInteger()
        val cache = DecryptionCache { ciphertext ->
            calls.incrementAndGet()
            decrypt(ciphertext)
        }
        val reader = PlatformKeyStore(stored, { error("本测试只读取快照") }, cache::open)
        repeat(300) {
            assertEquals(oldKey, prefs.platformKey("qwen"))
            assertEquals(oldKey, reader.defaultCredential(oldSnapshot, "qwen")?.key)
        }
        assertEquals(1, calls.get())
        prefs.savePlatformKey("qwen", newKey)
        val newSnapshot = stored.all
        assertNotEquals(oldCipher, newSnapshot["platformKey:qwen"])
        repeat(3) {
            assertEquals(newKey, prefs.platformKey("qwen"))
            assertEquals(newKey, reader.defaultCredential(newSnapshot, "qwen")?.key)
            assertEquals(oldKey, reader.defaultCredential(oldSnapshot, "qwen")?.key)
        }
        assertEquals(2, calls.get())
        assertEquals(oldKey, decrypt(oldCipher))
    }

    /** 已热缓存不能接受篡改后的完整密文；认证失败不回退旧明文且不改写存储。 */
    @Test fun tamperedCiphertextIsRejectedAfterWarmReads() {
        val context = isolated()
        val prefs = Prefs(context)
        val stored = context.getSharedPreferences("jpsub", 0)
        val raw = "  真实 AES-GCM 密钥\n  "
        prefs.savePlatformKey("qwen", raw)
        val sealed = requireNotNull(stored.getString("platformKey:qwen", null))
        assertEquals(raw, decrypt(sealed))
        repeat(3) { assertEquals(raw, prefs.platformKey("qwen")) }
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        assertEquals(12 + raw.toByteArray().size + 16, bytes.size)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val tampered = Base64.encodeToString(bytes, Base64.NO_WRAP)
        assertTrue(stored.edit().putString("platformKey:qwen", tampered).commit())
        repeat(3) { assertEquals("", prefs.platformKey("qwen")) }
        assertEquals(tampered, stored.getString("platformKey:qwen", null))
        assertTrue(stored.edit().putString("platformKey:qwen", sealed).commit())
        assertEquals(raw, Prefs(context).platformKey("qwen"))
        prefs.savePlatformKey("qwen", raw)
        val resealed = requireNotNull(stored.getString("platformKey:qwen", null))
        assertNotEquals(sealed, resealed)
        assertEquals(raw, decrypt(resealed))
    }

    /** 热缓存后切换默认、绑定、换密钥和清空均立即生效，旧三路会话值保持不变。 */
    @Test fun defaultsBindingsReplacementAndClearDoNotReturnStaleKeys() {
        val context = isolated()
        val prefs = Prefs(context)
        val alternate = UUID.randomUUID().toString()
        prefs.savePlatformKey("qwen", "中央甲")
        prefs.savePlatformCredential("qwen", alternate, "备用乙", "备用乙")
        prefs.saveByok("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", null)
        val asrSlot = listeningCredentialSlot(ListeningMode.CLOUD_ASR,
            CloudAsrConfig.DEFAULT_ENDPOINT, CloudAsrConfig.DEFAULT_MODEL)
        val oldOnline = prefs.onlineConfig()
        val oldAsr = prefs.cloudAsrConfig()
        val oldTranslation = requireNotNull(prefs.byokService())
        repeat(3) {
            assertEquals("中央甲", prefs.platformKey("qwen"))
            assertEquals("备用乙", prefs.platformCredentials("qwen").last().key)
        }
        prefs.setDefaultPlatformCredential("qwen", alternate)
        assertEquals("备用乙", prefs.onlineConfig().apiKey)
        assertEquals("备用乙", prefs.cloudAsrConfig().apiKey)
        assertEquals("备用乙", prefs.byokService()?.key)
        prefs.selectCredential("qwen", asrSlot, "default")
        assertEquals("中央甲", prefs.cloudAsrConfig().apiKey)
        prefs.savePlatformKey("qwen", "更新乙")
        assertEquals("更新乙", Prefs(context).platformKey("qwen"))
        assertEquals("更新乙", prefs.onlineConfig().apiKey)
        assertEquals("更新乙", prefs.byokService()?.key)
        assertEquals("中央甲", prefs.cloudAsrConfig().apiKey)
        prefs.savePlatformKey("qwen", "")
        assertEquals("", Prefs(context).platformKey("qwen"))
        assertEquals("", prefs.onlineConfig().apiKey)
        assertEquals("", prefs.byokService()?.key)
        assertEquals("中央甲", prefs.cloudAsrConfig().apiKey)
        prefs.selectCredential("qwen", asrSlot, null)
        assertEquals("", prefs.cloudAsrConfig().apiKey)
        prefs.setDefaultPlatformCredential("qwen", "default")
        assertEquals("中央甲", prefs.cloudAsrConfig().apiKey)
        assertEquals("中央甲", oldOnline.apiKey)
        assertEquals("中央甲", oldAsr.apiKey)
        assertEquals("中央甲", oldTranslation.key)
    }
}
