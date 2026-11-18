package io.github.korenkrita.simulsub

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/** 复现无声音和半帧读取中的积压、失败与用户停止，确保取消不依赖下一帧声音。 */
class CaptureFrameReaderTest {
    /** 积压恰好发生在零数据返回时，无需等待新声音或再次读取。 */
    @Test fun overloadEndsAReadWithNoAudio() {
        val overloaded = AtomicBoolean(false)
        var reads = 0
        assertFalse(readCapturedFrame(FloatArray(512), overloaded::get, { _, _, _ ->
            reads++; overloaded.set(true); 0
        }, { fail("积压后不能继续等待音频") }))
        assertEquals(1, reads)
    }

    /** 工作线程在半帧读取后失败，已有部分音频不能被提交为完整帧。 */
    @Test fun workerFailureDiscardsTheIncompleteFrame() {
        var failed = false
        var reads = 0
        assertFalse(readCapturedFrame(FloatArray(512), { failed }, { _, offset, count ->
            reads++
            if (reads == 1) { assertEquals(0, offset); assertEquals(512, count); 100 }
            else { assertEquals(100, offset); assertEquals(412, count); failed = true; 0 }
        }, { fail("失败后不能继续空转") }))
        assertEquals(2, reads)
    }

    /** 用户在零数据等待中结束时，下一轮检查立即退出，不再调用录音器。 */
    @Test fun userStopDuringIdleNeedsNoFurtherRead() {
        var stopped = false
        var reads = 0
        assertFalse(readCapturedFrame(FloatArray(512), { stopped }, { _, _, _ -> reads++; 0 }, { stopped = true }))
        assertEquals(1, reads)
    }

    /** 启动前已取消时完全不接触音频源。 */
    @Test fun anAlreadyStoppedSessionNeverReads() {
        assertFalse(readCapturedFrame(FloatArray(512), { true }, { _, _, _ -> fail("不能启动读取"); 0 }, {}))
    }

    /** 正常分批返回时按正确偏移补满，不能丢样本或覆盖上一批数据。 */
    @Test fun partialReadsPreserveSampleOrder() {
        val frame = FloatArray(512)
        val offsets = mutableListOf<Int>()
        assertTrue(readCapturedFrame(frame, { false }, { buffer, offset, count ->
            offsets += offset
            val n = minOf(100, count)
            repeat(n) { buffer[offset + it] = (offset + it).toFloat() }
            n
        }, { fail("有音频数据时无需等待") }))
        assertEquals(listOf(0, 100, 200, 300, 400, 500), offsets)
        assertArrayEquals(FloatArray(512) { it.toFloat() }, frame, 0f)
    }

    /** 非主动取消的录音错误保留错误码，交由服务显示原因并释放资源。 */
    @Test fun actualReadFailureKeepsTheErrorCode() {
        val error = runCatching { readCapturedFrame(FloatArray(512), { false }, { _, _, _ -> -6 }, {}) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("读取音频失败 (-6)", error!!.message)
    }
}
