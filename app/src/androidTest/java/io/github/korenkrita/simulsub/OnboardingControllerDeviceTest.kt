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

    /** 三页依次跳过后回首页并持久化完成；模板可预填，但不保存密钥、下载或启用模型。 */
    @Test fun firstLaunchThreeSkipsPersistCompletionAndReturnLive() = onMain {
        assertStep(OnboardingStep.PERMISSIONS)
        assertFalse(completed())
        actions.onNavigate(UiDestination.SETTINGS)
        actions.onOnboardingSkip()
        assertStep(OnboardingStep.MODE)
        actions.onOnboardingSkip()
        assertStep(OnboardingStep.API)
        assertFalse(completed())
        assertFalse(application.getSharedPreferences("appearance", 0).getBoolean("onboarding_local_requested", false))
        actions.onOnboardingSkip()
        assertNull(controller.state.value.onboarding)
        assertTrue(completed())
        assertEquals(UiDestination.LIVE, controller.state.value.destination)
        assertTrue(prefs.byokKey.isNullOrEmpty())
        assertFalse(prefs.byokReady)
        assertFalse(prefs.keepLogs)
        assertNull(repository.selectedPaths())
        assertTrue(pendingDownloads().isEmpty())
        createController()
        assertNull(controller.state.value.onboarding)
        assertTrue(completed())
    }

    /** 跳过方式选择不偷偷下载；配置页明确点击下载后仍记住自动启用意图。 */
    @Test fun explicitDownloadAfterSkippingRouteEnablesSelectionOnCompletion() = onMain {
        actions.onOnboardingSkip()
        actions.onOnboardingSkip()
        assertStep(OnboardingStep.API)
        assertFalse(application.getSharedPreferences("appearance", 0).getBoolean("onboarding_local_requested", false))
        assertTrue(pendingDownloads().isEmpty())
        actions.onDownloadModel("reazonspeech")
        assertTrue(application.getSharedPreferences("appearance", 0).getBoolean("onboarding_local_requested", false))
        assertTrue(pendingDownloads().contains("reazonspeech"))
    }

    /** 未完成引导不能提前写完成标记；控制器重建后仍需走完或跳过剩余配置。 */
    @Test fun incompleteOnboardingDoesNotPersistCompletion() = onMain {
        actions.onOnboardingSkip()
        assertStep(OnboardingStep.MODE)
        actions.onOnboardingSkip()
        assertStep(OnboardingStep.API)
        assertFalse(completed())
        createController()
        assertStep(OnboardingStep.PERMISSIONS)
        assertFalse(completed())
    }

    /** 已完成用户可以主动重开，保留已有接口；再次跳过完成仍回同传且不会自动复开。 */
    @Test fun restartExplicitlyReopensCompletedOnboarding() = onMain {
        repeat(3) { actions.onOnboardingSkip() }
        prefs.saveByok("https://example.com/v1", "已存模型", "", "", emptyList())
        actions.onNavigate(UiDestination.SETTINGS)
        actions.onRestartOnboarding()
        assertStep(OnboardingStep.PERMISSIONS)
        assertEquals("https://example.com/v1", prefs.byokUrl)
        assertTrue(completed())
        repeat(3) { actions.onOnboardingSkip() }
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
        actions.onOnboardingSkip()
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

    /** 选择本地仅进入配置，不自动下载；只有模型与翻译均就绪后才能完成。 */
    @Test fun localRouteWaitsForExplicitDownloadAndAutosavesTranslationKeyWithoutCompletingEarly() = onMain {
        actions.onOnboardingSkip()
        actions.onChooseOnboardingRoute(ListeningMode.LOCAL)
        assertStep(OnboardingStep.API)
        assertEquals(ListeningMode.LOCAL, prefs.listeningMode)
        assertEquals("local:reazonspeech", controller.state.value.live.listeningServiceId)
        assertFalse(application.getSharedPreferences("appearance", 0).getBoolean("onboarding_local_requested", false))
        assertTrue(pendingDownloads().isEmpty())
        val setup = controller.state.value.onboarding!!.setup
        assertEquals("deepseek", setup.providerId)
        assertTrue(setup.providers.isNotEmpty())
        assertTrue(setup.models.any { it.id == setup.model })
        assertFalse(setup.keySaved)
        assertFalse(controller.state.value.onboarding!!.apiReady)
        actions.onOnboardingKeyChange("测试本地自动保存密钥")
        assertEquals("测试本地自动保存密钥", prefs.platformKey("deepseek"))
        assertEquals("测试本地自动保存密钥", prefs.byokKey)
        assertTrue(controller.state.value.onboarding!!.setup.keySaved)
        assertTrue(controller.state.value.onboarding!!.apiReady)
        assertFalse(controller.state.value.settings.api.hasUnsavedChanges)
        actions.onOnboardingNext()
        assertStep(OnboardingStep.API)
        assertFalse(completed())
        assertFalse(controller.state.value.onboarding!!.modelReady)
    }

    /** 同页完成必须同时具备翻译密钥、已校验且选中的 ReazonSpeech；别的已选模型不能代替。 */
    @Test fun localCompletionRejectsPendingUnselectedAndDifferentModels() = onMain {
        actions.onOnboardingSkip()
        actions.onChooseOnboardingRoute(ListeningMode.LOCAL)
        actions.onOnboardingKeyChange("测试模型门槛密钥")
        for (status in listOf(ModelStatus.NOT_INSTALLED, ModelStatus.QUEUED, ModelStatus.DOWNLOADING,
            ModelStatus.VERIFYING, ModelStatus.READY)) {
            publishModel(status)
            controller.setPermissions(missingPermissions)
            assertFalse("未启用模型不能就绪：$status", controller.state.value.onboarding!!.modelReady)
            actions.onOnboardingNext()
            assertStep(OnboardingStep.API)
            assertFalse(completed())
        }
        publishModel(ModelStatus.READY, selected = true, modelId = "sensevoice")
        controller.setPermissions(missingPermissions)
        assertFalse(controller.state.value.onboarding!!.modelReady)
        actions.onOnboardingNext()
        assertStep(OnboardingStep.API)
        publishModel(ModelStatus.READY, selected = true)
        controller.setPermissions(missingPermissions)
        assertTrue(controller.state.value.onboarding!!.modelReady)
        actions.onOnboardingKeyChange("")
        assertFalse(controller.state.value.onboarding!!.apiReady)
        actions.onOnboardingNext()
        assertStep(OnboardingStep.API)
        actions.onOnboardingKeyChange("测试模型门槛密钥")
        actions.onOnboardingNext()
        assertTrue(completed())
        assertNull(controller.state.value.onboarding)
        assertEquals(UiDestination.LIVE, controller.state.value.destination)
    }

    /** 在线路线固定实时模型，不动已有文字翻译或本地模型；无密钥可跳过且不要求测试成功。 */
    @Test fun onlineRouteKeepsLocalConfigurationAndCanBeSkippedWithoutKey() = onMain {
        prefs.saveByok("https://example.com/v1", "旧文字模型", "原密钥")
        createController()
        actions.onOnboardingSkip()
        actions.onChooseOnboardingRoute(ListeningMode.ONLINE)
        assertStep(OnboardingStep.ONLINE)
        assertEquals(ListeningMode.ONLINE, prefs.listeningMode)
        assertEquals(io.github.korenkrita.simulsub.online.LiveTranslateModels.REALTIME_ENDPOINT, prefs.onlineEndpoint)
        assertEquals(io.github.korenkrita.simulsub.online.LiveTranslateModels.REALTIME_ID, prefs.onlineModel)
        assertFalse(controller.state.value.onboarding!!.onlineReady)
        actions.onOnboardingNext()
        assertStep(OnboardingStep.ONLINE)
        assertTrue(pendingDownloads().isEmpty())
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
        actions.onOnlineDraftChange(controller.state.value.settings.online.copy(endpoint = io.github.korenkrita.simulsub.online.LiveTranslateModels.REALTIME_ENDPOINT))
        actions.onSaveOnline()
        val savedEndpoint = prefs.onlineEndpoint
        actions.onOnlineDraftChange(controller.state.value.settings.online.copy(endpoint = "http://example.com", key = "changed-key"))
        // 换到其他来源的草稿不会展示千问密钥；已保存的千问连接及其密钥仍完整保留。
        assertEquals("", controller.state.value.settings.online.key)
        assertEquals("saved-test-key", prefs.platformKey("qwen"))
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

    /** 在线密钥即时写入共享凭据并加密，未发起连接测试也可完成，重建仍完整回填。 */
    @Test fun onlineKeyAutosavesAndCompletesWithoutTesting() = onMain {
        actions.onOnboardingSkip()
        actions.onChooseOnboardingRoute(ListeningMode.ONLINE)
        actions.onOnboardingKeyChange("测试在线自动保存密钥")
        val guide = controller.state.value.onboarding!!
        assertTrue(guide.onlineReady)
        assertTrue(guide.setup.keySaved)
        assertFalse(guide.setup.testing)
        assertEquals("测试在线自动保存密钥", guide.setup.key)
        assertEquals("测试在线自动保存密钥", prefs.platformKey("qwen"))
        assertEquals("测试在线自动保存密钥", controller.state.value.settings.cloudAsr.key)
        val stored = application.getSharedPreferences("jpsub", 0).all.values.joinToString()
        assertFalse("隔离偏好不得明文存储测试密钥", stored.contains("测试在线自动保存密钥"))
        actions.onOnboardingNext()
        assertTrue(completed())
        assertNull(controller.state.value.onboarding)
        createController()
        assertEquals("测试在线自动保存密钥", controller.state.value.settings.online.key)
        assertNull(repository.selectedPaths())
    }

    /** 供应商各自保留模型及共享密钥，非法供应商和模型不能覆盖已存配置。 */
    @Test fun localProviderAndModelSelectionKeepCredentialsSeparated() = onMain {
        actions.onOnboardingSkip()
        actions.onChooseOnboardingRoute(ListeningMode.LOCAL)
        actions.onOnboardingKeyChange("测试 DeepSeek 密钥")
        val deepseekModel = controller.state.value.onboarding!!.setup.model
        actions.onOnboardingProviderChange("qwen")
        assertEquals("qwen", controller.state.value.onboarding!!.setup.providerId)
        assertTrue(controller.state.value.onboarding!!.setup.key.isEmpty())
        assertFalse(controller.state.value.onboarding!!.setup.keySaved)
        val chosen = controller.state.value.onboarding!!.setup.models.last().id
        actions.onOnboardingModelChange(chosen)
        actions.onOnboardingKeyChange("测试千问共享密钥")
        assertEquals(chosen, prefs.byokModel)
        assertEquals("测试千问共享密钥", prefs.platformKey("qwen"))
        actions.onOnboardingProviderChange("deepseek")
        assertEquals(deepseekModel, controller.state.value.onboarding!!.setup.model)
        assertEquals("测试 DeepSeek 密钥", controller.state.value.onboarding!!.setup.key)
        val before = controller.state.value.onboarding!!.setup
        actions.onOnboardingProviderChange("不存在的供应商")
        actions.onOnboardingModelChange("不在预制目录中的模型")
        assertEquals(before, controller.state.value.onboarding!!.setup)
        actions.onOnboardingProviderChange("qwen")
        assertEquals(chosen, controller.state.value.onboarding!!.setup.model)
        assertEquals("测试千问共享密钥", controller.state.value.onboarding!!.setup.key)
        actions.onOnboardingKeyChange("")
        assertFalse(controller.state.value.onboarding!!.setup.keySaved)
        assertFalse(controller.state.value.onboarding!!.apiReady)
        assertEquals("测试 DeepSeek 密钥", prefs.platformKey("deepseek"))
    }

    /** 初次扫描已有模型不弹成功提示，真正下载并校验完成只提示一次。 */
    @Test fun completionNoticeRequiresDownloadAndNeverRepeatsOnOrdinaryRefresh() = onMain {
        publishModel(ModelStatus.READY, selected = true)
        announceDownloadState()
        assertNull(controller.state.value.message)
        publishModel(ModelStatus.DOWNLOADING)
        announceDownloadState()
        assertNull(controller.state.value.message)
        publishModel(ModelStatus.VERIFYING)
        announceDownloadState()
        assertNull(controller.state.value.message)
        publishModel(ModelStatus.READY, selected = true)
        announceDownloadState()
        assertEquals("ReazonSpeech 模型下载完成", controller.state.value.message?.text)
        actions.onDismissMessage()
        announceDownloadState()
        assertNull(controller.state.value.message)
    }

    /** 失败和取消显示各自状态，不能提前报下载成功。 */
    @Test fun failedAndCancelledDownloadsNeverReportCompletion() = onMain {
        publishModel(ModelStatus.DOWNLOADING)
        announceDownloadState()
        publishModel(ModelStatus.FAILED)
        announceDownloadState()
        assertTrue(controller.state.value.message?.isError == true)
        assertTrue(controller.state.value.message!!.text.contains("下载失败"))
        actions.onDismissMessage()
        publishModel(ModelStatus.QUEUED)
        announceDownloadState()
        publishModel(ModelStatus.CANCELLED)
        announceDownloadState()
        assertEquals("ReazonSpeech 下载已取消", controller.state.value.message?.text)
        assertFalse(controller.state.value.message!!.isError)
    }

    /** 模型删除后的操作结果不能污染重新打开的引导，重下载与启用也不能继续显示旧提示。 */
    @Test fun deletedModelNoticeDoesNotSurviveRestartOrExplicitRedownload() = onMain {
        setModelNotice("模型已删除，需要时可重新下载并启用")
        actions.onRestartOnboarding()
        assertNull(controller.state.value.models.message)
        actions.onOnboardingSkip(); actions.onChooseOnboardingRoute(ListeningMode.LOCAL)
        assertTrue(pendingDownloads().isEmpty())
        actions.onOnboardingKeyChange("fake-local-key")
        actions.onOnboardingNext()
        assertStep(OnboardingStep.API)
        setModelNotice("模型已删除，需要时可重新下载并启用")
        actions.onDownloadModel("reazonspeech")
        assertNull(controller.state.value.models.message)
        publishModel(ModelStatus.DOWNLOADING)
        announceDownloadState()
        publishModel(ModelStatus.READY, selected = true)
        announceDownloadState()
        assertEquals(ModelUiStatus.ACTIVE, controller.state.value.models.items.single().status)
        assertTrue(controller.state.value.onboarding!!.modelReady)
        assertNull(controller.state.value.models.message)
        actions.onOnboardingNext()
        assertNull(controller.state.value.onboarding)
    }

    /** 仅选本地后返回并改为在线，不能产生任何后台下载任务。 */
    @Test fun switchingToOnlineBeforeDownloadDoesNotScheduleLocalModel() = onMain {
        actions.onOnboardingSkip(); actions.onChooseOnboardingRoute(ListeningMode.LOCAL)
        assertTrue(pendingDownloads().isEmpty())
        actions.onOnboardingBack()
        actions.onChooseOnboardingRoute(ListeningMode.ONLINE)
        assertStep(OnboardingStep.ONLINE)
        assertTrue(pendingDownloads().isEmpty())
        assertFalse(application.getSharedPreferences("appearance", 0).getBoolean("onboarding_local_requested", false))
    }

    /** 注入与删除成功回调完全相同的瞬时消息，不删除设备或用户模型。 */
    @Suppress("UNCHECKED_CAST")
    private fun setModelNotice(text: String) {
        val state = AppController::class.java.getDeclaredField("mutableState").apply { isAccessible = true }
            .get(controller) as MutableStateFlow<SimulSubUiState>
        state.value = state.value.copy(models = state.value.models.copy(message = UiMessage(text)))
    }

    /** 仅注入隔离仓库状态，不启动系统下载任务或调用网络。 */
    private fun announceDownloadState() {
        AppController::class.java.getDeclaredMethod("announceModelDownloadChanges", ModelLibraryState::class.java)
            .apply { isAccessible = true }.invoke(controller, repository.state.value)
    }

    /** 只读取控制器确认过的下载标识；仓库协程已取消，不会真的安排 WorkManager 下载。 */
    @Suppress("UNCHECKED_CAST")
    private fun pendingDownloads(): Set<String> = AppController::class.java.getDeclaredField("pendingModelDownloads")
        .apply { isAccessible = true }.get(controller) as Set<String>

    /** 成功探测自动持久化地址、模型与新目录，用户可改选目录项，重建后仍完整回填。 */
    @Test fun localProbeAutomaticallyAppliesConnectionAndRefreshesSelectableModels() {
        val response = TranslationSetupResult("https://api.deepseek.com/chat/completions", "account-model-a", "none",
            listOf("account-model-a", "account-model-b"), true)
        onMain {
            actions.onOnboardingSkip(); actions.onChooseOnboardingRoute(ListeningMode.LOCAL)
            actions.onOnboardingKeyChange("fake-setup-key")
            controller.onboardingProbeFactory = { _, _, _, _ -> ImmediateDiscovery(response) }
            actions.onOnboardingTest()
        }
        awaitSetupProbe()
        onMain {
            assertEquals(response.url, prefs.byokUrl)
            assertEquals("account-model-a", prefs.byokModel)
            assertEquals("fake-setup-key", prefs.byokKey)
            val setup = controller.state.value.onboarding!!.setup
            assertEquals(response.models, setup.models.map { it.id })
            assertEquals("account-model-a", setup.model)
            assertTrue(setup.message!!.text.contains("已自动选择并保存"))
            assertFalse(setup.message!!.isError)
            assertEquals("", controller.state.value.settings.api.suggestedUrl)
            actions.onOnboardingModelChange("account-model-b")
            assertEquals("account-model-b", prefs.byokModel)
            assertEquals(response.url, prefs.byokUrl)
            createController()
            actions.onOnboardingSkip(); actions.onChooseOnboardingRoute(ListeningMode.LOCAL)
            assertEquals("account-model-b", controller.state.value.onboarding!!.setup.model)
            assertEquals(response.models, controller.state.value.onboarding!!.setup.models.map { it.id })
            assertEquals("fake-setup-key", controller.state.value.onboarding!!.setup.key)
        }
    }

    /** 失败或其他来源的结果不得改写原有配置，也不能把列表条目冒充可用配置。 */
    @Test fun failedOrForeignOriginSetupResultKeepsSavedConfiguration() {
        for (response in listOf(
            TranslationSetupResult(models = listOf("unverified"), catalogAvailable = true, error = "假模型不可用"),
            TranslationSetupResult("https://example.com/chat/completions", "foreign", "none", listOf("foreign"), true))) {
            lateinit var url: String
            lateinit var model: String
            onMain {
                actions.onRestartOnboarding(); actions.onOnboardingSkip(); actions.onChooseOnboardingRoute(ListeningMode.LOCAL)
                actions.onOnboardingKeyChange("fake-setup-key")
                url = prefs.byokUrl; model = prefs.byokModel
                controller.onboardingProbeFactory = { _, _, _, _ -> ImmediateDiscovery(response) }
                actions.onOnboardingTest()
            }
            awaitSetupProbe()
            onMain {
                assertEquals(url, prefs.byokUrl); assertEquals(model, prefs.byokModel)
                assertEquals(model, controller.state.value.onboarding!!.setup.model)
                assertEquals("fake-setup-key", prefs.byokKey)
                assertTrue(controller.state.value.onboarding!!.setup.message!!.isError)
                assertFalse(controller.state.value.onboarding!!.setup.models.any { it.id == "foreign" })
                if (response.url == null) assertTrue(controller.state.value.onboarding!!.setup.models.any { it.id == "unverified" })
            }
        }
    }

    /** 切供应商会取消进行中的探测，旧成功结果不能覆盖新供应商、模型或密钥。 */
    @Test fun changingProviderRejectsLateAutomaticSetupResult() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        onMain {
            actions.onOnboardingSkip(); actions.onChooseOnboardingRoute(ListeningMode.LOCAL)
            actions.onOnboardingKeyChange("fake-deepseek-key")
            controller.onboardingProbeFactory = { _, _, _, _ -> object : TranslationSetupDiscovery {
                /** 模拟不可立即返回的网络，仅使用内存栅栏。 */
                override fun probe(preferredModels: List<String>): TranslationSetupResult {
                    entered.countDown()
                    check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
                    return TranslationSetupResult("https://api.deepseek.com/chat/completions", "late", "none", listOf("late"), true)
                }
                /** 即使底层迟到返回成功，控制器仍须丢弃其结果。 */
                override fun cancel() { cancelled.set(true); release.countDown() }
            } }
            actions.onOnboardingTest()
        }
        assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
        onMain {
            actions.onOnboardingProviderChange("qwen")
            assertTrue(cancelled.get())
            actions.onOnboardingKeyChange("fake-qwen-key")
        }
        instrumentation.waitForIdleSync()
        onMain {
            assertEquals("qwen", controller.state.value.onboarding!!.setup.providerId)
            assertEquals("fake-qwen-key", controller.state.value.onboarding!!.setup.key)
            assertFalse(prefs.byokModel == "late")
            assertFalse(controller.state.value.onboarding!!.setup.models.any { it.id == "late" })
        }
    }

    /** 等待真实控制器协程退出后再读取主线程状态，不把任务启动当作成功。 */
    private fun awaitSetupProbe() {
        var task: Job? = null
        onMain { task = AppController::class.java.getDeclaredField("probeJob").apply { isAccessible = true }.get(controller) as? Job }
        runBlocking { kotlinx.coroutines.withTimeout(5000) { task?.join() } }
        instrumentation.waitForIdleSync()
    }

    /** 隔离返回经过核心协议专项验证的数据，不发出云端请求或变更真实配置。 */
    private class ImmediateDiscovery(private val result: TranslationSetupResult) : TranslationSetupDiscovery {
        /** 模拟单次网络完成，主控制器仍走实际自动保存路径。 */
        override fun probe(preferredModels: List<String>) = result
        /** 立即结果没有后台资源。 */
        override fun cancel() = Unit
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
    private fun publishModel(status: ModelStatus, selected: Boolean = false, modelId: String = "reazonspeech") {
        val spec = ModelCatalog.entries.first { it.id == modelId }
        repositoryField("verified").set(repository, if (selected) mapOf(spec.id to ModelPaths(spec, File(root, "仅内存路径"))) else emptyMap<String, ModelPaths>())
        val state = repositoryField("mutableState").get(repository) as MutableStateFlow<ModelLibraryState>
        state.value = ModelLibraryState(listOf(ModelEntry(spec, status)), if (selected) spec.id else null)
        // 隔离仓库的观察协程已取消，显式模拟其刷新通知，不重复使用权限快照触发刷新。
        if (::controller.isInitialized) AppController::class.java.getDeclaredMethod("refresh")
            .apply { isAccessible = true }.invoke(controller)
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
