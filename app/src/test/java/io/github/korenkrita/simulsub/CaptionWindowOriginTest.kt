package io.github.korenkrita.simulsub

import org.junit.Assert.*
import org.junit.Test

/** 系统栏偏移、异步窗口帧与无法满足的系统约束分别处理。 */
class CaptionWindowOriginTest {
    /** Android 10首帧采用真实系统栏偏移，不能因假设零原点而一直不绘制。 */
    @Test fun legacyOriginUsesActualFirstFrame() {
        val origin = CaptionWindowOrigin()
        assertTrue(origin.aligned(124, 100, 40, 140))
        assertFalse(origin.aligned(124, 80, 60, 140))
        assertTrue(origin.aligned(104, 80, 60, 140))
    }
    /** 窗口变高时旧帧必须被挡住，应用新位置后才绘制。 */
    @Test fun staleFrameWaitsForNewPosition() {
        val origin = CaptionWindowOrigin(24)
        repeat(3) { assertFalse(origin.aligned(124, 80, 60, 140)) }
        assertTrue(origin.aligned(104, 80, 60, 140))
    }
    /** 系统持续施加同一个偏移时有界恢复，后续使用同一屏幕原点。 */
    @Test fun persistentSystemOffsetDoesNotHideCaptionsForever() {
        val origin = CaptionWindowOrigin(0)
        repeat(7) { assertFalse(origin.aligned(124, 100, 40, 140)) }
        assertTrue(origin.aligned(124, 100, 40, 140))
        assertTrue(origin.aligned(104, 80, 60, 140))
    }
}
