package io.github.korenkrita.simulsub

import android.graphics.Rect
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** 用真实入口和系统键盘检查窗口坐标，不输入密钥、不保存配置或请求翻译服务。 */
class ApiImeDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** 聚焦密钥后顶栏和底部导航保持原位，仅表单避让键盘并能滚动到保存按钮。 */
    @Test fun apiKeyboardKeepsTitleAndSaveReachable() {
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("翻译 API").performClick()
        compose.waitForIdle()
        compose.waitUntil(10_000) { !screenBounds("翻译 API").isEmpty }
        val titleBefore = screenBounds("翻译 API")
        val navigationBefore = compose.onNodeWithText("设置").fetchSemanticsNode().boundsInRoot
        assertFalse("聚焦前应能读取标题屏幕坐标", titleBefore.isEmpty)
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText("API 密钥"))
        compose.onNode(hasSetTextAction() and hasText("API 密钥", substring = true)).performClick()
        compose.waitUntil(10_000) { insets().isVisible(WindowInsetsCompat.Type.ime()) }
        instrumentation.waitForIdleSync()
        try {
            // 等待真实系统窗口和键盘动画结束后再比较屏幕坐标。
            compose.waitUntil(5_000) {
                val title = screenBounds("翻译 API")
                val key = screenBounds("API 密钥")
                val imeTop = compose.activity.window.decorView.height - insets().getInsets(WindowInsetsCompat.Type.ime()).bottom
                !title.isEmpty && title.top == titleBefore.top && !key.isEmpty &&
                    key.top > title.bottom && key.bottom < imeTop
            }
            val titleAfter = screenBounds("翻译 API")
            val imeTop = compose.activity.window.decorView.height - insets().getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navigation = compose.onNodeWithText("设置").fetchSemanticsNode().boundsInRoot
            val key = screenBounds("API 密钥")
            assertEquals("键盘弹出不能平移顶栏", titleBefore, titleAfter)
            assertTrue("标题不能进入状态栏", titleAfter.top >= insets().getInsets(WindowInsetsCompat.Type.statusBars()).top)
            assertTrue("密钥输入框不能被标题遮挡", key.top > titleAfter.bottom)
            assertTrue("密钥输入框不能被键盘遮挡", key.bottom < imeTop)
            assertEquals("键盘不能把底部导航顶上来", navigationBefore, navigation)
            if (compose.activity.resources.configuration.screenWidthDp >= 600) {
                assertTrue("平板侧边导航应留在表单左侧", navigation.right < key.left)
            } else {
                assertTrue("底部导航应留在键盘覆盖的屏幕底部：导航=$navigation，键盘顶=$imeTop", navigation.top >= imeTop)
            }
            screenshot("api-keyboard")
            compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText("保存翻译配置"))
            compose.onNodeWithText("保存翻译配置").performScrollTo().assertIsDisplayed()
            compose.waitUntil(5_000) {
                val save = screenBounds("保存翻译配置", parent = true)
                !save.isEmpty && save.top > titleAfter.bottom && save.bottom <= imeTop
            }
            val save = screenBounds("保存翻译配置", parent = true)
            assertTrue("滚动到保存时键盘应保持打开", insets().isVisible(WindowInsetsCompat.Type.ime()))
            assertTrue("完整保存按钮应位于标题与键盘之间：保存=$save，键盘顶=$imeTop", save.top > titleAfter.bottom && save.bottom <= imeTop)
            screenshot("api-keyboard-save")
            android.util.Log.i("ApiImeDeviceTest", "键盘回归通过：标题=$titleAfter，密钥=$key，导航=$navigation，键盘顶=$imeTop，保存=$save")
        } finally {
            screenshot("api-keyboard-final")
            compose.activityRule.scenario.onActivity { activity ->
                androidx.core.view.WindowInsetsControllerCompat(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
            }
        }
    }

    /** 从系统无障碍树读取实际屏幕位置，捕获 adjustPan 对整窗造成的平移。 */
    private fun screenBounds(text: String, parent: Boolean = false): Rect {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return Rect()
        val pending = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.text?.toString() == text) {
                return Rect().also { (if (parent) node.parent ?: node else node).getBoundsInScreen(it) }
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let { pending.add(it) } }
        }
        return Rect()
    }

    /** 直接读取入口窗口的真实系统内边距。 */
    private fun insets(): WindowInsetsCompat = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!

    /** 把系统键盘在内的整屏证据留在应用外部缓存，便于 adb 导出审阅。 */
    private fun screenshot(name: String) {
        val directory = File(compose.activity.externalCacheDir, "ui-regression").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
