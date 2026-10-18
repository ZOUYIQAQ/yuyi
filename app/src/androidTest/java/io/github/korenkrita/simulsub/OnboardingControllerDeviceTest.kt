package io.github.korenkrita.simulsub

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.models.*
import io.github.korenkrita.simulsub.ui.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** 测试真实控制器的引导与持久化；偏好、记录和注入模型状态均与设备用户数据隔离。 */
class OnboardingControllerDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val token = UUID.randomUUID().toString()
    private val root = File(context.cacheDir, "引导控制器-$token")
    private val store = ViewModelStore()
    private val application = object : Application() {
        /** 沿用现有控制器测试的系统上下文，仅用于读取应用级依赖。 */
        override fun getApplicationContext(): Context = context.applicationContext
        /** 包名保持真实，不需要绑定测试 Application。 */
        override fun getPackageName(): String = context.packageName
        /** 每例独立存储，包括引导完成标记，不覆盖实际应用偏好。 */
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            context.getSharedPreferences("引导控制器-$token-$name", mode)
        /** 记录索引只读取隔离路径，不操作用户记录。 */
        override fun getExternalFilesDir(type: String?): File = File(root, "记录")
    }
    private lateinit var repository: ModelRepository
    private lateinit var controller: AppController
    private lateinit var actions: SimulSubActions
    private lateinit var prefs: Prefs
    private val missingPermissions = listOf(
        PermissionUiState("audio", "测试录音权限", "录音说明", false),
        PermissionUiState("overlay", "测试悬浮权限", "悬浮说明", false),
        PermissionUiState("notification", "测试通知权限", "可选说明", false, required = false),
    )

    /** 创建独立模型仓库并停止自动观察，再注入控制器；绝不修改共享仓库或调度任务。 */
    @Before fun prepare() {
        val isolatedContext = object : ContextWrapper(context) {
            /** 即使初始化仓库，也只能验证测试目录中的文件。 */
            override fun getFilesDir(): File = File(root, "模型存储")
            /** 仓库选择与删除历史同样使用隔离偏好。 */
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                application.getSharedPreferences(name, mode)
        }
        repository = ModelRepository::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(isolatedContext)
        val scope = repositoryField("scope").get(repository) as CoroutineScope
        runBlocking { requireNotNull(scope.coroutineContext[Job]).cancelAndJoin() }
        onMain {
            prefs = Prefs(application)
            publishModel(ModelStatus.NOT_INSTALLED)
            createController()
        }
    }

    /** 释放所有控制器监听和协程，不删除文件或改变共享模型仓库。 */
    @After fun finish() { onMain { store.clear() } }

    /** 首次出现四步本地引导，逐步跳过不保存接口或模型，最后回首页且重建不再自动出现。 */
    @Test fun firstLaunchFourSkipsPersistCompletionAndReturnLive() = onMain {
        assertStep(OnboardingStep.PERMISSIONS)
        assertFalse(completed())
        actions.onNavigate(UiDestination.SETTINGS)
        actions.onOnboardingSkip()
        assertStep(OnboardingStep.MODE)
        actions.onOnboardingNext()
        assertStep(OnboardingStep.API)
        assertFalse(completed())
        actions.onOnboardingSkip()
        assertStep(OnboardingStep.MODEL)
        assertFalse(completed())
        actions.onOnboardingSkip()
        assertNull(controller.state.value.onboarding)
        assertTrue(completed())
        assertEquals(UiDestination.LIVE, controller.state.value.destination)
        assertEquals("", prefs.byokUrl)
        assertEquals("", prefs.byokModel)
        assertFalse(prefs.keepLogs)
        assertNull(repository.selectedPaths())
        createController()
        assertNull(controller.state.value.onboarding)
        assertTrue(completed())
    }

    /** 未完成引导不能提前写完成标记；控制器重建后仍需走完或跳过剩余配置。 */
    @Test fun incompleteOnboardingDoesNotPersistCompletion() = onMain {
        actions.onOnboardingSkip()
        assertStep(OnboardingStep.MODE)
        actions.onOnboardingNext()
        assertStep(OnboardingStep.API)
        assertFalse(completed())
        createController()
        assertStep(OnboardingStep.PERMISSIONS)
        assertFalse(completed())
    }

    /** 已完成用户可以主动重开，保留已有接口；再次跳过完成仍回同传且不会自动复开。 */
    @Test fun restartExplicitlyReopensCompletedOnboarding() = onMain {
        repeat(4) { actions.onOnboardingSkip() }
        prefs.saveByok("https://example.com/v1", "已存模型", "", "", emptyList())
        actions.onNavigate(UiDestination.SETTINGS)
        actions.onRestartOnboarding()
        assertStep(OnboardingStep.PERMISSIONS)
        assertEquals("https://example.com/v1", prefs.byokUrl)
        assertTrue(completed())
        repeat(4) { actions.onOnboardingSkip() }
        assertNull(controller.state.value.onboarding)
        assertEquals(UiDestination.LIVE, controller.state.value.destination)
        createController()
        assertNull(controller.state.value.onboarding)
    }

    /** 下一步受必需权限约束；只授予部分权限不前进，可选通知未授予不阻断。 */
    @Test fun permissionNextRequiresEveryRequiredGrant() = onMain {
        actions.onOnboardingNext()
        assertStep(OnboardingStep.PERMISSIONS)
        controller.setPermissions(missingPermissions.map { if (it.id == "audio") it.copy(granted = true) else it })
        actions.onOnboardingNext()
        assertStep(OnboardingStep.PERMISSIONS)
        controller.setPermissions(missingPermissions.map { if (it.required) it.copy(granted = true) else it })
        actions.onOnboardingNext()
        assertStep(OnboardingStep.MODE)
        assertFalse(completed())
    }

    /** 空列表或仅可选通知都不能冒充必需授权，也不能因为已有接口而跳过首步。 */
    @Test fun emptyAndPartialPermissionSnapshotsCannotSkipFirstStep() = onMain {
        prefs.saveByok("https://example.com/v1", "已存模型", null, "", emptyList())
        createController()
        for (snapshot in listOf(emptyList(), missingPermissions.filter { !it.required },
            missingPermissions.filter { it.id == "audio" }.map { it.copy(granted = true) })) {
            controller.setPermissions(snapshot)
            val permissions = controller.state.value.live.permissions
            assertTrue(permissions.any { it.id == "audio" && it.required })
            assertTrue(permissions.any { it.id == "overlay" && it.required && !it.granted })
            actions.onOnboardingNext()
            assertStep(OnboardingStep.PERMISSIONS)
            assertFalse(controller.state.value.live.canStart)
            assertFalse(completed())
        }
        actions.onOnboardingSkip()
        assertStep(OnboardingStep.MODE)
        actions.onOnboardingNext()
        assertStep(OnboardingStep.API)
    }

    /** 尚未收到 Activity 回调时，控制器首个状态就包含系统实际录音与悬浮窗权限。 */
    @Test fun coldControllerReadsSystemPermissionsBeforeActivityCallback() = onMain {
        store.clear()
        val cold = AppController(application)
        store.put("冷启动权限", cold)
        val permissions = cold.state.value.live.permissions
        assertEquals(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED, permissions.single { it.id == "audio" }.granted)
        assertEquals(android.provider.Settings.canDrawOverlays(context), permissions.single { it.id == "overlay" }.granted)
        assertEquals(OnboardingStep.PERMISSIONS, cold.state.value.onboarding?.step)
    }

    /** 合法草稿不能冒充已存接口；显式保存后继续编辑仍受阻，放弃恢复后才允许前进。 */
    @Test fun apiNextRequiresSavedConfigurationWithoutDirtyDraft() = onMain {
        actions.onOnboardingSkip()
        actions.onOnboardingNext()
        actions.onApiDraftChange(controller.state.value.settings.api.copy(url = "https://example.com/v1", model = "待保存模型"))
        assertEquals("", prefs.byokUrl)
        assertFalse(controller.state.value.onboarding!!.apiReady)
        actions.onOnboardingNext()
        assertStep(OnboardingStep.API)
        actions.onSaveApi()
        assertTrue(controller.state.value.onboarding!!.apiReady)
        actions.onApiDraftChange(controller.state.value.settings.api.copy(model = "尚未保存的修改"))
        actions.onOnboardingNext()
        assertStep(OnboardingStep.API)
        actions.onDiscardApiDraft()
        actions.onOnboardingNext()
        assertStep(OnboardingStep.MODEL)
        assertEquals("待保存模型", prefs.byokModel)
    }

    /** 排队、下载、校验以及已装未启用均不能完成；选中的验证路径才允许进入首页。 */
    @Test fun modelNextRejectsPendingAndUnselectedModels() = onMain {
        actions.onOnboardingSkip()
        actions.onOnboardingSkip()
        actions.onOnboardingSkip()
        for (status in listOf(ModelStatus.NOT_INSTALLED, ModelStatus.QUEUED, ModelStatus.DOWNLOADING,
            ModelStatus.VERIFYING, ModelStatus.READY)) {
            publishModel(status)
            controller.setPermissions(missingPermissions)
            assertFalse("未启用模型不能就绪：$status", controller.state.value.onboarding!!.modelReady)
            actions.onOnboardingNext()
            assertStep(OnboardingStep.MODEL)
            assertFalse(completed())
        }
        publishModel(ModelStatus.READY, selected = true)
        controller.setPermissions(missingPermissions)
        assertTrue(controller.state.value.onboarding!!.modelReady)
        actions.onOnboardingNext()
        assertTrue(completed())
        assertNull(controller.state.value.onboarding)
        assertEquals(UiDestination.LIVE, controller.state.value.destination)
    }

    /** 在线引导省略本地模型及文字翻译接口，显式跳过后正常完成。 */
    @Test fun onlineBranchSkipsLocalSetupAndKeepsLocalConfiguration() = onMain {
        prefs.saveByok("https://example.com/v1", "旧文字模型", null)
        createController()
        val platform = controller.state.value.settings.platformKeys.single { it.id == "origin:https://example.com" }
        actions.onPlatformKeyChange(platform.id, "原密钥")
        actions.onSavePlatformKey(platform.id)
        actions.onOnboardingSkip()
        assertStep(OnboardingStep.MODE)
        actions.onListeningModeChange(ListeningMode.ONLINE)
        actions.onOnboardingNext()
        assertStep(OnboardingStep.ONLINE)
        actions.onOnboardingNext()
        assertStep(OnboardingStep.ONLINE)
        actions.onOnboardingSkip()
        assertTrue(completed())
        assertNull(controller.state.value.onboarding)
        assertEquals("旧文字模型", prefs.byokModel)
        assertEquals("原密钥", prefs.byokKey)
        assertNull(repository.selectedPaths())
        createController()
        assertEquals(ListeningMode.ONLINE, controller.state.value.live.listeningMode)
    }

    /** 平台密钥与在线连接分别保存；重建后共享密钥完整回填且磁盘不存明文。 */
    @Test fun savedOnlineConfigurationIsReadyWithoutLocalModelAndRefillsKey() = onMain {
        assertEquals(ListeningMode.LOCAL, prefs.listeningMode)
        actions.onListeningModeChange(ListeningMode.ONLINE)
        assertFalse(prefs.onlineReady)
        actions.onPlatformKeyChange("qwen", "test-yuyi-private-key")
        assertTrue(controller.state.value.settings.platformKeys.single { it.id == "qwen" }.hasUnsavedChanges)
        actions.onSavePlatformKey("qwen")
        actions.onOnlineDraftChange(controller.state.value.settings.online.copy(
            endpoint = "wss://llm-test.cn-beijing.maas.aliyuncs.com/realtime"))
        assertTrue(controller.state.value.settings.online.hasUnsavedChanges)
        assertTrue(prefs.onlineReady)
        assertNotEquals("wss://llm-test.cn-beijing.maas.aliyuncs.com/realtime", prefs.onlineEndpoint)
        actions.onSaveOnline()
        assertTrue(prefs.onlineReady)
        assertEquals("wss://llm-test.cn-beijing.maas.aliyuncs.com/realtime", prefs.onlineEndpoint)
        assertEquals("test-yuyi-private-key", controller.state.value.settings.online.key)
        assertFalse(controller.state.value.settings.online.hasUnsavedChanges)
        assertEquals("test-yuyi-private-key", controller.state.value.settings.cloudAsr.key)
        val stored = application.getSharedPreferences("jpsub", 0).getString("platformKey:qwen", "")
        assertNotEquals("test-yuyi-private-key", stored)
        controller.setPermissions(missingPermissions.map { it.copy(granted = true) })
        assertTrue(controller.state.value.live.canStart)
        assertNull(repository.selectedPaths())
        createController()
        assertEquals("test-yuyi-private-key", controller.state.value.settings.online.key)
        assertEquals("test-yuyi-private-key", controller.state.value.settings.platformKeys.single { it.id == "qwen" }.key)
        assertTrue(controller.state.value.settings.online.hasSavedConfiguration)
    }

    /** 无效连接不得覆盖已保存地址或共享密钥；控制器切模式仍保留草稿供界面确认。 */
    @Test fun invalidOnlineDraftCannotOverwriteSavedConfigurationOrLoseDraft() = onMain {
        actions.onListeningModeChange(ListeningMode.ONLINE)
        actions.onPlatformKeyChange("qwen", "saved-test-key")
        actions.onSavePlatformKey("qwen")
        actions.onOnlineDraftChange(controller.state.value.settings.online.copy(endpoint = "wss://llm-test.cn-beijing.maas.aliyuncs.com/realtime"))
        actions.onSaveOnline()
        val savedEndpoint = prefs.onlineEndpoint
        actions.onOnlineDraftChange(controller.state.value.settings.online.copy(endpoint = "http://example.com", key = "changed-key"))
        assertEquals("saved-test-key", controller.state.value.settings.online.key)
        actions.onSaveOnline()
        assertEquals(savedEndpoint, prefs.onlineEndpoint)
        assertEquals("saved-test-key", prefs.onlineKey)
        assertTrue(controller.state.value.settings.online.message!!.isError)
        actions.onListeningModeChange(ListeningMode.LOCAL)
        assertEquals(ListeningMode.LOCAL, prefs.listeningMode)
        assertEquals("http://example.com", controller.state.value.settings.online.endpoint)
        assertTrue(controller.state.value.settings.online.hasUnsavedChanges)
        actions.onDiscardOnlineDraft()
        assertEquals("saved-test-key", controller.state.value.settings.online.key)
        assertEquals(savedEndpoint, controller.state.value.settings.online.endpoint)
        assertFalse(controller.state.value.settings.online.hasUnsavedChanges)
        assertEquals(ListeningMode.LOCAL, prefs.listeningMode)
        assertEquals("saved-test-key", prefs.onlineKey)
    }

    /** 每次先销毁旧控制器再重建并注入隔离仓库，不调用下载、启用或其他外部动作。 */
    private fun createController() {
        store.clear()
        controller = AppController(application)
        AppController::class.java.getDeclaredField("models").apply { isAccessible = true }.set(controller, repository)
        store.put("引导控制器", controller)
        actions = controller.actions({}, {}, {}, {})
        controller.setPermissions(missingPermissions)
    }

    /** 只注入内存验证快照以测试控制器门槛，不建立虚假模型文件或修改共享状态。 */
    @Suppress("UNCHECKED_CAST")
    private fun publishModel(status: ModelStatus, selected: Boolean = false) {
        val spec = ModelCatalog.entries.first()
        repositoryField("verified").set(repository, if (selected) mapOf(spec.id to ModelPaths(spec, File(root, "仅内存路径"))) else emptyMap<String, ModelPaths>())
        val state = repositoryField("mutableState").get(repository) as MutableStateFlow<ModelLibraryState>
        state.value = ModelLibraryState(listOf(ModelEntry(spec, status)), if (selected) spec.id else null)
    }

    /** 反射仅用于测试依赖隔离，不为生产代码新增测试专用入口。 */
    private fun repositoryField(name: String) = ModelRepository::class.java.getDeclaredField(name).apply { isAccessible = true }

    /** 完成标记从独立偏好重新读取，不只依赖当前控制器的内存状态。 */
    private fun completed(): Boolean = application.getSharedPreferences("appearance", 0).getBoolean("onboarding_completed", false)

    /** 步骤断言直接检查真实控制器输出。 */
    private fun assertStep(step: OnboardingStep) { assertEquals(step, controller.state.value.onboarding?.step) }

    /** 业务动作在主线程执行，与实际界面回调的线程保持一致。 */
    private fun onMain(block: () -> Unit) { instrumentation.runOnMainSync { block() } }
}
