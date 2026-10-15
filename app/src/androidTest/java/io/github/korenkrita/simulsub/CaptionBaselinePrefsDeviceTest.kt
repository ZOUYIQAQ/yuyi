package io.github.korenkrita.simulsub

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** 使用独立偏好验证基线与显示区域的持久化契约，不读取设备已有配置。 */
class CaptionBaselinePrefsDeviceTest {
    /** 每个用例使用独立命名空间，重建 Prefs 时仍读取同一份测试配置。 */
    private fun isolatedContext(): Context {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "字幕基线回归-${UUID.randomUUID()}"
        return object : ContextWrapper(context) {
            /** 仅替换偏好文件名，避免覆盖应用的真实用户配置。 */
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                context.getSharedPreferences("$namespace-$name", mode)
        }
    }

    /** 相同可用区域重启后可恢复，并保持基线与所属宽高的对应关系。 */
    @Test fun sameAreaRestoresBaselineAfterPrefsRecreation() {
        val context = isolatedContext()
        Prefs(context).saveOverlayBaseline(950, 1_080, 1_920)
        val restored = Prefs(context)
        assertEquals(950, restored.overlayBaselineY)
        assertEquals(1_080, restored.overlayBaselineAreaWidth)
        assertEquals(1_920, restored.overlayBaselineAreaHeight)
        assertEquals(950, restored.overlayBaselineForArea(1_080, 1_920))
    }

    /** 任意一维改变或区域尚未测量时忽略旧基线，但不清空已保存数据。 */
    @Test fun changedOrInvalidAreaDoesNotRestoreBaseline() {
        val prefs = Prefs(isolatedContext())
        prefs.saveOverlayBaseline(950, 1_080, 1_920)
        for ((width, height) in listOf(1_920 to 1_080, 1_079 to 1_920, 1_080 to 1_919,
            0 to 1_920, 1_080 to 0, -1 to 1_920, 1_080 to -1)) {
            assertNull(prefs.overlayBaselineForArea(width, height))
        }
        assertEquals(950, prefs.overlayBaselineForArea(1_080, 1_920))
    }

    /** 没有尺寸的旧版配置不能恢复；单独覆盖基线也不能冒用原有尺寸。 */
    @Test fun legacyBaselineWithoutAreaIsIgnored() {
        val context = isolatedContext()
        context.getSharedPreferences("jpsub", Context.MODE_PRIVATE)
            .edit().putInt("overlayBaselineY", 950).apply()
        val prefs = Prefs(context)
        assertNull(prefs.overlayBaselineForArea(1_080, 1_920))
        prefs.saveOverlayBaseline(950, 1_080, 1_920)
        prefs.overlayBaselineY = 800
        assertEquals(-1, prefs.overlayBaselineAreaWidth)
        assertEquals(-1, prefs.overlayBaselineAreaHeight)
        assertNull(Prefs(context).overlayBaselineForArea(1_080, 1_920))
    }

    /** 旋转重置并完成新定位后，保存的新区域应取代旧区域，而实际正文仍保持新锚点。 */
    @Test fun resetAfterRotationPersistsNewAreaAndBaseline() {
        val context = isolatedContext()
        val prefs = Prefs(context)
        prefs.saveOverlayBaseline(950, 1_080, 1_920)
        val anchor = CaptionBaselineAnchor(prefs.overlayBaselineForArea(1_080, 1_920))
        anchor.reset()
        anchor.position(1_000, 60, 1_080, 160)
        prefs.saveOverlayBaseline(requireNotNull(anchor.baseline), 1_920, 1_080)

        val restored = Prefs(context)
        assertNull(restored.overlayBaselineForArea(1_080, 1_920))
        assertEquals(980, restored.overlayBaselineForArea(1_920, 1_080))
        val restarted = CaptionBaselineAnchor(restored.overlayBaselineForArea(1_920, 1_080))
        assertEquals(960, restarted.position(1_000, 20, 1_080, 200))
        assertEquals(860, restarted.position(1_000, 120, 1_080, 260))
        assertEquals(980, restarted.baseline)
    }

    /** 非法参数在编辑前拒绝，不能留下基线和区域来自两次保存的组合。 */
    @Test fun invalidSavePreservesPreviousPlacement() {
        val prefs = Prefs(isolatedContext())
        prefs.saveOverlayBaseline(950, 1_080, 1_920)
        for ((baseline, width, height) in listOf(Triple(-1, 1_080, 1_920), Triple(800, 0, 1_920),
            Triple(800, 1_080, 0), Triple(800, -1, 1_920), Triple(800, 1_080, -1))) {
            try {
                prefs.saveOverlayBaseline(baseline, width, height)
                fail("无效基线或可用区域必须拒绝保存")
            } catch (_: IllegalArgumentException) {
                assertEquals(950, prefs.overlayBaselineForArea(1_080, 1_920))
            }
        }
    }
}
