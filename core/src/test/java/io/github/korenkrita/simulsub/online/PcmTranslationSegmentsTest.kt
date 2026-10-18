package io.github.korenkrita.simulsub.online

import org.junit.Assert.*
import org.junit.Test

/** 验证省钱听译的上传边界与内存上限，不能为节约请求而漏掉或重复上传音频。 */
class PcmTranslationSegmentsTest {
    /** 静音不会发出请求；长时间连续讲话最多八秒一段且所有样本按原顺序保留。 */
    @Test fun silenceAndContinuousSpeechStayBounded() {
        val segmenter = PcmTranslationSegments()
        repeat(1000) { assertTrue(segmenter.append(ByteArray(3200)).isEmpty()) }
        val expected = ByteArray(6400) + sound(3200 * 170)
        val result = mutableListOf<ByteArray>()
        for (frame in expected.copyOfRange(6400, expected.size).asList().chunked(3200))
            result += segmenter.append(frame.toByteArray())
        segmenter.finish()?.let(result::add)
        assertTrue(result.size >= 3)
        assertTrue(result.all { it.size <= 256_000 })
        assertArrayEquals(expected, result.fold(ByteArray(0)) { acc, bytes -> acc + bytes })
    }

    /** 正常停顿提交一次，短感叹词也会在较长静音后显示，纯静音尾部不能反复提交。 */
    @Test fun pausesAndShortSpeechProduceOneSegment() {
        val segments = PcmTranslationSegments()
        val normal = sound(96_000)
        val output = mutableListOf<ByteArray>()
        normal.asList().chunked(3200).forEach { output += segments.append(it.toByteArray()) }
        repeat(5) { output += segments.append(ByteArray(3200)) }
        assertEquals(1, output.size)
        assertArrayEquals(normal + ByteArray(16000), output.single())
        repeat(40) { assertTrue(segments.append(ByteArray(3200)).isEmpty()) }
        assertNull(segments.finish())
        val short = PcmTranslationSegments()
        short.append(sound(3200))
        val shortResult = mutableListOf<ByteArray>()
        repeat(15) { shortResult += short.append(ByteArray(3200)) }
        assertEquals(1, shortResult.size)
    }

    /** 系统录音可能返回非百毫秒大小，任意偶数字节切片都必须拼回同一音频。 */
    @Test fun arbitrarySampleBoundariesNeverLoseAudio() {
        val segments = PcmTranslationSegments()
        val pcm = sound(96002)
        val result = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + 654, pcm.size)
            result += segments.append(pcm.copyOfRange(offset, end))
            offset = end
        }
        segments.finish()?.let(result::add)
        assertArrayEquals(pcm, result.fold(ByteArray(0)) { acc, bytes -> acc + bytes })
    }

    /** 生成稳定非静音 PCM16，不依赖本地文件、模型或声卡。 */
    private fun sound(size: Int): ByteArray = ByteArray(size) { if (it % 2 == 0) 0 else 4 }
}
