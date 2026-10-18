package io.github.korenkrita.simulsub

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.models.ModelRepository
import io.github.korenkrita.simulsub.online.LiveTranslateModels
import io.github.korenkrita.simulsub.ui.HeaderUiState
import io.github.korenkrita.simulsub.ui.OnboardingStep
import io.github.korenkrita.simulsub.ui.SimulSubActions
import io.github.korenkrita.simulsub.updates.AvailableUpdate
import io.github.korenkrita.simulsub.updates.ReleaseUpdateSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/** 真实控制器的引导与快捷密钥回归；只使用假密钥，不连接在线服务或下载模型。 */
class SetupCredentialControllerDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val token = UUID.randomUUID().toString()
    private val root = File(context.cacheDir, "引导快捷凭据-$token")
    private val store = ViewModelStore()
    private val application = object : Application() {
        /** 复用系统环境，但不将测试偏好写到真实应用空间。 */
        override fun getApplicationContext(): Context = context.applicationContext
        /** 权限查询保持实际应用身份。 */
        override fun getPackageName(): String = context.packageName
        /** 引导标记、服务目录和密钥均使用每例独占的命名空间。 */
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            context.getSharedPreferences("引导快捷凭据-$token-$name", mode)
        /** 日志与附属文件只访问本例缓存路径。 */
        override fun getExternalFilesDir(type: String?): File = File(root, "记录")
    }
    private lateinit var models: ModelRepository
    private lateinit var prefs: Prefs
    private lateinit var repo: ListeningServiceRepository
    private lateinit var controller: AppController
    private lateinit var actions: SimulSubActions
    private var updateRequests = 0
    private val releaseSource = object : ReleaseUpdateSource {
        /** 测试永不访问发行接口；若意外触发更新，结束时明确报错。 */
        override suspend fun latest(): AvailableUpdate? {
            updateRequests++
            return null
        }
    }

    /** 停止独立模型仓库的观察后注入真实控制器，避免依赖设备已有模型。 */
    @Before fun prepare() {
        val modelContext = object : ContextWrapper(context) {
            /** 模型扫描只允许访问本例目录，不验证或修改共享模型。 */
            override fun getFilesDir(): File = File(root, "模型")
            /** 模型选择状态也与用户偏好隔离。 */
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                application.getSharedPreferences(name, mode)
        }
        models = ModelRepository::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(modelContext)
        val scope = ModelRepository::class.java.getDeclaredField("scope")
            .apply { isAccessible = true }.get(models) as CoroutineScope
        runBlocking { withTimeout(5_000) { requireNotNull(scope.coroutineContext[Job]).cancelAndJoin() } }
        main {
            application.getSharedPreferences("updates", 0).edit().putBoolean("auto", false).commit()
            prefs = Prefs(application)
            repo = ListeningServiceRepository(application)
            startController()
        }
    }

    /** 只释放控制器监听与协程，保留测试文件，不删除任何设备数据。 */
    @After fun finish() = main {
        store.clear()
        assertEquals("配置动作不应请求在线更新", 0, updateRequests)
    }

    /** 列表按目标标识删除备用服务，不切换活动项、不清除共享平台密钥或别的配置。 */
    @Test fun deleteServiceByIdPreservesActiveAndSharedCredentials() = main {
        val active = prefs.byokProfile.id
        val target = prefs.addByokProfile("待删除的备用服务", select = false)
        prefs.savePlatformCredential("deepseek", "default", "默认密钥", "仅用于删除回归的假密钥")
        prefs.saveByok("https://api.deepseek.com/v1/chat/completions", "delete-fixture", null, profile = target)
        restartController()
        val before = prefs.byokProfiles.filterNot { it.id == target.id }
        actions.onDeleteTranslationService(target.id)
        assertEquals(before, prefs.byokProfiles)
        assertEquals(active, prefs.byokProfile.id)
        assertEquals("仅用于删除回归的假密钥", prefs.platformKey("deepseek"))
        assertTrue(controller.state.value.settings.translationProfiles.none { it.id == target.id })
        restartController()
        assertTrue(prefs.byokProfiles.none { it.id == target.id })
    }

    /** 删除活动服务按原存储规则切到剩余项；未保存的表单则阻止任何删除。 */
    @Test fun deletingActiveServiceSwitchesSafelyAndDirtyDraftBlocksDeletion() = main {
        val active = prefs.byokProfile.id
        val other = prefs.byokProfiles.first { it.id != active }
        actions.onApiDraftChange(controller.state.value.settings.api.copy(model = "尚未保存的假模型"))
        actions.onDeleteTranslationService(other.id)
        assertTrue(prefs.byokProfiles.any { it.id == other.id })
        actions.onDiscardApiDraft()
        actions.onDeleteTranslationService(active)
        assertTrue(prefs.byokProfiles.none { it.id == active })
        assertTrue(prefs.byokProfiles.any { it.id == prefs.byokProfile.id })
        assertEquals(prefs.byokProfile.id, controller.state.value.settings.api.profileId)
    }

    /** 听写缺少千问密钥仍可从模式选择直达连接步骤，首页继续过滤未就绪服务。 */
    @Test fun onboardingAsrWithoutKeyOpensOnlineSetupWithoutCompleting() = main {
        assertMissingKeyChoiceAdvances(repo.profiles.single { it.mode == ListeningMode.CLOUD_ASR })
    }

    /** 直接听译同样无需先配置密钥即可进入连接步骤，不提前完成引导。 */
    @Test fun onboardingRealtimeWithoutKeyOpensOnlineSetupWithoutCompleting() = main {
        assertMissingKeyChoiceAdvances(repo.profiles.single { it.model == LiveTranslateModels.REALTIME_ID })
    }

    /** 裸音频标识首次填写保留跟随默认，第二把显式绑定 UUID，均不改变连接草稿。 */
    @Test fun quickAudioKeysKeepFirstFollowingDefaultAndBindSecondUuidWithoutChangingDrafts() = main {
        val asr = repo.profiles.single { it.mode == ListeningMode.CLOUD_ASR }
        val realtime = repo.profiles.single { it.model == LiveTranslateModels.REALTIME_ID }
        makeDirtyConnectionDrafts()
        val before = connectionState()
        val firstRaw = "  假密钥甲\n原文尾部  "
        val defaultId = prefs.defaultPlatformCredentialId("qwen")
        assertNull(prefs.selectedCredentialId("qwen", slot(asr)))
        val firstId = saveQuickKey("qwen", asr.id, firstRaw)
        assertEquals(defaultId, firstId)
        assertNull(prefs.selectedCredentialId("qwen", slot(asr)))
        assertEquals(firstRaw, prefs.credentialFor("qwen", slot(asr))?.key)
        assertEquals(before, connectionState())

        val secondRaw = "  假密钥乙\n第二把尾部  "
        val secondId = saveQuickKey("qwen", realtime.id, secondRaw, "第二把假密钥")
        assertCanonicalUuid(secondId)
        assertNotEquals(firstId, secondId)
        assertEquals(secondId, prefs.selectedCredentialId("qwen", slot(realtime)))
        assertEquals(secondRaw, prefs.credentialFor("qwen", slot(realtime))?.key)
        assertEquals(listOf(firstId, secondId), prefs.platformCredentials("qwen").map { it.id })
        assertEquals(firstRaw, prefs.platformKey("qwen"))
        assertEquals(before, connectionState())
        val reopened = Prefs(application)
        assertNull(reopened.selectedCredentialId("qwen", slot(asr)))
        assertEquals(secondId, reopened.selectedCredentialId("qwen", slot(realtime)))
        assertEquals(firstRaw, reopened.credentialFor("qwen", slot(asr))?.key)
        assertEquals(secondRaw, reopened.credentialFor("qwen", slot(realtime))?.key)
        assertEquals("第二把假密钥", reopened.platformCredentials("qwen").single { it.id == secondId }.label)
    }

    /** 首次写入当前空备用默认项仍保留跟随关系，之后换默认会立即读取新项。 */
    @Test fun firstQuickKeyReusesTheSelectedEmptyDefaultCredential() = main {
        val selectedDefault = UUID.randomUUID().toString()
        prefs.savePlatformCredential("qwen", selectedDefault, "待填写的默认项", "")
        prefs.setDefaultPlatformCredential("qwen", selectedDefault)
        restartController()
        val asr = repo.profiles.single { it.mode == ListeningMode.CLOUD_ASR }
        val before = connectionState()
        val raw = "  假密钥：当前默认\n "
        assertEquals(selectedDefault, saveQuickKey("qwen", asr.id, raw))
        assertEquals(selectedDefault, prefs.defaultPlatformCredentialId("qwen"))
        assertEquals(listOf("default", selectedDefault), prefs.platformCredentials("qwen").map { it.id })
        assertEquals("", prefs.platformCredentials("qwen").first().key)
        assertNull(prefs.selectedCredentialId("qwen", slot(asr)))
        assertEquals(raw, prefs.credentialFor("qwen", slot(asr))?.key)
        assertEquals(before, connectionState())
        val realtime = repo.profiles.single { it.model == LiveTranslateModels.REALTIME_ID }
        val nextRaw = "  假密钥：后续默认\n "
        val nextId = saveQuickKey("qwen", realtime.id, nextRaw, "后续默认")
        assertCanonicalUuid(nextId)
        assertNotEquals(selectedDefault, nextId)
        actions.onSetDefaultPlatformKey("qwen", nextId)
        assertNull(prefs.selectedCredentialId("qwen", slot(asr)))
        assertEquals(nextRaw, prefs.credentialFor("qwen", slot(asr))?.key)
        assertEquals(raw, prefs.platformCredentials("qwen").single { it.id == selectedDefault }.key)
        assertEquals(before, connectionState())
    }

    /** 翻译来源首次填写默认保留跟随，不启用目标配置或保存地址模型与请求头草稿。 */
    @Test fun quickTranslationKeyBindsTargetWithoutActivatingOrSavingDrafts() = main {
        val active = prefs.byokProfile.id
        val target = prefs.byokProfiles.first { it.id != active }
        actions.onEditTranslationService(target.id)
        assertEquals(target.id, controller.state.value.settings.api.profileId)
        assertEquals(active, prefs.byokProfile.id)
        makeDirtyConnectionDrafts()
        val platform = requireNotNull(PlatformCredentials.forUrl(controller.state.value.settings.api.url)).id
        val before = connectionState()
        val raw = "  假翻译密钥\n原文  "
        val id = saveQuickKey(platform, "translation:${target.id}", raw)
        assertEquals(prefs.defaultPlatformCredentialId(platform), id)
        assertNull(prefs.selectedCredentialId(platform, translationCredentialSlot(target.id)))
        assertEquals(raw, prefs.byokService(target)?.key)
        assertEquals(active, prefs.byokProfile.id)
        assertEquals(before, connectionState())
    }

    /** 音频草稿入口首次填写仍跟随默认，第二把绑定新项，连接字段始终不落盘。 */
    @Test fun quickAudioAliasesBindDirtySlotsWithoutSavingConnections() = main {
        makeDirtyConnectionDrafts()
        val before = connectionState()
        for (mode in listOf(ListeningMode.CLOUD_ASR, ListeningMode.ONLINE)) {
            val draft = if (mode == ListeningMode.CLOUD_ASR) controller.state.value.settings.cloudAsr
                else controller.state.value.settings.online
            val raw = "  假音频密钥-${mode.name}\n "
            val id = saveQuickKey("qwen", "audio:${mode.name}", raw)
            val slot = listeningCredentialSlot(mode, draft.endpoint, draft.model)
            if (mode == ListeningMode.CLOUD_ASR) assertNull(prefs.selectedCredentialId("qwen", slot))
            else assertEquals(id, prefs.selectedCredentialId("qwen", slot))
            assertEquals(raw, prefs.keyForService(draft.endpoint, slot))
            assertEquals(before, connectionState())
        }
    }

    /** Jev 来源按供应商槽位绑定，保存第二把不覆盖平台当前默认或修改其他服务选择。 */
    @Test fun quickJevKeysBindProviderAndPreserveOtherConfiguration() = main {
        makeDirtyConnectionDrafts()
        val before = connectionState()
        for (provider in listOf(JevProvider.OFFICIAL, JevProvider.OPENROUTER)) {
            val platform = requireNotNull(PlatformCredentials.forUrl(provider.url)).id
            val rawA = "  假Jev甲-${provider.name}\n "
            val firstId = saveQuickKey(platform, "jev:${provider.name}", rawA)
            assertEquals(prefs.defaultPlatformCredentialId(platform), firstId)
            assertNull(prefs.selectedCredentialId(platform, jevCredentialSlot(provider)))
            assertEquals(rawA, prefs.jevKey(provider))
            val rawB = "  假Jev乙-${provider.name}\n "
            val secondId = saveQuickKey(platform, "jev:${provider.name}", rawB, "Jev 第二把")
            assertCanonicalUuid(secondId)
            assertEquals(secondId, prefs.selectedCredentialId(platform, jevCredentialSlot(provider)))
            assertEquals(rawB, prefs.jevKey(provider))
            assertEquals(rawA, prefs.platformKey(platform))
            assertEquals(before, connectionState())
        }
    }

    /** 首次填写者跟随新默认，第二把 UUID 和另行显式绑定的甲始终独立，重建后仍成立。 */
    @Test fun changingDefaultMovesFirstFollowerButPreservesExplicitAAndSecondUuid() = main {
        val asr = repo.profiles.single { it.mode == ListeningMode.CLOUD_ASR }
        val realtime = repo.profiles.single { it.model == LiveTranslateModels.REALTIME_ID }
        val economy = repo.profiles.single { it.model == LiveTranslateModels.ECONOMY_ID }
        val translation = prefs.byokProfiles.single {
            PlatformCredentials.forUrl(prefs.byokUrl(it)) == PlatformCredentials.QWEN
        }
        val rawA = "  假默认甲\n "
        val rawB = "  假默认乙\n "
        val idA = saveQuickKey("qwen", asr.id, rawA)
        val idB = saveQuickKey("qwen", realtime.id, rawB, "默认乙")
        assertNull(prefs.selectedCredentialId("qwen", slot(asr)))
        assertEquals(idB, prefs.selectedCredentialId("qwen", slot(realtime)))
        actions.onServiceCredentialChange(economy.id, idA)
        assertEquals(idA, prefs.selectedCredentialId("qwen", slot(economy)))
        assertNull(prefs.selectedCredentialId("qwen", translationCredentialSlot(translation.id)))
        val oldTranslation = requireNotNull(prefs.byokService(translation))
        val before = connectionState()
        val credentials = prefs.platformCredentials("qwen")

        actions.onSetDefaultPlatformKey("qwen", idB)

        assertEquals(idB, prefs.defaultPlatformCredentialId("qwen"))
        assertEquals(rawB, prefs.platformKey("qwen"))
        assertNull(prefs.selectedCredentialId("qwen", slot(asr)))
        assertEquals(rawB, prefs.credentialFor("qwen", slot(asr))?.key)
        assertEquals(idB, prefs.selectedCredentialId("qwen", slot(realtime)))
        assertEquals(rawB, prefs.credentialFor("qwen", slot(realtime))?.key)
        assertEquals(idA, prefs.selectedCredentialId("qwen", slot(economy)))
        assertEquals(rawA, prefs.credentialFor("qwen", slot(economy))?.key)
        assertEquals(rawB, prefs.byokService(translation)?.key)
        assertEquals(rawA, oldTranslation.key)
        assertEquals(credentials, prefs.platformCredentials("qwen"))
        assertEquals(before, connectionState())

        actions.onSetDefaultPlatformKey("qwen", idA)
        assertNull(prefs.selectedCredentialId("qwen", slot(asr)))
        assertEquals(rawA, prefs.credentialFor("qwen", slot(asr))?.key)
        assertEquals(idB, prefs.selectedCredentialId("qwen", slot(realtime)))
        assertEquals(rawB, prefs.credentialFor("qwen", slot(realtime))?.key)
        assertEquals(rawA, prefs.credentialFor("qwen", slot(economy))?.key)
        assertEquals(rawA, prefs.byokService(translation)?.key)
        assertEquals(before, connectionState())
        actions.onSetDefaultPlatformKey("qwen", idB)
        restartController()
        val reopened = Prefs(application)
        assertEquals(idB, reopened.defaultPlatformCredentialId("qwen"))
        assertNull(reopened.selectedCredentialId("qwen", slot(asr)))
        assertEquals(rawB, reopened.credentialFor("qwen", slot(asr))?.key)
        assertEquals(idA, reopened.selectedCredentialId("qwen", slot(economy)))
        assertEquals(rawA, reopened.credentialFor("qwen", slot(economy))?.key)
        assertEquals(idB, reopened.selectedCredentialId("qwen", slot(realtime)))
        assertEquals(rawB, reopened.credentialFor("qwen", slot(realtime))?.key)
        assertEquals(rawB, reopened.byokService(translation)?.key)
        assertEquals(before, connectionState())
    }

    /** 检查模式步骤不受缺钥阻碍，下一步仍要求保存凭据且不会误写完成标记。 */
    private fun assertMissingKeyChoiceAdvances(profile: ListeningServiceProfile) {
        assertEquals("", prefs.platformKey("qwen"))
        assertEquals(OnboardingStep.PERMISSIONS, controller.state.value.onboarding?.step)
        actions.onOnboardingSkip()
        assertEquals(OnboardingStep.MODE, controller.state.value.onboarding?.step)
        assertTrue(controller.state.value.settings.listeningProfiles.any { it.id == profile.id && !it.configured })
        actions.onChooseOnboardingListeningService("profile:${profile.id}")
        assertEquals(OnboardingStep.ONLINE, controller.state.value.onboarding?.step)
        assertEquals(profile.mode, prefs.listeningMode)
        assertEquals(profile.id, repo.activeId)
        assertEquals("profile:${profile.id}", controller.state.value.live.listeningServiceId)
        assertFalse(controller.state.value.onboarding!!.onlineReady)
        assertFalse(completed())
        assertFalse(controller.state.value.live.canStart)
        assertTrue(controller.state.value.live.listeningServices.none { it.id.startsWith("profile:") })
        assertTrue(controller.state.value.live.translationServices.isEmpty())
        actions.onOnboardingNext()
        assertEquals(OnboardingStep.ONLINE, controller.state.value.onboarding?.step)
        assertFalse(completed())
        restartController()
        assertNotNull(controller.state.value.onboarding)
        assertFalse(completed())
        assertTrue(controller.state.value.live.listeningServices.none { it.id.startsWith("profile:") })
    }

    /** 从真实动作打开、输入并保存，逐步核对原文与关闭状态，输入阶段禁止落盘。 */
    private fun saveQuickKey(platformId: String, serviceId: String, raw: String, name: String? = null): String {
        val oldCredentials = prefs.platformCredentials(platformId)
        actions.onOpenQuickPlatformKey(platformId, serviceId)
        val opened = requireNotNull(controller.state.value.settings.quickPlatformKey)
        assertEquals(platformId, opened.platformId)
        assertEquals(serviceId, opened.serviceId)
        assertEquals("", opened.key)
        actions.onQuickPlatformKeyValueChange(raw)
        if (name != null) actions.onQuickPlatformKeyNameChange(name)
        assertEquals(raw, controller.state.value.settings.quickPlatformKey?.key)
        assertEquals(oldCredentials, prefs.platformCredentials(platformId))
        actions.onSaveQuickPlatformKey()
        assertNull(controller.state.value.settings.quickPlatformKey)
        assertEquals(raw, prefs.platformCredentials(platformId).single { it.id == opened.credentialId }.key)
        return opened.credentialId
    }

    /** 为三路连接制造未保存输入；快捷保存必须保留这些地址、模型和附属字段。 */
    private fun makeDirtyConnectionDrafts() {
        actions.onApiDraftChange(controller.state.value.settings.api.copy(
            url = controller.state.value.settings.api.url.trimEnd('/') + "/draft-only",
            model = "未保存的假翻译模型", userAgent = "未保存的假客户端",
            headers = listOf(HeaderUiState("假请求头", "X-Setup-Fixture", "未保存原文"))))
        actions.onCloudAsrDraftChange(controller.state.value.settings.cloudAsr.copy(
            endpoint = "wss://llm-fixture.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference",
            model = "未保存的假听写模型"))
        actions.onOnlineDraftChange(controller.state.value.settings.online.copy(
            endpoint = "wss://llm-fixture.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime",
            model = "未保存的假听译模型"))
        assertTrue(controller.state.value.settings.api.hasUnsavedChanges)
        assertTrue(controller.state.value.settings.cloudAsr.hasUnsavedChanges)
        assertTrue(controller.state.value.settings.online.hasUnsavedChanges)
    }

    /** 同时记录持久化连接、当前服务和草稿输入，排除预期变化的密钥及凭据选项。 */
    private fun connectionState(): Map<String, Any?> {
        val state = controller.state.value
        val settings = state.settings
        return mapOf(
            "运行模式" to prefs.listeningMode,
            "活动听写服务" to repo.activeId,
            "活动翻译服务" to prefs.byokProfile.id,
            "首页听写选择" to state.live.listeningServiceId,
            "首页翻译选择" to state.live.translationServiceId,
            "听写服务目录" to repo.profiles,
            "翻译服务目录" to prefs.byokProfiles,
            "保存的在线连接" to listOf(prefs.onlineEndpoint, prefs.onlineModel),
            "保存的听写连接" to listOf(prefs.cloudAsrEndpoint, prefs.cloudAsrModel),
            "保存的翻译连接" to prefs.byokProfiles.map {
                listOf(it.id, prefs.byokUrl(it), prefs.byokModel(it), prefs.byokUserAgent(it), prefs.byokHeaders(it))
            },
            "保存的翻译版本" to application.getSharedPreferences("jpsub", 0).all["byokSaved"],
            "在线草稿" to listOf(settings.online.endpoint, settings.online.model, settings.online.hasUnsavedChanges),
            "听写草稿" to listOf(settings.cloudAsr.endpoint, settings.cloudAsr.model, settings.cloudAsr.hasUnsavedChanges),
            "翻译草稿" to listOf(settings.api.profileId, settings.api.url, settings.api.model,
                settings.api.userAgent, settings.api.headers, settings.api.hasUnsavedChanges),
            "听写编辑器" to settings.listeningEditor,
            "翻译编辑器" to settings.translationEditorId,
            "辅助服务选择" to prefs.jevProvider,
        )
    }

    /** 完成状态必须从隔离偏好读取，不能仅依据界面当前步骤。 */
    private fun completed(): Boolean = application.getSharedPreferences("appearance", 0)
        .getBoolean("onboarding_completed", false)

    /** 命名密钥使用规范 UUID，与固定中央标识和其他命名项区分。 */
    private fun assertCanonicalUuid(id: String) {
        assertNotEquals("default", id)
        assertEquals(id, UUID.fromString(id).toString())
    }

    /** 音频槽位使用实际服务连接字段，与生产解析规则一致。 */
    private fun slot(profile: ListeningServiceProfile): String =
        listeningCredentialSlot(profile.mode, profile.endpoint, profile.model)

    /** 释放旧控制器再从相同隔离偏好重建，不改变用户的模型仓库单例。 */
    private fun restartController() {
        store.clear()
        startController()
    }

    /** 创建真实动作入口并替换模型依赖，发行检查使用本地假响应。 */
    private fun startController() {
        controller = AppController(application, releaseSource) { 1_000L }
        AppController::class.java.getDeclaredField("models").apply { isAccessible = true }.set(controller, models)
        store.put("引导快捷凭据控制器", controller)
        actions = controller.actions({}, {}, {}, {})
        controller.setPermissions(emptyList())
    }

    /** 控制器动作与断言均在主线程执行，与真实页面调用方式保持一致。 */
    private fun main(action: () -> Unit) = inst.runOnMainSync(action)
}
