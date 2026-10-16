package io.github.korenkrita.simulsub

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** 在真实系统抽屉中反复快速滑动，核对选择与关闭，同时保留动画后的实际像素。 */
class OptionSheetGestureDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** 短列表不能被快速上滑拉成全屏，长列表仍可正常滚动并选择末项。 */
    @Test fun fastUpwardFlingKeepsShortAndLongSheetsStable() {
        var choices by mutableStateOf(listOf(
            UiOption("ja", "日语"), UiOption("en", "英语"), UiOption("auto", "自动识别")))
        var selected by mutableStateOf("ja")
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            SimulSubTheme(SettingsUiState()) {
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Text("抽屉手势验收")
                    OptionPicker("测试选项", selected, choices, onSelect = { selected = it })
                }
            }
        }
        compose.onNodeWithText("测试选项").performClick()
        val title = compose.onNodeWithText("测试选项").filterToOneDialogTitle()
        val before = title.fetchSemanticsNode().boundsInWindow
        repeat(4) {
            title.performTouchInput { swipe(Offset(center.x, height + 80f), Offset(center.x, -100f), 80) }
            assertSettled(title, before.top, "短列表快速上滑$it")
        }
        screenshot("short")
        compose.onNode(isDialog()).performTouchInput { click(Offset(8f, 8f)) }
        compose.onNodeWithText("测试选项").assertIsDisplayed()
        compose.runOnIdle {
            choices = (1..30).map { UiOption("$it", "选项 $it", if (it % 3 == 0) "较长说明，用于核对滚动后列表和抽屉的高度不会反复变化。" else "") }
            selected = "1"
        }
        compose.onNodeWithText("测试选项").performClick()
        val longTitle = compose.onNodeWithText("测试选项").filterToOneDialogTitle()
        val original = longTitle.fetchSemanticsNode().boundsInWindow
        repeat(4) {
            compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp(durationMillis = 80) }
            assertSettled(longTitle, original.top, "长列表快速上滑$it")
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("选项 30"))
        repeat(4) {
            compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp(durationMillis = 50) }
            assertSettled(longTitle, original.top, "末项边界高速上滑$it")
        }
        screenshot("long")
        compose.onNodeWithText("选项 30").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("30", selected) }
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    /** 区分页面入口与独立抽屉中的同名标题，避免命中背景节点。 */
    private fun SemanticsNodeInteraction.filterToOneDialogTitle(): SemanticsNodeInteraction =
        compose.onNode(hasText("测试选项") and hasAnyAncestor(isDialog()))

    /** 松手后等待正常回弹结束，连续真实帧中标题必须回到相同位置。 */
    private fun assertSettled(title: SemanticsNodeInteraction, expected: Float, stage: String) {
        compose.waitForIdle()
        Thread.sleep(500)
        val positions = List(12) {
            val y = title.fetchSemanticsNode().boundsInWindow.top
            Thread.sleep(32)
            y
        }
        assertTrue("$stage：抽屉未稳定或改变高度：$positions，原始=$expected", positions.all { kotlin.math.abs(it - expected) <= 1f })
    }

    /** 保存系统合成结果供手机和平板逐图核对。 */
    private fun screenshot(name: String) {
        Thread.sleep(400)
        val i = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(i.uiAutomation.takeScreenshot())
        val dir = File(compose.activity.externalCacheDir, "sheet-gestures/${bitmap.width}x${bitmap.height}").apply { mkdirs() }
        try { File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
