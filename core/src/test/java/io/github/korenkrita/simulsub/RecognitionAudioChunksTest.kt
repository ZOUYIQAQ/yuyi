package io.github.korenkrita.simulsub

import org.junit.Assert.*
import org.junit.Test

/** 长音频安全边界必须覆盖全部样本，不能用截断换取不崩溃。 */
class RecognitionAudioChunksTest {
    /** 边界不超过八秒且拼回后每个样本都和原音频一致。 */
    @Test fun coversEverySampleWithoutOverlap() {
        val samples = FloatArray(24 * 16_000 + 100) { (it % 31) / 31f }
        val parts = recognitionAudioChunks(samples)
        assertTrue(parts.all { it.isNotEmpty() && it.size <= 8 * 16_000 })
        assertArrayEquals(samples, parts.flatMap { it.toList() }.toFloatArray(), 0f)
    }

    /** 优先选择末段的静音位置，短音频不复制或改变内容。 */
    @Test fun favorsQuietBoundaryAndKeepsShortInput() {
        val samples = FloatArray(9 * 16_000) { 1f }
        samples.fill(0f, 7 * 16_000, 7 * 16_000 + 320)
        assertEquals(7 * 16_000 + 160, recognitionAudioChunks(samples).first().size)
        val short = FloatArray(100)
        assertSame(short, recognitionAudioChunks(short).single())
    }
}
