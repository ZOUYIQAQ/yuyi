package io.github.korenkrita.simulsub

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** 沿用历史测试类名，改为验证官方下拉菜单的锚定、滚动、选择和关闭。 */
class OptionSheetGestureDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    // 必须在打开菜单前连接无障碍测试服务，避免连接本身改变窗口焦点并收起菜单。
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val choices = listOf(UiOption("ja", "日语"), UiOption("en", "英语"), UiOption("auto", "自动识别"))

    /** 短菜单贴着选择框展开，选中项有状态，选择后只回调一次并自动收起。 */
    @Test fun shortDropdownIsAnchoredAndSelectionClosesIt() {
        var selected by mutableStateOf("ja")
        val events = mutableListOf<String>()
        show { OptionPicker("原声语言", selected, choices) { selected = it; events += it } }
        val anchor = screenBounds(compose.onNodeWithText("原声语言"))
        compose.onNodeWithText("原声语言").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        val menu = screenBounds(compose.onNode(isPopup()).assertIsDisplayed())
        assertEquals("菜单应匹配选择框宽度", anchor.width, menu.width, 2f)
        assertTrue("有空间时菜单应紧邻选择框下方", kotlin.math.abs(menu.top - anchor.bottom) < 8f)
        option("日语").assertIsSelected()
        option("英语").assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals("en", selected); assertEquals(listOf("en"), events) }
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.onNodeWithText("英语").assertIsDisplayed()
        screenshot("short-selected")
    }

    /** 长菜单快速滑动只滚动内容，不能变成全屏抽屉；末项仍能选择。 */
    @Test fun fastFlingKeepsLongDropdownStableAndLastOptionSelectable() {
        var selected by mutableStateOf("1")
        val options = (1..30).map { UiOption("$it", "选项 $it", if (it % 3 == 0) "较长说明，用于验证菜单内的换行与滚动。" else "") }
        show { OptionPicker("测试选项", selected, options) { selected = it } }
        compose.onNodeWithText("测试选项").performClick()
        val original = compose.onNode(isPopup()).fetchSemanticsNode().boundsInWindow
        repeat(4) {
            compose.onNode(hasScrollAction()).performTouchInput { swipeUp(durationMillis = 80) }
            assertSettled(original)
        }
        option("选项 30").performScrollTo().assertIsDisplayed()
        repeat(3) {
            compose.onNode(hasScrollAction()).performTouchInput { swipeUp(durationMillis = 50) }
            assertSettled(original)
        }
        screenshot("long-menu")
        option("选项 30").performClick()
        compose.runOnIdle { assertEquals("30", selected) }
        compose.onNode(isPopup()).assertDoesNotExist()
    }

    /** 系统返回只关闭菜单，不选择其他值；禁用和空列表不能打开菜单。 */
    @Test fun backDisabledAndEmptyOptionsDoNotChangeSelection() {
        var enabled by mutableStateOf(true)
        var options by mutableStateOf(choices)
        var calls = 0
        show { OptionPicker("测试选项", "ja", options, enabled) { calls++ } }
        compose.onNodeWithText("测试选项").performClick()
        compose.onNode(isPopup()).assertIsDisplayed()
        // 等菜单完成渲染后再返回，不能在点击状态尚未提交时直接退出测试宿主。
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(3_000) { compose.onAllNodes(isPopup()).fetchSemanticsNodes().isEmpty() }
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, calls); enabled = false }
        compose.onNodeWithText("测试选项").assertIsNotEnabled()
        compose.onNodeWithText("测试选项").performTouchInput { click() }
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle { enabled = true; options = emptyList() }
        compose.onNodeWithText("测试选项").assertIsNotEnabled()
        compose.onNodeWithText("测试选项").performTouchInput { click() }
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    /** 菜单展开时进入忙碌状态应关闭；恢复可编辑后不能自行重新弹出。 */
    @Test fun disablingAnOpenPickerDoesNotReopenWhenEnabledAgain() {
        var enabled by mutableStateOf(true)
        show { OptionPicker("测试选项", "ja", choices, enabled) {} }
        compose.onNodeWithText("测试选项").performClick()
        compose.onNode(isPopup()).assertIsDisplayed()
        compose.runOnIdle { enabled = false }
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle { enabled = true }
        compose.onNode(isPopup()).assertDoesNotExist()
    }

    /** 大字号和较长说明可以自然换行，菜单在小屏幕上仍可滚动到最后一个选项。 */
    @Test fun largeTextDescriptionsRemainReachable() {
        var selected by mutableStateOf("1")
        show(fontScale = 2f) {
            OptionPicker("长说明选项", selected, (1..12).map { UiOption("$it", "语言 $it", "这段说明需要换行，不应被固定高度裁掉。") }) { selected = it }
        }
        compose.onNodeWithText("长说明选项").performClick()
        option("语言 12").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("12", selected) }
        compose.onNode(isPopup()).assertDoesNotExist()
    }

    /** 靠近屏幕底边的选择框应向上展开，保持下拉菜单完整可见。 */
    @Test fun bottomPickerOpensUpwardWithinTheScreen() {
        show(atBottom = true) { OptionPicker("底部选项", "ja", choices) {} }
        val anchor = screenBounds(compose.onNodeWithText("底部选项"))
        compose.onNodeWithText("底部选项").performClick()
        val menu = screenBounds(compose.onNode(isPopup()).assertIsDisplayed())
        assertTrue("底部菜单应紧邻选择框向上展开：$anchor，$menu", kotlin.math.abs(menu.bottom - anchor.top) < 8f)
        assertTrue("菜单不能越过屏幕顶边", menu.top >= 0f)
        screenshot("bottom-menu")
    }

    /** 真实主题与窗口承载选择框，状态只在内存中，不触碰用户配置。 */
    private fun show(fontScale: Float = 1f, atBottom: Boolean = false, content: @Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                SimulSubTheme(SettingsUiState()) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp)) {
                        Text("下拉菜单验收")
                        if (atBottom) Spacer(Modifier.weight(1f))
                        content()
                    }
                }
            }
        }
    }

    /** 相同标签也可能出现在背景选择框中，操作只定位独立菜单内的选项。 */
    private fun option(label: String): SemanticsNodeInteraction =
        compose.onNode(hasText(label) and hasAnyAncestor(isPopup()))

    /** 弹出菜单拥有独立窗口，以屏幕坐标比较锚点，不能混用各窗口的局部原点。 */
    private fun screenBounds(node: SemanticsNodeInteraction): Rect {
        val info = node.fetchSemanticsNode().layoutInfo
        val coordinates = info.getModifierInfo().first().coordinates
        val position = coordinates.positionOnScreen()
        return Rect(position, androidx.compose.ui.geometry.Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
    }

    /** 连续绘制帧中的菜单边界应相同，快速滑动不能改变锚点或面板高度。 */
    private fun assertSettled(expected: Rect) {
        compose.waitForIdle()
        val positions = List(8) {
            Thread.sleep(32)
            compose.onNode(isPopup()).fetchSemanticsNode().boundsInWindow
        }
        assertTrue("菜单快速滑动后位置或高度改变：$positions，原始=$expected", positions.all {
            kotlin.math.abs(it.top - expected.top) <= 1f && kotlin.math.abs(it.bottom - expected.bottom) <= 1f
        })
    }

    /** 保存系统合成结果供手机和平板逐图检查。 */
    private fun screenshot(name: String) {
        val bitmap = checkNotNull(automation.takeScreenshot())
        val dir = File(compose.activity.externalCacheDir, "dropdown-menus/${bitmap.width}x${bitmap.height}").apply { mkdirs() }
        try { File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
