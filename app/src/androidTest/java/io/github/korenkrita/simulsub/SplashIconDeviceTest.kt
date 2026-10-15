package io.github.korenkrita.simulsub

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 核对系统启动主题使用透明人物资源，桌面图标背景独立保留。 */
class SplashIconDeviceTest {
    /** 真实启动Activity的主题明确指定去背景图，图外像素透明且人物仍有彩色内容。 */
    @Test fun startupIconUsesTransparentArtworkInsteadOfLauncherBackground() {
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 31)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activity = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        val themed = ContextThemeWrapper(context, activity.themeResource)
        val attrs = themed.obtainStyledAttributes(intArrayOf(android.R.attr.windowSplashScreenAnimatedIcon,
            android.R.attr.windowSplashScreenIconBackgroundColor))
        try {
            assertEquals(R.drawable.ic_splash_portrait, attrs.getResourceId(0, 0))
            assertEquals(android.graphics.Color.TRANSPARENT, attrs.getColor(1, -1))
        } finally { attrs.recycle() }
        val drawable = themed.getDrawable(R.drawable.ic_splash_portrait)!!
        val bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, 432, 432); drawable.draw(Canvas(bitmap))
        assertEquals(0, bitmap.getPixel(0, 0))
        assertEquals(0, bitmap.getPixel(90, 90))
        assertEquals(0, bitmap.getPixel(341, 341))
        assertTrue(android.graphics.Color.alpha(bitmap.getPixel(216, 216)) > 0)
        assertTrue("必须保留彩色花瓣和人物", (0 until 432).any { y ->
            (0 until 432).any { x -> val color = bitmap.getPixel(x, y); android.graphics.Color.alpha(color) > 200 &&
                android.graphics.Color.red(color) > 180 && android.graphics.Color.green(color) < 130 }
        })
    }
}
