package io.github.korenkrita.simulsub

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** 删除仅作用于隔离偏好中的虚构密钥，不操作用户配置。 */
class PlatformKeyDeletionDeviceTest {
    /** 隔离每个用例的凭据与迁移标记，仍使用真实设备加密。 */
    private fun isolated(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "删除凭据-${UUID.randomUUID()}"
        return object : ContextWrapper(base) {
            /** 所有偏好访问重定向到本测试命名空间。 */
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$prefix-$name", mode)
        }
    }

    /** 删除默认后跟随服务失效，显式绑定其他账号仍可用，重启也不回退。 */
    @Test fun deletingDefaultDoesNotBorrowAlternative() {
        val context = isolated()
        val prefs = Prefs(context)
        val alternate = UUID.randomUUID().toString()
        prefs.savePlatformKey("qwen", "sk-假默认-1234")
        prefs.savePlatformCredential("qwen", alternate, "备用", "sk-假备用-5678")
        prefs.selectCredential("qwen", "translation:bound", alternate)
        prefs.deletePlatformCredential("qwen", "default")
        val reopened = Prefs(context)
        assertEquals("", reopened.defaultPlatformCredentialId("qwen"))
        assertNull(reopened.credentialFor("qwen", "translation:follows"))
        assertEquals("sk-假备用-5678", reopened.credentialFor("qwen", "translation:bound")?.key)
        assertEquals(listOf(alternate), reopened.platformCredentials("qwen").map { it.id })
        assertFalse(reopened.hasStoredPlatformCredential("qwen", "default"))
        assertFalse(context.getSharedPreferences("jpsub", 0).contains("platformKey:qwen"))
        reopened.setDefaultPlatformCredential("qwen", alternate)
        assertEquals("sk-假备用-5678", reopened.credentialFor("qwen", "translation:follows")?.key)
    }

    /** 删除显式绑定项后拒绝回退默认；其他平台、密钥和绑定保持原样。 */
    @Test fun deletingBoundKeyLeavesReferenceUnavailable() {
        val context = isolated()
        val prefs = Prefs(context)
        val alternate = UUID.randomUUID().toString()
        prefs.savePlatformKey("qwen", "默认原文")
        prefs.savePlatformKey("deepseek", "另个平台原文")
        prefs.savePlatformCredential("qwen", alternate, "指定账号", "备用原文")
        prefs.selectCredential("qwen", "translation:bound", alternate)
        prefs.deletePlatformCredential("qwen", alternate)
        assertNull(Prefs(context).credentialFor("qwen", "translation:bound"))
        assertEquals("默认原文", prefs.platformKey("qwen"))
        assertEquals("另个平台原文", prefs.platformKey("deepseek"))
        assertTrue(runCatching { prefs.selectCredential("qwen", "translation:other", alternate) }.isFailure)
        assertTrue(runCatching { prefs.savePlatformCredential("qwen", alternate, "复活", "不应恢复") }.isFailure)
    }

    /** 最后一把删除后可通过旧引导保存入口重新配置，旧显式绑定不会复活。 */
    @Test fun addingAfterLastDeletionCreatesFreshDefault() {
        val context = isolated()
        val prefs = Prefs(context)
        prefs.savePlatformKey("qwen", "旧原文")
        prefs.selectCredential("qwen", "translation:old", "default")
        prefs.deletePlatformCredential("qwen", "default")
        assertTrue(prefs.platformCredentials("qwen").isEmpty())
        prefs.savePlatformKey("qwen", "  新原文._+/=  ")
        val reopened = Prefs(context)
        assertNotEquals("default", reopened.defaultPlatformCredentialId("qwen"))
        assertEquals("  新原文._+/=  ", reopened.platformKey("qwen"))
        assertNull(reopened.credentialFor("qwen", "translation:old"))
    }

    /** 无默认时显式输入的连接配置可原子创建新默认，其他绑定不被改写。 */
    @Test fun connectionSaveCanRestoreAnEmptyDefault() {
        val prefs = Prefs(isolated())
        prefs.savePlatformKey("qwen", "旧原文")
        prefs.deletePlatformCredential("qwen", "default")
        prefs.saveOnline(prefs.onlineEndpoint, prefs.onlineModel, "新连接原文")
        assertEquals("新连接原文", prefs.onlineKey)
        assertTrue(prefs.onlineReady)
    }

    /** 旧版官方来源被归并后，删除旧来源的最后一把密钥也不能使目录重读崩溃。 */
    @Test fun deletingLegacyOriginKeepsPlatformDirectoryReadable() {
        val context = isolated()
        val legacyId = "origin:https://dashscope.aliyuncs.com"
        Prefs(context).savePlatformKey("qwen", "旧来源虚构密钥")
        val stored = context.getSharedPreferences("jpsub", 0)
        stored.edit().putString("platformKey:$legacyId", stored.getString("platformKey:qwen", null)).commit()
        val prefs = Prefs(context)
        assertTrue(prefs.hasStoredPlatformCredential(legacyId, "default"))
        prefs.deletePlatformCredential(legacyId, "default")
        val reopened = Prefs(context)
        assertTrue(reopened.platformCredentials(legacyId).isEmpty())
        reopened.credentialPlatforms.forEach { reopened.platformCredentials(it.id) }
    }

    /** 新平台通过命名入口保存第一把时直接就绪，无需额外设置默认。 */
    @Test fun firstNamedKeyBecomesDefault() {
        val prefs = Prefs(isolated())
        val fresh = UUID.randomUUID().toString()
        prefs.savePlatformCredential("deepseek", fresh, "直播账号", "完整原文")
        assertEquals(fresh, prefs.defaultPlatformCredentialId("deepseek"))
        assertEquals("完整原文", prefs.platformKey("deepseek"))
    }
}
