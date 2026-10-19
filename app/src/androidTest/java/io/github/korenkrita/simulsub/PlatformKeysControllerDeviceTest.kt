package io.github.korenkrita.simulsub

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.ui.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** 中央凭据与真实控制器连通，不使用网络、系统录音或用户的偏好文件。 */
class PlatformKeysControllerDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val token = UUID.randomUUID().toString()
    private val store = ViewModelStore()
    private val application = object : Application() {
        /** 原生模型仓库沿用真实上下文，只读现有模型目录。 */
        override fun getApplicationContext(): Context = context.applicationContext
        /** 权限检查使用实际应用身份。 */
        override fun getPackageName(): String = context.packageName
        /** 每个用例独占偏好，禁止覆盖用户的密钥和模式。 */
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = context.getSharedPreferences("平台控制器-$token-$name", mode)
        /** 日志索引使用空目录，测试不创建字幕记录。 */
        override fun getExternalFilesDir(type: String?): File = File(context.cacheDir, "平台控制器-$token")
    }
    private lateinit var prefs: Prefs
    private lateinit var controller: AppController
    private lateinit var actions: SimulSubActions

    /** 在主线程创建真实生产控制器，构造已授予的权限快照。 */
    @Before fun prepare() = onMain {
        prefs = Prefs(application)
        controller = AppController(application)
        store.put("平台配置", controller)
        actions = controller.actions({}, {}, {}, {})
        controller.setPermissions(listOf(PermissionUiState("audio", "录制", "", true), PermissionUiState("overlay", "字幕", "", true)))
    }

    /** 释放控制器的监听和协程，不删除任何数据。 */
    @After fun finish() = onMain { store.clear() }

    /** 删除动作同步存储与就绪状态，空占位隐藏且备用密钥不被借用。 */
    @Test fun deletingDefaultUpdatesReadinessAndHiddenPlaceholder() = onMain {
        actions.onPlatformKeyChange("qwen", "测试默认密钥")
        actions.onSavePlatformKey("qwen")
        val alternate = UUID.randomUUID().toString()
        prefs.savePlatformCredential("qwen", alternate, "备用账号", "测试备用密钥")
        actions.onDeletePlatformKey("qwen", "default")
        assertEquals("", prefs.onlineKey)
        assertFalse(controller.state.value.settings.online.hasSavedConfiguration)
        assertTrue(controller.state.value.settings.platformKeys.none { it.id == "qwen" && it.credentialId == "default" && !it.isPlaceholder })
        assertTrue(controller.state.value.settings.platformKeys.any { it.credentialId == alternate && !it.isPlaceholder })
    }

    /** 快捷新增可修复删除后的失效绑定，不重用被删除凭据的标识。 */
    @Test fun quickAddRepairsDeletedCredentialForItsOwnService() = onMain {
        actions.onPlatformKeyChange("deepseek", "旧测试密钥")
        actions.onSavePlatformKey("deepseek")
        val serviceId = "translation:${prefs.byokProfile.id}"
        prefs.selectCredential("deepseek", translationCredentialSlot(prefs.byokProfile.id), "default")
        actions.onDeletePlatformKey("deepseek", "default")
        actions.onOpenQuickPlatformKey("deepseek", serviceId)
        assertNotNull(controller.state.value.settings.quickPlatformKey)
        actions.onQuickPlatformKeyValueChange("新测试密钥")
        actions.onSaveQuickPlatformKey()
        assertNull(controller.state.value.settings.quickPlatformKey)
        assertEquals("新测试密钥", prefs.byokKey)
        assertNotEquals("default", prefs.selectedCredentialId("deepseek", translationCredentialSlot(prefs.byokProfile.id)))
    }

    /** 只放弃目标密钥草稿，另一个平台的未保存文本不丢失。 */
    @Test fun discardOnlyAffectsOneCredential() = onMain {
        actions.onPlatformKeyChange("qwen", "草稿一")
        actions.onPlatformKeyChange("deepseek", "草稿二")
        actions.onDiscardPlatformKey("qwen", "default")
        assertEquals("", controller.state.value.settings.platformKeys.first { it.id == "qwen" }.key)
        assertEquals("草稿二", controller.state.value.settings.platformKeys.first { it.id == "deepseek" }.key)
        assertTrue(controller.state.value.settings.platformKeys.first { it.id == "deepseek" }.hasUnsavedChanges)
    }

    /** 保存后原文不清空；同一个长密钥立即用于两种千问音频连接。 */
    @Test fun rawKeyRoundtripAndSharedAcrossCloudModes() = onMain {
        val raw = " sk-ws-H.PYDLPID_+-/=".repeat(40) + "中文\n尾部 "
        actions.onPlatformKeyChange("qwen", raw)
        assertEquals(raw, controller.state.value.settings.platformKeys.first { it.id == "qwen" }.key)
        assertEquals("", prefs.onlineKey)
        actions.onSavePlatformKey("qwen")
        assertEquals(raw, prefs.onlineKey)
        assertEquals(raw, prefs.cloudAsrKey)
        assertEquals(raw, controller.state.value.settings.online.key)
        assertEquals(raw, controller.state.value.settings.cloudAsr.key)
        assertFalse(controller.state.value.settings.platformKeys.first { it.id == "qwen" }.hasUnsavedChanges)
        ListeningMode.entries.forEach { mode ->
            actions.onListeningModeChange(mode)
            assertEquals(raw, controller.state.value.settings.platformKeys.first { it.id == "qwen" }.key)
        }
    }

    /** 听写字段只更新听写连接，不再错误写入直接听译连接。 */
    @Test fun asrDraftAndDirectDraftRemainSeparateWhileKeyIsShared() = onMain {
        actions.onPlatformKeyChange("qwen", "sk-ws-test._-=")
        actions.onSavePlatformKey("qwen")
        val original = controller.state.value.settings.online.endpoint
        actions.onCloudAsrDraftChange(controller.state.value.settings.cloudAsr.copy(endpoint = "wss://workspace.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference"))
        assertEquals(original, controller.state.value.settings.online.endpoint)
        assertTrue(controller.state.value.settings.cloudAsr.hasUnsavedChanges)
        assertFalse(controller.state.value.settings.online.hasUnsavedChanges)
        actions.onSaveCloudAsr()
        assertEquals("wss://workspace.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference", prefs.cloudAsrEndpoint)
        assertEquals("sk-ws-test._-=", prefs.onlineKey)
    }

    /** 预设与手工地址绑定目标平台；保存旧连接草稿不能覆写新平台密钥。 */
    @Test fun presetsReuseTheirPlatformAndNeverCopyForeignKeys() = onMain {
        actions.onPlatformKeyChange("deepseek", "first-key")
        actions.onSavePlatformKey("deepseek")
        actions.onApplyApiPreset("deepseek")
        assertEquals("https://api.deepseek.com/v1/chat/completions", controller.state.value.settings.api.url)
        assertEquals("deepseek", controller.state.value.settings.api.platformId)
        assertEquals("first-key", controller.state.value.settings.api.key)
        actions.onPlatformKeyChange("deepseek", "new-key")
        actions.onSavePlatformKey("deepseek")
        actions.onSaveApi()
        assertEquals("new-key", prefs.byokService()?.key)
        actions.onApiDraftChange(controller.state.value.settings.api.copy(url = "https://unknown.example/v1"))
        assertEquals("", controller.state.value.settings.api.key)
        assertFalse(controller.state.value.settings.api.hasSavedKey)
        actions.onApplyApiPreset("deepseek")
        assertEquals("new-key", controller.state.value.settings.api.key)
    }

    /** 直接语音翻译不依赖文字接口，本地与在线听写仍需要配置文字翻译。 */
    @Test fun directTranslationSkipsTextApiAndCloudAsrRequiresIt() = onMain {
        actions.onPlatformKeyChange("qwen", "test-key")
        actions.onSavePlatformKey("qwen")
        actions.onListeningModeChange(ListeningMode.ONLINE)
        assertFalse(prefs.byokReady)
        assertTrue(controller.state.value.live.canStart)
        assertFalse(onboardingSteps(ListeningMode.ONLINE).contains(OnboardingStep.API))
        actions.onListeningModeChange(ListeningMode.CLOUD_ASR)
        assertFalse(controller.state.value.live.canStart)
        actions.onApplyApiPreset("deepseek")
        actions.onPlatformKeyChange("deepseek", "test-translation-key")
        actions.onSavePlatformKey("deepseek")
        actions.onSaveApi()
        assertTrue(controller.state.value.live.canStart)
    }

    /** 音频改到自定义来源后可以配置专用密钥，不能继承官方千问凭据。 */
    @Test fun customAudioOriginAppearsAndCannotInheritOfficialKey() = onMain {
        actions.onPlatformKeyChange("qwen", "official-qwen")
        actions.onSavePlatformKey("qwen")
        actions.onCloudAsrDraftChange(controller.state.value.settings.cloudAsr.copy(endpoint = "wss://private.example/api-ws/v1/inference"))
        val id = "origin:https://private.example"
        assertEquals("", controller.state.value.settings.platformKeys.single { it.id == id }.key)
        actions.onPlatformKeyChange(id, "private-only")
        actions.onSavePlatformKey(id)
        actions.onSaveCloudAsr()
        assertEquals("private-only", prefs.cloudAsrConfig().apiKey)
        assertEquals("official-qwen", prefs.onlineConfig().apiKey)
        actions.onCloudAsrDraftChange(controller.state.value.settings.cloudAsr.copy(endpoint = "wss://another.example/api-ws/v1/inference"))
        actions.onSaveCloudAsr()
        assertEquals("", prefs.cloudAsrConfig().apiKey)
        assertEquals("official-qwen", prefs.platformKey("qwen"))
    }

    /** 放弃集中密钥草稿仅恢复输入，不清除已保存密钥。 */
    @Test fun discardRestoresSavedKey() = onMain {
        actions.onPlatformKeyChange("qwen", "saved")
        actions.onSavePlatformKey("qwen")
        actions.onPlatformKeyChange("qwen", "draft")
        actions.onDiscardPlatformKeys()
        assertEquals("saved", controller.state.value.settings.platformKeys.first { it.id == "qwen" }.key)
        assertEquals("saved", prefs.onlineKey)
    }

    /** Android控制器状态仅在主线程读写，断言失败正常向测试线程传播。 */
    private fun onMain(block: () -> Unit) { inst.runOnMainSync(block) }
}
