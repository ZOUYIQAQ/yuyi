package io.github.korenkrita.simulsub

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.online.CloudAsrConfig
import io.github.korenkrita.simulsub.online.LiveTranslateModels
import io.github.korenkrita.simulsub.ui.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/** 从真实动作入口验证两步选择，不发起网络或下载，不覆盖用户偏好。 */
class ServiceSelectionControllerDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val token = UUID.randomUUID().toString()
    private val store = ViewModelStore()
    private val application = object : Application() {
        /** 系统权限与资源沿用真实应用。 */
        override fun getApplicationContext(): Context = context.applicationContext
        /** 权限检查使用目标包名。 */
        override fun getPackageName(): String = context.packageName
        /** 每个用例独立保存，保留设备上的真实配置。 */
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            context.getSharedPreferences("两步服务-$token-$name", mode)
        /** 无字幕记录的测试目录。 */
        override fun getExternalFilesDir(type: String?): File = File(context.cacheDir, "两步服务-$token")
    }
    private lateinit var prefs: Prefs
    private lateinit var controller: AppController
    private lateinit var actions: SimulSubActions

    /** 在主线程建立控制器。 */
    @Before fun prepare() = main {
        prefs = Prefs(application)
        controller = AppController(application)
        store.put("控制器", controller)
        actions = controller.actions({}, {}, {}, {})
    }

    /** 释放控制器监听。 */
    @After fun finish() = main { store.clear() }

    /** 切换听写不覆盖文字服务，直译不依赖文字配置，并保留业务空间。 */
    @Test fun directSelectionSwitchesProtocolAndRetainsTranslation() = main {
        actions.onSelectListeningService("asr:${CloudAsrConfig.DEFAULT_MODEL}")
        assertEquals(ListeningMode.CLOUD_ASR, prefs.listeningMode)
        actions.onPlatformKeyChange("qwen", "测试-未发请求")
        actions.onSavePlatformKey("qwen")
        val profile = prefs.byokProfile.id
        actions.onSelectListeningService("direct:${LiveTranslateModels.ECONOMY_ID}")
        assertEquals(ListeningMode.ONLINE, prefs.listeningMode)
        assertEquals(LiveTranslateModels.ECONOMY_ENDPOINT, prefs.onlineEndpoint)
        assertEquals(profile, prefs.byokProfile.id)
        assertTrue(prefs.onlineReady)
        assertFalse(prefs.byokReady)
        actions.onSelectListeningService("direct:${LiveTranslateModels.REALTIME_ID}")
        assertEquals(LiveTranslateModels.REALTIME_ENDPOINT, prefs.onlineEndpoint)
        assertTrue(controller.state.value.live.listeningServiceId.startsWith("profile:"))
        assertEquals(controller.state.value.settings.listeningServices, controller.state.value.live.listeningServices)
    }

    /** 用户通过翻译下拉选择已保存的配置，不靠模式标签间接切换。 */
    @Test fun translationSelectionChangesActiveProfile() = main {
        val original = prefs.byokProfile.id
        actions.onAddApiProfile("第二个平台")
        val second = prefs.byokProfile.id
        assertNotEquals(original, second)
        actions.onSelectTranslationService(original)
        assertEquals(original, prefs.byokProfile.id)
        assertEquals(original, controller.state.value.live.translationServiceId)
        actions.onSelectTranslationService(second)
        assertEquals(second, controller.state.value.settings.translationServiceId)
    }

    /** 未保存的连接不会被切换服务静默丢弃。 */
    @Test fun dirtyConnectionBlocksServiceChangeAndInvalidChoiceIsIgnored() = main {
        actions.onSelectListeningService("direct:unknown")
        assertEquals(ListeningMode.LOCAL, prefs.listeningMode)
        actions.onCloudAsrDraftChange(controller.state.value.settings.cloudAsr.copy(endpoint = "wss://example.com/api-ws/v1/inference"))
        actions.onSelectListeningService("direct:${LiveTranslateModels.REALTIME_ID}")
        assertEquals(ListeningMode.LOCAL, prefs.listeningMode)
        assertTrue(controller.state.value.settings.cloudAsr.hasUnsavedChanges)
    }

    /** 首页下拉只有保存后的服务，新建和下载不会混在可选择选项里。 */
    @Test fun homeOnlyListsSavedServicesAndCreatedProfilesSurviveController() = main {
        assertTrue(controller.state.value.live.listeningServices.none { it.id == "catalog" || it.id == "manage" })
        assertEquals(2, controller.state.value.live.translationServices.size)
        actions.onCreateListeningService("asr", "日语直播听写")
        val profileId = controller.state.value.settings.listeningEditor!!.id
        assertTrue(controller.state.value.settings.listeningEditor!!.isNew)
        assertTrue(controller.state.value.live.listeningServices.none { it.label == "日语直播听写" })
        actions.onSaveListeningService()
        val profile = ListeningServiceRepository(application).profiles.single { it.id == profileId }
        assertFalse(controller.state.value.settings.listeningEditor!!.isNew)
        assertTrue(controller.state.value.live.listeningServices.any { it.id == "profile:${profile.id}" })
        actions.onCloseListeningServiceEditor()
        actions.onSelectListeningService("profile:${profile.id}")
        assertEquals(ListeningMode.CLOUD_ASR, prefs.listeningMode)
        assertEquals("profile:${profile.id}", controller.state.value.live.listeningServiceId)
        val restarted = AppController(application)
        store.put("重建控制器", restarted)
        assertTrue(restarted.state.value.live.listeningServices.any { it.id == "profile:${profile.id}" })
        assertEquals("profile:${profile.id}", restarted.state.value.live.listeningServiceId)
    }

    /** 编辑另一套服务不修改当前连接，保存和放弃都保持原首页选择。 */
    @Test fun editingOtherServiceKeepsActiveConnectionAndKeys() = main {
        actions.onCreateListeningService("asr", "当前听写")
        val firstId = controller.state.value.settings.listeningEditor!!.id
        actions.onSaveListeningService()
        val first = ListeningServiceRepository(application).profiles.single { it.id == firstId }
        actions.onCloseListeningServiceEditor()
        actions.onSelectListeningService("profile:${first.id}")
        val endpoint = prefs.cloudAsrEndpoint
        actions.onCreateListeningService("asr", "另一套听写")
        val otherId = controller.state.value.settings.listeningEditor!!.id
        actions.onCloudAsrDraftChange(controller.state.value.settings.cloudAsr.copy(endpoint = "wss://workspace.example/api-ws/v1/inference"))
        actions.onSaveListeningService()
        val other = ListeningServiceRepository(application).profiles.single { it.id == otherId }
        assertEquals(endpoint, prefs.cloudAsrEndpoint)
        assertEquals("profile:${first.id}", controller.state.value.live.listeningServiceId)
        actions.onCloseListeningServiceEditor()
        actions.onEditListeningService(other.id)
        assertEquals("wss://workspace.example/api-ws/v1/inference", controller.state.value.settings.cloudAsr.endpoint)
        actions.onListeningServiceNameChange("临时名称")
        assertTrue(controller.state.value.settings.cloudAsr.hasUnsavedChanges)
        actions.onDiscardCloudAsrDraft()
        assertEquals("另一套听写", controller.state.value.settings.listeningEditor!!.name)
        assertFalse(controller.state.value.settings.cloudAsr.hasUnsavedChanges)
        actions.onCloseListeningServiceEditor()
        assertEquals(endpoint, prefs.cloudAsrEndpoint)
        actions.onSelectListeningService("profile:${other.id}")
        assertEquals("wss://workspace.example/api-ws/v1/inference", prefs.cloudAsrEndpoint)
        assertTrue(prefs.cloudAsrKey.isEmpty())
    }

    /** 引导保留已恢复的本地选择；明确选中 A 后，保存 B 只更新目录。 */
    @Test fun guideExplicitSelectionKeepsFirstServiceAfterSavingSecond() = main {
        assertNotNull(controller.state.value.onboarding)
        actions.onCreateListeningService("asr", "引导听写 A")
        val firstId = controller.state.value.settings.listeningEditor!!.id
        actions.onSaveListeningService()
        val first = ListeningServiceRepository(application).profiles.single { it.id == firstId }
        assertNull(ListeningServiceRepository(application).activeId)
        assertEquals(ListeningMode.LOCAL, prefs.listeningMode)
        actions.onCloseListeningServiceEditor()
        actions.onSelectListeningService("profile:${first.id}")
        assertEquals(first.id, ListeningServiceRepository(application).activeId)
        assertEquals(ListeningMode.CLOUD_ASR, prefs.listeningMode)
        assertEquals("profile:${first.id}", controller.state.value.live.listeningServiceId)
        val endpoint = prefs.cloudAsrEndpoint
        actions.onCreateListeningService("asr", "引导听写 B")
        val secondId = controller.state.value.settings.listeningEditor!!.id
        actions.onCloudAsrDraftChange(controller.state.value.settings.cloudAsr.copy(
            endpoint = "wss://guide-b.example/api-ws/v1/inference"))
        actions.onSaveListeningService()
        assertEquals(endpoint, prefs.cloudAsrEndpoint)
        assertEquals(first.id, ListeningServiceRepository(application).activeId)
        assertEquals("profile:${first.id}", controller.state.value.live.listeningServiceId)
        assertTrue(ListeningServiceRepository(application).profiles.any { it.id == secondId })
    }

    /** 引导中明确选中听写 A 后，保存另一协议的 B 不切换模式或活动服务。 */
    @Test fun guideSavingOtherProtocolKeepsSelectedService() = main {
        actions.onCreateListeningService("asr", "引导听写 A")
        val firstId = controller.state.value.settings.listeningEditor!!.id
        actions.onSaveListeningService()
        val first = ListeningServiceRepository(application).profiles.single { it.id == firstId }
        actions.onCloseListeningServiceEditor()
        actions.onSelectListeningService("profile:${first.id}")
        actions.onCreateListeningService("realtime", "引导直译 B")
        val secondId = controller.state.value.settings.listeningEditor!!.id
        actions.onSaveListeningService()
        val second = ListeningServiceRepository(application).profiles.single { it.id == secondId }
        assertEquals(first.id, ListeningServiceRepository(application).activeId)
        assertEquals(ListeningMode.CLOUD_ASR, prefs.listeningMode)
        assertEquals("profile:${first.id}", controller.state.value.live.listeningServiceId)
        assertTrue(controller.state.value.live.listeningServices.any { it.id == "profile:${second.id}" })
    }

    /** 新配置保存前不在首页，保存后按实际平台和模型显示。 */
    @Test fun savedTranslationAppearsInHomeChoice() = main {
        actions.onAddApiProfile("DeepSeek 直播翻译")
        val id = prefs.byokProfile.id
        assertTrue(controller.state.value.live.translationServices.none { it.id == id })
        actions.onApiDraftChange(controller.state.value.settings.api.copy(
            url = "https://api.deepseek.com/v1/chat/completions", model = "deepseek-flash"))
        actions.onSaveApi()
        assertTrue(controller.state.value.live.translationServices.any {
            it.id == id && it.label.startsWith("DeepSeek · deepseek-flash")
        })
    }

    /** 编辑或创建备用翻译只影响编辑器，退出后运行配置和首页选择都恢复。 */
    @Test fun translationEditingDoesNotReplaceHomeSelection() = main {
        val original = prefs.byokProfile.id
        actions.onCreateTranslationService("备用直播翻译")
        val created = controller.state.value.settings.api.profileId
        assertNotEquals(original, created)
        assertEquals(original, prefs.byokProfile.id)
        assertEquals(original, controller.state.value.live.translationServiceId)
        actions.onApiDraftChange(controller.state.value.settings.api.copy(
            url = "https://api.deepseek.com/v1/chat/completions", model = "deepseek-flash"))
        actions.onSaveApi()
        assertEquals(original, prefs.byokProfile.id)
        assertEquals(original, controller.state.value.live.translationServiceId)
        actions.onCloseTranslationServiceEditor()
        assertEquals(original, prefs.byokProfile.id)
        assertTrue(controller.state.value.live.translationServices.any { it.id == created })
        actions.onEditTranslationService(created)
        assertEquals(created, controller.state.value.settings.api.profileId)
        assertEquals(original, prefs.byokProfile.id)
        assertEquals(original, controller.state.value.live.translationServiceId)
        actions.onCloseTranslationServiceEditor()
        assertEquals(original, prefs.byokProfile.id)
        actions.onSelectTranslationService(created)
        assertEquals(created, prefs.byokProfile.id)
    }

    /** 编辑备用翻译期间重建控制器，活动配置仍是此前明确选择的服务。 */
    @Test fun translationEditorRecreationKeepsActiveProfile() = main {
        val original = prefs.byokProfile.id
        actions.onCreateTranslationService("重建时备用翻译")
        val editing = controller.state.value.settings.api.profileId
        assertNotEquals(original, editing)
        assertEquals(original, prefs.byokProfile.id)
        val restarted = AppController(application)
        store.put("重建翻译控制器", restarted)
        assertEquals(original, prefs.byokProfile.id)
        assertEquals(original, restarted.state.value.live.translationServiceId)
        assertTrue(restarted.state.value.live.translationServices.none { it.id == editing })
    }

    /** 放弃并离开新建草稿后，不残留脏标记阻止首页使用已有服务。 */
    @Test fun abandonedNewServiceDoesNotBlockSelection() = main {
        actions.onCreateListeningService("asr", "已保存听写")
        val existingId = controller.state.value.settings.listeningEditor!!.id
        actions.onSaveListeningService()
        val existing = ListeningServiceRepository(application).profiles.single { it.id == existingId }
        actions.onCloseListeningServiceEditor()
        actions.onCreateListeningService("realtime", "临时直译")
        actions.onDiscardOnlineDraft()
        actions.onCloseListeningServiceEditor()
        assertFalse(controller.state.value.settings.online.hasUnsavedChanges)
        actions.onSelectListeningService("profile:${existing.id}")
        assertEquals("profile:${existing.id}", controller.state.value.live.listeningServiceId)
    }

    /** 所有状态动作在主线程执行，与真实界面一致。 */
    private fun main(action: () -> Unit) = inst.runOnMainSync(action)
}
