package io.github.korenkrita.simulsub

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.graphics.toArgb
import io.github.korenkrita.simulsub.ui.*
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** 使用独立偏好验证新增预设和外观设置，不修改设备上的用户配置。 */
class RefinementDeviceTest {
    /** 新选项默认关闭，保存、其他样式修改和定稿模式切换均保持同一持久化选择。 */
    @Test fun draftBackgroundReservationPersistsWithoutChangingOtherDefaults() = withController { controller, prefs, actions ->
        assertFalse(prefs.reserveDraftSpace)
        assertFalse(controller.state.value.settings.subtitle.reserveDraftSpace)
        val initial = controller.state.value.settings.subtitle
        actions.onSubtitleStyleChange(initial.copy(reserveDraftSpace = true))
        assertTrue(prefs.reserveDraftSpace)
        assertTrue(controller.state.value.settings.subtitle.reserveDraftSpace)
        assertTrue(Prefs(controller.getApplication()).reserveDraftSpace)
        actions.onFinalOnly(true)
        assertTrue(controller.state.value.live.finalOnly)
        assertTrue(controller.state.value.settings.subtitle.reserveDraftSpace)
        actions.onSubtitleStyleChange(controller.state.value.settings.subtitle.copy(textSp = 22f))
        assertTrue(prefs.reserveDraftSpace)
        assertEquals(initial.maxSentences, prefs.captionSentenceCount)
        actions.onFinalOnly(false)
        assertTrue(controller.state.value.settings.subtitle.reserveDraftSpace)
        actions.onSubtitleStyleChange(controller.state.value.settings.subtitle.copy(reserveDraftSpace = false))
        assertFalse(Prefs(controller.getApplication()).reserveDraftSpace)
    }

    /** 已保存的选项在首次创建控制器时也必须回填，而非只留在本次内存状态。 */
    @Test fun savedDraftBackgroundReservationLoadsIntoController() = withController(
        configurePrefs = { it.reserveDraftSpace = true },
    ) { controller, _, _ -> assertTrue(controller.state.value.settings.subtitle.reserveDraftSpace) }

    /** 模板仅改草稿；保存后才切换服务，不能沿用旧服务的凭据。 */
    @Test fun presetIsEditableAndOnlySavesOnExplicitAction() = withController { controller, prefs, actions ->
        prefs.saveByok("https://old.example/v1", "旧模型", "旧服务密钥", "旧服务UA", listOf("X-Key" to "旧请求头"))
        actions.onApplyApiPreset("deepseek")
        assertEquals("https://old.example/v1", prefs.byokUrl)
        assertEquals("旧服务密钥", prefs.byokKey)
        val draft = controller.state.value.settings.api
        assertEquals("deepseek-flash", draft.model)
        assertEquals("", draft.key)
        actions.onApiDraftChange(draft.copy(model = "用户自定义模型", url = "https://custom.example/relay/v1"))
        assertEquals("", controller.state.value.settings.api.key)
        actions.onApiDraftChange(controller.state.value.settings.api.copy(key = "新服务密钥"))
        actions.onSaveApi()
        assertEquals("https://custom.example/relay/v1", prefs.byokUrl)
        assertEquals("用户自定义模型", prefs.byokModel)
        assertEquals("新服务密钥", prefs.byokKey)
        assertTrue(prefs.byokHeaders.isEmpty())
        assertEquals("", prefs.byokUserAgent)
    }

    /** 同域换路径必须清空全部旧凭据并取消旧探测，保存前不改持久化配置。 */
    @Test fun sameOriginPathChangeClearsCredentialsAndCancelsProbe() = withController(
        configurePrefs = { it.saveByok("https://127.0.0.1:1/provider-a/v1", "old-model", "old-api-key", "RefinementTest/1.0", listOf("X-Key" to "old-header-key")) },
    ) { controller, prefs, actions ->
        val initial = controller.state.value.settings.api
        assertNull("测试夹具必须通过请求头校验", ByokService.badAdvanced(initial.userAgent, initial.headers.map { it.name to it.value }))
        assertEquals(listOf("X-Key" to "old-header-key"), initial.headers.map { it.name to it.value })
        actions.onTestApi()
        assertTrue("旧端点应处于探测状态", controller.state.value.settings.api.testing)
        assertEquals("探测前应回填已存密钥", "old-api-key", controller.state.value.settings.api.key)
        assertTrue("探测前保存不能丢失已存密钥", controller.state.value.settings.api.hasSavedKey)
        val oldJob = AppController::class.java.getDeclaredField("probeJob").apply { isAccessible = true }.get(controller) as Job
        actions.onApiDraftChange(controller.state.value.settings.api.copy(url = "https://127.0.0.1:1/provider-b/v1"))
        val draft = controller.state.value.settings.api
        assertEquals("", draft.key)
        assertFalse(draft.hasSavedKey)
        assertTrue(draft.headers.isEmpty())
        assertEquals("", draft.userAgent)
        assertFalse("编辑地址后必须退出旧探测状态", draft.testing)
        assertTrue("编辑地址必须取消旧探测协程", oldJob.isCancelled)
        assertNull("旧探测不能继续回写结果", AppController::class.java.getDeclaredField("probe").apply { isAccessible = true }.get(controller))
        assertEquals("https://127.0.0.1:1/provider-a/v1", prefs.byokUrl)
        assertEquals("old-api-key", prefs.byokKey)
        assertEquals(listOf("X-Key" to "old-header-key"), prefs.byokHeaders)
        assertEquals("RefinementTest/1.0", prefs.byokUserAgent)
        actions.onSaveApi()
        assertEquals("翻译配置已保存", controller.state.value.settings.api.message?.text)
        assertEquals("https://127.0.0.1:1/provider-b/v1", prefs.byokUrl)
        assertNull("保存新端点不能沿用旧密钥", prefs.byokKey)
        assertTrue(prefs.byokHeaders.isEmpty())
        assertEquals("", prefs.byokUserAgent)
    }

    /** 等价基础地址和完整聊天地址双向切换，保留已存及待存密钥、请求头和UA。 */
    @Test fun equivalentBaseAndFullEndpointsKeepCredentials() = withController(
        configurePrefs = { it.saveByok("https://relay.example/provider-a/v1", "old-model", "saved-api-key", "RefinementTest/1.0", listOf("X-Key" to "service-header-key")) },
    ) { controller, prefs, actions ->
        val initial = controller.state.value.settings.api
        assertNull("测试夹具必须通过请求头校验", ByokService.badAdvanced(initial.userAgent, initial.headers.map { it.name to it.value }))
        assertEquals(listOf("X-Key" to "service-header-key"), initial.headers.map { it.name to it.value })
        val base = "https://relay.example/provider-a/v1"
        val full = "$base/chat/completions"
        for (key in listOf(null, "draft-api-key")) {
            val savedKey = prefs.byokKey
            actions.onApiDraftChange(controller.state.value.settings.api.copy(key = key))
            val headers = controller.state.value.settings.api.headers
            for (url in listOf(full, "$base/", base)) {
                actions.onApiDraftChange(controller.state.value.settings.api.copy(url = url))
                val draft = controller.state.value.settings.api
                assertEquals(key, draft.key)
                assertTrue(draft.hasSavedKey)
                assertEquals(headers, draft.headers)
                assertEquals("RefinementTest/1.0", draft.userAgent)
                assertEquals(savedKey, prefs.byokKey)
            }
            actions.onSaveApi()
            assertEquals("翻译配置已保存", controller.state.value.settings.api.message?.text)
            assertEquals(key ?: savedKey, prefs.byokKey)
            assertEquals("保存后应保留实际密钥值供用户核对", key ?: savedKey, controller.state.value.settings.api.key)
            assertTrue("保存后应明确标记已有密钥", controller.state.value.settings.api.hasSavedKey)
        }
        assertEquals("draft-api-key", prefs.byokKey)
        assertEquals(listOf("X-Key" to "service-header-key"), prefs.byokHeaders)
        assertEquals("RefinementTest/1.0", prefs.byokUserAgent)
    }

    /** 行数持久化有边界，配色切换会退出动态色，旧视图可继续读取同一偏好。 */
    @Test fun captionLimitAndThemePreferencesPersist() = withController { controller, prefs, actions ->
        assertEquals(0, prefs.maxCaptionLines)
        actions.onSubtitleStyleChange(controller.state.value.settings.subtitle.copy(maxLines = 3))
        assertEquals(3, prefs.maxCaptionLines)
        assertEquals(3, controller.state.value.settings.subtitle.maxLines)
        prefs.maxCaptionLines = 99
        assertEquals(20, prefs.maxCaptionLines)
        prefs.maxCaptionLines = -1
        assertEquals(0, prefs.maxCaptionLines)
        actions.onDynamicColorsChange(true)
        actions.onThemeColorChange("forest")
        assertEquals("forest", controller.state.value.settings.themeColorId)
        assertFalse(controller.state.value.settings.dynamicColors)
    }

    /** 官方色调算法生成的主要文字组合在所有手动主题及浅深色中均达到正常文字对比要求。 */
    @Test fun paletteRolesHaveReadableContrast() {
        ThemePalettes.entries.forEach { palette ->
            listOf(false, true).forEach { dark ->
                val scheme = ThemePalettes.scheme(palette.id, dark)
                val pairs = listOf(scheme.onPrimary to scheme.primary, scheme.onPrimaryContainer to scheme.primaryContainer,
                    scheme.onSurface to scheme.surface, scheme.onSurfaceVariant to scheme.surfaceContainerLow,
                    scheme.onSecondaryContainer to scheme.secondaryContainer)
                pairs.forEach { (text, background) ->
                    assertTrue("${palette.title} 深色=$dark 对比不足", ColorUtils.calculateContrast(text.toArgb(), background.toArgb()) >= 4.5)
                }
            }
        }
    }

    /** 在主线程预置隔离偏好并创建真实控制器，测试结束释放全部监听。 */
    private fun withController(configurePrefs: (Prefs) -> Unit = {}, body: (AppController, Prefs, SimulSubActions) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val token = UUID.randomUUID().toString()
        val application = object : Application() {
            /** 模型仓库继续使用真实应用上下文，不发起下载。 */
            override fun getApplicationContext(): Context = context.applicationContext
            /** 每项回归独立保存偏好。 */
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = context.getSharedPreferences("改进回归-$token-$name", mode)
            /** 日志读取使用隔离目录，不访问已有记录。 */
            override fun getExternalFilesDir(type: String?): File = File(context.cacheDir, "改进回归-$token")
        }
        instrumentation.runOnMainSync {
            val prefs = Prefs(application).also(configurePrefs)
            val controller = AppController(application)
            val store = ViewModelStore().apply { put("改进回归", controller) }
            try { body(controller, prefs, controller.actions({}, {}, {}, {})) }
            finally { store.clear() }
        }
    }
}
