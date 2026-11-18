package io.github.korenkrita.simulsub

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.online.CloudAsrConfig
import io.github.korenkrita.simulsub.online.LiveTranslateModels
import io.github.korenkrita.simulsub.ui.SimulSubActions
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/** 用独立偏好和真实控制器验证首页就绪过滤、服务凭据绑定及运行快照。 */
class ReadyServicesAndKeysControllerDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val token = UUID.randomUUID().toString()
    private val application = object : Application() {
        /** 复用目标应用上下文以读取模型状态，不使用真实偏好。 */
        override fun getApplicationContext(): Context = context.applicationContext
        /** 保持设备权限查询的真实包名。 */
        override fun getPackageName(): String = context.packageName
        /** 每条测试独立保存密钥和服务目录。 */
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            context.getSharedPreferences("就绪服务密钥-$token-$name", mode)
        /** 字幕与附属文件也隔离到本条测试目录。 */
        override fun getExternalFilesDir(type: String?): File = File(context.cacheDir, "就绪服务密钥-$token")
    }
    private var store = ViewModelStore()
    private lateinit var prefs: Prefs
    private lateinit var repo: ListeningServiceRepository
    private lateinit var controller: AppController
    private lateinit var actions: SimulSubActions

    /** 初始化隔离偏好并启动真实控制器，不连接在线服务。 */
    @Before fun prepare() = main {
        prefs = Prefs(application)
        repo = ListeningServiceRepository(application)
        startController()
    }

    /** 只释放本测试的控制器，不清理任何设备文件。 */
    @After fun finish() = main { store.clear() }

    /** 各模型绑定独立密钥，清空其中一份后不从默认或备用密钥静默回退。 */
    @Test fun audioModelsKeepIndependentNamedKeysAndReadyState() = main {
        val asr = repo.profiles.single { it.mode == ListeningMode.CLOUD_ASR }
        val realtime = repo.profiles.single { it.model == LiveTranslateModels.REALTIME_ID }
        val economy = repo.profiles.single { it.model == LiveTranslateModels.ECONOMY_ID }
        val allIds = repo.profiles.map { it.id }
        assertEquals(allIds, controller.state.value.settings.listeningProfiles.filter { it.kind != "local" }.map { it.id })
        assertTrue(controller.state.value.live.listeningServices.none { it.id.startsWith("profile:") })

        val asrId = addKey("qwen", "听写专用", "测试听写密钥")
        val realtimeId = addKey("qwen", "实时专用", "测试实时密钥")
        actions.onPlatformKeyChange("qwen", "测试默认密钥")
        actions.onSavePlatformKey("qwen")
        actions.onServiceCredentialChange(asr.id, asrId)
        actions.onServiceCredentialChange(realtime.id, realtimeId)

        assertEquals(asrId, prefs.selectedCredentialId("qwen", listeningCredentialSlot(asr.mode, asr.endpoint, asr.model)))
        assertEquals(realtimeId, prefs.selectedCredentialId("qwen", listeningCredentialSlot(realtime.mode, realtime.endpoint, realtime.model)))
        assertNull(prefs.selectedCredentialId("qwen", listeningCredentialSlot(economy.mode, economy.endpoint, economy.model)))
        assertEquals("听写专用", controller.state.value.settings.listeningProfiles.single { it.id == asr.id }.credentialLabel)
        assertEquals("实时专用", controller.state.value.settings.listeningProfiles.single { it.id == realtime.id }.credentialLabel)
        assertEquals("默认密钥", controller.state.value.settings.listeningProfiles.single { it.id == economy.id }.credentialLabel)
        assertEquals(allIds.map { "profile:$it" }, controller.state.value.live.listeningServices
            .filter { it.id.startsWith("profile:") }.map { it.id })
        assertTrue(controller.state.value.live.listeningServices.single { it.id == "profile:${asr.id}" }
            .description.contains("听写专用"))
        assertFalse(controller.state.value.live.listeningServices.any { it.description.contains("测试听写密钥") })

        actions.onSelectListeningService("profile:${asr.id}")
        assertEquals("测试听写密钥", prefs.cloudAsrConfig().apiKey)
        actions.onSelectListeningService("profile:${realtime.id}")
        assertEquals("测试实时密钥", prefs.onlineConfig().apiKey)
        actions.onSelectListeningService("profile:${economy.id}")
        assertEquals("测试默认密钥", prefs.onlineConfig().apiKey)

        actions.onPlatformCredentialChange("qwen", asrId, "")
        actions.onSavePlatformCredential("qwen", asrId)
        assertEquals("", prefs.credentialFor("qwen", listeningCredentialSlot(asr.mode, asr.endpoint, asr.model))?.key)
        assertTrue(controller.state.value.live.listeningServices.none { it.id == "profile:${asr.id}" })
        assertTrue(controller.state.value.live.listeningServices.any { it.id == "profile:${realtime.id}" })
        assertTrue(controller.state.value.live.listeningServices.any { it.id == "profile:${economy.id}" })
        assertFalse(controller.state.value.settings.listeningProfiles.single { it.id == asr.id }.configured)
        assertEquals("测试实时密钥", prefs.credentialFor("qwen",
            listeningCredentialSlot(realtime.mode, realtime.endpoint, realtime.model))?.key)

        restartController()
        assertTrue(controller.state.value.live.listeningServices.none { it.id == "profile:${asr.id}" })
        assertEquals(realtimeId, prefs.selectedCredentialId("qwen",
            listeningCredentialSlot(realtime.mode, realtime.endpoint, realtime.model)))
        assertEquals(economy.model, prefs.onlineConfig().model)
        assertEquals("测试默认密钥", prefs.onlineConfig().apiKey)
        actions.onSelectListeningService("profile:${realtime.id}")
        assertEquals(realtime.model, prefs.onlineConfig().model)
        assertEquals("测试实时密钥", prefs.onlineConfig().apiKey)
        assertEquals("测试默认密钥", prefs.platformKey("qwen"))
    }

    /** 翻译槽位使用 profileId；空标识恢复默认，自定义旧连接保持首页可见。 */
    @Test fun translationBindingAndCustomExceptionSurviveRestart() = main {
        val deepSeek = repo.translationServices(prefs).single { it.url.startsWith("https://api.deepseek.com/") }
        val qwen = repo.translationServices(prefs).single { it.url.startsWith("https://dashscope.aliyuncs.com/") }
        assertTrue(controller.state.value.live.translationServices.isEmpty())
        assertEquals(2, controller.state.value.settings.translationProfiles.size)

        actions.onPlatformKeyChange("deepseek", "测试DeepSeek默认密钥")
        actions.onSavePlatformKey("deepseek")
        val backupId = addKey("deepseek", "翻译备用", "测试DeepSeek备用密钥")
        actions.onServiceCredentialChange("translation:${deepSeek.id}", backupId)
        assertEquals(backupId, prefs.selectedCredentialId("deepseek", translationCredentialSlot(deepSeek.id)))
        assertEquals("翻译备用", controller.state.value.settings.translationProfiles.single { it.id == deepSeek.id }.credentialLabel)
        assertTrue(controller.state.value.live.translationServices.any { it.id == deepSeek.id && it.description.contains("翻译备用") })
        assertTrue(controller.state.value.live.translationServices.none { it.id == qwen.id })
        assertFalse(controller.state.value.live.translationServices.any { it.description.contains("测试DeepSeek备用密钥") })
        actions.onSelectTranslationService(deepSeek.id)
        assertEquals("测试DeepSeek备用密钥", prefs.byokKey(prefs.byokProfile))

        actions.onPlatformCredentialChange("deepseek", backupId, "")
        actions.onSavePlatformCredential("deepseek", backupId)
        assertTrue(controller.state.value.live.translationServices.none { it.id == deepSeek.id })
        assertFalse(prefs.byokReady)
        assertEquals("测试DeepSeek默认密钥", prefs.platformKey("deepseek"))
        actions.onServiceCredentialChange("translation:${deepSeek.id}", "")
        assertNull(prefs.selectedCredentialId("deepseek", translationCredentialSlot(deepSeek.id)))
        assertTrue(controller.state.value.live.translationServices.any { it.id == deepSeek.id })
        assertEquals("测试DeepSeek默认密钥", prefs.byokKey(prefs.byokProfile))

        val custom = prefs.addByokProfile("旧自定义", select = false)
        prefs.saveByok("https://old-custom.example/v1/chat/completions", "legacy-model", null, profile = custom)
        val customAudio = ListeningServiceProfile(ListeningServiceProfile.newId(), "旧听写连接",
            ListeningMode.CLOUD_ASR, "wss://old-custom.example/api-ws/v1/inference", CloudAsrConfig.DEFAULT_MODEL)
        repo.save(customAudio)
        restartController()
        assertTrue(controller.state.value.live.translationServices.any { it.id == custom.id })
        assertTrue(controller.state.value.settings.translationProfiles.any { it.id == custom.id })
        assertTrue(controller.state.value.live.listeningServices.any { it.id == "profile:${customAudio.id}" })
        assertTrue(controller.state.value.settings.listeningProfiles.any { it.id == customAudio.id && !it.builtIn })
        assertEquals("测试DeepSeek默认密钥", prefs.byokKey(prefs.byokProfile))
    }

    /** 新建兼容接口先保留为编辑草稿，绑定备用密钥后保存才进入首页并用于请求配置。 */
    @Test fun customTranslationDraftBindsNamedKeyBeforeFirstSave() = main {
        actions.onPlatformKeyChange("deepseek", "测试默认密钥")
        actions.onSavePlatformKey("deepseek")
        val backupId = addKey("deepseek", "自定义接口专用", "测试自定义备用密钥")
        val activeId = prefs.byokProfile.id
        actions.onCreateTranslationProvider("custom")
        val draftId = controller.state.value.settings.api.profileId
        assertNotEquals(activeId, draftId)
        assertEquals(draftId, controller.state.value.settings.translationEditorId)
        assertEquals(activeId, prefs.byokProfile.id)
        assertTrue(controller.state.value.live.translationServices.none { it.id == draftId })

        actions.onApiDraftChange(controller.state.value.settings.api.copy(
            url = "https://api.deepseek.com/v1/chat/completions", model = "custom-compatible-model"))
        assertEquals("deepseek", controller.state.value.settings.api.platformId)
        assertTrue(controller.state.value.settings.api.credentialOptions.any { it.id == backupId && it.label == "自定义接口专用" })
        actions.onServiceCredentialChange("translation:$draftId", backupId)
        assertEquals(backupId, prefs.selectedCredentialId("deepseek", translationCredentialSlot(draftId)))
        assertEquals("测试自定义备用密钥", controller.state.value.settings.api.key)
        assertEquals("自定义接口专用", controller.state.value.settings.api.credentialLabel)
        assertTrue(controller.state.value.live.translationServices.none { it.id == draftId })

        actions.onSaveApi()
        val saved = prefs.byokProfiles.single { it.id == draftId }
        assertEquals("custom-compatible-model", prefs.byokModel(saved))
        assertTrue(prefs.byokReady(saved))
        assertTrue(controller.state.value.live.translationServices.any { it.id == draftId &&
            it.description.contains("自定义接口专用") })
        assertEquals(activeId, prefs.byokProfile.id)
        actions.onSelectTranslationService(draftId)
        assertEquals(draftId, prefs.byokProfile.id)
        assertEquals("测试自定义备用密钥", prefs.byokService()?.key)
        assertEquals("测试默认密钥", prefs.platformKey("deepseek"))
    }

    /** 用户主动新增的模板服务也属于自定义项，缺密钥时保留可见但不可启动。 */
    @Test fun userAddedDefaultTemplateKeepsCustomExceptionAfterRestart() = main {
        actions.onCreateTranslationProvider("deepseek")
        val id = controller.state.value.settings.api.profileId
        actions.onSaveApi()
        assertTrue(prefs.isCustomTranslation(id))
        assertFalse(prefs.byokProfiles.single { it.id == id }.let(prefs::byokReady))
        assertTrue(controller.state.value.live.translationServices.any { it.id == id })
        assertFalse(controller.state.value.settings.translationProfiles.single { it.id == id }.builtIn)
        restartController()
        assertTrue(controller.state.value.live.translationServices.any { it.id == id })
        assertFalse(controller.state.value.settings.translationProfiles.single { it.id == id }.builtIn)
    }

    /** 新建并保存命名凭据，检查独立标识和名称草稿均进入持久化目录。 */
    private fun addKey(platformId: String, label: String, key: String): String {
        actions.onAddPlatformKey(platformId)
        val draft = controller.state.value.settings.platformKeys.last { it.id == platformId && it.isNew }
        assertNotEquals("default", draft.credentialId)
        actions.onPlatformKeyLabelChange(platformId, draft.credentialId, label)
        actions.onPlatformCredentialChange(platformId, draft.credentialId, key)
        actions.onSavePlatformCredential(platformId, draft.credentialId)
        assertEquals(PlatformCredential(draft.credentialId, label, key),
            prefs.platformCredentials(platformId).single { it.id == draft.credentialId })
        return draft.credentialId
    }

    /** 重建控制器验证服务选择与命名凭据从隔离偏好恢复。 */
    private fun restartController() {
        store.clear()
        store = ViewModelStore()
        startController()
    }

    /** 创建真实控制器；所有测试动作由主线程同步派发。 */
    private fun startController() {
        controller = AppController(application)
        store.put("就绪服务控制器", controller)
        actions = controller.actions({}, {}, {}, {})
    }

    /** 控制器与界面使用相同主线程上下文。 */
    private fun main(action: () -> Unit) = inst.runOnMainSync(action)
}
