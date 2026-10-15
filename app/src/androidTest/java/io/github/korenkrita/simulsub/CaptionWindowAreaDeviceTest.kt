package io.github.korenkrita.simulsub

import android.graphics.Insets
import android.graphics.Rect
import android.view.WindowInsets
import android.view.WindowMetrics
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Test

/** 系统隐藏导航栏后，其历史尺寸不能继续挡住字幕贴底。 */
@SdkSuppress(minSdkVersion = 30)
class CaptionWindowAreaDeviceTest {
    /** 显示中的状态栏和导航栏正常避让，保留窗口在屏幕中的偏移。 */
    @Test fun visibleBarsKeepTheirSafeArea() {
        val insets = WindowInsets.Builder()
            .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, 60, 0, 0))
            .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 120))
            .setInsetsIgnoringVisibility(WindowInsets.Type.systemBars(), Insets.of(0, 60, 0, 120))
            .setVisible(WindowInsets.Type.systemBars(), true).build()
        assertEquals(Rect(10, 80, 1090, 2300), captionWindowArea(WindowMetrics(Rect(10, 20, 1090, 2420), insets)))
    }

    /** 全屏下实际边距归零，即使系统记得导航栏原高度也必须允许到屏幕底部。 */
    @Test fun hiddenBarsDoNotLeaveAnInvisibleBottomGap() {
        val insets = WindowInsets.Builder()
            .setInsets(WindowInsets.Type.systemBars(), Insets.NONE)
            .setInsetsIgnoringVisibility(WindowInsets.Type.systemBars(), Insets.of(0, 60, 0, 120))
            .setVisible(WindowInsets.Type.systemBars(), false).build()
        assertEquals(Rect(0, 0, 1080, 2400), captionWindowArea(WindowMetrics(Rect(0, 0, 1080, 2400), insets)))
    }
}
