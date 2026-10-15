package io.github.korenkrita.simulsub

import android.graphics.Rect
import android.view.WindowInsets
import android.view.WindowMetrics
import androidx.annotation.RequiresApi

/** 只避让实际可见的系统栏与显示缺口；全屏视频隐藏导航栏后可使用原本被占用的底部。 */
@RequiresApi(30)
internal fun captionWindowArea(metrics: WindowMetrics): Rect {
    val insets = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
    val bounds = metrics.bounds
    return Rect(bounds.left + insets.left, bounds.top + insets.top,
        bounds.right - insets.right, bounds.bottom - insets.bottom)
}
