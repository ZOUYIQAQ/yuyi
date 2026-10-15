package io.github.korenkrita.simulsub

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** 所有配置及加密数据都在独立偏好中，不访问用户保存的服务。 */
class PrefsProfilesDeviceTest {
    /** 创建独立命名空间，让偏好重建读取同一测试存储。 */
    private fun context(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "命名配置-${UUID.randomUUID()}"
        return object : ContextWrapper(base) {
            /** 只改偏好文件名，系统加密能力仍来自真实应用。 */
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$namespace-$name", mode)
        }
    }
    /** 旧默认地址、密钥和附加参数无需迁移，也不能写出明文凭据。 */
    @Test fun defaultKeepsLegacyStorageAndEncryptedCredentials() {
        val context = context()
        val prefs = Prefs(context)
        prefs.saveByok("https://first.example/v1", "旧模型", "旧测试密钥", "Test-Agent", listOf("X-Key" to "test-header"))
        val saved = context.getSharedPreferences("jpsub", 0)
        assertEquals("旧模型", saved.getString("byokModel", null))
        assertNotEquals("旧测试密钥", saved.getString("byokKey", null))
        assertFalse(saved.getString("byokHeaders", "")!!.contains("test-header"))
        assertFalse(saved.contains("byokProfiles"))
        val recreated = Prefs(context)
        assertEquals(LlmProfile.FIRST, recreated.byokProfile)
        assertEquals("旧测试密钥", recreated.byokKey)
        assertEquals(listOf("X-Key" to "test-header"), recreated.byokHeaders)
    }
    /** 新配置没有任何旧凭据，切换后整套地址、密钥和请求头各自回填。 */
    @Test fun profilesOwnIndependentCompleteSettings() {
        val context = context()
        val prefs = Prefs(context)
        prefs.saveByok("https://first.example/v1", "模型一", "密钥一", "Agent-One", listOf("X-Key" to "header-one"))
        val second = prefs.addByokProfile("备用")
        assertEquals("", prefs.byokUrl); assertEquals("", prefs.byokModel)
        assertNull(prefs.byokKey); assertTrue(prefs.byokHeaders.isEmpty()); assertEquals("", prefs.byokUserAgent)
        prefs.saveByok("http://127.0.0.1:8000", "模型二", "密钥二", "Agent-Two", listOf("X-Key" to "header-two"))
        val recreated = Prefs(context)
        assertEquals(second, recreated.byokProfile)
        assertEquals("密钥二", recreated.byokService()!!.key)
        prefs.useByokProfile(LlmProfile.FIRST)
        assertEquals("密钥一", prefs.byokService()!!.key)
        assertEquals("模型一", prefs.byokModel)
        assertEquals(listOf("X-Key" to "header-one"), prefs.byokHeaders)
        prefs.useByokProfile(second)
        assertEquals("Agent-Two", prefs.byokUserAgent)
    }
    /** 只有完整配置的保存或切换才通知会话，新增空项与重命名不打断翻译。 */
    @Test fun emptyProfileAndRenameDoNotSignalSessionSwitch() {
        val context = context()
        val prefs = Prefs(context)
        val stored = context.getSharedPreferences("jpsub", 0)
        prefs.saveByok("https://first.example", "模型一", "密钥一")
        val stamp = stored.getLong("byokSaved", 0)
        val second = prefs.addByokProfile("备用")
        assertEquals(stamp, stored.getLong("byokSaved", 0))
        prefs.renameByokProfile(second, "本地")
        assertEquals(stamp, stored.getLong("byokSaved", 0))
        prefs.useByokProfile(LlmProfile.FIRST)
        assertNotEquals(stamp, stored.getLong("byokSaved", 0))
    }
    /** 新增或切到空配置不打断会话，保存完整服务后才整体替换。 */
    @Test fun runningSessionKeepsItsServiceUntilSelectedProfileIsReady() {
        val prefs = Prefs(context())
        prefs.saveByok("https://first.example/v1", "模型一", "密钥一")
        val running = prefs.byokForSession(null)!!
        prefs.addByokProfile("本地服务")
        assertEquals(running, prefs.byokForSession(running))
        assertNull(prefs.byokForSession(null))
        prefs.byokUrl = "http://127.0.0.1:8000"
        assertEquals(running, prefs.byokForSession(running))
        prefs.saveByok("http://127.0.0.1:8000", "本地模型", "local-key", "Local-Agent", listOf("X-Key" to "local-header"))
        val switched = prefs.byokForSession(running)!!
        assertEquals("本地模型", switched.model)
        assertEquals("local-key", switched.key)
        assertEquals(listOf("X-Key" to "local-header"), switched.headers)
        prefs.useByokProfile(LlmProfile.FIRST)
        assertEquals(running, prefs.byokForSession(switched))
    }
    /** 删除当前项后回到剩余配置，凭据只删除指定项，不能删除最后一项。 */
    @Test fun deletionKeepsOtherSettingsAndAtLeastOneProfile() {
        val context = context()
        val prefs = Prefs(context)
        prefs.saveByok("https://first.example", "模型一", "密钥一")
        val second = prefs.addByokProfile("备用")
        prefs.saveByok("https://second.example", "模型二", "密钥二", "Agent-Two", listOf("X-Key" to "header-two"))
        prefs.deleteByokProfile(second)
        assertEquals(LlmProfile.FIRST, prefs.byokProfile)
        assertEquals("密钥一", prefs.byokKey)
        assertFalse(context.getSharedPreferences("jpsub", 0).all.keys.any { it.endsWith(second.suffix) })
        try { prefs.deleteByokProfile(LlmProfile.FIRST); fail("不能删除最后一项") } catch (_: IllegalArgumentException) { }
        assertEquals("密钥一", Prefs(context).byokKey)
    }
    /** 会话每次读取同一偏好快照，即使并行切换也不能拿到另一套配置的密钥。 */
    @Test fun sessionSnapshotNeverMixesProfilesDuringSwitches() {
        val prefs = Prefs(context())
        prefs.saveByok("https://first.example", "模型一", "密钥一")
        val second = prefs.addByokProfile("备用")
        prefs.saveByok("https://second.example", "模型二", "密钥二")
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val switching = executor.submit { repeat(200) { prefs.useByokProfile(if (it % 2 == 0) LlmProfile.FIRST else second) } }
            val reading = executor.submit { repeat(200) {
                val service = prefs.byokService()!!
                if (service.url.contains("first.example")) { assertEquals("模型一", service.model); assertEquals("密钥一", service.key) }
                else { assertEquals("模型二", service.model); assertEquals("密钥二", service.key) }
            } }
            switching.get(15, java.util.concurrent.TimeUnit.SECONDS)
            reading.get(15, java.util.concurrent.TimeUnit.SECONDS)
        } finally { executor.shutdownNow() }
    }
}
