package io.github.korenkrita.simulsub

import android.app.Application
import android.content.Intent
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.icons.*
import io.github.korenkrita.simulsub.ui.UiThemeMode
import org.junit.Assert.*
import org.junit.Test

/** 通过真实控制器回调核对主题、桌面入口和重启后的偏好，不启动录音或下载。 */
class LauncherIconThemeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** 自动纯色随主题变化、手动纯色固定，重新创建管理器后保持状态且入口唯一。 */
    @Test fun themeCallbacksPersistAutomaticAndManualIconColors() {
        val icons = LauncherIcons(context)
        val previous = icons.current
        val appearance = context.getSharedPreferences("appearance", 0)
        val savedAppearance = appearance.all
        val prefs = Prefs(context)
        val savedTheme = prefs.theme
        val store = ViewModelStore()
        try {
            instrumentation.runOnMainSync {
                val controller = AppController(context.applicationContext as Application)
                store.put("图标主题验证", controller)
                val actions = controller.actions({}, {}, {}, {})
                actions.onThemeChange(UiThemeMode.LIGHT)
                actions.onThemeColorChange("ocean")
                actions.onLauncherIconChange(LauncherIconChoice(style = IconStyle.MONO))
                assertEquals(IconTone.OCEAN, icons.current.resolvedTone)
                val oceanKey = icons.current.key
                actions.onThemeColorChange("forest")
                assertEquals(IconTone.FOREST, icons.current.resolvedTone)
                assertNotEquals(oceanKey, icons.current.key)
                assertEquals(icons.current, controller.state.value.settings.launcherIcon)
                assertEquals(icons.current, LauncherIcons(context).current)
                actions.onCustomThemeColorChange(0xFF6A5F94.toInt())
                assertEquals(IconTone.VIOLET, icons.current.resolvedTone)
                actions.onThemeChange(UiThemeMode.DARK)
                assertEquals(icons.themeTone(), icons.current.resolvedTone)
                actions.onDynamicColorsChange(true)
                assertEquals(icons.themeTone(), icons.current.resolvedTone)
                val automatic = icons.current
                assertTrue(LauncherIcons(context).current.followTheme)
                actions.onLauncherIconChange(automatic.copy(followTheme = false, resolvedTone = IconTone.CAMELLIA))
                val manual = icons.current
                actions.onThemeColorChange("slate")
                actions.onThemeChange(UiThemeMode.LIGHT)
                assertEquals(manual, icons.current)
                assertEquals(manual, LauncherIcons(context).current)
                actions.onLauncherIconChange(manual.copy(style = IconStyle.VECTOR))
                val colorful = icons.current
                actions.onThemeColorChange("rose")
                assertEquals(colorful, icons.current)
                controller.restoreLauncherIcon()
                val restored = AppController(context.applicationContext as Application)
                store.put("图标重启验证", restored)
                assertEquals(colorful, restored.state.value.settings.launcherIcon)
                val launchers = context.packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0)
                assertEquals(listOf(icons.component(colorful).className), launchers.map { it.activityInfo.name })
            }
        } finally {
            instrumentation.runOnMainSync {
                val editor = appearance.edit()
                listOf("color", "dynamic", "custom_color").forEach { key ->
                    when (val value = savedAppearance[key]) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        else -> editor.remove(key)
                    }
                }
                editor.commit()
                prefs.theme = savedTheme
                icons.apply(previous)
                store.clear()
            }
        }
    }
}
