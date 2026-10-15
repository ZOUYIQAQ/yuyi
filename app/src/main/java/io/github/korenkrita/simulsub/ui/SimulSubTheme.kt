package io.github.korenkrita.simulsub.ui

import android.os.Build
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.core.view.WindowCompat
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** 依据控制器的主题与动态配色设置绘制界面，旧系统回退到选中的手动配色。 */
@Composable
fun SimulSubTheme(settings: SettingsUiState, content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        UiThemeMode.SYSTEM -> isSystemInDarkTheme()
        UiThemeMode.LIGHT -> false
        UiThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = if (settings.dynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else remember(settings.themeColorId, dark) { ThemePalettes.scheme(settings.themeColorId, dark) }
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        context.findActivity()?.let { activity ->
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = colors, shapes = Shapes(
        extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
    ), content = content)
}

/** 沿上下文包装寻找窗口，预览或无Activity环境保持安全。 */
private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeIf { it !== this }?.findActivity()
    else -> null
}
