package io.github.korenkrita.simulsub

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.color.MaterialColors
import io.github.korenkrita.simulsub.ui.ThemePalettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 在设备上的官方色调算法中检查图标取色来源及一般文字的浅深色对比度。 */
@RunWith(AndroidJUnit4::class)
class ThemeContrastDeviceTest {
    /** 默认主题保留稳定标识，三个强调色分别由原图的发色、山茶和叶色生成。 */
    @Test fun defaultThemeUsesExtractedIconColors() {
        val hair = 0xFFD38569.toInt()
        val camellia = 0xFFE24E37.toInt()
        val sage = 0xFF9AAF87.toInt()
        assertEquals("amber", ThemePalettes.DEFAULT_ID)
        assertEquals(hair, ThemePalettes.DEFAULT_CUSTOM_SEED)
        val palette = ThemePalettes.entries.single { it.id == ThemePalettes.DEFAULT_ID }
        assertEquals("山茶", palette.title)
        assertEquals(hair, palette.seed)
        listOf(false, true).forEach { dark ->
            val scheme = ThemePalettes.scheme("amber", dark)
            val primary = MaterialColors.getColorRoles(hair, !dark)
            val secondary = MaterialColors.getColorRoles(camellia, !dark)
            val tertiary = MaterialColors.getColorRoles(sage, !dark)
            if (!dark) {
            assertEquals(primary.accent, scheme.primary.toArgb())
            assertEquals(primary.onAccent, scheme.onPrimary.toArgb())
            assertEquals(primary.accentContainer, scheme.primaryContainer.toArgb())
            assertEquals(primary.onAccentContainer, scheme.onPrimaryContainer.toArgb())
            assertEquals(secondary.accent, scheme.secondary.toArgb())
            assertEquals(secondary.onAccent, scheme.onSecondary.toArgb())
            assertEquals(secondary.accentContainer, scheme.secondaryContainer.toArgb())
            assertEquals(secondary.onAccentContainer, scheme.onSecondaryContainer.toArgb())
            }
            assertEquals(tertiary.accent, scheme.tertiary.toArgb())
            assertEquals(tertiary.onAccent, scheme.onTertiary.toArgb())
            assertEquals(tertiary.accentContainer, scheme.tertiaryContainer.toArgb())
            assertEquals(tertiary.onAccentContainer, scheme.onTertiaryContainer.toArgb())
            assertTrue("奶油色背景应保留暖色通道顺序", scheme.surface.red > scheme.surface.green && scheme.surface.green > scheme.surface.blue)
        }
    }

    /** 深色山茶红降低饱和度，不能重新出现刺眼的浓红选中容器。 */
    @Test fun darkCamelliaRedIsMuted() {
        val before = MaterialColors.getColorRoles(0xFFE24E37.toInt(), false).accentContainer
        val after = ThemePalettes.scheme("amber", true).secondaryContainer.toArgb()
        val originalHsl = FloatArray(3).also { ColorUtils.colorToHSL(before, it) }
        val softenedHsl = FloatArray(3).also { ColorUtils.colorToHSL(after, it) }
        assertTrue("深色容器红色应明显降饱和", softenedHsl[1] < originalHsl[1] * 0.6f)
        assertTrue("深色容器应保留暖红色相", softenedHsl[0] < 40f || softenedHsl[0] > 345f)
    }

    /** 所有预设及高低亮度、饱和度的自定义种子，都必须满足一般文字的对比度要求。 */
    @Test fun textRolesMeetMinimumContrastInLightAndDarkThemes() {
        listOf(false, true).forEach { dark ->
            ThemePalettes.entries.forEach { palette ->
                assertTextContrast(ThemePalettes.scheme(palette.id, dark), "${palette.id}，深色=$dark")
            }
            listOf(
                ThemePalettes.DEFAULT_CUSTOM_SEED, 0xFF000000.toInt(), 0xFFFFFFFF.toInt(),
                0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(),
                0xFF808080.toInt(), 0xFFFAF0E2.toInt(),
            ).forEach { seed ->
                assertTextContrast(ThemePalettes.scheme("custom", dark, seed), "自定义种子=$seed，深色=$dark")
            }
        }
    }

    /** 对应角色和各层级表面均按实际不透明颜色计算，边框色不作为文字角色验收。 */
    private fun assertTextContrast(scheme: ColorScheme, label: String) {
        val pairs = listOf(
            Triple("主色文字", scheme.onPrimary, scheme.primary),
            Triple("主色容器文字", scheme.onPrimaryContainer, scheme.primaryContainer),
            Triple("次色文字", scheme.onSecondary, scheme.secondary),
            Triple("次色容器文字", scheme.onSecondaryContainer, scheme.secondaryContainer),
            Triple("第三色文字", scheme.onTertiary, scheme.tertiary),
            Triple("第三色容器文字", scheme.onTertiaryContainer, scheme.tertiaryContainer),
            Triple("背景文字", scheme.onBackground, scheme.background),
            Triple("表面文字", scheme.onSurface, scheme.surface),
            Triple("变体表面文字", scheme.onSurfaceVariant, scheme.surfaceVariant),
            Triple("错误文字", scheme.onError, scheme.error),
            Triple("错误容器文字", scheme.onErrorContainer, scheme.errorContainer),
            Triple("反色表面文字", scheme.inverseOnSurface, scheme.inverseSurface),
            Triple("反色主色文字", scheme.inversePrimary, scheme.inverseSurface),
            Triple("主固定色文字", scheme.onPrimaryFixed, scheme.primaryFixed),
            Triple("主固定暗色文字", scheme.onPrimaryFixed, scheme.primaryFixedDim),
            Triple("主固定色辅助文字", scheme.onPrimaryFixedVariant, scheme.primaryFixed),
            Triple("主固定暗色辅助文字", scheme.onPrimaryFixedVariant, scheme.primaryFixedDim),
            Triple("次固定色文字", scheme.onSecondaryFixed, scheme.secondaryFixed),
            Triple("次固定暗色文字", scheme.onSecondaryFixed, scheme.secondaryFixedDim),
            Triple("次固定色辅助文字", scheme.onSecondaryFixedVariant, scheme.secondaryFixed),
            Triple("次固定暗色辅助文字", scheme.onSecondaryFixedVariant, scheme.secondaryFixedDim),
            Triple("第三固定色文字", scheme.onTertiaryFixed, scheme.tertiaryFixed),
            Triple("第三固定暗色文字", scheme.onTertiaryFixed, scheme.tertiaryFixedDim),
            Triple("第三固定色辅助文字", scheme.onTertiaryFixedVariant, scheme.tertiaryFixed),
            Triple("第三固定暗色辅助文字", scheme.onTertiaryFixedVariant, scheme.tertiaryFixedDim),
        )
        pairs.forEach { (role, foreground, background) -> assertContrast(foreground, background, "$label，$role") }
        listOf(
            "最低容器" to scheme.surfaceContainerLowest, "低容器" to scheme.surfaceContainerLow,
            "容器" to scheme.surfaceContainer, "高容器" to scheme.surfaceContainerHigh,
            "最高容器" to scheme.surfaceContainerHighest, "暗表面" to scheme.surfaceDim,
            "亮表面" to scheme.surfaceBright,
        ).forEach { (surface, background) ->
            assertContrast(scheme.onSurface, background, "$label，$surface 的主要文字")
            assertContrast(scheme.onSurfaceVariant, background, "$label，$surface 的辅助文字")
        }
    }

    /** 失败信息保留完整前景、背景及实测比值，方便集成时定位不合格角色。 */
    private fun assertContrast(foreground: Color, background: Color, label: String) {
        assertEquals("$label 的文字必须不透明", 1f, foreground.alpha, 0f)
        assertEquals("$label 的背景必须不透明", 1f, background.alpha, 0f)
        val contrast = ColorUtils.calculateContrast(foreground.toArgb(), background.toArgb())
        assertTrue("$label：前景=$foreground，背景=$background，对比度=$contrast，要求至少 4.5", contrast >= 4.5)
    }
}
