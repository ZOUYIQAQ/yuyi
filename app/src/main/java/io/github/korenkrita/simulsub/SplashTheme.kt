package io.github.korenkrita.simulsub

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.ContextThemeWrapper
import androidx.annotation.StyleRes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import io.github.korenkrita.simulsub.ui.SettingsUiState
import io.github.korenkrita.simulsub.ui.ThemePalettes
import io.github.korenkrita.simulsub.ui.UiThemeMode

/** 系统启动页使用可持久化资源，Activity 窗口首帧使用应用实际背景；不延长启动页停留。 */
internal class SplashTheme(private val activity: Activity) {
    private var persistedTheme: Int? = null
    private var persistedAppearance: Appearance? = null
    private var windowColor: Int? = null

    /** 只保留主题输入，字幕状态和权限刷新不会引起启动主题重复写入。 */
    data class Appearance(
        val theme: UiThemeMode = UiThemeMode.SYSTEM,
        val dynamicColors: Boolean = false,
        val colorId: String = ThemePalettes.DEFAULT_ID,
        val customSeed: Int = ThemePalettes.DEFAULT_CUSTOM_SEED,
    ) {
        companion object {
            /** 从控制器已有状态提取主题，不增加控制器接口或修改保存流程。 */
            fun from(settings: SettingsUiState): Appearance = Appearance(
                settings.theme, settings.dynamicColors, settings.themeColorId, settings.customThemeSeed,
            )
        }
    }

    /** 在 super.onCreate 前选择当前窗口资源；已经显示的系统启动页由上次持久化主题决定。 */
    fun prepare() {
        activity.setTheme(themeResource(activity, readAppearance(activity)))
    }

    /** 更新首帧底色并为下次冷启动持久化主题；系统按资源名称保存，主题名必须跨版本稳定。 */
    fun synchronize(appearance: Appearance) {
        val color = backgroundColor(activity, appearance)
        if (windowColor != color) {
            activity.window.setBackgroundDrawable(ColorDrawable(color))
            windowColor = color
        }
        if (Build.VERSION.SDK_INT >= 31 && persistedAppearance != appearance) {
            val theme = themeResource(activity, appearance)
            if (persistedTheme != theme) {
                activity.splashScreen.setSplashScreenTheme(theme)
                persistedTheme = theme
            }
            persistedAppearance = appearance
        }
    }

    companion object {
        /** 三种模式分别使用夜间资源、固定浅色与固定深色，避免手动深色被系统浅色覆盖。 */
        private data class Preset(val id: String, @StyleRes val system: Int, @StyleRes val light: Int, @StyleRes val dark: Int)

        private val presets = listOf(
            Preset("ocean", R.style.Theme_Yuyi_Starting_Ocean, R.style.Theme_Yuyi_Starting_Ocean_Light, R.style.Theme_Yuyi_Starting_Ocean_Dark),
            Preset("forest", R.style.Theme_Yuyi_Starting_Forest, R.style.Theme_Yuyi_Starting_Forest_Light, R.style.Theme_Yuyi_Starting_Forest_Dark),
            Preset("violet", R.style.Theme_Yuyi_Starting_Violet, R.style.Theme_Yuyi_Starting_Violet_Light, R.style.Theme_Yuyi_Starting_Violet_Dark),
            Preset("amber", R.style.Theme_Yuyi_Starting_Amber, R.style.Theme_Yuyi_Starting_Amber_Light, R.style.Theme_Yuyi_Starting_Amber_Dark),
            Preset("rose", R.style.Theme_Yuyi_Starting_Rose, R.style.Theme_Yuyi_Starting_Rose_Light, R.style.Theme_Yuyi_Starting_Rose_Dark),
            Preset("slate", R.style.Theme_Yuyi_Starting_Slate, R.style.Theme_Yuyi_Starting_Slate_Light, R.style.Theme_Yuyi_Starting_Slate_Dark),
        )
        private val dynamic = Preset("dynamic", R.style.Theme_Yuyi_Starting_Dynamic,
            R.style.Theme_Yuyi_Starting_Dynamic_Light, R.style.Theme_Yuyi_Starting_Dynamic_Dark)

        /** 直接读取控制器的现有主题键，避免首次创建窗口时构造带业务副作用的 Prefs。 */
        fun readAppearance(context: Context): Appearance {
            val prefs = context.getSharedPreferences("jpsub", Context.MODE_PRIVATE)
            val appearance = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
            return Appearance(
                theme = when (prefs.getString("theme", "system")) {
                    "light" -> UiThemeMode.LIGHT
                    "dark" -> UiThemeMode.DARK
                    else -> UiThemeMode.SYSTEM
                },
                dynamicColors = appearance.getBoolean("dynamic", false),
                colorId = appearance.getString("color", ThemePalettes.DEFAULT_ID) ?: ThemePalettes.DEFAULT_ID,
                customSeed = appearance.getInt("custom_color", ThemePalettes.DEFAULT_CUSTOM_SEED),
            )
        }

        /** 与 Compose 使用同一个背景角色；动态色在旧系统和应用一样回退到手动配色。 */
        fun backgroundColor(context: Context, appearance: Appearance): Int {
            val dark = isDark(context, appearance.theme)
            return if (appearance.dynamicColors && Build.VERSION.SDK_INT >= 31) {
                (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).background.toArgb()
            } else ThemePalettes.scheme(appearance.colorId, dark, appearance.customSeed).background.toArgb()
        }

        /** 预设精确同步；自定义种子选最接近的背景，动态色由分版本的系统中性色资源提供。 */
        @StyleRes
        fun themeResource(context: Context, appearance: Appearance): Int {
            val preset = when {
                appearance.dynamicColors && Build.VERSION.SDK_INT >= 31 -> dynamic
                appearance.colorId == "custom" -> nearestPreset(context, appearance)
                else -> presets.firstOrNull { it.id == appearance.colorId } ?: presets.first { it.id == ThemePalettes.DEFAULT_ID }
            }
            return when (appearance.theme) {
                UiThemeMode.SYSTEM -> preset.system
                UiThemeMode.LIGHT -> preset.light
                UiThemeMode.DARK -> preset.dark
            }
        }

        /** 系统模式同时比较浅深背景，持久化后系统明暗切换仍沿用同一组相近的中性色。 */
        private fun nearestPreset(context: Context, appearance: Appearance): Preset {
            val modes = if (appearance.theme == UiThemeMode.SYSTEM) listOf(false, true)
                else listOf(isDark(context, appearance.theme))
            val wanted = modes.map { ThemePalettes.scheme("custom", it, appearance.customSeed).background.toArgb() }
            return presets.minBy { preset ->
                modes.indices.sumOf { index ->
                    colorDistance(wanted[index], presetBackground(context, if (modes[index]) preset.dark else preset.light))
                }
            }
        }

        /** 候选背景直接读取已断言相等的静态资源，避免启动时为六个预设重复计算整套调色板。 */
        private fun presetBackground(context: Context, @StyleRes theme: Int): Int {
            val attrs = ContextThemeWrapper(context, theme).obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
            return try { attrs.getColor(0, Color.BLACK) } finally { attrs.recycle() }
        }

        /** 比较不透明中性色的 RGB 距离，仅选择已有资源，不声称可持久化任意 RGB。 */
        private fun colorDistance(first: Int, second: Int): Int =
            listOf(Color.red(first) - Color.red(second), Color.green(first) - Color.green(second),
                Color.blue(first) - Color.blue(second)).sumOf { it * it }

        /** 手动模式优先，只有跟随系统时读取设备夜间配置。 */
        private fun isDark(context: Context, mode: UiThemeMode): Boolean = when (mode) {
            UiThemeMode.LIGHT -> false
            UiThemeMode.DARK -> true
            UiThemeMode.SYSTEM -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
    }
}
