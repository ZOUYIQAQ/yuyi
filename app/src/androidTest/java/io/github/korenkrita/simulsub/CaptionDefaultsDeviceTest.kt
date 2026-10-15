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
            assertTrue(defaultText.contains("第9句示例"))
            assertFalse(defaultText.contains("字幕の設定"))
            assertFalse(defaultText.contains("识别 120ms"))
            render(stage, style.copy(showOriginal = true, showStats = true))
            val enabledText = visibleText(stage)
            assertTrue(enabledText.contains("第9句示例"))
            assertTrue(enabledText.contains("字幕の設定"))
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
