package io.github.korenkrita.simulsub

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 上游正式版 v1.0.9 的静音边界和纯标点回归，不使用真实模型或外部网络。 */
class Upstream109RegressionTest {
    /** 新开关加入后重试旧失败缓存；已成功的旧参数名称不变。 */
    @Test fun thinkingFailureCacheIsVersioned() {
        assertNull(ByokTranslator.current("-"))
        assertNull(ByokTranslator.current("-6"))
        assertEquals(ByokTranslator.NONE_WORKED, ByokTranslator.current(ByokTranslator.NONE_WORKED))
        assertEquals("enable", ByokTranslator.current("enable"))
        assertNull(ByokTranslator.current("unknown"))
    }

    /** 地址只接受合法 HTTP 来源，完整协议路径保持原样。 */
    @Test fun customJevConfigurationRoundTripsAndRejectsAmbiguousUrls() {
        for (protocol in JevProtocol.entries) {
            val c = JevCustom(protocol, "https://h.example/base", "m")
            assertEquals(c, JevCustom.decode(c.encode()))
            val complete = c.copy(url = "https://h.example" + protocol.path)
            assertEquals(complete.url, complete.endpoint().second)
            for (bad in listOf("", "wss://h.example", "https://u:p@h.example", "https://h.example?key=x", "https://h.example#x", "https://h.example:0"))
                assertNotNull(c.copy(url = bad).endpoint().first)
        }
        assertEquals("https://h.example/v1/systemone", JevCustom(JevProtocol.SYSTEMONE, "https://h.example/v1", "m").endpoint().second)
        assertNull(JevCustom.decode("broken"))
    }

    private var fed = 0

    /** 为每帧写入唯一编号，同时保留真实时间戳。 */
    private fun feed(s: Segmenter, speech: Boolean, ms: Int) {
        repeat(ms / 32) { fed++; s.accept(FloatArray(s.frameSize) { fed.toFloat() }, if (speech) .9f else .1f, fed * 32L) }
    }

    /** 按帧提取编号，直接核查切句后音频连续性。 */
    private fun ids(job: AsrJob) = job.samples.indices.step(512).map { job.samples[it].toInt() }

    /** 强制切句后保留的尾静音必须计入下一次停顿。 */
    @Test fun forcedCutRetainsTheTrailingSilenceCount() {
        val jobs = mutableListOf<AsrJob>(); val times = mutableListOf<Int>()
        val s = Segmenter({ if (it.isFinal) { jobs += it; times += fed } }, maxUtteranceMs = 7000, endSilenceMs = 400)
        feed(s, true, 4512); feed(s, false, 320); feed(s, true, 1952); feed(s, false, 1000)
        assertEquals(2, jobs.size)
        assertEquals((148..215).toList(), ids(jobs[1]))
        assertEquals(225, times[1])
    }

    /** 仅剩静音时结束本句，不能多发送一段纯静音识别。 */
    @Test fun forcedCutDoesNotRecognizeASilentTail() {
        val jobs = mutableListOf<AsrJob>()
        val s = Segmenter({ if (it.isFinal) jobs += it }, maxUtteranceMs = 7000, endSilenceMs = 400)
        feed(s, true, 6784); feed(s, false, 3000); s.flush()
        assertEquals(1, jobs.size)
    }

    /** 静音尾仍作为下一句的前置缓冲，不能丢失边界音频。 */
    @Test fun forcedCutPreservesSilenceAsPreroll() {
        val jobs = mutableListOf<AsrJob>()
        val s = Segmenter({ if (it.isFinal) jobs += it }, maxUtteranceMs = 7000, endSilenceMs = 400)
        feed(s, true, 6784); feed(s, false, 224); feed(s, true, 1024); feed(s, false, 1000)
        val all = jobs.flatMap(::ids)
        assertEquals(2, jobs.size)
        assertEquals((1..all.last()).toList(), all)
    }

    /** 纯标点不翻译、不进入上下文；后续正常句依然送达。 */
    @Test fun punctuationNeverCostsATranslationOrPollutesContext() {
        val prompts = CopyOnWriteArrayList<String>(); val done = CountDownLatch(1)
        val asr = object : Asr {
            /** 样本长度选择固定识别结果。 */
            override fun recognize(samples: FloatArray) = if (samples.size == 1) "。…！？" else "花が咲くでしょう"
        }
        val mt = object : Mt {
            /** 保存实际提示以核对上下文。 */
            override fun translate(prompt: String): MtResult { prompts += prompt; return MtResult("花朵绽放出来吧", 'e', 1, 1) }
            /** 无外部资源需要取消。 */
            override fun cancel() = Unit
        }
        val p = Pipeline(asr, mt, { if (it.segmentId == 2L && it.isFinal) done.countDown() })
        p.start()
        try {
            p.submit(AsrJob(1, 1, true, FloatArray(1), monoMs()))
            p.submit(AsrJob(2, 1, true, FloatArray(2), monoMs()))
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertEquals(1, prompts.size)
            assertFalse(prompts.single().contains("。…！？"))
        } finally { p.stop(); p.awaitStopped() }
    }
}
