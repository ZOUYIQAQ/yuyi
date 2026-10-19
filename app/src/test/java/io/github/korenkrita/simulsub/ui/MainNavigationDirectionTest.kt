package io.github.korenkrita.simulsub.ui

import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.*
import org.junit.Test

/** 以明确的导航顺序验证所有往返方向，不依赖页面枚举声明顺序。 */
class MainNavigationDirectionTest {
    private val moves = listOf(
        Triple(UiDestination.LIVE, UiDestination.SUBTITLE, 1),
        Triple(UiDestination.LIVE, UiDestination.SETTINGS, 1),
        Triple(UiDestination.SUBTITLE, UiDestination.SETTINGS, 1),
        Triple(UiDestination.SUBTITLE, UiDestination.LIVE, -1),
        Triple(UiDestination.SETTINGS, UiDestination.LIVE, -1),
        Triple(UiDestination.SETTINGS, UiDestination.SUBTITLE, -1),
    )

    /** 从左至右布局中，右边页面从右进入、左边页面从左进入。 */
    @Test fun horizontalDirectionsFollowNavigationOrder() {
        moves.forEach { (from, to, expected) -> assertEquals(expected, mainNavigationDirection(from, to, false, LayoutDirection.Ltr)) }
    }

    /** 从右至左布局反转横向关系，但不改变侧栏上下关系。 */
    @Test fun rtlOnlyMirrorsHorizontalDirections() {
        moves.forEach { (from, to, expected) ->
            assertEquals(-expected, mainNavigationDirection(from, to, false, LayoutDirection.Rtl))
            assertEquals(expected, mainNavigationDirection(from, to, true, LayoutDirection.Rtl))
            assertEquals(expected, mainNavigationDirection(from, to, true, LayoutDirection.Ltr))
        }
    }

    /** 原地点击不产生方向，详情页不能误用主导航索引。 */
    @Test fun sameDestinationHasNoDirectionAndDetailsAreRejected() {
        mainNavigationDestinations.forEach { assertEquals(0, mainNavigationDirection(it, it, false, LayoutDirection.Ltr)) }
        assertThrows(IllegalArgumentException::class.java) {
            mainNavigationDirection(UiDestination.SCENES, UiDestination.SUBTITLE, false, LayoutDirection.Ltr)
        }
    }
}
