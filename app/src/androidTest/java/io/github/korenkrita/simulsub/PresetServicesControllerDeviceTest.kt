package io.github.korenkrita.simulsub

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.models.ModelCatalog
import io.github.korenkrita.simulsub.models.ModelRepository
import io.github.korenkrita.simulsub.models.ModelStatus
import io.github.korenkrita.simulsub.online.CloudAsrConfig
import io.github.korenkrita.simulsub.online.LiveTranslateModels
import io.github.korenkrita.simulsub.ui.PermissionUiState
import io.github.korenkrita.simulsub.ui.SimulSubActions
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/** 通过真实控制器验证预制基础服务和中央密钥，不访问网络或覆盖设备偏好。 */
class PresetServicesControllerDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val token = UUID.randomUUID().toString()
    private val store = ViewModelStore()
    private val application = object : Application() {
        /** 模型库只读设备现状，不假定设备没有安装或选择模型。 */
        override fun getApplicationContext(): Context = context.applicationContext
        /** 权限检查沿用目标应用包名。 */
        override fun getPackageName(): String = context.packageName
        /** 所有配置写入本用例专属命名空间。 */
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            context.getSharedPreferences("预制服务-$token-$name", mode)
        /** 字幕记录使用独立目录。 */
        override fun getExternalFilesDir(type: String?): File = File(context.cacheDir, "预制服务-$token")
    }
    private lateinit var prefs: Prefs
    private lateinit var repo: ListeningServiceRepository
    private lateinit var controller: AppController
    private lateinit var actions: SimulSubActions

    /** 先建立隔离偏好，允许迁移用例在控制器初始化前准备旧连接。 */
    @Before fun prepare() = main {
        prefs = Prefs(application)
        repo = ListeningServiceRepository(application)
    }

    /** 只清理控制器监听，不触碰真实模型或用户目录。 */
    @After fun finish() = main { store.clear() }

    /** 首页只展示已就绪服务，设置管理仍完整列出内置目录。 */
    @Test fun freshCatalogShowsAllBuiltInServices() = main {
        startController()
        val live = controller.state.value.live
        val settings = controller.state.value.settings
        assertEquals(listOf(CloudAsrConfig.DEFAULT_MODEL, LiveTranslateModels.REALTIME_ID,
            LiveTranslateModels.ECONOMY_ID), repo.profiles.map { it.model })
        assertEquals(ModelRepository.get(application).state.value.entries.filter { it.status == ModelStatus.READY }
            .map { "local:${it.spec.id}" },
            live.listeningServices.filter { it.id.startsWith("local:") }.map { it.id })
        assertTrue(live.listeningServices.none { it.id.startsWith("profile:") })
        assertEquals(ModelCatalog.entries.map { "local:${it.id}" } + repo.profiles.map { it.id },
            settings.listeningProfiles.map { it.id })
        assertEquals(2, repo.translationServices(prefs).size)
        assertTrue(live.translationServices.isEmpty())
        assertEquals(repo.translationServices(prefs).map { it.id }, settings.translationProfiles.map { it.id })
        assertNull(repo.activeId)
    }

    /** 千问中央密钥使两套预制直译可启动；听写另需翻译密钥，无需保存服务。 */
    @Test fun builtInQwenAudioBecomesReadyBySharedKey() = main {
        startController()
        val asr = repo.profiles.single { it.mode == ListeningMode.CLOUD_ASR }
        val direct = repo.profiles.filter { it.mode == ListeningMode.ONLINE }
        assertEquals(2, direct.size)
        val initialCount = repo.profiles.size
        actions.onSelectListeningService("profile:${direct.first().id}")
        assertFalse(prefs.byokReady)
        assertFalse(controller.state.value.live.canStart)
        actions.onPlatformKeyChange("qwen", "测试千问共享密钥")
        actions.onSavePlatformKey("qwen")
        assertEquals(repo.profiles.map { "profile:${it.id}" }, controller.state.value.live.listeningServices
            .filter { it.id.startsWith("profile:") }.map { it.id })
        direct.forEach { profile ->
            actions.onSelectListeningService("profile:${profile.id}")
            assertEquals(profile.id, repo.activeId)
            assertTrue(prefs.onlineReady)
            assertFalse(prefs.byokReady)
            assertTrue(controller.state.value.live.canStart)
        }
        actions.onSelectListeningService("profile:${asr.id}")
        assertTrue(prefs.cloudAsrReady)
        assertFalse(controller.state.value.live.canStart)
        actions.onPlatformKeyChange("deepseek", "测试DeepSeek共享密钥")
        actions.onSavePlatformKey("deepseek")
        assertEquals(2, controller.state.value.live.translationServices.size)
        assertTrue(prefs.byokReady)
        assertTrue(controller.state.value.live.canStart)
        assertEquals(initialCount, repo.profiles.size)
    }

    /** DeepSeek 预制接口只填中央密钥就就绪，不经过新增或保存翻译服务。 */
    @Test fun builtInDeepSeekTranslationBecomesReadyByKey() = main {
        startController()
        val deepSeek = repo.translationServices(prefs).single { it.url.startsWith("https://api.deepseek.com/") }
        val before = prefs.byokProfiles.map { it.id }
        actions.onSelectTranslationService(deepSeek.id)
        assertFalse(prefs.byokReady)
        actions.onPlatformKeyChange("deepseek", "测试DeepSeek密钥")
        actions.onSavePlatformKey("deepseek")
        assertTrue(controller.state.value.live.translationServices.any { it.id == deepSeek.id })
        assertTrue(prefs.byokReady)
        assertTrue(controller.state.value.live.translationReady)
        assertEquals(deepSeek.id, prefs.byokProfile.id)
        assertEquals(before, prefs.byokProfiles.map { it.id })
    }

    /** 未安装模型仍在管理列表，但首页隐藏；旧待下载选择不会误报可启动。 */
    @Test fun uninstalledLocalModelCanBePendingWithoutStarting() {
        val pendingId = ModelRepository.get(application).state.value.entries
            .firstOrNull { it.status == ModelStatus.NOT_INSTALLED }?.spec?.id
        assumeTrue("设备上没有可验证的未安装模型", pendingId != null)
        main {
            startController()
            val id = requireNotNull(pendingId)
            assumeTrue("模型状态已变化", ModelRepository.get(application).state.value.entries
                .any { it.spec.id == id && it.status == ModelStatus.NOT_INSTALLED })
            assertTrue(controller.state.value.settings.listeningProfiles.any { it.id == "local:$id" && !it.configured })
            assertTrue(controller.state.value.live.listeningServices.none { it.id == "local:$id" })
            actions.onSelectListeningService("local:$id")
            assertEquals("local:$id", controller.state.value.live.listeningServiceId)
            assertEquals(id, application.getSharedPreferences("appearance", 0)
                .getString("preferred_local_model", null))
            assertEquals(ListeningMode.LOCAL, prefs.listeningMode)
            assertFalse(controller.state.value.live.canStart)
        }
    }

    /** 新增自定义供应商只打开备用草稿，运行中的翻译选择不改变。 */
    @Test fun customProviderDraftKeepsActiveTranslation() = main {
        startController()
        val active = prefs.byokProfile.id
        actions.onCreateTranslationProvider("custom")
        val editing = controller.state.value.settings.api.profileId
        assertNotEquals(active, editing)
        assertEquals(active, prefs.byokProfile.id)
        assertEquals(active, controller.state.value.live.translationServiceId)
        assertEquals(editing, controller.state.value.settings.translationEditorId)
        assertTrue(repo.translationServices(prefs).any { it.id == editing && !it.configured })
    }

    /** 旧版同配置听写连接保留原标识和当前选择，预制项只补齐缺项。 */
    @Test fun existingConnectionIsPreservedWhenDefaultsAreSeeded() = main {
        prefs.saveCloudAsr(CloudAsrConfig.DEFAULT_ENDPOINT, CloudAsrConfig.DEFAULT_MODEL, "旧千问密钥")
        prefs.listeningMode = ListeningMode.CLOUD_ASR
        val existing = ListeningServiceProfile(ListeningServiceProfile.newId(), "旧连接", ListeningMode.CLOUD_ASR,
            CloudAsrConfig.DEFAULT_ENDPOINT, CloudAsrConfig.DEFAULT_MODEL)
        repo.save(existing)
        repo.select(existing.id)
        startController()
        assertEquals(existing, repo.profiles.single { it.mode == ListeningMode.CLOUD_ASR })
        assertEquals(3, repo.profiles.size)
        assertEquals(existing.id, repo.activeId)
        assertEquals("profile:${existing.id}", controller.state.value.live.listeningServiceId)
        assertEquals("旧千问密钥", prefs.cloudAsrKey)
    }

    /** 构造控制器并提供已授予权限快照，避免权限影响就绪断言。 */
    private fun startController() {
        controller = AppController(application)
        store.put("预制控制器", controller)
        actions = controller.actions({}, {}, {}, {})
        controller.setPermissions(listOf(PermissionUiState("audio", "录制", "", true),
            PermissionUiState("overlay", "字幕", "", true)))
    }

    /** 控制器与界面相同，所有状态动作都在主线程执行。 */
    private fun main(action: () -> Unit) = inst.runOnMainSync(action)
}
