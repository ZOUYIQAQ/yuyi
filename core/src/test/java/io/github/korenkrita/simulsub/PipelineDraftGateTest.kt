package io.github.korenkrita.simulsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 用识别线程屏障验证草稿门槛，避免靠固定睡眠判断草稿是否已处理。 */
class PipelineDraftGateTest {
    /** 仅在内存中识别和翻译；可阻塞首个翻译以检查队列保留。 */
    private class Session(blockFirst: Boolean = false) : AutoCloseable {
        private val texts = CopyOnWriteArrayList<String>()
        private val recognized = LinkedBlockingQueue<Int>()
        val captions = LinkedBlockingQueue<Caption>()
        val calls = CopyOnWriteArrayList<String>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(if (blockFirst) 1 else 0)
        @Volatile var live = true
        private var revision = 0
        private val asr = object : Asr {
            /** 零长度音频是屏障，其他长度对应测试原文。 */
            override fun recognize(samples: FloatArray) = if (samples.isEmpty()) "" else texts[samples.size - 1]
        }
        private val mt = object : Mt {
            /** 首次调用可等待放行，所有结果都不访问网络。 */
            override fun translate(prompt: String): MtResult {
                val text = prompt.substringAfter("【当前句】\n").substringBefore("\n\n")
                calls.add(text)
                entered.countDown()
                if (calls.size == 1) release.await()
                return MtResult("译文：$text", 'e', 1, 1)
            }
            /** 停止时解除等待，让线程可以正常退出。 */
            override fun cancel() = release.countDown()
        }
        val pipeline = Pipeline(asr, mt, { captions.offer(it) }, judgeEnds = false, drafts = { live },
            log = { if (it["ev"] == "asr") recognized.offer(it["rev"] as Int) })

        init { pipeline.start() }

        /** 可提交普通草稿；空句尾候选屏障确保门槛判断已完成，且不消耗或清除跳过标记。 */
        fun process(text: String, final: Boolean = false, segment: Long = 1, candidate: Boolean = true) {
            texts.add(text)
            val rev = ++revision
            assertTrue(pipeline.submit(AsrJob(segment, rev, final, FloatArray(texts.size), monoMs(), endOfSpeech = candidate)))
            assertEquals("识别必须按序完成", rev, recognized.poll(2, TimeUnit.SECONDS))
            val barrier = ++revision
            assertTrue(pipeline.submit(AsrJob(segment, barrier, false, FloatArray(0), monoMs(), endOfSpeech = true)))
            assertEquals("屏障必须在上一任务处理后执行", barrier, recognized.poll(2, TimeUnit.SECONDS))
        }

        /** 等待实际字幕，超时时给出明确断言失败。 */
        fun caption(): Caption {
            val result = captions.poll(2, TimeUnit.SECONDS)
            assertTrue("预期字幕必须实际送达", result != null)
            return result!!
        }

        /** 识别屏障已完成后，确认小改动没有产生额外字幕。 */
        fun assertSkipped() = assertNull("未达门槛的草稿不应送达字幕", captions.poll(80, TimeUnit.MILLISECONDS))

        /** 不论测试成功与否，均解除等待并回收工作线程。 */
        override fun close() { pipeline.stop(); pipeline.awaitStopped() }
    }

    /** 被跳过的每次两字增长必须累计到最后真正接受的草稿，接受后重新累计。 */
    @Test
    fun smallIncrementsAccumulateFromTheLastAcceptedDraft() {
        Session().use { s ->
            s.process("起点"); assertEquals("起点", s.caption().ja)
            s.process("起点甲乙"); s.process("起点甲乙丙丁")
            s.assertSkipped()
            assertEquals(listOf("起点"), s.calls.toList())
            s.process("起点甲乙丙丁戊"); assertEquals("起点甲乙丙丁戊", s.caption().ja)
            s.process("起点甲乙丙丁戊己庚")
            s.assertSkipped()
            assertEquals(2, s.calls.size)
            s.process("起点甲乙丙丁戊己庚辛壬癸"); assertEquals("起点甲乙丙丁戊己庚辛壬癸", s.caption().ja)
        }
    }

    /** 重复出现的同一个字也算增长，不能按字符集合去重。 */
    @Test
    fun repeatedCharactersStillCountAsGrowth() {
        Session().use { s ->
            s.process("哈"); s.caption()
            s.process("哈哈哈"); s.assertSkipped(); assertEquals(1, s.calls.size)
            s.process("哈哈哈哈哈哈"); assertEquals("哈哈哈哈哈哈", s.caption().ja)
        }
    }

    /** 补充平面字符按码点计数，共享代理项也不能被当成同一字符。 */
    @Test
    fun unicodeCodePointsAreCountedInsteadOfSurrogates() {
        Session().use { s ->
            s.process("😀"); s.caption()
            s.process("😀😁😂😃😄"); s.assertSkipped(); assertEquals(1, s.calls.size)
            s.process("😀😁😂😃😄😅"); s.caption()
            s.process("😀😆😇😈😉😊"); assertEquals("😀😆😇😈😉😊", s.caption().ja)
        }
    }

    /** 重排、替换和明显缩短都应算作变化，不能只比较净长度或字符集合。 */
    @Test
    fun rewritesAndShorteningAreMeasuredByTheChangedSpan() {
        Session().use { s ->
            s.process("abcde"); s.caption()
            s.process("edcba"); assertEquals("edcba", s.caption().ja)
            s.process("zyxwvutsrq"); s.caption()
            s.process("zyxwv"); assertEquals("zyxwv", s.caption().ja)
        }
    }

    /** 跳过小增长、空草稿或关闭显示的候选时，不得清除已排队草稿或推进基线。 */
    @Test
    fun skippedDraftsPreserveQueuedWorkAndTheAcceptedBaseline() {
        Session(blockFirst = true).use { s ->
            s.process("起点")
            assertTrue("首稿翻译必须已经开始", s.entered.await(2, TimeUnit.SECONDS))
            s.process("起点甲乙丙丁戊")
            // 比较基准是正在翻译的「起点」，不是尚未被取走的排队草稿。
            s.process("起点甲乙")
            s.process("")
            s.live = false
            s.process("起点甲乙丙丁戊己庚辛壬癸")
            s.live = true
            s.release.countDown()
            assertEquals("起点", s.caption().ja)
            assertEquals("起点甲乙丙丁戊", s.caption().ja)
            s.process("起点甲乙丙丁戊己庚辛壬癸")
            assertEquals("起点甲乙丙丁戊己庚辛壬癸", s.caption().ja)
        }
    }

    /** 定稿始终送达：相同文本复用翻译，小改动重新翻译，空定稿仍结束分段。 */
    @Test
    fun finalsBypassTheGateIncludingIdenticalAndEmptyText() {
        for (finalText in listOf("起点", "起点甲", "")) {
            Session().use { s ->
                s.process("起点"); s.caption()
                s.process(finalText, final = true)
                val final = s.caption()
                assertTrue("必须送达定稿", final.isFinal)
                assertEquals(finalText, final.ja)
                assertEquals(if (finalText == "起点甲") 2 else 1, s.calls.size)
                assertEquals(0, s.pipeline.drainFinals(1_000))
            }
        }
    }

    /** 没有定稿的分段也要限制缓存；编号跳跃或倒序不影响条数上限。 */
    @Test
    fun abandonedSegmentsHaveBoundedDraftHistory() {
        Session().use { s ->
            for (seg in 100L downTo 1L) {
                s.process("首稿", segment = seg)
                assertEquals(seg, s.caption().segmentId)
            }
            val field = Pipeline::class.java.getDeclaredField("sentDraft").apply { isAccessible = true }
            val history = field.get(s.pipeline) as Map<*, *>
            assertTrue("草稿缓存最多保留六十四个分段", history.size <= 64)
            s.process("首稿", final = true)
            assertTrue(s.caption().isFinal)
            assertTrue("定稿识别完成后释放基线", !history.containsKey(1L))
        }
    }

    /** 无定稿且编号跳跃倒序时，普通草稿跳过标记最多保留六十四项，并淘汰最早插入的分段。 */
    @Test
    fun abandonedOrdinaryDraftsHaveBoundedSkipMarkers() {
        Session().use { s ->
            val segments = (100L downTo 1L).map { it * 17 }
            for (seg in segments) {
                s.process("首稿", segment = seg, candidate = false)
                assertEquals("首稿必须已被翻译，才能建立该段基线", seg, s.caption().segmentId)
                // 同段第二份普通草稿没有增量，应留下标记；process 的空候选不能把它清掉。
                s.process("首稿", segment = seg, candidate = false)
            }
            val field = Pipeline::class.java.getDeclaredField("skipNextDraft").apply { isAccessible = true }
            val skipped = (field.get(s.pipeline) as Set<*>).toSet()
            assertEquals("必须实际生成并保留六十四个跳过标记", 64, skipped.size)
            assertEquals("应保留最后插入的分段，不能按编号大小淘汰", segments.takeLast(64).toSet(), skipped)
            assertEquals("重复普通草稿及空候选屏障都不应额外翻译", segments.size, s.calls.size)
        }
    }

    /** 最早分段的标记淘汰后恢复普通识别；再次留下跳过标记也不能阻止其小增量定稿送达。 */
    @Test
    fun evictedSkipMarkersAllowRecognitionAndNeverDropTheFinal() {
        Session().use { s ->
            val segments = (100L downTo 1L).map { it * 17 }
            for (seg in segments) {
                s.process("首稿", segment = seg, candidate = false)
                assertEquals(seg, s.caption().segmentId)
                s.process("首稿", segment = seg, candidate = false)
            }
            val field = Pipeline::class.java.getDeclaredField("skipNextDraft").apply { isAccessible = true }
            val oldest = segments.first()
            assertTrue("最早插入的标记必须已淘汰", !(field.get(s.pipeline) as Set<*>).contains(oldest))
            s.process("首稿甲乙丙丁戊", segment = oldest, candidate = false)
            val resumed = s.caption()
            assertEquals("淘汰后普通草稿应重新识别并翻译", "首稿甲乙丙丁戊", resumed.ja)
            assertEquals(oldest, resumed.segmentId)
            s.process("首稿甲乙丙丁戊", segment = oldest, candidate = false)
            assertTrue("重复普通草稿必须再次留下跳过标记", (field.get(s.pipeline) as Set<*>).contains(oldest))
            // 尚有跳过标记且文本只增加一字，定稿仍必须绕过识别跳过和翻译增量门槛。
            s.process("首稿甲乙丙丁戊己", final = true, segment = oldest, candidate = false)
            val final = s.caption()
            assertTrue("跳过标记不能丢掉定稿", final.isFinal)
            assertEquals(oldest, final.segmentId)
            assertEquals("首稿甲乙丙丁戊己", final.ja)
            assertTrue("定稿处理完应清理其跳过标记", !(field.get(s.pipeline) as Set<*>).contains(oldest))
            assertEquals("只增加恢复后的普通草稿和最终定稿两次翻译", segments.size + 2, s.calls.size)
            assertEquals("所有已接受定稿必须交付完成", 0, s.pipeline.drainFinals(1_000))
        }
    }
}
