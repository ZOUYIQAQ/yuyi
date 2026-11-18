package io.github.korenkrita.simulsub

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.ui.PermissionUiState
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow

/** 用隔离配置和假密钥复现多服务首页的主线程开销，不联网、不访问用户配置。 */
class ControllerPerformanceDeviceTest {
    /** 拖动落点只保存坐标，不排队重建首页；真实尺寸变化仍同步字幕预览。 */
    @Test fun movingOverlayDoesNotRefreshServicesButResizingUpdatesPreview() {
        val app = isolatedApplication()
        val inst = InstrumentationRegistry.getInstrumentation()
        val store = ViewModelStore()
        lateinit var controller: AppController
        lateinit var prefs: Prefs
        inst.runOnMainSync {
            prefs = Prefs(app)
            controller = AppController(app)
            store.put("浮窗位置", controller)
        }
        try {
            inst.waitForIdleSync()
            inst.runOnMainSync {
                val scheduled = AppController::class.java.getDeclaredField("refreshJob").apply { isAccessible = true }
                val previous = scheduled.get(controller)
                prefs.overlayX = 100
                prefs.overlayY = 200
                prefs.saveOverlayBaseline(300, 1080, 2200)
                assertSame("抬手保存位置不能排队重建服务", previous, scheduled.get(controller))
                prefs.widthFrac = 0.7f
            }
            inst.waitForIdleSync()
            assertTrue("实际尺寸变化仍要同步预览", controller.state.value.settings.subtitle.widthFraction == 0.7f)
        } finally { inst.runOnMainSync { store.clear() } }
    }

    /** 前台恢复重复取得同一权限时不重建配置；实际授权变化仍立即更新首页。 */
    @Test fun repeatedPermissionSnapshotDoesNotRebuildConfiguration() {
        val app = isolatedApplication()
        val store = ViewModelStore()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val controller = AppController(app)
            store.put("权限刷新", controller)
            val before = controller.state.value
            repeat(10) { controller.setPermissions(before.live.permissions) }
            assertSame("同一系统授权快照不能重复重建页面", before, controller.state.value)
            val changed = before.live.permissions.map { if (it.id == "audio") it.copy(granted = !it.granted) else it }
            controller.setPermissions(changed)
            assertTrue(controller.state.value.live.permissions == changed)
            store.clear()
        }
    }

    /** 在线字幕密集更新只交给浮窗，首页已移除的字幕预览不能反复重建密钥和服务列表。 */
    @Test fun subtitleBurstsDoNotRebuildConfiguration() {
        val app = isolatedApplication()
        val inst = InstrumentationRegistry.getInstrumentation()
        val store = ViewModelStore()
        val field = CaptionService::class.java.getDeclaredField("sessionState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val sessions = field.get(null) as MutableStateFlow<CaptionService.Companion.SessionSnapshot>
        val previous = sessions.value
        lateinit var controller: AppController
        try {
            inst.runOnMainSync {
                controller = AppController(app)
                store.put("字幕突发", controller)
                sessions.value = CaptionService.Companion.SessionSnapshot(true, "正常听译中")
            }
            inst.waitForIdleSync()
            val settings = controller.state.value.settings
            val started = SystemClock.elapsedRealtime()
            repeat(60) { revision ->
                sessions.value = sessions.value.copy(captions = listOf(Caption(1, revision, false, "原文", "临时译文$revision",
                    Timing(0, 0, 0, 0, 0, 0, 0))))
                inst.waitForIdleSync()
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            assertSame("正文更新不应触碰设置快照", settings, controller.state.value.settings)
            Log.i("羽译性能回归", "60次在线正文更新及主线程响应：${elapsed}ms")
        } finally {
            inst.runOnMainSync { store.clear(); sessions.value = previous }
        }
    }

    /** 多次读取相同加密配置不能每次都往返系统密钥服务。 */
    @Test fun repeatedCredentialReadsRemainCheap() {
        val app = isolatedApplication()
        val prefs = Prefs(app)
        prefs.savePlatformKey("qwen", "性能测试专用假凭据")
        val started = SystemClock.elapsedRealtime()
        repeat(300) { assertTrue(prefs.platformKey("qwen").isNotEmpty()) }
        val elapsed = SystemClock.elapsedRealtime() - started
        Log.i("羽译性能回归", "相同凭据读取300次：${elapsed}ms")
        assertTrue("相同密文不应重复进行昂贵解密：${elapsed}ms", elapsed < 1_500)
    }

    /** 已配置的三种听译与翻译服务冷建控制器和连续刷新不能长时间阻塞触控。 */
    @Test fun configuredControllerAndRefreshStayResponsive() {
        val app = isolatedApplication()
        val prefs = Prefs(app)
        prefs.savePlatformKey("qwen", "性能测试千问假凭据")
        prefs.savePlatformKey("deepseek", "性能测试翻译假凭据")
        prefs.saveByok("https://api.deepseek.com/v1/chat/completions", "deepseek-flash", null)
        val store = ViewModelStore()
        val inst = InstrumentationRegistry.getInstrumentation()
        var creation = 0L
        var refreshes = 0L
        inst.runOnMainSync {
            val started = SystemClock.elapsedRealtime()
            val controller = AppController(app)
            store.put("性能测试", controller)
            creation = SystemClock.elapsedRealtime() - started
            val refreshing = SystemClock.elapsedRealtime()
            repeat(10) { controller.setPermissions(emptyList<PermissionUiState>()) }
            refreshes = SystemClock.elapsedRealtime() - refreshing
        }
        inst.runOnMainSync { store.clear() }
        Log.i("羽译性能回归", "控制器初始化：${creation}ms，配置刷新10次：${refreshes}ms")
        assertTrue("控制器初始化不能长时间阻塞首帧：${creation}ms", creation < 1_500)
        assertTrue("重复配置刷新不能长时间占用主线程：${refreshes}ms", refreshes < 1_000)
    }

    /** 所有偏好和日志目录都独立，系统模型与权限查询仅复用只读上下文。 */
    private fun isolatedApplication(): Application {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val token = "性能测试-${UUID.randomUUID()}"
        return object : Application() {
            /** 复用实际包名以保持系统能力查询正确。 */
            override fun getPackageName(): String = base.packageName
            /** 仓库只读取目标应用目录，不切换系统身份。 */
            override fun getApplicationContext(): Context = base.applicationContext
            /** 偏好按测试隔离，绝不覆盖正在开发的配置。 */
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$token-$name", mode)
            /** 日志索引使用隔离空目录。 */
            override fun getExternalFilesDir(type: String?): File = File(base.cacheDir, token)
        }
    }
}
