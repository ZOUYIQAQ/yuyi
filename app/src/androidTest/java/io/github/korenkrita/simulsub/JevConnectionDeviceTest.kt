package io.github.korenkrita.simulsub

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.ui.SimulSubActions
import io.github.korenkrita.simulsub.ui.UiDestination
import io.github.korenkrita.simulsub.updates.AvailableUpdate
import io.github.korenkrita.simulsub.updates.ReleaseUpdateSource
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** 使用隔离偏好和受控 Judge 验证 Jev 联通测试，不连接真实服务或采集音频。 */
class JevConnectionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val token = UUID.randomUUID().toString()
    private val store = ViewModelStore()
    private val factoryCalls = AtomicInteger()
    private val receivedConnections = mutableListOf<JevConnection>()
    private val receivedKeys = mutableListOf<String>()
    private val receivedProviders = mutableListOf<JevProvider>()
    private val source = object : ReleaseUpdateSource {
        /** 更新检查与本测试无关，禁止发起真实网络请求。 */
        override suspend fun latest(): AvailableUpdate? = null
    }
    private val application = object : Application() {
        /** 系统依赖读取目标应用上下文，偏好由本夹具隔离。 */
        override fun getApplicationContext(): Context = context.applicationContext
        /** 保持真实包名以初始化控制器依赖。 */
        override fun getPackageName(): String = context.packageName
        /** 每例独立偏好，不触碰设备用户配置。 */
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            context.getSharedPreferences("Jev联通-$token-$name", mode)
        /** 日志索引只访问本测试的独立目录。 */
        override fun getExternalFilesDir(type: String?): File = File(context.cacheDir, "Jev联通-$token")
    }
    private lateinit var controller: AppController
    private lateinit var actions: SimulSubActions
    private lateinit var fake: FakeJudge

    /** 构造真实控制器，仅将 Jev 请求替换成可控 Judge。 */
    @Before fun prepare() = main {
        fake = FakeJudge()
        controller = AppController(application, source, { 0L }) { connection, _, _ ->
            synchronized(receivedKeys) { receivedConnections += connection; receivedProviders += connection.provider; receivedKeys += connection.key.orEmpty() }
            factoryCalls.incrementAndGet()
            fake
        }
        store.put("Jev联通测试", controller)
        actions = controller.actions({}, {}, {}, {})
    }

    /** 释放阻塞请求并销毁控制器，避免测试间共享协程。 */
    @After fun finish() {
        fake.release.countDown()
        main { store.clear() }
    }

    /** 未保存连接不能发送请求；保存后的协议、地址和模型随同一个快照送入测试。 */
    @Test fun customProbeUsesSavedConnectionAndAllowsAnonymousAccess() {
        main {
            selectProvider(JevProvider.CUSTOM)
            actions.onTestJevConnection()
            assertEquals(0, factoryCalls.get())
            assertTrue(message()!!.text.contains("地址与模型"))
            actions.onSaveJevCustom(JevCustom(JevProtocol.DECISIONS, "https://jev.example", "custom-jev"))
            actions.onTestJevConnection()
        }
        waitFor { !controller.state.value.settings.advanced.jevTesting }
        main {
            assertEquals(1, factoryCalls.get())
            val c = receivedConnections.single()
            assertEquals("https://jev.example/alpha/decisions", c.url)
            assertEquals("custom-jev", c.model)
            assertEquals("", c.key)
            assertFalse(message()!!.isError)
        }
    }

    /** 自定义服务选择中央命名密钥，切来源不借用旧密钥；旧快照保持原值。 */
    @Test fun customCredentialsShareTheCentralStoreAndNeverCrossOrigins() = main {
        val prefs = Prefs(application)
        val c = JevCustom(JevProtocol.SYSTEMONE, "https://first.example/v1", "jev-a")
        val platform = PlatformCredentials.forUrl(c.url)!!.id
        val id = UUID.randomUUID().toString()
        prefs.savePlatformCredential(platform, id, "备用", "  原文密钥 +/=  ")
        prefs.selectCredential(platform, jevCredentialSlot(JevProvider.CUSTOM), id)
        actions.onSaveJevCustom(c)
        selectProvider(JevProvider.CUSTOM)
        val before = prefs.jevConnection()
        assertEquals("  原文密钥 +/=  ", before.key)
        actions.onSaveJevCustom(c.copy(url = "https://second.example", model = "jev-b"))
        val after = prefs.jevConnection()
        assertEquals("", after.key)
        assertEquals("  原文密钥 +/=  ", before.key)
        assertEquals("jev-a", before.model)
        assertEquals("jev-b", after.model)
        assertTrue(prefs.credentialPlatforms.any { it.id == PlatformCredentials.forUrl("https://second.example")!!.id })
    }

    /** 修改自定义连接会取消测试，旧模型的成功结果不能回填新配置。 */
    @Test fun editingCustomConnectionInvalidatesLateProbeResults() {
        fake.hold = true
        main {
            actions.onSaveJevCustom(JevCustom(JevProtocol.SYSTEMONE, "https://jev.example", "old"))
            selectProvider(JevProvider.CUSTOM)
            actions.onTestJevConnection()
        }
        waitFor { fake.calls.get() == 1 }
        main {
            actions.onSaveJevCustom(JevCustom(JevProtocol.DECISIONS, "https://jev.example", "new"))
            assertFalse(controller.state.value.settings.advanced.jevTesting)
            assertNull(message())
        }
        fake.release.countDown()
        assertTrue(fake.returned.await(3, TimeUnit.SECONDS))
        main { assertNull(message()); assertEquals("new", Prefs(application).jevConnection().model) }
    }

    /** 自定义平台的保存与绑定都会发出重建信号，其他平台不造成多余重建。 */
    @Test fun customCredentialChangesNotifySessionWithoutTouchingOtherPlatforms() = main {
        val prefs = Prefs(application)
        prefs.setJevCustom(JevCustom(JevProtocol.SYSTEMONE, "https://jev.example", "jev"))
        val platform = PlatformCredentials.forUrl("https://jev.example")!!.id
        val id = UUID.randomUUID().toString()
        var notifications = 0
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "jevKey:changed") notifications++ }
        prefs.listen(listener)
        try {
            prefs.savePlatformCredential(platform, id, "测试", "假密钥")
            assertTrue(notifications > 0)
            val afterSave = notifications
            prefs.selectCredential(platform, jevCredentialSlot(JevProvider.CUSTOM), id)
            assertTrue(notifications > afterSave)
            val afterSelect = notifications
            prefs.savePlatformCredential(PlatformCredentials.DEEPSEEK.id, UUID.randomUUID().toString(), "其他", "其他假密钥")
            assertEquals(afterSelect, notifications)
        } finally { prefs.unlisten(listener) }
    }

    /** 官方服务无已保存平台密钥时拒绝请求，提示实际配置入口。 */
    @Test fun officialWithoutKeyDoesNotCreateJudge() = main {
        selectProvider(JevProvider.OFFICIAL)
        actions.onTestJevConnection()
        assertEquals(0, factoryCalls.get())
        assertEquals(0, fake.calls.get())
        assertFalse(controller.state.value.settings.advanced.jevTesting)
        assertTrue(controller.state.value.settings.advanced.jevMessage!!.isError)
        assertTrue(controller.state.value.settings.advanced.jevMessage!!.text.contains("平台密钥"))
    }

    /** 已保存中央密钥的 HTTP 200 有效 noul 成功，示例日文只送入 unfinished。 */
    @Test fun validAnswerReportsHttpAndTimeWithoutEnablingJev() {
        main {
            selectProvider(JevProvider.OFFICIAL)
            savePlatformKey(JevProvider.OFFICIAL, "完整测试密钥")
            assertFalse(controller.state.value.settings.advanced.jevOn)
            assertFalse(controller.state.value.settings.advanced.jevCheck)
            actions.onTestJevConnection()
        }
        waitFor { message()?.text?.contains("HTTP 200") == true }
        main {
            val result = controller.state.value.settings.advanced
            assertFalse(result.jevTesting)
            assertFalse(result.jevMessage!!.isError)
            assertTrue(result.jevMessage!!.text.contains("37 毫秒"))
            assertEquals("このV何者気になるV、他とは違うVだから", fake.text)
            assertEquals(1, fake.calls.get())
            assertEquals(1, factoryCalls.get())
            synchronized(receivedKeys) {
                assertEquals(listOf(JevProvider.OFFICIAL), receivedProviders)
                assertEquals(listOf("完整测试密钥"), receivedKeys)
            }
            assertFalse(result.jevOn)
            assertFalse(result.jevCheck)
        }
    }

    /** 401、429、无效判断和网络失败分别给出可理解的错误，均不伪报成功。 */
    @Test fun errorsExplainAuthenticationLimitFormatAndNetwork() {
        main { selectProvider(JevProvider.OFFICIAL); savePlatformKey(JevProvider.OFFICIAL, "完整测试密钥") }
        val cases = listOf(
            Triple(JevCall("", 401, "", 17, null, null), "鉴权失败", "平台密钥"),
            Triple(JevCall("", 429, "", 18, null, null), "限流", "配额"),
            Triple(JevCall("", 200, "{}", 19, null, mapOf("choice" to "yes")), "未返回有效", "判断结果"),
            Triple(JevCall("", 0, null, 20, "timeout", null), "连接失败", "网络"),
        )
        cases.forEachIndexed { index, (reply, first, second) ->
            fake.reply = reply
            main { actions.onTestJevConnection() }
            waitFor { fake.calls.get() == index + 1 && !controller.state.value.settings.advanced.jevTesting &&
                message()?.text?.contains(first) == true }
            main {
                assertTrue(message()!!.isError)
                assertTrue(message()!!.text.contains(second))
            }
        }
        assertEquals(cases.size, factoryCalls.get())
    }

    /** 进行中重复点击只建立一次请求，主动取消后迟到的成功不能覆盖取消提示。 */
    @Test fun duplicateClickAndExplicitCancelIgnoreLateResult() {
        fake.hold = true
        main { actions.onTestJevConnection(); actions.onTestJevConnection() }
        waitFor { fake.calls.get() == 1 }
        main {
            assertTrue(controller.state.value.settings.advanced.jevTesting)
            assertEquals(1, factoryCalls.get())
            actions.onCancelJevTest()
            assertFalse(controller.state.value.settings.advanced.jevTesting)
            assertTrue(message()!!.text.contains("已取消"))
        }
        fake.release.countDown()
        assertTrue(fake.returned.await(3, TimeUnit.SECONDS))
        main { assertTrue(message()!!.text.contains("已取消")); assertTrue(fake.cancelCalls.get() >= 1) }
    }

    /** 切供应商、改中央密钥都取消旧请求，旧供应商结果不能写回新状态。 */
    @Test fun providerAndKeyChangesInvalidateInFlightResult() {
        fake.hold = true
        main { actions.onTestJevConnection() }
        waitFor { fake.calls.get() == 1 }
        main {
            selectProvider(JevProvider.OFFICIAL)
            assertFalse(controller.state.value.settings.advanced.jevTesting)
            assertNull(controller.state.value.settings.advanced.jevMessage)
        }
        fake.release.countDown()
        assertTrue(fake.returned.await(3, TimeUnit.SECONDS))
        main { assertNull(controller.state.value.settings.advanced.jevMessage) }

        fake = FakeJudge().apply { hold = true }
        main {
            savePlatformKey(JevProvider.OFFICIAL, "原密钥")
            actions.onTestJevConnection()
        }
        waitFor { fake.calls.get() == 1 }
        main {
            val platform = requireNotNull(PlatformCredentials.forUrl(JevProvider.OFFICIAL.url))
            actions.onPlatformKeyChange(platform.id, "新密钥")
            assertFalse(controller.state.value.settings.advanced.jevTesting)
            assertNull(controller.state.value.settings.advanced.jevMessage)
        }
        fake.release.countDown()
        assertTrue(fake.returned.await(3, TimeUnit.SECONDS))
        main { assertNull(controller.state.value.settings.advanced.jevMessage) }
        assertEquals(2, factoryCalls.get())
    }

    /** 导航离开与保存新的中央密钥都取消当前请求，旧结果不得覆盖新页面状态。 */
    @Test fun navigationAndKeySaveCancelOldProbe() {
        fake.hold = true
        main { actions.onTestJevConnection() }
        waitFor { fake.calls.get() == 1 }
        main {
            actions.onNavigate(UiDestination.LIVE)
            assertFalse(controller.state.value.settings.advanced.jevTesting)
            assertNull(message())
        }
        fake.release.countDown()
        assertTrue(fake.returned.await(3, TimeUnit.SECONDS))
        main { assertNull(message()) }

        fake = FakeJudge().apply { hold = true }
        main {
            selectProvider(JevProvider.OFFICIAL)
            savePlatformKey(JevProvider.OFFICIAL, "旧平台密钥")
            actions.onTestJevConnection()
        }
        waitFor { fake.calls.get() == 1 }
        main {
            savePlatformKey(JevProvider.OFFICIAL, "新平台密钥")
            assertFalse(controller.state.value.settings.advanced.jevTesting)
            assertNull(message())
        }
        fake.release.countDown()
        assertTrue(fake.returned.await(3, TimeUnit.SECONDS))
        main { assertNull(message()) }
        assertEquals(2, factoryCalls.get())
    }

    /** 销毁控制器时取消进行中的 Judge，请求结束后不再回写成功消息。 */
    @Test fun destroyingControllerCancelsInFlightJudge() {
        fake.hold = true
        main { actions.onTestJevConnection() }
        waitFor { fake.calls.get() == 1 }
        main { store.clear(); assertTrue(fake.cancelCalls.get() >= 1) }
        fake.release.countDown()
        assertTrue(fake.returned.await(3, TimeUnit.SECONDS))
        main { assertFalse(message()?.text?.contains("HTTP 200") == true) }
    }

    /** 切换当前 Jev 服务，控制器必须保存供应商选择。 */
    private fun selectProvider(provider: JevProvider) {
        actions.onAdvancedChange(controller.state.value.settings.advanced.copy(jevProviderId = provider.name))
        assertEquals(provider.name, controller.state.value.settings.advanced.jevProviderId)
    }

    /** 按生产动作保存该服务所属平台的唯一密钥。 */
    private fun savePlatformKey(provider: JevProvider, key: String) {
        val id = requireNotNull(PlatformCredentials.forUrl(provider.url)).id
        actions.onPlatformKeyChange(id, key)
        actions.onSavePlatformKey(id)
        assertEquals(key, Prefs(application).platformKey(id))
    }

    /** 读取 Jev 区域提示，不把其他全局消息误认为测试结果。 */
    private fun message() = controller.state.value.settings.advanced.jevMessage

    /** 所有控制器动作在主线程同步执行。 */
    private fun main(block: () -> Unit) { instrumentation.runOnMainSync(block) }

    /** 只等待受控内存状态，限时避免设备测试卡死。 */
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!condition()) {
            if (System.currentTimeMillis() >= deadline) throw AssertionError("等待 Jev 测试状态超时")
            Thread.sleep(10)
        }
    }

    /** 可阻塞返回的 Judge，用于验证取消后迟到结果不会污染界面。 */
    private class FakeJudge : Judge {
        val calls = AtomicInteger()
        val cancelCalls = AtomicInteger()
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        @Volatile var hold = false
        @Volatile var text: String? = null
        @Volatile var reply = JevCall("", 200, "{}", 37, null, mapOf("noul" to 0.7))

        /** 接受唯一短日文样本；阻塞时即使取消也等测试显式放行。 */
        override fun unfinished(text: String): JevCall {
            calls.incrementAndGet()
            this.text = text
            if (hold) while (true) {
                try { release.await(); break } catch (_: InterruptedException) { /* 等待测试释放迟到结果。 */ }
            }
            returned.countDown()
            return reply
        }

        /** 联通测试不能调用译文检查。 */
        override fun mistranslation(ja: String, zh: String): JevCall =
            throw AssertionError("联通测试不应调用译文检查")

        /** 记录取消，不替测试自动释放请求。 */
        override fun cancel() { cancelCalls.incrementAndGet() }
    }
}
