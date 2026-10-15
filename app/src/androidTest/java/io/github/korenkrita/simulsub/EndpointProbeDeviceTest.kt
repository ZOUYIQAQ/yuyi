package io.github.korenkrita.simulsub

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.ui.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/** 使用本地HTTPS夹具验证真实探测与人工确认，不读取用户API或修改已有模型。 */
class EndpointProbeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val token = UUID.randomUUID().toString()
    private val store = ViewModelStore()
    private val application = object : Application() {
        /** 模型仓库沿用真实上下文，但本测试不派发模型操作。 */
        override fun getApplicationContext(): Context = context.applicationContext
        /** 每个用例的服务配置独立，不能触及用户密钥。 */
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            context.getSharedPreferences("地址确认-$token-$name", mode)
        /** 记录索引只访问隔离目录，不访问用户记录。 */
        override fun getExternalFilesDir(type: String?): File = File(context.cacheDir, "地址确认-$token")
    }
    private lateinit var controller: AppController
    private lateinit var prefs: Prefs
    private lateinit var actions: SimulSubActions
    private val base = "https://10.0.2.2:18443/probe/no-v1"

    /** 只有明确启动本地夹具才执行真实网络测试。 */
    @Before fun prepare() {
        assumeTrue("需要专用本地HTTPS夹具", InstrumentationRegistry.getArguments().getString("runEndpointProbe") == "true")
        instrumentation.runOnMainSync {
            prefs = Prefs(application)
            prefs.saveByok(base, "本地探测验收", "fixture-key", "EndpointTest/1.0", listOf("X-Test-Key" to "fixture-header"))
            controller = AppController(application)
            store.put("地址确认", controller)
            actions = controller.actions({}, {}, {}, {})
        }
    }

    /** 清除协程与监听，保留失败现场且不删除任何用户数据。 */
    @After fun finish() { instrumentation.runOnMainSync { store.clear() } }

    /** 回退地址先作为建议，点击替换才持久化，并保留本次同源已测试凭据。 */
    @Test fun fallbackWaitsForConfirmationThenPersists() {
        testConnection()
        instrumentation.runOnMainSync {
            assertEquals(base, prefs.byokUrl)
            assertEquals("$base/chat/completions", controller.state.value.settings.api.suggestedUrl)
            assertNull(prefs.byokVariant("$base/v1/chat/completions", prefs.byokModel))
            actions.onApplyTestedApiUrl()
            assertEquals("$base/chat/completions", prefs.byokUrl)
            assertEquals("fixture-key", prefs.byokKey)
            assertEquals("EndpointTest/1.0", prefs.byokUserAgent)
            assertEquals(listOf("X-Test-Key" to "fixture-header"), prefs.byokHeaders)
            assertNotNull(prefs.byokVariant(prefs.byokUrl, prefs.byokModel))
            assertEquals("", controller.state.value.settings.api.suggestedUrl)
            val restored = AppController(application)
            store.put("地址恢复", restored)
            assertEquals("$base/chat/completions", restored.state.value.settings.api.url)
        }
    }

    /** 保持原地址按钮不写入建议，不会清空现有密钥或模型。 */
    @Test fun keepingOriginalDoesNotApplySuggestion() {
        testConnection()
        instrumentation.runOnMainSync {
            actions.onKeepApiUrl()
            actions.onApplyTestedApiUrl()
            assertEquals(base, prefs.byokUrl)
            assertEquals("", controller.state.value.settings.api.suggestedUrl)
            assertEquals("fixture-key", prefs.byokKey)
        }
    }

    /** 修改模型后旧建议失效，重复点击不能将旧配置恢复回来。 */
    @Test fun editingInvalidatesTheTestedConfiguration() {
        testConnection()
        instrumentation.runOnMainSync {
            actions.onApiDraftChange(controller.state.value.settings.api.copy(model = "新的模型"))
            assertEquals("", controller.state.value.settings.api.suggestedUrl)
            actions.onApplyTestedApiUrl()
            actions.onSaveApi()
            assertEquals("新的模型", prefs.byokModel)
            assertEquals(base, prefs.byokUrl)
        }
    }

    /** 显式完整自定义版本地址原样发出，测试成功也不会自行保存草稿。 */
    @Test fun explicitFullEndpointIsPreserved() {
        val full = "https://10.0.2.2:18443/probe/full/api/v3/chat/completions"
        instrumentation.runOnMainSync {
            actions.onApiDraftChange(controller.state.value.settings.api.copy(url = full, key = "fixture-key"))
        }
        testConnection()
        instrumentation.runOnMainSync {
            assertEquals(base, prefs.byokUrl)
            assertEquals(full, controller.state.value.settings.api.suggestedUrl)
            assertFalse(controller.state.value.settings.api.message!!.isError)
        }
    }

    /** 已存地址A时只测试草稿B，成功、等待和保持原址都不能把A改成B。 */
    @Test fun testingADifferentDraftNeverSavesWithoutConfirmation() {
        val saved = "https://10.0.2.2:18443/previous/v1"
        instrumentation.runOnMainSync {
            prefs.saveByok(saved, "原模型", "original-key", "Original/1.0", emptyList())
        }
        testConnection()
        instrumentation.runOnMainSync {
            assertEquals(saved, prefs.byokUrl)
            assertEquals("原模型", prefs.byokModel)
            assertEquals("original-key", prefs.byokKey)
            actions.onKeepApiUrl()
            assertEquals(saved, prefs.byokUrl)
            assertEquals("Original/1.0", prefs.byokUserAgent)
        }
    }

    /** 草稿测试失败时持久地址、模型与密钥必须全部仍为原配置。 */
    @Test fun failedDraftTestLeavesTheSavedConfigurationUntouched() {
        instrumentation.runOnMainSync {
            actions.onApiDraftChange(controller.state.value.settings.api.copy(url = "https://10.0.2.2:18443/probe/unavailable"))
            actions.onTestApi()
        }
        awaitTest()
        instrumentation.runOnMainSync {
            assertTrue(controller.state.value.settings.api.message!!.isError)
            assertEquals("", controller.state.value.settings.api.suggestedUrl)
            assertEquals(base, prefs.byokUrl)
            assertEquals("本地探测验收", prefs.byokModel)
            assertEquals("fixture-key", prefs.byokKey)
        }
    }

    /** 开始同传的动作会撤销尚未确认的建议，不能留待其他会话误用。 */
    @Test fun startingDiscardsPendingSuggestion() {
        testConnection()
        instrumentation.runOnMainSync {
            actions.onStart()
            assertEquals("", controller.state.value.settings.api.suggestedUrl)
            actions.onApplyTestedApiUrl()
            assertEquals(base, prefs.byokUrl)
        }
    }

    /** 等待真实网络结果并使用截止时间防止测试无限挂起。 */
    private fun testConnection() {
        instrumentation.runOnMainSync { actions.onTestApi(); assertTrue(controller.state.value.settings.api.testing) }
        awaitTest()
        instrumentation.runOnMainSync { assertFalse(controller.state.value.settings.api.message!!.isError) }
    }

    /** 成功或失败都在限定时间内结束，不把失败提示当作无限等待。 */
    private fun awaitTest() {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (true) {
            var testing = true
            instrumentation.runOnMainSync { testing = controller.state.value.settings.api.testing }
            if (!testing) break
            assertTrue("连接测试超时", System.nanoTime() < deadline)
            Thread.sleep(30)
        }
    }
}
