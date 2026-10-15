package io.github.korenkrita.simulsub

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 检查安装后的真实应用身份与系统解析结果，防止改名后仍覆盖原版。 */
class AppIdentityDeviceTest {
    /** 系统安装包、启动入口和两个文件分享入口均属于独立应用。 */
    @Test fun installedIdentityAndProvidersAreIndependent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = "io.github.zouyiqaq.yuyi"
        assertEquals(expected, context.packageName)
        val pm = context.packageManager
        assertEquals("羽译", pm.getApplicationLabel(context.applicationInfo).toString())
        val launch = requireNotNull(pm.getLaunchIntentForPackage(expected))
        assertEquals(expected, launch.component?.packageName)
        assertEquals("io.github.korenkrita.simulsub.MainActivity", pm.getActivityInfo(requireNotNull(launch.component), 0).targetActivity)
        for (suffix in listOf("logs", "updates")) {
            val provider = requireNotNull(pm.resolveContentProvider("$expected.$suffix", 0))
            assertEquals(expected, provider.packageName)
            assertFalse(provider.exported)
            assertTrue(provider.grantUriPermissions)
        }
        assertTrue(context.filesDir.absolutePath.contains(expected))
    }

    /** 由Android实际加载新矢量图标，并验证单色主题层存在且可绘制。 */
    @Test fun launcherIconRendersOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val icon = context.packageManager.getActivityIcon(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)?.component))
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 512, 512)
        icon.draw(Canvas(bitmap))
        assertTrue("图标中心应为不透明色块", android.graphics.Color.alpha(bitmap.getPixel(256,256)) > 0)
        File(context.getExternalFilesDir(null), "hinome-launcher-icon.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        if (Build.VERSION.SDK_INT >= 33) {
            assertTrue("启动器图标应使用自适应资源", icon is AdaptiveIconDrawable)
            val mono = requireNotNull((icon as AdaptiveIconDrawable).monochrome) { "缺少单色主题图标" }
            val themed = AdaptiveIconDrawable(ColorDrawable(android.graphics.Color.rgb(243, 223, 206)), mono)
            mono.setTint(android.graphics.Color.rgb(111, 78, 45))
            val themedBitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            themed.setBounds(0, 0, 512, 512)
            themed.draw(Canvas(themedBitmap))
            File(context.getExternalFilesDir(null), "yuyi-themed-icon.png").outputStream().use {
                themedBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            themedBitmap.recycle()
        }
    }
}
