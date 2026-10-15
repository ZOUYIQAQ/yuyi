package io.github.korenkrita.simulsub

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 检查安装后的真实应用身份与系统解析结果，防止改名后仍覆盖原版。 */
class AppIdentityDeviceTest {
    /** 系统安装包、启动入口和两个文件分享入口均属于独立应用。 */
    @Test fun installedIdentityAndProvidersAreIndependent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = "io.github.zouyiqaq.hinome"
        assertEquals(expected, context.packageName)
        val pm = context.packageManager
        assertEquals("火羽同传", pm.getApplicationLabel(context.applicationInfo).toString())
        val launch = requireNotNull(pm.getLaunchIntentForPackage(expected))
        assertEquals(expected, launch.component?.packageName)
        assertEquals("io.github.korenkrita.simulsub.MainActivity", launch.component?.className)
        for (suffix in listOf("logs", "updates")) {
            val provider = requireNotNull(pm.resolveContentProvider("$expected.$suffix", 0))
            assertEquals(expected, provider.packageName)
            assertFalse(provider.exported)
            assertTrue(provider.grantUriPermissions)
        }
        assertTrue(context.filesDir.absolutePath.contains(expected))
    }

    /** 由Android实际加载自适应图标，确认原生矢量可绘制并留存视觉验收图。 */
    @Test fun launcherIconRendersOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val icon = context.packageManager.getApplicationIcon(context.applicationInfo)
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 512, 512)
        icon.draw(Canvas(bitmap))
        assertTrue("图标中心应为不透明色块", android.graphics.Color.alpha(bitmap.getPixel(256,256)) > 0)
        File(context.getExternalFilesDir(null), "hinome-launcher-icon.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
