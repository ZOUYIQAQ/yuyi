package io.github.korenkrita.simulsub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 在手机和平板、明暗主题下验证置顶强调卡与固定主操作，不改变系统授权。 */
@RunWith(Parameterized::class)
class LivePermissionPriorityDeviceTest(private val wide: Boolean, private val dark: Boolean) {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(LiveUiState(statusTitle = "会话状态", permissions = listOf(
        PermissionUiState("audio", "录制播放音频", "录音权限说明", false),
        PermissionUiState("overlay", "显示悬浮字幕", "悬浮权限说明", false),
        PermissionUiState("notifications", "可选通知", "通知说明", false, false))))
    private val events = mutableListOf<String>()
    private var emphasisColor = Color.Unspecified
    private var surfaceColor = Color.Unspecified
    private var contentColor = Color.Unspecified

    companion object {
        /** 主题和窗口独立组合，覆盖暖色明暗主题以及双列布局。 */
        @JvmStatic @Parameterized.Parameters(name = "宽屏={0}，深色={1}")
        fun cases(): List<Array<Any>> = listOf(false, true).flatMap { wide ->
            listOf(false, true).map { dark -> arrayOf<Any>(wide, dark) }
        }
    }

    /** 强调卡先于会话卡，授权与查看全部可直接点击，主操作滚动前后保持固定。 */
    @Test fun missingPermissionsLeadWithThemeEmphasisAndDirectActions() {
        show()
        val card = compose.onNodeWithTag("live-permission-warning").assertIsDisplayed()
        val bounds = card.fetchSemanticsNode().boundsInRoot
        val pixels = card.captureToImage().toPixelMap()
        assertEquals("权限卡必须使用主题强调底色", emphasisColor.toArgb(), pixels[pixels.width / 2, 4].toArgb())
        assertNotEquals("强调底色必须区别于普通卡片", surfaceColor, emphasisColor)
        assertTrue("权限文案与强调底色对比应达到正常文字要求",
            ColorUtils.calculateContrast(contentColor.toArgb(), emphasisColor.toArgb()) >= 4.5)
        compose.onAllNodesWithText("去允许").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("查看全部权限").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("audio", "all"), events) }
        val action = compose.onNodeWithTag("live-primary-action").assertIsDisplayed()
        val before = action.fetchSemanticsNode().boundsInRoot
        val list = compose.onAllNodes(hasScrollToIndexAction()).onFirst()
        list.performScrollToNode(hasText("会话状态"))
        val session = compose.onNodeWithText("会话状态").fetchSemanticsNode().boundsInRoot
        assertTrue("权限卡必须位于会话卡之前", session.top >= bounds.top)
        list.performScrollToNode(hasText("声音与译文"))
        assertEquals("滚动不能改变底部主操作位置", before, action.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
    }

    /** 返回后更新必需授权即隐藏卡片，可选通知拒绝不继续提醒或阻断。 */
    @Test fun grantingRequiredPermissionsHidesWarningImmediately() {
        show()
        compose.onNodeWithTag("live-permission-warning").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(permissions = state.permissions.map { if (it.required) it.copy(granted = true) else it }) }
        compose.onNodeWithTag("live-permission-warning").assertDoesNotExist()
        compose.onNodeWithText("查看全部权限").assertDoesNotExist()
        compose.onNodeWithTag("live-primary-action").assertIsDisplayed()
    }

    /** 控件仍使用真实主题与可滚动布局，只替换系统动作回调。 */
    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(if (wide) 0.6f else 1f, 1f)) {
                SimulSubTheme(SettingsUiState(theme = if (dark) UiThemeMode.DARK else UiThemeMode.LIGHT)) {
                    emphasisColor = MaterialTheme.colorScheme.primaryContainer
                    surfaceColor = MaterialTheme.colorScheme.surfaceContainerLow
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    Box(Modifier.requiredSize(if (wide) 1040.dp else 360.dp, 740.dp)) {
                        LiveScreen(state, SimulSubActions(onPermission = { events += it }), wide)
                    }
                }
            }
        }
    }
}
