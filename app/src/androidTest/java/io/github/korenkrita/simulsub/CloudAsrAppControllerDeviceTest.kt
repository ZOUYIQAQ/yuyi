package io.github.korenkrita.simulsub

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.online.CloudAsrConfig
import io.github.korenkrita.simulsub.online.LiveTranslateConfig
import io.github.korenkrita.simulsub.ui.*
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** 隔离偏好验证三模式配置与引导，不启动录音、连接测试、下载或更新。 */
class CloudAsrAppControllerDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val token = UUID.randomUUID().toString()
    private val store = ViewModelStore()
    private val application = object : Application() {
        /** 系统依赖沿用真实应用上下文，偏好仍由下方隔离。 */
        override fun getApplicationContext(): Context = context.applicationContext
        /** 保留包名以读取系统权限，不执行授权或录音。 */
        override fun getPackageName(): String = context.packageName
        /** 每例使用独立偏好命名，避免覆盖设备上已有配置。 */
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            context.getSharedPreferences("听写接入-$token-$name", mode)
        /** 只读一个不存在的独立日志目录，不操作用户记录。 */
        override fun getExternalFilesDir(type: String?): File = File(context.cacheDir, "听写接入-$token")
    }
    private lateinit var prefs: Prefs
    private lateinit var controller: AppController
    private lateinit var actions: SimulSubActions

    /** 控制器与用户操作均在主线程初始化。 */
    @Before fun prepare() = onMain {
        prefs = Prefs(application)
        controller = AppController(application)
        store.put("在线听写测试", controller)
        actions = controller.actions({}, {}, {}, {})
        grantTestPermissions()
    }

    /** 释放监听与协程，不删除测试文件或设备数据。 */
    @After fun finish() = onMain { store.clear() }

    /** 新安装保留本地默认，同时为两种云端连接提供不同协议默认值。 */
    @Test fun defaultsKeepLocalAndDistinctCloudEndpoints() = onMain {
        assertEquals(ListeningMode.LOCAL, prefs.listeningMode)
        assertEquals(LiveTranslateConfig.DEFAULT_ENDPOINT, controller.state.value.settings.online.endpoint)
        assertEquals(CloudAsrConfig.DEFAULT_ENDPOINT, controller.state.value.settings.cloudAsr.endpoint)
        assertEquals(CloudAsrConfig.DEFAULT_MODEL, controller.state.value.settings.cloudAsr.model)
        assertFalse(prefs.cloudAsrReady)
        assertFalse(prefs.onlineReady)
        assertFalse(prefs.byokReady)
        assertEquals(OnboardingStep.PERMISSIONS, controller.state.value.onboarding?.step)
    }

    /** 三条引导顺序明确；在线听写最后进入文字 API，前后流程均不经过模型库。 */
    @Test fun cloudAsrOnboardingEndsAtApiAndBackReturnsToAsr() = onMain {
        assertEquals(listOf(OnboardingStep.PERMISSIONS, OnboardingStep.MODE, OnboardingStep.API, OnboardingStep.MODEL), onboardingSteps(ListeningMode.LOCAL))
        assertEquals(listOf(OnboardingStep.PERMISSIONS, OnboardingStep.MODE, OnboardingStep.ONLINE), onboardingSteps(ListeningMode.ONLINE))
        assertEquals(listOf(OnboardingStep.PERMISSIONS, OnboardingStep.MODE, OnboardingStep.ONLINE, OnboardingStep.API), onboardingSteps(ListeningMode.CLOUD_ASR))
        actions.onOnboardingNext()
        actions.onListeningModeChange(ListeningMode.CLOUD_ASR)
        actions.onOnboardingNext()
        assertEquals(OnboardingStep.ONLINE, controller.state.value.onboarding?.step)
        actions.onOnboardingNext()
        assertEquals(OnboardingStep.ONLINE, controller.state.value.onboarding?.step)
        actions.onOnboardingSkip()
        assertEquals(OnboardingStep.API, controller.state.value.onboarding?.step)
        actions.onOnboardingBack()
        assertEquals(OnboardingStep.ONLINE, controller.state.value.onboarding?.step)
        actions.onOnboardingSkip()
        actions.onOnboardingSkip()
        assertNull(controller.state.value.onboarding)
        assertEquals(UiDestination.LIVE, controller.state.value.destination)
        assertTrue(application.getSharedPreferences("appearance", 0).getBoolean("onboarding_completed", false))
    }

    /** 两套连接独立保存，千问密钥只保存一次并完整回填到听写与直译。 */
    @Test fun independentFormsSaveTheirOwnConfigurationsInAnyMode() = onMain {
        actions.onPlatformKeyChange("qwen", "shared-qwen-test-key")
        assertFalse(prefs.cloudAsrReady)
        assertTrue(controller.state.value.settings.platformKeys.single { it.id == "qwen" }.hasUnsavedChanges)
        actions.onSavePlatformKey("qwen")
        actions.onOnlineDraftChange(controller.state.value.settings.online.copy(endpoint = "wss://llm-test.cn-beijing.maas.aliyuncs.com/realtime"))
        actions.onCloudAsrDraftChange(controller.state.value.settings.cloudAsr.copy(endpoint = "wss://llm-test.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference"))
        actions.onSaveCloudAsr()
        assertTrue(prefs.cloudAsrReady)
        assertTrue(prefs.onlineReady)
        assertEquals("wss://llm-test.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference", prefs.cloudAsrEndpoint)
        assertEquals(LiveTranslateConfig.DEFAULT_ENDPOINT, prefs.onlineEndpoint)
        assertTrue(controller.state.value.settings.online.hasUnsavedChanges)
        actions.onSaveOnline()
        assertEquals("wss://llm-test.cn-beijing.maas.aliyuncs.com/realtime", prefs.onlineEndpoint)
        actions.onPlatformKeyChange("deepseek", "translation-test-key")
        actions.onSavePlatformKey("deepseek")
        actions.onApiDraftChange(controller.state.value.settings.api.copy(url = "https://api.deepseek.com/v1/chat/completions", model = "deepseek-chat"))
        actions.onSaveApi()
        assertEquals("shared-qwen-test-key", prefs.onlineKey)
        assertEquals(prefs.onlineKey, prefs.cloudAsrKey)
        assertEquals("translation-test-key", prefs.byokKey)
        assertEquals("shared-qwen-test-key", controller.state.value.settings.cloudAsr.key)
        assertNotEquals("shared-qwen-test-key", application.getSharedPreferences("jpsub", 0).getString("platformKey:qwen", ""))
        ListeningMode.entries.forEach { mode ->
            actions.onListeningModeChange(mode)
            assertEquals("shared-qwen-test-key", controller.state.value.settings.cloudAsr.key)
            assertEquals("shared-qwen-test-key", controller.state.value.settings.online.key)
            assertEquals("translation-test-key", controller.state.value.settings.api.key)
        }
    }

    /** 切模式保留两套连接草稿；分别放弃只恢复对应地址和模型。 */
    @Test fun modeSwitchPreservesBothDraftsAndDiscardIsIndependent() = onMain {
        actions.onOnlineDraftChange(controller.state.value.settings.online.copy(endpoint = "wss://llm-test.cn-beijing.maas.aliyuncs.com/realtime"))
        actions.onCloudAsrDraftChange(controller.state.value.settings.cloudAsr.copy(endpoint = "wss://llm-test.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference"))
        ListeningMode.entries.forEach { actions.onListeningModeChange(it) }
        assertEquals("wss://llm-test.cn-beijing.maas.aliyuncs.com/realtime", controller.state.value.settings.online.endpoint)
        assertEquals("wss://llm-test.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference", controller.state.value.settings.cloudAsr.endpoint)
        assertTrue(controller.state.value.settings.online.hasUnsavedChanges)
        assertTrue(controller.state.value.settings.cloudAsr.hasUnsavedChanges)
        actions.onDiscardCloudAsrDraft()
        assertEquals(CloudAsrConfig.DEFAULT_ENDPOINT, controller.state.value.settings.cloudAsr.endpoint)
        assertEquals("wss://llm-test.cn-beijing.maas.aliyuncs.com/realtime", controller.state.value.settings.online.endpoint)
        actions.onDiscardOnlineDraft()
        assertEquals(LiveTranslateConfig.DEFAULT_ENDPOINT, controller.state.value.settings.online.endpoint)
    }

    /** ASR 自身就绪与文字翻译分开计算；合法草稿和直接听译配置均不能冒充听写就绪。 */
    @Test fun readinessRequiresSavedAsrAndSavedTranslation() = onMain {
        actions.onListeningModeChange(ListeningMode.CLOUD_ASR)
        assertFalse(prefs.cloudAsrReady)
        actions.onPlatformKeyChange("qwen", "cloud-ready-test-key")
        assertFalse(prefs.cloudAsrReady)
        actions.onSavePlatformKey("qwen")
        assertTrue(prefs.cloudAsrReady)
        assertFalse(controller.state.value.live.canStart)
        actions.onCloudAsrDraftChange(controller.state.value.settings.cloudAsr.copy(endpoint = "wss://llm-test.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference"))
        assertEquals(CloudAsrConfig.DEFAULT_ENDPOINT, prefs.cloudAsrEndpoint)
        actions.onSaveCloudAsr()
        assertEquals("wss://llm-test.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference", prefs.cloudAsrEndpoint)
        assertTrue(prefs.cloudAsrReady)
        assertFalse(prefs.byokReady)
        assertFalse(controller.state.value.live.canStart)
        assertTrue(controller.state.value.live.statusTitle.contains("文字翻译"))
        actions.onApiDraftChange(controller.state.value.settings.api.copy(url = "https://api.deepseek.com/v1/chat/completions", model = "deepseek-chat"))
        assertFalse(controller.state.value.live.canStart)
        actions.onSaveApi()
        assertFalse(prefs.byokReady)
        assertFalse(controller.state.value.live.canStart)
        actions.onPlatformKeyChange("deepseek", "deepseek-ready-test-key")
        assertFalse(controller.state.value.live.canStart)
        actions.onSavePlatformKey("deepseek")
        assertTrue(prefs.byokReady)
        assertTrue(controller.state.value.live.canStart)
        actions.onListeningModeChange(ListeningMode.ONLINE)
        assertTrue(controller.state.value.live.canStart)
        actions.onOnlineDraftChange(controller.state.value.settings.online.copy(endpoint = "wss://llm-test.cn-beijing.maas.aliyuncs.com/realtime"))
        actions.onSaveOnline()
        assertTrue(controller.state.value.live.canStart)
        actions.onCloudAsrDraftChange(controller.state.value.settings.cloudAsr.copy(endpoint = "http://invalid.example"))
        actions.onSaveCloudAsr()
        assertEquals("wss://llm-test.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference", prefs.cloudAsrEndpoint)
        assertTrue(controller.state.value.settings.cloudAsr.message!!.isError)
        assertTrue(prefs.cloudAsrReady)
    }

    /** 在线听写提供全部源语种和自动识别，保留文字翻译的自定义目标能力。 */
    @Test fun cloudAsrAllowsAutoSourceAndCustomTranslationTarget() = onMain {
        actions.onListeningModeChange(ListeningMode.CLOUD_ASR)
        assertEquals(Lang.entries.map { it.code }.toSet() + "auto", controller.state.value.live.sourceOptions.map { it.id }.toSet())
        actions.onSourceLanguage("auto")
        assertEquals("auto", prefs.cloudAsrConfig().sourceLanguage)
        actions.onCustomTargetChange("粤语")
        actions.onApplyCustomTarget()
        assertEquals("粤语", prefs.targetLang.zh)
        assertEquals(prefs.scene.background.orEmpty().take(400), prefs.cloudAsrConfig().context)
    }

    /** 来回切换直接听译和受限本地识别时，听写自己的自定义目标与源语种完整恢复。 */
    @Test fun modeLanguagesRestoreCustomTargetAndAsrSourceAfterOtherModes() = onMain {
        actions.onListeningModeChange(ListeningMode.CLOUD_ASR)
        actions.onCustomTargetChange("粤语")
        actions.onApplyCustomTarget()
        for (source in listOf("ja", "auto")) {
            actions.onSourceLanguage(source)
            actions.onListeningModeChange(ListeningMode.ONLINE)
            assertNotNull(prefs.targetLang.lang)
            actions.onTargetLanguage("en")
            actions.onListeningModeChange(ListeningMode.CLOUD_ASR)
            assertEquals("粤语", prefs.targetLang.zh)
            assertEquals(source, prefs.sourceLang?.code ?: "auto")
            actions.onListeningModeChange(ListeningMode.LOCAL)
            // 模拟受限本地模型只能使用英语；不得覆盖听写模式已保存的日语或自动选择。
            prefs.sourceLang = Lang.EN
            actions.onListeningModeChange(ListeningMode.CLOUD_ASR)
            assertEquals(source, prefs.sourceLang?.code ?: "auto")
            assertEquals("粤语", prefs.targetLang.zh)
        }
    }

    /** 用内存授权快照验证业务就绪，不改变设备权限。 */
    private fun grantTestPermissions() = controller.setPermissions(listOf(
        PermissionUiState("audio", "录制播放音频", "测试权限", true),
        PermissionUiState("overlay", "显示悬浮字幕", "测试权限", true),
    ))

    /** 所有控制器回调在主线程同步执行，避免依赖调度时机。 */
    private fun onMain(block: () -> Unit) { instrumentation.runOnMainSync { block() } }
}
