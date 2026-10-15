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
    const val DEFAULT_ID = "amber"
    const val DEFAULT_CUSTOM_SEED: Int = 0xFFD38569.toInt()
    // 默认配色取自原图：发色、山茶红、奶油底色和鼠尾草绿。
    private const val CAMELLIA_SEED: Int = 0xFFE24E37.toInt()
    private const val CREAM_SEED: Int = 0xFFFAF0E2.toInt()
    private const val SAGE_SEED: Int = 0xFF9AAF87.toInt()
    val entries = listOf(
        ThemePalette("ocean", "海蓝", 0xFF365F82.toInt()),
        ThemePalette("forest", "松绿", 0xFF436A4D.toInt()),
        ThemePalette("violet", "鸢尾", 0xFF6A5F94.toInt()),
        ThemePalette("amber", "山茶", DEFAULT_CUSTOM_SEED),
        ThemePalette("rose", "蔷薇", 0xFF985569.toInt()),
        ThemePalette("slate", "岩灰", 0xFF546574.toInt()),
    )

    /** 默认主题按原图分配三个强调色，其他预设与自定义配色保留原算法，未知标识回退默认。 */
    fun scheme(id: String, dark: Boolean, customSeed: Int = DEFAULT_CUSTOM_SEED): ColorScheme {
        val palette = entries.firstOrNull { it.id == id } ?: entries.first { it.id == DEFAULT_ID }
        val useIconPalette = id != "custom" && palette.id == DEFAULT_ID
        val seed = if (id == "custom") customSeed or 0xFF000000.toInt()
            else palette.seed
        val primarySeed = if (useIconPalette && dark) soften(seed, 0.5f) else seed
        val primary = MaterialColors.getColorRoles(primarySeed, !dark)
        val secondary = MaterialColors.getColorRoles(
            if (useIconPalette) (if (dark) soften(CAMELLIA_SEED, 0.35f) else CAMELLIA_SEED) else ColorUtils.blendARGB(seed, 0xFF808080.toInt(), 0.65f), !dark)
        val tertiary = if (useIconPalette) MaterialColors.getColorRoles(SAGE_SEED, !dark) else secondary
        val neutralSeed = if (useIconPalette) CREAM_SEED else seed
        val base = if (dark) darkColorScheme() else lightColorScheme()
        val scheme = base.copy(
            primary = Color(primary.accent), onPrimary = Color(primary.onAccent),
            primaryContainer = Color(primary.accentContainer), onPrimaryContainer = Color(primary.onAccentContainer),
            secondary = Color(secondary.accent), onSecondary = Color(secondary.onAccent),
            secondaryContainer = Color(secondary.accentContainer), onSecondaryContainer = Color(secondary.onAccentContainer),
            tertiary = Color(tertiary.accent), onTertiary = Color(tertiary.onAccent),
            tertiaryContainer = Color(tertiary.accentContainer), onTertiaryContainer = Color(tertiary.onAccentContainer),
            background = neutral(neutralSeed, if (dark) 17 else 248), onBackground = neutral(neutralSeed, if (dark) 226 else 28),
            surface = neutral(neutralSeed, if (dark) 17 else 248), onSurface = neutral(neutralSeed, if (dark) 226 else 28),
            surfaceVariant = neutral(neutralSeed, if (dark) 64 else 229), onSurfaceVariant = neutral(neutralSeed, if (dark) 195 else 67),
            outline = neutral(neutralSeed, if (dark) 143 else 118), outlineVariant = neutral(neutralSeed, if (dark) 65 else 216),
            surfaceContainerLowest = neutral(neutralSeed, if (dark) 12 else 255),
            surfaceContainerLow = neutral(neutralSeed, if (dark) 24 else 244),
            surfaceContainer = neutral(neutralSeed, if (dark) 29 else 239),
            surfaceContainerHigh = neutral(neutralSeed, if (dark) 38 else 233),
            surfaceContainerHighest = neutral(neutralSeed, if (dark) 48 else 226),
            surfaceDim = neutral(neutralSeed, if (dark) 17 else 220), surfaceBright = neutral(neutralSeed, if (dark) 55 else 248),
            inverseSurface = neutral(neutralSeed, if (dark) 241 else 47), inverseOnSurface = neutral(neutralSeed, if (dark) 47 else 241),
            inversePrimary = Color(MaterialColors.getColorRoles(primarySeed, dark).accent), surfaceTint = Color(primary.accent),
        )
        return if (useIconPalette) scheme.withIconFixedRoles() else scheme
    }

    /** 固定色沿用官方浅色容器及深色强调角色，明暗模式保持相同来源和可读对比。 */
    private fun ColorScheme.withIconFixedRoles(): ColorScheme {
        val primaryLight = MaterialColors.getColorRoles(DEFAULT_CUSTOM_SEED, true)
        val primaryDark = MaterialColors.getColorRoles(DEFAULT_CUSTOM_SEED, false)
        val secondaryLight = MaterialColors.getColorRoles(CAMELLIA_SEED, true)
        val secondaryDark = MaterialColors.getColorRoles(CAMELLIA_SEED, false)
        val tertiaryLight = MaterialColors.getColorRoles(SAGE_SEED, true)
        val tertiaryDark = MaterialColors.getColorRoles(SAGE_SEED, false)
        return copy(
            primaryFixed = Color(primaryLight.accentContainer), primaryFixedDim = Color(primaryDark.accent),
            onPrimaryFixed = Color(primaryLight.onAccentContainer), onPrimaryFixedVariant = Color(primaryDark.accentContainer),
            secondaryFixed = Color(secondaryLight.accentContainer), secondaryFixedDim = Color(secondaryDark.accent),
            onSecondaryFixed = Color(secondaryLight.onAccentContainer), onSecondaryFixedVariant = Color(secondaryDark.accentContainer),
            tertiaryFixed = Color(tertiaryLight.accentContainer), tertiaryFixedDim = Color(tertiaryDark.accent),
            onTertiaryFixed = Color(tertiaryLight.onAccentContainer), onTertiaryFixedVariant = Color(tertiaryDark.accentContainer),
        )
    }

    /** 深色模式只降低原图强调色饱和度，避免红色容器过浓，文字色仍由官方算法生成。 */
    private fun soften(seed: Int, saturationScale: Float): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(seed, hsl)
        hsl[1] *= saturationScale
        return ColorUtils.HSLToColor(hsl)
    }

    /** 用极少主题色染中性色，保留安静背景和一致的文字对比。 */
    private fun neutral(seed: Int, level: Int): Color = Color(ColorUtils.blendARGB(android.graphics.Color.rgb(level, level, level), seed, if (seed == CREAM_SEED && level >= 200) 0.35f else 0.07f))
}
