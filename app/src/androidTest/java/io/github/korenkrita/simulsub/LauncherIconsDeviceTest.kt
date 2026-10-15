package io.github.korenkrita.simulsub

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.icons.*
import org.junit.Assert.*
import org.junit.Test

/** 使用设备的真实组件解析、资源加载和持久化验证图标选择。 */
class LauncherIconsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** 每一种组合切换后都只有一个可启动入口，重新创建管理器保留选择。 */
    @Test fun everyChoiceHasExactlyOnePersistentLauncher() {
        val icons = LauncherIcons(context)
        val previous = icons.current
        try {
            LauncherIconChoice.all.map { it.copy(followTheme = false) }.forEach { choice ->
                icons.apply(choice)
                val activities = context.packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0)
                assertEquals("每次切换必须只有一个桌面入口：${choice.key}", 1, activities.size)
                assertEquals(icons.component(choice).className, activities.single().activityInfo.name)
                assertEquals("io.github.korenkrita.simulsub.MainActivity", activities.single().activityInfo.targetActivity)
                assertEquals(choice, LauncherIcons(context).current)
                val icon = context.packageManager.getActivityIcon(icons.component(choice))
                assertTrue("全部选择都应为自适应图标", icon is AdaptiveIconDrawable)
                assertNotNull("全部组合都应保留系统主题图标层", (icon as AdaptiveIconDrawable).monochrome)
                val bitmap = Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
                icon.setBounds(0,0,128,128)
                icon.draw(Canvas(bitmap))
                assertEquals(255, android.graphics.Color.alpha(bitmap.getPixel(64,64)))
                bitmap.recycle()
            }
        } finally { icons.apply(previous) }
    }

    /** 资源和安装入口都没有重复原图模式，彩色键完全不受纯色颜色影响。 */
    @Test fun colorChoicesNeverDuplicateOriginalOrVectorEntries() {
        assertEquals(110, LauncherIconChoice.all.size)
        assertEquals(110, LauncherIconChoice.all.map { it.key }.distinct().size)
        assertEquals(10, LauncherIconChoice.all.count { it.style == IconStyle.VECTOR })
        IconTone.entries.forEach { tone ->
            assertEquals(LauncherIconChoice.DEFAULT.resourceId,
                LauncherIconChoice.DEFAULT.copy(resolvedTone = tone, followTheme = false).resourceId)
        }
        val aliases = requireNotNull(context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS).activities)
            .filter { it.targetActivity != null }
        assertEquals(110, aliases.size)
        assertTrue(aliases.none { "original_" in it.name || ".v2." in it.name })
    }

    /** 旧原图、旧纯色和未知键都能恢复有效组合，开屏修复唯一真实入口。 */
    @Test fun oldAndUnknownPreferencesMigrateThroughReconcile() {
        val icons = LauncherIcons(context)
        val previous = icons.current
        val preferences = context.getSharedPreferences("launcher_icons", 0)
        try {
            assertEquals(LauncherIconChoice(IconStyle.VECTOR, IconBackground.INK, false),
                LauncherIconChoice.parse("original_ink_plain"))
            assertEquals(IconStyle.MONO, LauncherIconChoice.parse("mono_cream_plain").style)
            preferences.edit().putString("choice", "unknown_old_choice").putBoolean("follow_theme", true).commit()
            icons.reconcile()
            assertEquals(LauncherIconChoice.DEFAULT.key, icons.current.key)
            val launchers = context.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0)
            assertEquals(listOf(icons.component(icons.current).className), launchers.map { it.activityInfo.name })
            assertEquals(icons.current, LauncherIcons(context).current)
        } finally { icons.apply(previous) }
    }

    /** 自动色改为同色手动选择也必须保存状态，覆盖安装的默认入口不能覆盖手动偏好。 */
    @Test fun reconcileRepairsUpgradeDefaultWithoutChangingManualColor() {
        val icons = LauncherIcons(context)
        val previous = icons.current
        try {
            icons.apply(LauncherIconChoice(style = IconStyle.MONO, background = IconBackground.INK))
            val automatic = icons.current
            val manual = automatic.copy(followTheme = false)
            icons.apply(manual)
            assertEquals(automatic.key, icons.current.key)
            assertFalse(LauncherIcons(context).current.followTheme)
            context.packageManager.setComponentEnabledSetting(icons.component(LauncherIconChoice.DEFAULT),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            LauncherIcons(context).reconcile()
            icons.syncTheme()
            assertEquals(manual, LauncherIcons(context).current)
            val launchers = context.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0)
            assertEquals(listOf(icons.component(manual).className), launchers.map { it.activityInfo.name })
        } finally { icons.apply(previous) }
    }

    /** 单色复用透明轮廓，切换色样能实际改变系统加载的图层像素。 */
    @Test fun manualToneChangesActualAdaptiveForeground() {
        val images = listOf(IconTone.OCEAN, IconTone.CAMELLIA).map { tone ->
            val choice = LauncherIconChoice(style = IconStyle.MONO, followTheme = false, resolvedTone = tone)
            Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888).also { bitmap ->
                val icon = context.getDrawable(choice.resourceId) as AdaptiveIconDrawable
                icon.foreground.setBounds(0, 0, 432, 432)
                icon.foreground.draw(Canvas(bitmap))
            }
        }
        var colored = 0
        for (y in 0 until 432) for (x in 0 until 432) {
            val first = images[0].getPixel(x, y)
            val second = images[1].getPixel(x, y)
            assertEquals(android.graphics.Color.alpha(first), android.graphics.Color.alpha(second))
            if (android.graphics.Color.alpha(first) == 255) {
                assertEquals(IconTone.OCEAN.foregroundArgb(IconBackground.ARTWORK), first)
                assertEquals(IconTone.CAMELLIA.foregroundArgb(IconBackground.ARTWORK), second)
                colored++
            }
        }
        assertTrue("轮廓必须包含可见纯色像素", colored > 100)
        images.forEach(Bitmap::recycle)
    }

    /** 所有纯色真实资源按背景使用官方深浅强调角色，避免浅种子色直接叠在鼠尾草背景上。 */
    @Test fun everyMonoForegroundUsesOfficialMaterialContrastRole() {
        LauncherIconChoice.all.filter { it.style == IconStyle.MONO }.forEach { choice ->
            val expected = com.google.android.material.color.MaterialColors.getColorRoles(
                choice.resolvedTone.argb, choice.background != IconBackground.INK).accent
            // 原始尺寸核对精确色值，避免透明边缘缩小时预乘Alpha产生一阶舍入。
            val bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
            val foreground = (context.getDrawable(choice.resourceId) as AdaptiveIconDrawable).foreground
            foreground.setBounds(0, 0, 432, 432)
            foreground.draw(Canvas(bitmap))
            val pixels = IntArray(432 * 432)
            bitmap.getPixels(pixels, 0, 432, 0, 0, 432, 432)
            val opaque = pixels.filter { android.graphics.Color.alpha(it) == 255 }
            assertTrue("必须存在清晰轮廓：${choice.key}", opaque.isNotEmpty())
            assertTrue("必须使用官方背景适配色：${choice.key}，期望=${Integer.toHexString(expected)}，实测=${opaque.distinct().map(Integer::toHexString)}", opaque.all { it == expected })
            bitmap.recycle()
        }
    }

    /** 花瓣开关改变左下飘落花瓣和右下绿叶，头上的山茶花区域逐像素保持一致。 */
    @Test fun petalsToggleKeepsHeadFlowerAndChangesOnlyFallingPetal() {
        val choices = listOf(LauncherIconChoice.DEFAULT, LauncherIconChoice.DEFAULT.copy(petals=false))
        val images = choices.map { choice ->
            Bitmap.createBitmap(432,432,Bitmap.Config.ARGB_8888).also { bitmap ->
                val icon = context.getDrawable(choice.resourceId) as AdaptiveIconDrawable
                icon.background.setBounds(0,0,432,432)
                icon.background.draw(Canvas(bitmap))
            }
        }
        var differences = 0
        for (y in 0 until 432) for (x in 0 until 432) {
            if (images[0].getPixel(x,y) != images[1].getPixel(x,y)) {
                differences++
                assertTrue("变化不能影响头花或人物：$x,$y", (x in 85..140 && y in 270..330) || (x in 280..350 && y in 280..350))
            }
        }
        assertTrue("关闭花瓣应实际改变图标", differences > 10)
        images.forEach(Bitmap::recycle)
    }
}
