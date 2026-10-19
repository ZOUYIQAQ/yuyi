package io.github.korenkrita.simulsub

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** 用隔离偏好重建首版密文布局，不接触现有用户配置或发出请求。 */
class LegacyCredentialMigrationDeviceTest {
    /** 每例只使用自己的偏好文件，保留真实设备加密环境。 */
    private fun isolated(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "首版凭据升级-${UUID.randomUUID()}"
        return object : ContextWrapper(base) {
            /** 只替换偏好命名空间。 */
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$prefix-$name", mode)
        }
    }

    /** 用生产加密得到合法密文，再写入首版实际使用的字段和配置目录。 */
    private fun seed(context: Context, firstKey: String, secondKey: String?, jev: String? = null): Pair<String, String?> {
        val prefs = Prefs(context)
        val sp = context.getSharedPreferences("jpsub", 0)
        /** 测试密文只用于隔离偏好的旧格式夹具。 */
        fun encrypted(value: String): String {
            prefs.savePlatformKey("deepseek", value)
            return sp.getString("platformKey:deepseek", null)!!
        }
        val a = encrypted(firstKey)
        val b = secondKey?.let(::encrypted)
        val j = jev?.let(::encrypted)
        val edit = sp.edit().clear().putString("byokProfiles", LlmProfile.store(listOf(LlmProfile.FIRST, LlmProfile("backup", "备用账号"))))
            .putString("byokActive", "").putString("byokUrl", "https://api.deepseek.com/v1")
            .putString("byokModel", "deepseek-chat").putString("byokKey", a)
            .putString("byokUrl:backup", "https://api.deepseek.com/v1").putString("byokModel:backup", "deepseek-chat")
        if (b != null) edit.putString("byokKey:backup", b)
        if (j != null) edit.putString("jevKey:${JevProvider.OPENROUTER.name}", j)
            .putString("byokUrl:backup", "https://openrouter.ai/api/v1")
        check(edit.commit())
        return a to b
    }

    /** 同平台两份配置继续使用各自账号，旧密文和选中标识不变。 */
    @Test fun samePlatformProfilesKeepIndependentKeys() {
        val context = isolated()
        val original = seed(context, "首版账号甲", "首版账号乙")
        val prefs = Prefs(context)
        val second = prefs.byokProfiles.single { it.id == "backup" }
        assertEquals("首版账号甲", prefs.byokKey)
        assertEquals("首版账号乙", prefs.byokKey(second))
        assertEquals("", prefs.byokProfile.id)
        assertNull(prefs.selectedCredentialId("deepseek", translationCredentialSlot("")))
        assertNotNull(prefs.selectedCredentialId("deepseek", translationCredentialSlot("backup")))
        val raw = context.getSharedPreferences("jpsub", 0)
        assertEquals(original.first, raw.getString("byokKey", null))
        assertEquals(original.second, raw.getString("byokKey:backup", null))
    }

    /** Jev 与同平台翻译密钥不同，升级后仍分别读取，不能替换为另一业务账号。 */
    @Test fun jevAndTranslationKeepSeparateAccounts() {
        val context = isolated()
        seed(context, "DeepSeek账号", "OpenRouter翻译账号", "OpenRouter辅助账号")
        val prefs = Prefs(context)
        val second = prefs.byokProfiles.single { it.id == "backup" }
        assertEquals("OpenRouter翻译账号", prefs.byokKey(second))
        assertEquals("OpenRouter辅助账号", prefs.jevKey(JevProvider.OPENROUTER))
        assertNotNull(prefs.selectedCredentialId(PlatformCredentials.OPENROUTER.id, jevCredentialSlot(JevProvider.OPENROUTER)))
    }

    /** 原先未填写的配置保持空密钥，不因同平台其他账号存在而开始调用。 */
    @Test fun missingLegacyKeyDoesNotBorrowAnotherAccount() {
        val context = isolated()
        seed(context, "首版账号甲", null)
        val prefs = Prefs(context)
        val second = prefs.byokProfiles.single { it.id == "backup" }
        assertEquals("", prefs.byokKey(second))
        assertFalse(prefs.byokReady(second))
        assertEquals("首版账号甲", prefs.byokKey)
    }

    /** 相同密钥不重复创建钥匙扣，两份配置继续跟随平台默认。 */
    @Test fun identicalKeysAreDeduplicated() {
        val context = isolated()
        seed(context, "相同旧密钥", "相同旧密钥")
        val prefs = Prefs(context)
        assertEquals(1, prefs.platformCredentials("deepseek").count { prefs.hasStoredPlatformCredential("deepseek", it.id) })
        assertNull(prefs.selectedCredentialId("deepseek", translationCredentialSlot("backup")))
    }

    /** 用户后续编辑或删除后，多次启动不再从旧字段复活密钥。 */
    @Test fun completedMigrationNeverRestoresDeletedOrEditedKeys() {
        val context = isolated()
        seed(context, "首版账号甲", "首版账号乙")
        val prefs = Prefs(context)
        val id = prefs.selectedCredentialId("deepseek", translationCredentialSlot("backup"))!!
        prefs.savePlatformCredential("deepseek", id, "后来修改", "新密钥")
        prefs.deletePlatformCredential("deepseek", "default")
        repeat(3) {
            val reopened = Prefs(context)
            assertNull(reopened.byokKey)
            assertEquals("新密钥", reopened.byokKey(reopened.byokProfiles.single { profile -> profile.id == "backup" }))
            assertFalse(reopened.hasStoredPlatformCredential("deepseek", "default"))
        }
    }

    /** 加密或目录准备失败时不提交半份绑定，也不提前写完成标志。 */
    @Test fun encryptionFailureLeavesTheWholeMigrationUncommitted() {
        val sp = isolated().getSharedPreferences("atomic-migration", 0)
        val store = PlatformKeyStore(sp, { value -> check(value != "备用密钥") { "模拟加密失败" }; value }, { it })
        val before = sp.all
        val editor = sp.edit().putString("platformKey:deepseek", "默认密钥")
        val failed = runCatching {
            store.putLegacyBindings(editor, before, mapOf("deepseek" to "默认密钥"),
                listOf(LegacyPlatformCredential("deepseek", "旧备用账号", translationCredentialSlot("backup"), "备用密钥")))
            check(editor.putBoolean("platformCredentialsMigratedV1", true).commit())
        }
        assertTrue(failed.isFailure)
        assertEquals(before, sp.all)
    }

    /** 已经显式保存的新版中央空串优先，旧字段不能越过清空意图恢复凭据。 */
    @Test fun existingNewerEmptyDefaultIsNotOverridden() {
        val context = isolated()
        seed(context, "旧密钥甲", "旧密钥乙")
        val source = isolated()
        val sourcePrefs = Prefs(source)
        sourcePrefs.savePlatformKey("deepseek", "")
        val empty = source.getSharedPreferences("jpsub", 0).getString("platformKey:deepseek", null)
        context.getSharedPreferences("jpsub", 0).edit().putString("platformKey:deepseek", empty).commit()
        val reopened = Prefs(context)
        assertEquals("", reopened.byokKey)
        assertEquals("", reopened.byokKey(reopened.byokProfiles.single { it.id == "backup" }))
    }
}
