package io.github.korenkrita.simulsub.icons

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import io.github.korenkrita.simulsub.BuildConfig
import io.github.korenkrita.simulsub.ui.ThemePalettes

/** 彩色和纯色共用用户包内的轮廓，不再保留视觉重复的原图模式。 */
enum class IconStyle(val id: String, val title: String) {
    VECTOR("vector", "彩色"), MONO("mono", "纯色")
}

/** 背景选项与主题独立，插画背景保留柔和渐变与角部装饰。 */
enum class IconBackground(val id: String, val title: String) {
    ARTWORK("artwork", "插画背景"), SAGE("sage", "鼠尾草绿"), CREAM("cream", "奶油白"), ROSE("rose", "浅山茶粉"), INK("ink", "暖墨色")
}

/** 桌面入口采用有限预设色，透明轮廓通过资源着色复用。 */
enum class IconTone(val id: String, val title: String, val argb: Int) {
    AMBER("amber", "暖杏", 0xFFD38569.toInt()), CAMELLIA("camellia", "山茶红", 0xFFE24E37.toInt()),
    SAGE("sage", "鼠尾草", 0xFF9AAF87.toInt()), OCEAN("ocean", "海蓝", 0xFF365F82.toInt()),
    FOREST("forest", "松绿", 0xFF436A4D.toInt()), VIOLET("violet", "鸢尾", 0xFF6A5F94.toInt()),
    ROSE("rose", "蔷薇", 0xFF985569.toInt()), SLATE("slate", "岩灰", 0xFF546574.toInt()),
    CREAM("cream", "浅奶油", 0xFFFAF0E2.toInt()), INK("ink", "深墨", 0xFF302A27.toInt());

    /** 浅背景使用深色强调角色，暖墨背景使用亮色强调角色，资源生成遵循同一官方算法。 */
    fun foregroundArgb(background: IconBackground): Int = MaterialColors.getColorRoles(argb, background != IconBackground.INK).accent

    companion object {
        /** 对比同一明暗模式的官方强调色角色，避免深色主题被误判成浅奶油色。 */
        fun nearest(primary: Int, dark: Boolean): IconTone {
            val target = DoubleArray(3).also { ColorUtils.colorToLAB(primary, it) }
            return entries.minBy { tone ->
                val role = MaterialColors.getColorRoles(tone.argb, !dark).accent
                val candidate = DoubleArray(3).also { ColorUtils.colorToLAB(role, it) }
                candidate.indices.sumOf { axis ->
                    val delta = target[axis] - candidate[axis]
                    delta * delta
                }
            }
        }
    }
}

/** 跟随状态独立持久化；资源键仅描述真实图标，彩色资源不受纯色选色影响。 */
data class LauncherIconChoice(
    val style: IconStyle = IconStyle.VECTOR,
    val background: IconBackground = IconBackground.ARTWORK,
    val petals: Boolean = true,
    val followTheme: Boolean = true,
    val resolvedTone: IconTone = IconTone.AMBER,
) {
    val key: String get() = "${style.id}_${background.id}_${if (petals) "petals" else "plain"}" + if (style == IconStyle.MONO) "_${resolvedTone.id}" else ""
    val resourceId: Int get() = requireNotNull(LauncherIconResources.ids[key])
    val colorSummary: String get() = if (followTheme) "跟随主题相近色 · ${resolvedTone.title}" else resolvedTone.title
    val summary: String get() = "${style.title} · ${background.title} · ${if (petals) "有飘落花瓣" else "无飘落花瓣"}" + if (style == IconStyle.MONO) " · $colorSummary" else ""

    companion object {
        val DEFAULT = LauncherIconChoice()
        val all: List<LauncherIconChoice> = IconStyle.entries.flatMap { style ->
            IconBackground.entries.flatMap { background -> listOf(true, false).flatMap { petals ->
                (if (style == IconStyle.MONO) IconTone.entries.toList() else listOf(IconTone.AMBER))
                    .map { LauncherIconChoice(style, background, petals, resolvedTone = it) }
            } }
        }

        /** 旧原图和未包含颜色的纯色键保留背景与装饰，未知值安全回退。 */
        fun parse(key: String?): LauncherIconChoice {
            val migrated = key?.replaceFirst("original_", "vector_")
            return all.firstOrNull { it.key == migrated }
                ?: all.firstOrNull { it.style == IconStyle.MONO && it.key == "${migrated}_amber" }
                ?: DEFAULT
        }
    }
}

/** 通过系统启动入口切换真实桌面图标，不创建重复快捷方式。 */
class LauncherIcons(private val context: Context) {
    private val prefs = context.getSharedPreferences("launcher_icons", Context.MODE_PRIVATE)
    private val pm = context.applicationContext.packageManager
    val current: LauncherIconChoice get() {
        val parsed = LauncherIconChoice.parse(prefs.getString("choice", null))
        val tone = IconTone.entries.firstOrNull { it.id == prefs.getString("resolved_tone", null) } ?: parsed.resolvedTone
        return parsed.copy(followTheme = prefs.getBoolean("follow_theme", true), resolvedTone = tone)
    }

    /** 别名限定在本包，升级使用新入口防止旧默认入口的禁用状态被继承。 */
    fun component(choice: LauncherIconChoice): ComponentName = ComponentName(context.packageName, "${BuildConfig.APPLICATION_ID}.launcher.v3.${choice.key}")

    /** 使用界面相同的主题角色，涵盖自定义种子、动态壁纸色和系统明暗模式。 */
    fun themeTone(): IconTone {
        val appearance = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        val dark = when (context.getSharedPreferences("jpsub", Context.MODE_PRIVATE).getString("theme", "system")) {
            "dark" -> true
            "light" -> false
            else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        val scheme = if (appearance.getBoolean("dynamic", false) && Build.VERSION.SDK_INT >= 31) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else ThemePalettes.scheme(
            appearance.getString("color", ThemePalettes.DEFAULT_ID) ?: ThemePalettes.DEFAULT_ID,
            dark, appearance.getInt("custom_color", ThemePalettes.DEFAULT_CUSTOM_SEED),
        )
        return IconTone.nearest(scheme.primary.toArgb(), dark)
    }

    /** 开屏恢复偏好与唯一入口；相同组件状态不会重复调用系统切换。 */
    fun reconcile() {
        val saved = current
        val expected = if (saved.style == IconStyle.MONO && saved.followTheme) saved.copy(resolvedTone = themeTone()) else saved
        val active = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0)
        if (active.size != 1 || active.single().activityInfo.name != component(expected).className ||
            saved != expected || prefs.getString("choice", null) != expected.key) apply(expected)
    }

    /** 仅主题操作或恢复时解析颜色；手动选择和彩色样式均保持原状。 */
    fun syncTheme() {
        val saved = current
        if (saved.style == IconStyle.MONO && saved.followTheme) {
            val resolved = saved.copy(resolvedTone = themeTone())
            if (resolved != saved) apply(resolved)
        }
    }

    /** 原子切换并持久化；旧系统先开启新入口，出错时恢复实际组件和全部偏好。 */
    fun apply(choice: LauncherIconChoice) {
        require(choice.key in LauncherIconResources.ids) { "未知图标" }
        val resolved = if (choice.style == IconStyle.MONO && choice.followTheme) choice.copy(resolvedTone = themeTone()) else choice
        val saved = prefs.all
        val before = LauncherIconChoice.all.associate { component(it) to pm.getComponentEnabledSetting(component(it)) }
        val selectedComponent = component(resolved)
        val changes = before.keys.associateWith { candidate ->
            if (candidate == selectedComponent) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }.filter { (component, state) -> before[component] != state }
        try {
            if (changes.isNotEmpty()) setStates(changes)
            if (prefs.getString("choice", null) != resolved.key || prefs.getBoolean("follow_theme", true) != resolved.followTheme || prefs.getString("resolved_tone", null) != resolved.resolvedTone.id) {
                check(prefs.edit().putString("choice", resolved.key).putBoolean("follow_theme", resolved.followTheme)
                    .putString("resolved_tone", resolved.resolvedTone.id).commit()) { "图标设置保存失败" }
            }
        } catch (error: Exception) {
            val restore = prefs.edit()
            listOf("choice", "resolved_tone").forEach { key -> restore.putString(key, saved[key] as? String) }
            if (saved.containsKey("follow_theme")) restore.putBoolean("follow_theme", saved["follow_theme"] as Boolean) else restore.remove("follow_theme")
            restore.commit()
            if (changes.isNotEmpty()) runCatching { setStates(before.filterKeys { it in changes }) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    /** 新系统使用同一批设置；旧系统保证始终至少有一个可用入口。 */
    private fun setStates(states: Map<ComponentName, Int>) {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.setComponentEnabledSettings(states.map { (component, state) ->
                PackageManager.ComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            })
        } else {
            states.entries.sortedBy { (_, state) -> if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) 1 else 0 }
                .forEach { (component, state) -> pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP) }
        }
    }
}
