package io.github.korenkrita.simulsub

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.ui.*
import io.github.korenkrita.simulsub.updates.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/** 更新检查仅使用可控制的本地响应，独立偏好不触及用户真实配置。 */
class UpdateSettingsControllerDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val id = UUID.randomUUID().toString()
    private val store = ViewModelStore()
    private var now = 1_000L
    private lateinit var controller: AppController
    private lateinit var actions: SimulSubActions
    private val opened = mutableListOf<String>()
    private val replies = Channel<CompletableDeferred<AvailableUpdate?>>(Channel.UNLIMITED)
    @Volatile private var calls = 0
    private val source = object : ReleaseUpdateSource {
        /** 每个请求等待测试显式给出结果，允许验证取消和迟到响应。 */
        override suspend fun latest(): AvailableUpdate? { calls++; return replies.receive().await() }
    }
    private val application = object : Application() {
        /** 模型读取沿用实际上下文，不发起下载。 */
        override fun getApplicationContext(): Context = context.applicationContext
        /** 每个测试使用独立配置命名空间。 */
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = context.getSharedPreferences("更新回归-$id-$name", mode)
        /** 日志索引不访问用户已有文件。 */
        override fun getExternalFilesDir(type: String?): File = File(context.cacheDir, "更新回归-$id")
    }
    private val release = AvailableUpdate("v99.1.0", "99.1.0", "字幕显示和更新设置改进", "https://github.com/ZOUYIQAQ/yuyi/releases/tag/v99.1.0", "https://github.com/ZOUYIQAQ/yuyi/releases/download/v99.1.0/yuyi-arm64.apk")

    /** 控制器按生产路径创建，时钟和网络来源可控。 */
    @Before fun prepare() { main {
        application.getSharedPreferences("appearance", 0).edit().putBoolean("onboarding_completed", true).commit()
        createController()
    } }
    /** 只释放本测试的控制器。 */
    @After fun finish() { main { store.clear() } }

    /** 每次启动检查一次；同一控制器返回前台或旋转不重复。 */
    @Test fun launchChecksOnceAndReturnsActualRelease() {
        val result = queue()
        main { controller.onAppStarted(); controller.onAppStarted() }
        waitFor { calls == 1 }
        result.complete(release)
        waitFor { controller.state.value.settings.updates.prompt != null }
        main { assertTrue(controller.state.value.settings.updates.autoCheck); assertEquals("99.1.0", controller.state.value.settings.updates.latest?.versionName) }
    }
    /** 自动检查关闭后持久化，启动不请求，手动仍可检查和提醒。 */
    @Test fun disabledAutoSurvivesRecreationButManualStillWorks() {
        main { actions.onAutoUpdateChange(false); store.clear(); createController(); controller.onAppStarted() }
        Thread.sleep(120)
        assertEquals(0, calls)
        val result = queue()
        main { actions.onCheckUpdate() }
        waitFor { calls == 1 }; result.complete(release)
        waitFor { controller.state.value.settings.updates.prompt != null }
        main { assertFalse(controller.state.value.settings.updates.autoCheck) }
    }
    /** 关闭自动检查会取消本次自动请求，迟到结果不能重新打开提示。 */
    @Test fun turningOffCancelsAutomaticResult() {
        val result = queue()
        main { controller.onAppStarted() }; waitFor { calls == 1 }
        main { actions.onAutoUpdateChange(false) }
        result.complete(release); Thread.sleep(150)
        main { assertNull(controller.state.value.settings.updates.prompt); assertFalse(controller.state.value.settings.checkingUpdate) }
    }
    /** 跳过仅作用于本版本，重新创建后也保持；手动检查可再次展示并恢复提醒。 */
    @Test fun skippedVersionPersistsAndManualOverridesIt() {
        offer()
        main { actions.onSkipUpdate(); assertEquals("99.1.0", controller.state.value.settings.updates.skippedVersion); store.clear(); createController() }
        val result = queue(); main { controller.onAppStarted() }; waitFor { calls == 2 }; result.complete(release)
        waitFor { !controller.state.value.settings.checkingUpdate }
        main { assertNull(controller.state.value.settings.updates.prompt) }
        val manual = queue(); main { actions.onCheckUpdate() }; waitFor { calls == 3 }; manual.complete(release)
        waitFor { controller.state.value.settings.updates.prompt != null }
        main { actions.onRestoreUpdateReminders(); assertEquals("", controller.state.value.settings.updates.skippedVersion) }
    }
    /** 稍后提醒跨重建生效，二十四小时之后才恢复自动提示。 */
    @Test fun laterExpiresAfterOneDay() {
        offer(); main { actions.onLaterUpdate(); store.clear(); createController() }
        val before = queue(); main { controller.onAppStarted() }; waitFor { calls == 2 }; before.complete(release)
        waitFor { !controller.state.value.settings.checkingUpdate }
        main { assertNull(controller.state.value.settings.updates.prompt); now += 24 * 60 * 60 * 1000L; store.clear(); createController() }
        val after = queue(); main { controller.onAppStarted() }; waitFor { calls == 3 }; after.complete(release)
        waitFor { controller.state.value.settings.updates.prompt != null }
    }
    /** 点击更新只打开浏览器目标，不启动应用内下载或安装；无APK时使用发布页。 */
    @Test fun browserActionChoosesApkOrReleasePage() {
        offer(); main { actions.onOpenUpdate(); assertEquals(listOf(release.downloadUrl), opened); assertNull(controller.state.value.settings.updates.prompt) }
        val second = queue(); main { actions.onCheckUpdate() }; waitFor { calls == 2 }; second.complete(release.copy(downloadUrl = null))
        waitFor { controller.state.value.settings.updates.prompt != null }
        main { actions.onOpenUpdate(); assertEquals(release.releaseUrl, opened.last()) }
    }
    /** 首次引导完成前不发自动请求，明确跳过最后一步后才检查。 */
    @Test fun onboardingDefersNetworkUntilFinished() {
        main { store.clear(); application.getSharedPreferences("appearance", 0).edit().putBoolean("onboarding_completed", false).commit(); createController(); controller.onAppStarted() }
        Thread.sleep(120); assertEquals(0, calls)
        val result = queue(); main { repeat(3) { actions.onOnboardingSkip() } }
        waitFor { calls == 1 }; result.complete(release)
        waitFor { controller.state.value.settings.updates.prompt != null }
    }
    /** 同版本和未发布均有明确手动结果，自动失败安静留在设置而不弹更新对话框。 */
    @Test fun noReleaseCurrentVersionAndFailureHaveDistinctMessages() {
        val absent = queue(); main { actions.onCheckUpdate() }; waitFor { calls == 1 }; absent.complete(null)
        waitFor { !controller.state.value.settings.checkingUpdate }
        main { assertTrue(controller.state.value.settings.updateMessage.contains("尚未发布")); assertNull(controller.state.value.settings.updates.latest) }
        val current = queue(); main { actions.onCheckUpdate() }; waitFor { calls == 2 }; current.complete(release.copy(versionName = BuildConfig.VERSION_NAME))
        waitFor { !controller.state.value.settings.checkingUpdate }
        main { assertTrue(controller.state.value.settings.updateMessage.contains("最新版本")); assertNull(controller.state.value.settings.updates.prompt) }
        val failure = queue(); main { controller.onAppStarted() }; waitFor { calls == 3 }; failure.completeExceptionally(java.io.IOException("测试网络不可用"))
        waitFor { !controller.state.value.settings.checkingUpdate }
        main { assertNull(controller.state.value.message); assertNull(controller.state.value.settings.updates.prompt); assertTrue(controller.state.value.settings.updates.checkFailed) }
    }

    /** 新检查失败后不能把上次的版本操作伪装成这次的结果。 */
    @Test fun failedRecheckClearsPreviousReleaseActions() {
        offer(); main { actions.onLaterUpdate() }
        val failure = queue(); main { actions.onCheckUpdate() }
        waitFor { calls == 2 }
        main { assertNull(controller.state.value.settings.updates.latest) }
        failure.completeExceptionally(java.io.IOException("本次检查失败"))
        waitFor { !controller.state.value.settings.checkingUpdate }
        main { assertTrue(controller.state.value.settings.updates.checkFailed); assertNull(controller.state.value.settings.updates.latest) }
    }

    /** 浏览器选择器保留APK原始URL，避免被GitHub应用链接处理器接管。 */
    @Test fun updateIntentSelectsBrowserAndPreservesDownloadUrl() {
        val intent = updateBrowserIntent(release.downloadUrl!!)
        assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
        assertEquals(release.downloadUrl, intent.dataString)
        assertTrue(intent.hasCategory(android.content.Intent.CATEGORY_BROWSABLE))
        assertEquals(android.content.Intent.ACTION_MAIN, intent.selector?.action)
        assertTrue(intent.selector!!.hasCategory(android.content.Intent.CATEGORY_APP_BROWSER))
        val declared = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse("浏览器更新不应要求应用安装权限", declared.contains(android.Manifest.permission.REQUEST_INSTALL_PACKAGES))
    }

    /** 用测试来源新建控制器，所有链接都只记录而不打开真实浏览器。 */
    private fun createController() {
        controller = AppController(application, source) { now }; store.put("更新回归", controller)
        actions = controller.actions({}, {}, {}, { opened += it })
    }
    /** 队列响应只供紧接着的一个请求消费。 */
    private fun queue() = CompletableDeferred<AvailableUpdate?>().also { replies.trySend(it).getOrThrow() }
    /** 先得到一个实际更新提示供提醒操作验证。 */
    private fun offer() { val result = queue(); main { controller.onAppStarted() }; waitFor { calls == 1 }; result.complete(release); waitFor { controller.state.value.settings.updates.prompt != null } }
    /** 所有控制器修改均在主线程执行。 */
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    /** 有界等待真实协程状态，失败不会无限挂起。 */
    private fun waitFor(condition: () -> Boolean) { val end = System.currentTimeMillis() + 5000; while (!condition()) { assertTrue("更新状态等待超时", System.currentTimeMillis() < end); Thread.sleep(20) } }
}
