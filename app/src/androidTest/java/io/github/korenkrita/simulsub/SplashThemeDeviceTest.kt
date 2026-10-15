package io.github.korenkrita.simulsub

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.ContextThemeWrapper
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.ui.SettingsUiState
import io.github.korenkrita.simulsub.ui.ThemePalettes
import io.github.korenkrita.simulsub.ui.UiThemeMode
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 验证系统启动资源与应用背景角色一致；真正跨进程冷启动仍需设备录屏验收。
 * 冷启动验收：在独立测试安装上首次从桌面启动，确认默认山茶暖底；系统浅色下在应用中选择深色，
 * 正常返回桌面后强行停止该测试应用，再从桌面图标启动，确认第一张系统启动页已是深色。
 * 六预设分别复测；SYSTEM模式退出后切换系统明暗再冷启动；手动LIGHT/DARK须不受系统反向设置影响。
 * Android12/13动态色按邻近中性色验收，Android14起按系统background验收；自定义只要求原生回退合理，
 * 应用首帧精确。另测更换壁纸、覆盖升级和不同桌面图标入口，不增加停留时间或用Activity重建冒充冷启动。
 * 本类偏好重建测试只验证保存键的读取，不能代替上述系统持久化和跨进程启动验证。
 */
class SplashThemeDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** 首安装的默认主题直接来自清单资源，浅色为奶油暖底，系统深色也有对应背景。 */
    @Test fun firstInstallUsesCamelliaBackgroundBeforeActivityExists() {
        listOf(false, true).forEach { dark ->
            val configured = configuredContext(dark)
            val expected = ThemePalettes.scheme(ThemePalettes.DEFAULT_ID, dark).background.toArgb()
            assertEquals(expected, resourceBackground(configured, R.style.Theme_Yuyi_Starting))
            if (!dark) assertEquals(0xFFF8F5F0.toInt(), expected)
        }
    }

    /** 六个预设逐一验证系统明暗与手动明暗，手动模式不能被系统夜间配置覆盖。 */
    @Test fun everyPresetStartingColorEqualsApplicationBackgroundInEveryMode() {
        ThemePalettes.entries.forEach { palette ->
            UiThemeMode.entries.forEach { mode ->
                listOf(false, true).forEach { systemDark ->
                    val configured = configuredContext(systemDark)
                    val appearance = SplashTheme.Appearance(theme = mode, colorId = palette.id)
                    val dark = mode == UiThemeMode.DARK || (mode == UiThemeMode.SYSTEM && systemDark)
                    val expected = ThemePalettes.scheme(palette.id, dark).background.toArgb()
                    assertEquals("${palette.id} / $mode / 系统深色=$systemDark", expected,
                        resourceBackground(configured, SplashTheme.themeResource(configured, appearance)))
                    assertEquals(expected, SplashTheme.backgroundColor(configured, appearance))
                }
            }
        }
    }

    /** 重建读取器后仍读到已保存的手动深色，不依赖上一个 Activity 的内存状态。 */
    @Test fun savedManualDarkSurvivesASeparatePreferenceReader() {
        val prefix = "启动主题测试.${System.nanoTime()}"
        val firstReader = isolatedPreferences(prefix)
        assertEquals(SplashTheme.Appearance(), SplashTheme.readAppearance(firstReader))
        assertTrue(firstReader.getSharedPreferences("jpsub", 0).edit().putString("theme", "dark").commit())
        assertTrue(firstReader.getSharedPreferences("appearance", 0).edit().putString("color", "rose")
            .putBoolean("dynamic", false).commit())
        val nextLaunch = SplashTheme.readAppearance(isolatedPreferences(prefix))
        assertEquals(UiThemeMode.DARK, nextLaunch.theme)
        val lightSystem = configuredContext(false)
        assertEquals(ThemePalettes.scheme("rose", true).background.toArgb(),
            resourceBackground(lightSystem, SplashTheme.themeResource(lightSystem, nextLaunch)))
        assertEquals(nextLaunch, SplashTheme.Appearance.from(SettingsUiState(theme = UiThemeMode.DARK, themeColorId = "rose")))
    }

    /** 自定义中性色可匹配已有预设；任意种子在 Activity 首帧仍保持应用的精确颜色。 */
    @Test fun customSeedKeepsExactRuntimeColorAndUsesNearestNativePreset() {
        listOf(false, true).forEach { dark ->
            val configured = configuredContext(dark)
            val sameAsOcean = SplashTheme.Appearance(colorId = "custom", customSeed = 0xFF365F82.toInt())
            val ocean = SplashTheme.Appearance(colorId = "ocean")
            assertEquals(SplashTheme.themeResource(configured, ocean), SplashTheme.themeResource(configured, sameAsOcean))
            val arbitrary = sameAsOcean.copy(customSeed = 0xFF12AB34.toInt())
            assertEquals(ThemePalettes.scheme("custom", dark, arbitrary.customSeed).background.toArgb(),
                SplashTheme.backgroundColor(configured, arbitrary))
            val fallback = resourceBackground(configured, SplashTheme.themeResource(configured, arbitrary))
            assertNotEquals("任意种子不得伪称系统背景完全相等", SplashTheme.backgroundColor(configured, arbitrary), fallback)
        }
    }

    /** 动态色首帧采用实际 Material3 背景，原生资源按系统版本精确匹配或明确采用邻近色阶。 */
    @Test fun dynamicColorsUseSystemNeutralResourcesAndExactRuntimeBackground() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        UiThemeMode.entries.forEach { mode ->
            listOf(false, true).forEach { systemDark ->
                val configured = configuredContext(systemDark)
                val appearance = SplashTheme.Appearance(theme = mode, dynamicColors = true)
                val dark = mode == UiThemeMode.DARK || (mode == UiThemeMode.SYSTEM && systemDark)
                val expected = (if (dark) dynamicDarkColorScheme(configured) else dynamicLightColorScheme(configured)).background.toArgb()
                assertEquals(expected, SplashTheme.backgroundColor(configured, appearance))
                val native = resourceBackground(configured, SplashTheme.themeResource(configured, appearance))
                if (Build.VERSION.SDK_INT >= 34) assertEquals(expected, native)
                else assertEquals(configured.getColor(if (dark) android.R.color.system_neutral2_900 else android.R.color.system_neutral2_10), native)
            }
        }
    }

    /** 只切换资源配置，不改设备的系统明暗设置。 */
    private fun configuredContext(dark: Boolean): Context = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        },
    )

    /** 同时断言旧版窗口背景、Android12启动背景和透明人物图标，防止主题继承丢失资源。 */
    private fun resourceBackground(context: Context, theme: Int): Int {
        val themed = ContextThemeWrapper(context, theme)
        val attrs = themed.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
        val background = try { (attrs.getDrawable(0) as ColorDrawable).color } finally { attrs.recycle() }
        if (Build.VERSION.SDK_INT >= 31) {
            val splash = themed.obtainStyledAttributes(intArrayOf(android.R.attr.windowSplashScreenBackground,
                android.R.attr.windowSplashScreenAnimatedIcon, android.R.attr.windowSplashScreenIconBackgroundColor))
            try {
                assertEquals(background, splash.getColor(0, 0))
                assertEquals(R.drawable.ic_splash_portrait, splash.getResourceId(1, 0))
                assertEquals(android.graphics.Color.TRANSPARENT, splash.getColor(2, -1))
            } finally { splash.recycle() }
        }
        return background
    }

    /** 在独立命名的偏好中模拟两次读取，不覆盖用户应用设置。 */
    private fun isolatedPreferences(prefix: String): Context = object : ContextWrapper(context) {
        /** 各个读取器共用独立命名空间，以验证持久化键与默认值。 */
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            context.getSharedPreferences("$prefix.$name", mode)
    }
}
