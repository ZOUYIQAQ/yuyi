package io.github.korenkrita.simulsub

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.ui.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** 使用隔离偏好测试真实加密保存和草稿回填，不调用网络、录音或模型下载。 */
class ApiKeySaveControllerDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val token = UUID.randomUUID().toString()
    private val store = ViewModelStore()
    private val application = object : Application() {
        /** 系统能力只读取真实应用上下文。 */
        override fun getApplicationContext(): Context = context.applicationContext
        /** 保持应用身份，避免测试 Application 未绑定基础上下文。 */
        override fun getPackageName(): String = context.packageName
        /** 每例独立保存，绝不改写用户的真实接口或引导状态。 */
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            context.getSharedPreferences("密钥保存-$token-$name", mode)
        /** 记录扫描仅访问本例路径。 */
        override fun getExternalFilesDir(type: String?): File = File(context.cacheDir, "密钥保存-$token")
    }
    private lateinit var controller: AppController
    private lateinit var actions: SimulSubActions
    private lateinit var prefs: Prefs

    /** 所有业务动作与真实页面一样在主线程执行。 */
    @Before fun prepare() = onMain {
        prefs = Prefs(application)
        createController()
    }

    /** 只释放本例监听和协程，不删除任何文件。 */
    @After fun finish() = onMain { store.clear() }

    /** 保存后不清空输入值，重新创建仍可见，同时偏好里保持密文。 */
    @Test fun saveAndRecreateKeepKeyVisibleAndEncrypted() = onMain {
        actions.onApiDraftChange(controller.state.value.settings.api.copy(
            url = "https://example.com/v1", model = "测试模型", key = "本次输入的测试密钥"))
        assertTrue(controller.state.value.settings.api.hasUnsavedChanges)
        actions.onSaveApi()
        assertSavedKey("本次输入的测试密钥")
        assertEquals("翻译配置已保存", controller.state.value.settings.api.message?.text)
        val stored = application.getSharedPreferences("jpsub", 0).getString("byokKey", null)
        assertNotNull("偏好中必须真实写入密文", stored)
        assertNotEquals("偏好中不能出现明文密钥", "本次输入的测试密钥", stored)
        createController()
        assertSavedKey("本次输入的测试密钥")
    }

    /** 从真实输入框输入并点击保存，控制器、密文存储与重组后的输入框必须一致。 */
    @Test fun typingAndSavingThroughRealFormKeepsKeyVisible() {
        onMain {
            actions.onApiDraftChange(controller.state.value.settings.api.copy(
                url = "https://example.com/v1", model = "表单测试模型"))
        }
        compose.setContent {
            val state by controller.state.collectAsState()
            SimulSubTheme(state.settings) {
                Box(Modifier.requiredSize(360.dp, 740.dp)) {
                    TranslationApiSettings(state.settings.api, false, actions, false, null)
                }
            }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("API 密钥"))
        compose.onNode(hasSetTextAction() and hasText("API 密钥", substring = true)).performTextInput("表单新输入的测试密钥")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("保存翻译配置"))
        compose.onNodeWithText("保存翻译配置").performScrollTo().performClick()
        onMain { assertSavedKey("表单新输入的测试密钥") }
        compose.onNodeWithText("保存状态：已保存").performScrollTo().assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("API 密钥"))
        compose.onNodeWithContentDescription("显示密钥").performClick()
        compose.onNode(hasSetTextAction() and hasText("API 密钥", substring = true))
            .assertTextContains("表单新输入的测试密钥")
    }

    /** 输入框留空对应保留契约；只有显式清空才移除旧密钥，放弃草稿恢复旧值。 */
    @Test fun retainClearAndDiscardUseOneConsistentKeyContract() = onMain {
        prefs.saveByok("https://example.com/v1", "测试模型", "原有测试密钥", "", emptyList())
        actions.onDiscardApiDraft()
        actions.onApiDraftChange(controller.state.value.settings.api.copy(key = null, model = "修改模型"))
        actions.onSaveApi()
        assertSavedKey("原有测试密钥")
        actions.onApiDraftChange(controller.state.value.settings.api.copy(key = "未保存的新密钥"))
        assertTrue(controller.state.value.settings.api.hasUnsavedChanges)
        actions.onDiscardApiDraft()
        assertSavedKey("原有测试密钥")
        actions.onApiDraftChange(controller.state.value.settings.api.copy(key = ""))
        assertTrue(controller.state.value.settings.api.hasUnsavedChanges)
        actions.onSaveApi()
        assertNull(prefs.byokKey)
        assertFalse(controller.state.value.settings.api.hasSavedKey)
        assertFalse(controller.state.value.settings.api.hasUnsavedChanges)
    }

    /** 切换端点后留空不能把旧服务密钥带到新服务，与测试连接的有效密钥保持一致。 */
    @Test fun blankKeyAfterEndpointChangeCannotRetainOldProviderCredential() = onMain {
        prefs.saveByok("https://example.com/provider-a/v1", "测试模型", "旧端点测试密钥", "", emptyList())
        actions.onDiscardApiDraft()
        actions.onApiDraftChange(controller.state.value.settings.api.copy(url = "https://example.com/provider-b/v1"))
        assertFalse(controller.state.value.settings.api.hasSavedKey)
        actions.onApiDraftChange(controller.state.value.settings.api.copy(key = null))
        actions.onSaveApi()
        assertNull("新端点不能继承旧服务密钥", prefs.byokKey)
        assertFalse(controller.state.value.settings.api.hasSavedKey)
        assertFalse(controller.state.value.settings.api.hasUnsavedChanges)
    }

    /** 校验失败不能丢失密钥草稿，也不能标成已保存。 */
    @Test fun invalidConfigurationRetainsUnsavedKey() = onMain {
        actions.onApiDraftChange(controller.state.value.settings.api.copy(url = "无效地址", key = "待保存测试密钥"))
        actions.onSaveApi()
        assertEquals("待保存测试密钥", controller.state.value.settings.api.key)
        assertTrue(controller.state.value.settings.api.hasUnsavedChanges)
        assertTrue(controller.state.value.settings.api.message!!.isError)
        assertNull(prefs.byokKey)
    }

    /** 销毁旧控制器后从同一份隔离存储重建。 */
    private fun createController() {
        store.clear()
        controller = AppController(application)
        store.put("密钥保存", controller)
        actions = controller.actions({}, {}, {}, {})
    }

    /** 同时检查存储、输入内容和未保存状态，避免仅检查提示文字。 */
    private fun assertSavedKey(expected: String) {
        assertEquals(expected, prefs.byokKey)
        assertEquals(expected, controller.state.value.settings.api.key)
        assertTrue(controller.state.value.settings.api.hasSavedKey)
        assertFalse(controller.state.value.settings.api.hasUnsavedChanges)
    }

    /** 统一主线程入口，不创建真实服务。 */
    private fun onMain(block: () -> Unit) { instrumentation.runOnMainSync { block() } }
}
