package io.github.korenkrita.simulsub.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors

/** 固定主题种子色，真正的前景和容器色由Material官方色调算法生成。 */
data class ThemePalette(val id: String, val title: String, val seed: Int)

/** 手动配色和动态壁纸色共用Material3语义，不直接把亮色用作文字背景。 */
object ThemePalettes {
    val entries = listOf(
        ThemePalette("ocean", "海蓝", 0xFF365F82.toInt()),
        ThemePalette("forest", "松绿", 0xFF436A4D.toInt()),
        ThemePalette("violet", "鸢尾", 0xFF6A5F94.toInt()),
        ThemePalette("amber", "暖砂", 0xFF8B633D.toInt()),
        ThemePalette("rose", "蔷薇", 0xFF985569.toInt()),
        ThemePalette("slate", "岩灰", 0xFF546574.toInt()),
    )

    /** 未知旧偏好安全回退，浅深色分别生成对比清楚的角色色。 */
    fun scheme(id: String, dark: Boolean): ColorScheme {
        val seed = (entries.firstOrNull { it.id == id } ?: entries.first()).seed
        val primary = MaterialColors.getColorRoles(seed, !dark)
        val secondary = MaterialColors.getColorRoles(ColorUtils.blendARGB(seed, 0xFF808080.toInt(), 0.65f), !dark)
        val base = if (dark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = Color(primary.accent), onPrimary = Color(primary.onAccent),
            primaryContainer = Color(primary.accentContainer), onPrimaryContainer = Color(primary.onAccentContainer),
            secondary = Color(secondary.accent), onSecondary = Color(secondary.onAccent),
            secondaryContainer = Color(secondary.accentContainer), onSecondaryContainer = Color(secondary.onAccentContainer),
            tertiary = Color(secondary.accent), onTertiary = Color(secondary.onAccent),
            tertiaryContainer = Color(secondary.accentContainer), onTertiaryContainer = Color(secondary.onAccentContainer),
            background = neutral(seed, if (dark) 17 else 248), onBackground = neutral(seed, if (dark) 226 else 28),
            surface = neutral(seed, if (dark) 17 else 248), onSurface = neutral(seed, if (dark) 226 else 28),
            surfaceVariant = neutral(seed, if (dark) 64 else 229), onSurfaceVariant = neutral(seed, if (dark) 195 else 67),
            outline = neutral(seed, if (dark) 143 else 118), outlineVariant = neutral(seed, if (dark) 65 else 216),
            surfaceContainerLowest = neutral(seed, if (dark) 12 else 255),
            surfaceContainerLow = neutral(seed, if (dark) 24 else 244),
            surfaceContainer = neutral(seed, if (dark) 29 else 239),
            surfaceContainerHigh = neutral(seed, if (dark) 38 else 233),
            surfaceContainerHighest = neutral(seed, if (dark) 48 else 226),
            surfaceDim = neutral(seed, if (dark) 17 else 220), surfaceBright = neutral(seed, if (dark) 55 else 248),
            inverseSurface = neutral(seed, if (dark) 226 else 47), inverseOnSurface = neutral(seed, if (dark) 47 else 241),
            inversePrimary = Color(MaterialColors.getColorRoles(seed, dark).accent), surfaceTint = Color(primary.accent),
        )
    }

    /** 用极少主题色染中性色，保留安静背景和一致的文字对比。 */
    private fun neutral(seed: Int, level: Int): Color = Color(ColorUtils.blendARGB(android.graphics.Color.rgb(level, level, level), seed, 0.025f))
}
