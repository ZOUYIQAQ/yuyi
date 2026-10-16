package io.github.korenkrita.simulsub

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** 首页不再承载会话字幕；默认及显式原文、耗时开关仍由真实浮窗预览验证。 */
class CaptionDefaultsDeviceTest {
    @get:Rule val compose=createComposeRule()

    /** 新安装默认自动高度且继续预留草稿空间，不写入高度或旧迁移标记。 */
    @Test fun freshInstallDefaultsToAutoHeightWithoutWritingCaptionChoices() {
        assertEquals(0f, SubtitleStyleUiState().heightFraction, 0f)
        assertTrue(SubtitleStyleUiState().reserveDraftSpace)
        assertCaptionChoicesPreserved(height = null, reserve = null, migrated = null)
    }

    /** 旧自动高度在没有迁移标记或标记为假时均保持零，显式预留选择也保持不变。 */
    @Test fun legacyAutoHeightAndReserveChoicesArePreserved() {
        for (migrated in listOf(null, false)) {
            for (reserve in listOf(false, true)) {
                assertCaptionChoicesPreserved(height = 0f, reserve = reserve, migrated = migrated)
            }
        }
    }

    /** 旧固定高度按原值保留，包含原迁移目标两成及滑块两端，不被统一改回自动。 */
    @Test fun legacyNonzeroHeightsAndReserveChoicesArePreserved() {
        for (height in listOf(0.05f, 0.1f, 0.15f, 0.2f, 0.25f, 0.3f, 0.35f, 0.4f)) {
            for (reserve in listOf(false, true)) {
                assertCaptionChoicesPreserved(height = height, reserve = reserve, migrated = null)
            }
        }
    }

    /** 已迁移用户当前选择的自动或固定高度均保留，不猜测两成高度是否来自历史迁移。 */
    @Test fun migratedUsersKeepTheirCurrentHeightAndReserveChoices() {
        for (height in listOf(0f, 0.05f, 0.2f, 0.4f)) {
            for (reserve in listOf(false, true)) {
                assertCaptionChoicesPreserved(height = height, reserve = reserve, migrated = true)
            }
        }
    }

    /** 缺少高度或预留键的旧配置只读取默认值，不补写选择或改变旧迁移标记。 */
    @Test fun missingCaptionChoicesUseDefaultsWithoutPersistingThem() {
        for (migrated in listOf(null, false, true)) {
            for (reserve in listOf(false, true)) {
                assertCaptionChoicesPreserved(height = null, reserve = reserve, migrated = migrated)
            }
            for (height in listOf(0f, 0.2f)) {
                assertCaptionChoicesPreserved(height = height, reserve = null, migrated = migrated)
            }
        }
    }

    /** 在独立存储中写入历史配置，两次重建真实偏好并核对读值及原始键值均未被覆盖。 */
    private fun assertCaptionChoicesPreserved(height: Float?, reserve: Boolean?, migrated: Boolean?) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = UUID.randomUUID().toString()
        val isolated = object : ContextWrapper(context) {
            /** 将偏好全部隔离到本用例，避免读取或覆盖设备上的用户选择。 */
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                context.getSharedPreferences("字幕高度默认-$token-$name", mode)
        }
        val stored = isolated.getSharedPreferences("jpsub", Context.MODE_PRIVATE)
        assertTrue(stored.edit().apply {
            if (height != null) putFloat("heightFrac", height)
            if (reserve != null) putBoolean("reserveDraftSpace", reserve)
            if (migrated != null) putBoolean("fixedCaptionHeightDefaultMigrated", migrated)
        }.commit())
        repeat(2) {
            val prefs = Prefs(isolated)
            assertEquals(height ?: 0f, prefs.heightFrac, 0f)
            assertEquals(reserve ?: true, prefs.reserveDraftSpace)
            assertEquals(height, stored.all["heightFrac"])
            assertEquals(reserve, stored.all["reserveDraftSpace"])
            assertEquals(migrated, stored.all["fixedCaptionHeightDefaultMigrated"])
        }
    }

    /** 即使内存仍有字幕且显示开关均打开，首页也不能重新显示原文、译文或耗时。 */
    @Test fun liveDoesNotDisplayInMemoryCaptions() {
        val live = LiveUiState(showOriginal = true, showStats = true,
            captions = listOf(CaptionUiState("1", "测试原文", "测试译文", detail = "测试耗时")))
        compose.setContent { SimulSubTheme(SettingsUiState()) { LiveScreen(live,SimulSubActions(),false) } }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("当前服务"))
        compose.onNodeWithText("本次字幕").assertDoesNotExist()
        compose.onNodeWithText("测试译文").assertDoesNotExist()
        compose.onNodeWithText("测试原文").assertDoesNotExist()
        compose.onNodeWithText("测试耗时").assertDoesNotExist()
    }

    /** 真实原生预览默认只显示译文；开启原文和耗时后仍分别可见，不以首页移除字幕削弱检查。 */
    @Test fun overlayDefaultsToTranslationOnlyAndHonorsExplicitSwitches() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val token = UUID.randomUUID().toString()
            val isolated = object : ContextWrapper(context) {
                /** 只读取新建偏好的默认值，不覆盖实际字幕显示开关。 */
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    context.getSharedPreferences("字幕默认-$token-$name", mode)
            }
            val prefs = Prefs(isolated)
            val stage = SubtitlePreviewStage(context)
            val style = SubtitleStyleUiState(showOriginal = prefs.showJa, showStats = prefs.showStats)
            assertFalse(style.showOriginal)
            assertFalse(style.showStats)
            render(stage, style)
            val defaultText = visibleText(stage)
            assertTrue(defaultText.contains("这个v是什么人？真是令人在意的v"))
            assertFalse(defaultText.contains("このV何者気になるV、他とは違うVだから"))
            assertFalse(defaultText.contains("识别 120ms"))
            render(stage, style.copy(showOriginal = true, showStats = true))
            val enabledText = visibleText(stage)
            assertTrue(enabledText.contains("这个v是什么人？真是令人在意的v"))
            assertTrue(enabledText.contains("このV何者気になるV、他とは違うVだから"))
            assertTrue(enabledText.contains("识别 120ms · 翻译 320ms"))
        }
    }

    /** 复用生产预览并进行实际原生测量，保持默认句数和真实观看排版。 */
    private fun render(stage: SubtitlePreviewStage, style: SubtitleStyleUiState) {
        stage.render(style, landscape = false, enlarged = false, finalOnly = true)
        val density = stage.resources.displayMetrics.density
        stage.measure(View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((240 * density).toInt(), View.MeasureSpec.EXACTLY))
        stage.layout(0, 0, stage.measuredWidth, stage.measuredHeight)
    }

    /** 只读取实际可见原生文本，隐藏诊断或草稿控件不能冒充已展示。 */
    private fun visibleText(view: View): String = when {
        view.visibility != View.VISIBLE -> ""
        view is TextView -> view.text.toString()
        view is ViewGroup -> (0 until view.childCount).joinToString("\n") { visibleText(view.getChildAt(it)) }
        else -> ""
    }
}
