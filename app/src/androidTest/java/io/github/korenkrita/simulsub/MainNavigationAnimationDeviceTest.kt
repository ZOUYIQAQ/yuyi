package io.github.korenkrita.simulsub

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.korenkrita.simulsub.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 手动时钟测量真实主页面位置，同一台设备覆盖底部导航、侧栏和文字方向。 */
@RunWith(Parameterized::class)
class MainNavigationAnimationDeviceTest(private val tablet: Boolean, private val rtl: Boolean, private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(SimulSubUiState())

    companion object {
        /** 不启动其他模拟器，也不修改系统屏幕与文字方向。 */
        @JvmStatic @Parameterized.Parameters(name = "平板={0}，从右向左={1}，字号={2}")
        fun cases(): List<Array<Any>> = listOf(arrayOf(false, false, 1f), arrayOf(false, false, 1.5f),
            arrayOf(false, true, 1f), arrayOf(true, false, 1f), arrayOf(true, true, 1f))
    }

    /** 六种往返都测量中间帧与稳定帧，同时核对按钮真实坐标决定的方向。 */
    @Test fun allMainPageTransitionsFollowVisibleButtonPositions() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(if (tablet) 0.6f else 1f, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Box(Modifier.requiredSize(if (tablet) 1040.dp else 360.dp, 720.dp)
                    .consumeWindowInsets(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
                    SimulSubApp(state, SimulSubActions(onNavigate = { state = state.copy(destination = it) }))
                }
            }
        }
        compose.mainClock.autoAdvance = false
        val moves = listOf(UiDestination.LIVE to UiDestination.SUBTITLE, UiDestination.SUBTITLE to UiDestination.SETTINGS,
            UiDestination.SETTINGS to UiDestination.SUBTITLE, UiDestination.SUBTITLE to UiDestination.LIVE,
            UiDestination.LIVE to UiDestination.SETTINGS, UiDestination.SETTINGS to UiDestination.LIVE)
        moves.forEach { (from, to) ->
            assertEquals(from, state.destination)
            val before = compose.onNodeWithTag("main-page-${from.name}").getUnclippedBoundsInRoot()
            val sourceButton = compose.onNode(isSelectable() and hasText(from.title)).getUnclippedBoundsInRoot()
            val targetButton = compose.onNode(isSelectable() and hasText(to.title)).getUnclippedBoundsInRoot()
            val delta = if (tablet) targetButton.top.value - sourceButton.top.value else targetButton.left.value - sourceButton.left.value
            assertTrue("导航项必须占不同位置", kotlin.math.abs(delta) > 1f)
            compose.onNode(isSelectable() and hasText(to.title)).performClick()
            compose.mainClock.advanceTimeBy(80)
            val incoming = compose.onNodeWithTag("main-page-${to.name}").getUnclippedBoundsInRoot()
            val outgoing = compose.onNodeWithTag("main-page-${from.name}").getUnclippedBoundsInRoot()
            val targetOffset = if (tablet) incoming.top.value - before.top.value else incoming.left.value - before.left.value
            val oldOffset = if (tablet) outgoing.top.value - before.top.value else outgoing.left.value - before.left.value
            assertTrue("$from → $to 新页方向：导航差=$delta，中间帧=$targetOffset", delta * targetOffset > 0.1f)
            assertTrue("$from → $to 旧页应反向移出：导航差=$delta，中间帧=$oldOffset", delta * oldOffset < -0.1f)
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("main-page-${from.name}").assertDoesNotExist()
            val settled = compose.onNodeWithTag("main-page-${to.name}").getUnclippedBoundsInRoot()
            assertEquals(before.left.value, settled.left.value, 0.5f)
            assertEquals(before.top.value, settled.top.value, 0.5f)
        }
    }
}
