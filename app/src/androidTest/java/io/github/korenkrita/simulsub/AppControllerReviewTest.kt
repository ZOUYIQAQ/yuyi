package io.github.korenkrita.simulsub

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.ui.SimulSubActions
import io.github.korenkrita.simulsub.ui.UiMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/** 使用独立偏好和日志目录验证审阅问题，不启动录音、下载或真实字幕服务。 */
class AppControllerReviewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val token = UUID.randomUUID().toString()
    private val root = File(context.cacheDir, "审阅回归-$token")
    private val store = ViewModelStore()
    private lateinit var controller: AppController
    private lateinit var actions: SimulSubActions
    private lateinit var prefs: Prefs
    private var starts = 0
    private var stops = 0
    private val application = object : Application() {
        /** 模型库只读取真实应用上下文，不创建新的下载任务。 */
        override fun getApplicationContext(): Context = context.applicationContext

        /** 显式服务意图沿用应用包名，不依赖未绑定的测试 Application。 */
        override fun getPackageName(): String = context.packageName

        /** 每个测试使用全新偏好，避免覆盖设备上的用户配置。 */
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            context.getSharedPreferences("审阅回归-$token-$name", mode)

        /** 删除动作只能访问本测试创建的独立记录目录。 */
        override fun getExternalFilesDir(type: String?): File = root

        /** 停止动作仅记录调用，不干扰主代理的设备验收。 */
        override fun startService(service: Intent): ComponentName? { stops++; return service.component }
    }

    /** 在主线程创建真实控制器，使用隔离的系统动作。 */
    @Before fun prepare() {
        instrumentation.runOnMainSync {
            prefs = Prefs(application)
            controller = AppController(application)
            store.put("审阅回归", controller)
            actions = controller.actions({ starts++ }, {}, {}, {})
        }
        awaitRecordOperation()
    }

    /** 释放监听和协程，不清理任何已有文件。 */
    @After fun finish() { instrumentation.runOnMainSync { store.clear() } }

    /** 带密钥的服务切换后必须丢弃草稿，保存不能覆盖新服务的独立密钥。 */
    @Test fun switchingProviderDropsUnsavedKey() {
        instrumentation.runOnMainSync {
            prefs.jevProvider = JevProvider.OFFICIAL
            prefs.setJevKey(JevProvider.OFFICIAL, "official-saved")
            prefs.setJevKey(JevProvider.OPENROUTER, "router-saved")
            actions.onAdvancedChange(controller.state.value.settings.advanced.copy(jevProviderId = JevProvider.OFFICIAL.name))
            actions.onAdvancedChange(controller.state.value.settings.advanced.copy(jevKey = "official-draft", jevMessage = UiMessage("旧服务提示")))
            actions.onAdvancedChange(controller.state.value.settings.advanced.copy(jevProviderId = JevProvider.OPENROUTER.name))
            assertNull("切换服务后不应保留旧密钥草稿", controller.state.value.settings.advanced.jevKey)
            assertNull("切换服务后不应保留旧提示", controller.state.value.settings.advanced.jevMessage)
            actions.onSaveJevKey()
            assertEquals("router-saved", prefs.jevKey(JevProvider.OPENROUTER))
            assertEquals("official-saved", prefs.jevKey(JevProvider.OFFICIAL))
            actions.onAdvancedChange(controller.state.value.settings.advanced.copy(jevProviderId = JevProvider.ZEN.name, jevKey = "router-draft"))
            actions.onSaveJevKey()
            assertNull("免密钥服务不能收到其他服务的草稿", prefs.jevKey(JevProvider.ZEN))
        }
    }

    /** 开始同传先取消连接测试，再派发开始动作，晚到结果不能覆盖后续草稿。 */
    @Test fun startingCancelsProbeBeforeRequestingCapture() { verifyProbeCancellation(true) }

    /** 停止同传同样释放测试状态，后续编辑不会被旧探测回写。 */
    @Test fun stoppingCancelsProbeBeforeStoppingCapture() { verifyProbeCancellation(false) }

    /** 同一主线程轮次触发探测与取消，确保旧结果没有机会提前提交。 */
    private fun verifyProbeCancellation(start: Boolean) {
        lateinit var translator: ByokTranslator
        lateinit var job: Job
        instrumentation.runOnMainSync {
            actions.onApiDraftChange(controller.state.value.settings.api.copy(url = "https://127.0.0.1:1/v1", model = "审阅测试"))
            actions.onTestApi()
            assertTrue("测试应已进入进行状态", controller.state.value.settings.api.testing)
            translator = AppController::class.java.getDeclaredField("probe").apply { isAccessible = true }.get(controller) as ByokTranslator
            job = AppController::class.java.getDeclaredField("probeJob").apply { isAccessible = true }.get(controller) as Job
            if (start) actions.onStart() else actions.onStop()
            assertFalse("开始或停止后必须立即结束测试状态", controller.state.value.settings.api.testing)
            assertTrue("取消后应能再次测试", controller.state.value.settings.api.canTest)
            assertTrue("旧探测协程必须被取消", job.isCancelled)
            actions.onApiDraftChange(controller.state.value.settings.api.copy(model = "新的模型", message = UiMessage("新的草稿")))
        }
        runBlocking { withTimeout(5_000) { job.join() } }
        val steps = mutableListOf<ByokTranslator.Step>()
        assertNull("旧翻译器不可再次完成探测", translator.probe(steps))
        assertEquals("cancelled", steps.single().result.error)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals("新的模型", controller.state.value.settings.api.model)
            assertEquals("新的草稿", controller.state.value.settings.api.message?.text)
            assertEquals(if (start) 1 else 0, starts)
            assertEquals(if (start) 0 else 1, stops)
        }
    }

    /** 保存新的服务配置必须取消旧探测，后续回调不能改回模型或探测参数。 */
    @Test fun savingNewConfigurationCancelsTheOldProbe() {
        lateinit var oldJob: Job
        instrumentation.runOnMainSync {
            actions.onApiDraftChange(controller.state.value.settings.api.copy(url = "https://127.0.0.1:1/v1", model = "旧模型"))
            actions.onTestApi()
            oldJob = AppController::class.java.getDeclaredField("probeJob").apply { isAccessible = true }.get(controller) as Job
            actions.onApiDraftChange(controller.state.value.settings.api.copy(url = "https://127.0.0.1:2/v1", model = "新模型"))
            actions.onSaveApi()
            assertTrue("修改配置必须取消旧探测", oldJob.isCancelled)
        }
        runBlocking { withTimeout(5_000) { oldJob.join() } }
        instrumentation.runOnMainSync {
            assertEquals("新模型", prefs.byokModel)
            assertEquals("新模型", controller.state.value.settings.api.model)
            assertEquals("翻译配置已保存", controller.state.value.settings.api.message?.text)
            assertNull(prefs.byokVariant("https://127.0.0.1:1/v1/chat/completions", "旧模型"))
        }
    }

    /** 单条删除和全部清空均通过真实日志接口执行，活动记录始终保留。 */
    @Test fun deletingRecordsKeepsTheActiveSession() {
        val active = SessionLog(SessionLog.dir(root))
        val finished = SessionLog(SessionLog.dir(root)).also { assertTrue(it.close()) }
        try {
            instrumentation.runOnMainSync { actions.onDeleteRecord(active.file.name) }
            awaitRecordOperation()
            assertTrue("活动记录不能删除", active.file.exists())
            instrumentation.runOnMainSync { actions.onDeleteRecord(finished.file.name) }
            awaitRecordOperation()
            assertFalse("已结束记录应可单独删除", finished.file.exists())
            val another = SessionLog(SessionLog.dir(root)).also { assertTrue(it.close()) }
            instrumentation.runOnMainSync { actions.onDeleteAllRecords() }
            awaitRecordOperation()
            assertFalse("全部清空应删除已结束记录", another.file.exists())
            assertTrue("全部清空不能删除活动记录", active.file.exists())
            instrumentation.runOnMainSync { actions.onDeleteRecord("../无关文件") }
            awaitRecordOperation()
            assertTrue("无效标识不能影响活动记录", active.file.exists())
        } finally { assertTrue(active.close()) }
    }

    /** 等待记录操作完成，使用明确截止时间防止测试卡死。 */
    private fun awaitRecordOperation() {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            var done = false
            instrumentation.runOnMainSync { done = !controller.state.value.settings.recordsLoading }
            if (done) return
            Thread.sleep(20)
        }
        fail("读取或删除记录超时")
    }
}
