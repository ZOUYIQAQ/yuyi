package io.github.korenkrita.simulsub

import org.junit.Assert.*
import org.junit.Test

/** 按上游v1.0.7的32毫秒音频帧检查长句提前定稿及Jev边界，无网络或模型依赖。 */
class LongUtteranceCutTest {
    private var fed = 0
    private val speechIds = mutableListOf<Int>()

    /** 满三秒后在192毫秒停顿处立即定稿，不回等七秒；保留真实音频终点。 */
    @Test fun longSpeechFinalizesAtTheShortPauseAndKeepsEverySpeechFrameOnce() {
        val jobs = mutableListOf<AsrJob>()
        val finalFrames = mutableListOf<Int>()
        val s = Segmenter({ jobs += it; if (it.isFinal) finalFrames += fed },
            earlyCutAfterMs = 3_000, earlyCutSilenceMs = 192)
        feed(s, 3328, true); feed(s, 192, false)
        assertEquals(listOf(110), finalFrames)
        assertEquals(107 * 32L, jobs.single { it.isFinal }.audioEndAt)
        feed(s, 1600, true); feed(s, 512, false); s.flush()
        val ids = jobs.filter { it.isFinal }.flatMap { job ->
            job.samples.indices.step(s.frameSize).map { job.samples[it].toInt() }.filter { it > 0 }
        }
        assertEquals("所有语音帧必须原序出现一次", speechIds, ids)
        assertEquals(listOf(1L, 2L), jobs.filter { it.isFinal }.map { it.segmentId })
    }

    /** 说话未满三秒时，短暂换气不能被当作句末。 */
    @Test fun aShortPauseBeforeThreeSecondsDoesNotSplitTheSentence() {
        val jobs = mutableListOf<AsrJob>()
        val s = Segmenter(jobs::add, earlyCutAfterMs = 3_000, earlyCutSilenceMs = 192)
        feed(s, 2016, true); feed(s, 256, false); feed(s, 1504, true); feed(s, 512, false)
        assertEquals(1, jobs.count { it.isFinal })
    }

    /** 即使是长句，也不能在不足192毫秒的停顿时抢先切开。 */
    @Test fun aShorterPauseStillWaitsForTheSpeaker() {
        val jobs = mutableListOf<AsrJob>()
        val s = Segmenter(jobs::add, earlyCutAfterMs = 3_000, earlyCutSilenceMs = 192)
        feed(s, 3328, true); feed(s, 160, false)
        assertTrue(jobs.none { it.isFinal })
    }

    /** 开启Jev后仍交给它判断，不能被普通提前定稿抢走。 */
    @Test fun anActiveJudgeKeepsItsOwnPauseDecision() {
        val jobs = mutableListOf<AsrJob>()
        val s = Segmenter(jobs::add, hintWaitMs = 1_000, askSilenceMs = 224,
            earlyCutAfterMs = 3_000, earlyCutSilenceMs = 192)
        feed(s, 3328, true); feed(s, 256, false)
        assertTrue(jobs.none { it.isFinal })
        assertTrue(jobs.any { it.endOfSpeech })
    }

    /** Jev因缺密钥或暂停而退出判断时，本句回到普通提前定稿策略。 */
    @Test fun aPausedJudgeUsesTheUnjudgedEarlyCut() {
        val jobs = mutableListOf<AsrJob>()
        val s = Segmenter(jobs::add, hintWaitMs = 1_000, judged = { false },
            earlyCutAfterMs = 3_000, earlyCutSilenceMs = 192)
        feed(s, 3328, true); feed(s, 192, false)
        assertEquals(1, jobs.count { it.isFinal })
        assertTrue(jobs.none { it.endOfSpeech })
    }

    /** 未显式启用时保留原规则，已有调用方不会被默认参数改变行为。 */
    @Test fun disabledEarlyCutKeepsTheExistingSilenceThreshold() {
        val jobs = mutableListOf<AsrJob>()
        val s = Segmenter(jobs::add)
        feed(s, 3328, true); feed(s, 192, false)
        assertTrue(jobs.none { it.isFinal })
        feed(s, 224, false)
        assertEquals(1, jobs.count { it.isFinal })
    }

    /** 连续一分钟帧流反复提前定稿，段号、顺序与完整语音样本始终保持一致。 */
    @Test fun aMinuteOfRepeatedLongUtterancesKeepsEverySpeechFrame() {
        val finals = mutableListOf<AsrJob>()
        val s = Segmenter({ if (it.isFinal) finals += it }, earlyCutAfterMs = 3_000, earlyCutSilenceMs = 192)
        repeat(17) { feed(s, 3328, true); feed(s, 192, false) }
        feed(s, 160, false); s.flush()
        assertEquals(60_000, fed * 32)
        assertEquals((1L..17L).toList(), finals.map { it.segmentId })
        val delivered = finals.flatMap { job -> job.samples.indices.step(s.frameSize)
            .map { job.samples[it].toInt() }.filter { it > 0 } }
        assertEquals("一分钟内的每个语音帧都不能丢失、重复或乱序", speechIds, delivered)
    }

    /** 每帧带唯一正数语音标识或负数静音标识，能检查切分后是否漏掉或重复语音。 */
    private fun feed(segmenter: Segmenter, millis: Int, speech: Boolean) {
        repeat(millis / 32) {
            fed++
            if (speech) speechIds += fed
            segmenter.accept(FloatArray(segmenter.frameSize) { if (speech) fed.toFloat() else -fed.toFloat() },
                if (speech) 0.9f else 0.1f, fed * 32L)
        }
    }
}
