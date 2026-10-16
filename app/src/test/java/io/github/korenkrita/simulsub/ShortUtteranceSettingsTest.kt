package io.github.korenkrita.simulsub

import org.junit.Assert.*
import org.junit.Test

/** 使用安卓实际分句阈值验证短应答提早输出，同时保留极短碎片的等待。 */
class ShortUtteranceSettingsTest {
    /** 640毫秒短语在正常停顿后即可定稿，旧阈值仍会等待续句。 */
    @Test fun shortAnswerNoLongerWaitsForLongSilence() {
        val pattern = listOf(false to 320, true to 640, false to 608)
        assertEquals(1, jobs(CaptionService.MIN_FINAL_MS, pattern).count { it.isFinal })
        assertEquals(0, jobs(1200, pattern).count { it.isFinal })
    }

    /** 不足400毫秒的碎片仍等待续句，长停顿后仍必须输出且不丢语音。 */
    @Test fun veryBriefFragmentStillWaitsUntilLongSilence() {
        val prefix = listOf(false to 320, true to 256)
        assertEquals(0, jobs(CaptionService.MIN_FINAL_MS, prefix + (false to 608)).count { it.isFinal })
        val final = jobs(CaptionService.MIN_FINAL_MS, prefix + (false to 1600)).single { it.isFinal }
        assertTrue(final.samples.size >= 256 * 16)
    }

    /** 每帧32毫秒、16kHz采样，直接驱动生产分句器而非模拟回调结果。 */
    private fun jobs(threshold: Int, pattern: List<Pair<Boolean, Int>>): List<AsrJob> {
        val output = ArrayList<AsrJob>()
        val segmenter = Segmenter(output::add, minFinalMs = threshold, maxHoldSilenceMs = 1500)
        var frame = 0
        for ((speech, duration) in pattern) repeat(duration / 32) {
            frame++
            segmenter.accept(FloatArray(512) { frame.toFloat() }, if (speech) 0.9f else 0.1f, frame * 32L)
        }
        return output
    }
}
