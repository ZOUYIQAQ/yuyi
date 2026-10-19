package io.github.korenkrita.simulsub.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.LayoutDirection

/** 按导航项的实际相对位置计算新页进入方向；侧栏依纵向排列，不受文字方向影响。 */
internal fun mainNavigationDirection(from: UiDestination, to: UiDestination,
    vertical: Boolean, layoutDirection: LayoutDirection): Int {
    val initial = mainNavigationDestinations.indexOf(from)
    val target = mainNavigationDestinations.indexOf(to)
    require(initial >= 0 && target >= 0) { "仅支持主导航页面" }
    val direction = target.compareTo(initial)
    return if (!vertical && layoutDirection == LayoutDirection.Rtl) -direction else direction
}

/** 主导航遵循按钮位置：向右或向下选择时从相应边进入，回程反向；详情仍用原层级过渡。 */
internal fun AnimatedContentTransitionScope<UiDestination>.mainPageTransition(
    vertical: Boolean, layoutDirection: LayoutDirection,
): ContentTransform {
    if (initialState !in mainNavigationDestinations || targetState !in mainNavigationDestinations)
        return routeTransition(forward = targetState !in mainNavigationDestinations)
    val direction = mainNavigationDirection(initialState, targetState, vertical, layoutDirection)
    val incoming = if (vertical) slideInVertically(tween(220)) { it / 12 * direction }
        else slideInHorizontally(tween(220)) { it / 12 * direction }
    val outgoing = if (vertical) slideOutVertically(tween(180)) { -it / 16 * direction }
        else slideOutHorizontally(tween(180)) { -it / 16 * direction }
    return (incoming + fadeIn(tween(180))) togetherWith (outgoing + fadeOut(tween(120)))
}
