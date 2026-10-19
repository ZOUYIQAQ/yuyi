package io.github.korenkrita.simulsub

import io.github.korenkrita.simulsub.online.LiveTranslationText
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 用虚拟时间验证真实分段、字幕板和阅读队列之间的契约，不等待真实时间或访问云端。 */
class OnlineCaptionPacerTest {
    /** 生产默认值保证短句也至少停留 1.2 秒，而不是只在缩短时间的测试配置中成立。 */
    @Test fun productionDefaultsKeepShortFinalsReadable() {
        val h = Harness(policy = OnlineCaptionPacer.Policy())
        h.text("一。二。")
        h.time.advance(1_199)
        assertEquals(listOf("一。"), h.output.map { it.zh })
        h.time.advance(1)
        assertEquals(listOf("一。", "二。"), h.output.map { it.zh })
    }

    /** 一次收到四句时第一句立即异步出现，后续定稿不能在同一帧挤掉前三句。 */
    @Test fun burstFinalsRespectReadingTimeAndThreeSentenceRetention() {
        val h = Harness()
        h.text("一。二。三。四。")
        assertTrue(h.output.isEmpty())
        h.time.advance(0)
        assertEquals(listOf("一。"), h.board.snapshot(false).map { it.zh })
        h.time.advance(999)
        assertEquals(1, h.output.size)
        h.time.advance(1)
        assertEquals(listOf("一。", "二。"), h.board.snapshot(false).map { it.zh })
        h.time.advance(2_000)
        assertEquals(listOf("二。", "三。", "四。"), h.board.snapshot(false).map { it.zh })
        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L), h.times)
    }

    /** 长句比短句获得更长阅读时间，但每屏等待有上限。 */
    @Test fun readingTimeUsesUnicodeCodePointsAndHasFloorAndCeiling() {
        val h = Harness()
        h.pacer.offer(listOf(h.caption(1, "😀".repeat(15)), h.caption(2, "后句")))
        h.time.advance(0)
        h.time.advance(1_499)
        assertEquals(1, h.output.size)
        h.time.advance(1)
        assertEquals(2, h.output.size)
        val long = Harness()
        long.pacer.offer(listOf(long.caption(1, "reading ".repeat(7) + "end"), long.caption(2, "后句")))
        long.time.advance(2_999)
        assertEquals(1, long.output.size)
        long.time.advance(1)
        assertEquals(2, long.output.size)
    }

    /** 休息很久后只投递一屏，从实际投递时重新计时，不能补发一串过期定时任务。 */
    @Test fun delayedSchedulerNeverCatchesUpByFlashingAllFinals() {
        val h = Harness()
        h.text("一。二。三。四。")
        h.time.advance(0)
        h.time.jump(20_000)
        h.time.advance(0)
        assertEquals(listOf("一。", "二。"), h.output.map { it.zh })
        h.time.advance(999)
        assertEquals(2, h.output.size)
    }

    /** 阅读等待期间持续缓存草稿，未来尾部不能超前显示；轮到当前位置时只显示最新版本。 */
    @Test fun draftsWaitBehindFinalsAndKeepOnlyLatestPrediction() {
        val h = Harness()
        h.text("一。二。预测", final = false, confirmed = "一。二。")
        h.time.advance(0)
        assertEquals(listOf("一。"), h.board.snapshot().map { it.zh })
        h.text("一。二。预测改写", final = false, confirmed = "一。二。")
        h.time.advance(0)
        assertEquals(listOf("一。"), h.board.snapshot().map { it.zh })
        assertEquals(1, h.output.count { it.isFinal })
        h.time.advance(1_000)
        assertEquals(listOf("一。", "二。", "预测改写"), h.board.snapshot().map { it.zh })
    }

    /** 不存在排队定稿时草稿仍立即异步更新，不会额外等待上一句的阅读倒计时。 */
    @Test fun draftsAtCurrentPositionDoNotWaitForReadingDeadline() {
        val h = Harness()
        h.text("一。预测", final = false, confirmed = "一。")
        h.time.advance(0)
        assertEquals(listOf("一。", "预测"), h.board.snapshot().map { it.zh })
        h.text("一。修改", final = false, confirmed = "一。")
        h.time.advance(0)
        assertEquals(listOf("一。", "修改"), h.board.snapshot().map { it.zh })
        assertEquals(1, h.output.count { it.isFinal })
    }

    /** 隐藏中的预测最终确认时必须直接排成定稿，不能先闪现草稿或重复定稿。 */
    @Test fun hiddenDraftCanBecomeFinalWithoutFlashingPrediction() {
        val h = Harness()
        h.text("一。二。预测", final = false, confirmed = "一。二。")
        h.time.advance(0)
        h.text("一。二。最终三。")
        h.time.advance(5_000)
        assertTrue(h.output.all { it.isFinal })
        assertEquals(listOf("一。", "二。", "最终三。"), h.output.map { it.zh })
    }

    /** 排队修订和撤回按原始子段生效，不能通过拼成一行留下被撤回的旧文字。 */
    @Test fun queuedCorrectionsKeepOriginalShortSegments() {
        val h = Harness()
        h.pacer.offer(listOf(h.caption(1, "旧一。"), h.caption(2, "旧二。")))
        h.pacer.offer(listOf(h.caption(1, "改一。", revision = 2), h.caption(2, "", revision = 2)))
        h.time.advance(0)
        assertEquals(listOf("改一。", ""), h.output.map { it.zh })
        assertEquals(listOf("改一。"), h.board.snapshot().map { it.zh })
        assertEquals(1L, h.board.snapshot().single().segmentId)
    }

    /** 即使后续还有定稿待显示，已显示句子的译文及原文校正也要及时原位发布。 */
    @Test fun displayedCorrectionsBypassReadingQueueWithoutReleasingFutureDraft() {
        val h = Harness()
        h.text("旧一。二。三。预测", final = false, confirmed = "旧一。二。三。")
        h.time.advance(0)
        h.text("改一。二。三。预测", final = false, confirmed = "改一。二。三。")
        h.time.advance(0)
        assertEquals(listOf("改一。"), h.board.snapshot().map { it.zh })
        assertTrue(h.board.snapshot().all { it.isFinal })
        assertEquals(2, h.output.size)
        assertEquals(h.output.first().segmentId, h.output.last().segmentId)
    }

    /** 定稿还在队列里时收到全文校正，只投递修正后的内容，不闪现旧句或重复计句。 */
    @Test fun queuedFinalsUseLatestCorrectionAndLateOriginal() {
        val h = Harness()
        h.text("一。旧二。")
        h.time.advance(0)
        h.text("一。新二。", source = "原一。原二。")
        h.time.advance(0)
        assertEquals("原一。", h.board.snapshot(false).single().ja)
        h.time.advance(1_000)
        assertEquals(listOf("一。", "新二。"), h.board.snapshot(false).map { it.zh })
        assertEquals("原二。", h.board.snapshot(false).last().ja)
        assertFalse(h.output.any { it.zh == "旧二。" })
    }

    /** 校正使分段减少时，尚未显示的多余句子必须撤回，不能在等待后重新出现。 */
    @Test fun queuedRetractionsDoNotLeaveObsoleteFinals() {
        val h = Harness()
        h.text("旧一。旧二。旧三。")
        h.time.advance(0)
        h.text("修正一句。")
        h.time.advance(5_000)
        assertEquals(listOf("修正一句。"), h.board.snapshot(false).map { it.zh })
        assertFalse(h.output.any { it.zh == "旧二。" || it.zh == "旧三。" })
    }

    /** 四十条正常长句超过默认容量时整批拒绝，不以拼成长段的方式伪装成少数几屏。 */
    @Test fun fortyNormalSentencesAreRejectedInsteadOfJoinedIntoParagraphs() {
        val h = Harness(policy = OnlineCaptionPacer.Policy())
        val captions = (1L..40L).map { h.caption(it, "中".repeat(17) + "。", "原".repeat(17) + "。") }
        assertFalse(h.pacer.offer(captions))
        assertEquals(0L, h.pacer.retainedCharacterCount())
        h.pacer.finish()
        h.time.advance(70_000)
        assertTrue(h.output.isEmpty())
        assertEquals(1, h.failures.size)
        assertTrue(h.failures.single().contains("本批字幕未接收、未显示"))
        assertTrue(h.pacer.awaitDrained(0))
    }

    /** 超大批次在复制字幕状态前拒绝，整条长文本和大量已分段文本都不进入任何文字缓存。 */
    @Test fun oversizedBatchIsRejectedWithoutRetainingPayload() {
        val single = Harness()
        assertFalse(single.pacer.offer(listOf(single.caption(1, "中".repeat(65_536)))))
        assertEquals(0L, single.pacer.retainedCharacterCount())
        single.time.advance(0)
        assertEquals(1, single.failures.size)
        assertTrue(single.failures.single().contains("本批字幕未接收、未显示"))
        assertTrue(single.output.isEmpty())

        val segmented = Harness()
        val batch = OnlineCaptionAdapter().acceptSegmented(LiveTranslationText("huge", "", "中".repeat(65_536), true))
        assertTrue(batch.size > 16)
        assertFalse(segmented.pacer.offer(batch))
        assertEquals(0L, segmented.pacer.retainedCharacterCount())
        segmented.time.advance(0)
        assertTrue(segmented.output.isEmpty())
        assertEquals(1, segmented.failures.size)
    }

    /** 满队保留十六条可读短句，拒绝下一批以后仍按原间隔投递，不能为了追赶自动闪屏。 */
    @Test fun fullQueueRejectsMoreTextWithoutAcceleratingOrJoiningSentences() {
        val h = Harness()
        val sentence = "reading ".repeat(7) + "end"
        val accepted = (1L..16L).map { h.caption(it, sentence) }
        assertTrue(h.pacer.offer(accepted))
        assertFalse(h.pacer.offer((17L..56L).map { h.caption(it, sentence) }))
        h.time.advance(0)
        assertEquals(1, h.output.size)
        h.time.advance(2_999)
        assertEquals(1, h.output.size)
        h.time.advance(1)
        assertEquals(2, h.output.size)
        h.time.jump(20_000)
        h.time.advance(0)
        assertEquals(3, h.output.size)
        h.pacer.finish()
        h.time.advance(70_000)
        assertEquals(accepted.map { it.segmentId }, h.output.map { it.segmentId })
        assertTrue(h.output.all { it.zh == sentence && it.zh.codePointCount(0, it.zh.length) <= 60 })
        assertTrue(h.times.zipWithNext().all { (before, after) -> after - before >= 3_000 })
        assertTrue(h.pacer.awaitDrained(0))
    }

    /** 字数预算约束当前队列加新批次，不能让每批各自不超限却累计越过总容量。 */
    @Test fun batchesWithinIndividualBudgetCannotExceedTotalCapacity() {
        val h = Harness(policy = policy(maxPendingCharacters = 8))
        assertTrue(h.pacer.offer(listOf(h.caption(1, "已接收。"))))
        val before = h.pacer.retainedCharacterCount()
        assertFalse(h.pacer.offer(listOf(h.caption(2, "后续内容。"))))
        assertEquals(before, h.pacer.retainedCharacterCount())
        h.time.advance(5_000)
        assertEquals(listOf("已接收。"), h.output.map { it.zh })
        assertEquals(1, h.failures.size)
    }

    /** 拒绝是整批原子的，批内较早出现的校正、后续新句和草稿均不能部分生效。 */
    @Test fun rejectedBatchDoesNotPartiallyApplyCorrectionOrDraft() {
        val h = Harness(policy = policy(maxPendingSegments = 2))
        assertTrue(h.pacer.offer(listOf(h.caption(1, "旧一。"), h.caption(2, "旧二。"))))
        h.time.advance(0)
        val retained = h.pacer.retainedCharacterCount()
        assertFalse(h.pacer.offer(listOf(h.caption(1, "不应出现。", revision = 2),
            h.caption(3, "第三句。"), h.caption(4, "未来草稿").copy(isFinal = false))))
        assertEquals(retained, h.pacer.retainedCharacterCount())
        h.time.advance(5_000)
        assertEquals(listOf("旧一。", "旧二。"), h.output.map { it.zh })
        assertTrue(h.output.all { it.isFinal })
    }

    /** 补充平面汉字按码点保留原分段，组合重音也沿用分段器的完整字形边界，不拼接邻句。 */
    @Test fun unicodeBoundariesRemainExactlyAsProducedBySegmenter() {
        val h = Harness()
        val value = "𠀀".repeat(17) + "。" + "中".repeat(17) + "e\u0301" + "后文。"
        val expected = h.adapter.acceptSegmented(LiveTranslationText("unicode", "", value, true))
        assertTrue(h.pacer.offer(expected))
        h.pacer.finish()
        h.time.advance(20_000)
        assertEquals(expected.map { it.zh }, h.output.map { it.zh })
        assertEquals(value, h.output.joinToString("") { it.zh })
        assertEquals(18, h.output.first().zh.codePointCount(0, h.output.first().zh.length))
        assertTrue(h.output[1].zh.endsWith("e\u0301"))
        assertEquals(expected.map { it.segmentId }, h.output.map { it.segmentId })
    }

    /** 迟到校正始终保持原始短句的位置与编号，不把相邻两句连成一段或复活已裁剪的旧句。 */
    @Test fun finalCorrectionsStayInPlaceWithoutJoiningAdjacentSentences() {
        val h = Harness()
        h.pacer.offer(listOf(h.caption(1, "一。", "原一。"), h.caption(2, "二。", "原二。")))
        h.time.advance(1_000)
        h.pacer.offer(listOf(h.caption(1, "改一。", "补原一。", revision = 2)))
        h.time.advance(0)
        assertEquals(listOf(1L, 2L), h.board.snapshot(false).map { it.segmentId })
        assertEquals(listOf("改一。", "二。"), h.board.snapshot(false).map { it.zh })
        assertEquals(listOf("补原一。", "原二。"), h.board.snapshot(false).map { it.ja })
        h.board.setRetention(CaptionLimitMode.SENTENCES, 1, 100)
        h.pacer.offer(listOf(h.caption(3, "新。")))
        h.time.advance(3_000)
        h.pacer.offer(listOf(h.caption(1, "迟改。", revision = 3)))
        h.time.advance(0)
        assertEquals(listOf("新。"), h.board.snapshot(false).map { it.zh })
    }

    /** 重放快照、相同定稿和过期草稿不消耗阅读间隔，也不重复输出。 */
    @Test fun replayedFinalsAndStaleDraftsDoNotDuplicateRows() {
        val h = Harness()
        h.text("一。二。")
        h.time.advance(0)
        h.text("一。二。")
        h.pacer.offer(listOf(h.output.first(), h.output.first().copy(isFinal = false)))
        h.time.advance(5_000)
        assertEquals(listOf("一。", "二。"), h.output.map { it.zh })
    }

    /** 超限整批拒绝且只报错一次，已接收字幕完整读完，不把拒绝批次的一部分留在队尾。 */
    @Test fun overloadRejectsWholeBatchAndSignalsOnce() {
        val h = Harness(policy = policy(maxPendingCharacters = 7))
        assertTrue(h.pacer.offer(listOf(h.caption(1, "已接收。"))))
        assertFalse(h.pacer.offer(listOf(h.caption(2, "第二句。"), h.caption(3, "第三句。"))))
        assertTrue(h.failures.isEmpty())
        h.time.advance(0)
        assertEquals(1, h.failures.size)
        assertFalse(h.pacer.offer(listOf(h.caption(4, "停止后的新句"))))
        h.pacer.finish()
        h.time.advance(10_000)
        assertEquals(listOf("已接收。"), h.output.map { it.zh })
        assertEquals(1, h.failures.size)
        assertTrue(h.pacer.awaitDrained(0))
    }

    /** 大量极短句也要按数量限流，不能只限制字数而无限保留分段对象。 */
    @Test fun pendingSegmentCountAlsoTriggersOverload() {
        val h = Harness(policy = policy(maxPendingSegments = 2))
        assertFalse(h.pacer.offer((1L..3L).map { h.caption(it, "字") }))
        h.time.advance(0)
        assertEquals(1, h.failures.size)
        assertTrue(h.output.isEmpty())
        assertEquals(0L, h.pacer.retainedCharacterCount())
    }

    /** 原文也消耗内存预算，不能让只有译文很短的字幕绕过高水位。 */
    @Test fun originalTextCountsTowardBacklogBudget() {
        val h = Harness(policy = policy(maxPendingCharacters = 8))
        assertFalse(h.pacer.offer(listOf(h.caption(1, "字", "原".repeat(10)))))
        h.time.advance(0)
        assertEquals(1, h.failures.size)
        assertEquals(0L, h.pacer.retainedCharacterCount())
    }

    /** 正常收尾保留最后一屏的最短阅读时间，立即停止则取消所有待投递任务。 */
    @Test fun finishHoldsLastPageButCloseCancelsImmediately() {
        val h = Harness()
        h.text("一。二。")
        h.pacer.finish()
        h.time.advance(1_000)
        assertFalse(h.pacer.awaitDrained(0))
        h.time.advance(1_000)
        assertTrue(h.pacer.awaitDrained(0))
        val stopped = Harness()
        stopped.text("一。二。")
        stopped.time.advance(0)
        stopped.pacer.close()
        stopped.time.advance(20_000)
        assertEquals(listOf("一。"), stopped.output.map { it.zh })
        assertFalse(stopped.pacer.offer(listOf(stopped.caption(3, "迟到"))))
        assertTrue(stopped.pacer.awaitDrained(0))
        assertTrue(stopped.time.closed)
    }

    /** 停止发生在首次任务运行前时，连草稿和超载通知也不能穿越停止边界。 */
    @Test fun closeBeforeFirstWakeSuppressesAllCallbacks() {
        val h = Harness(policy = policy(maxPendingSegments = 1))
        h.text("一。二。预测", final = false, confirmed = "一。二。")
        h.pacer.close()
        h.time.advance(10_000)
        assertTrue(h.output.isEmpty())
        assertTrue(h.failures.isEmpty())
    }

    /** 草稿撤回也必须保持真实编号，读完后不能残留已经撤销的预测内容。 */
    @Test fun emptyFinalRetractsDraftWithoutTakingAVisibleFinalSlot() {
        val h = Harness()
        h.text("预测", final = false)
        h.time.advance(0)
        assertEquals("预测", h.board.snapshot().single().zh)
        h.text("")
        h.time.advance(0)
        assertTrue(h.board.snapshot().isEmpty())
        h.pacer.finish()
        h.time.advance(0)
        assertTrue(h.pacer.awaitDrained(0))
    }

    /** 校正缓存淘汰后仍拒绝旧定稿，不能把旧段当新句重新放进阅读队列。 */
    @Test fun correctionsOlderThanRememberedPagesNeverRevive() {
        val h = Harness()
        for (id in 1L..40L) {
            h.pacer.offer(listOf(h.caption(id, "第${id}句。")))
            h.time.advance(1_000)
        }
        val count = h.output.size
        h.pacer.offer(listOf(h.caption(1, "迟到校正。", revision = 2)))
        h.time.advance(3_000)
        assertEquals(count, h.output.size)
        assertEquals("第40句。", h.board.snapshot(false).last().zh)
    }

    /** 下游回调暂停时网络入队和关闭均能返回，只有已进入的回调自行结束，后续回调不启动。 */
    @Test fun blockedDeliveryDoesNotBlockOfferOrClose() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val offered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val callbackReturned = CountDownLatch(1)
        val calls = AtomicInteger()
        val h = Harness()
        val pacer = OnlineCaptionPacer({
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            callbackReturned.countDown()
        }, {})
        var producer: Thread? = null
        var stopper: Thread? = null
        try {
            pacer.offer(listOf(h.caption(1, "一。")))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            producer = Thread {
                pacer.offer(listOf(h.caption(2, "二。")))
                offered.countDown()
            }.apply { start() }
            assertTrue(offered.await(2, TimeUnit.SECONDS))
            stopper = Thread {
                pacer.close()
                closed.countDown()
            }.apply { start() }
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
            assertEquals(1L, callbackReturned.count)
            release.countDown()
            assertTrue(callbackReturned.await(2, TimeUnit.SECONDS))
            assertFalse(pacer.offer(listOf(h.caption(3, "停止后"))))
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            pacer.close()
            producer?.join(2_000)
            stopper?.join(2_000)
        }
    }

    /** 停止发生在同次唤醒的第一个外部回调内时，其余草稿回调也必须取消，不能只取消定时器。 */
    @Test fun closeInsideCallbackSuppressesRestOfCurrentDispatch() {
        val time = VirtualScheduler()
        val output = mutableListOf<Caption>()
        val h = Harness()
        lateinit var pacer: OnlineCaptionPacer
        pacer = OnlineCaptionPacer({ caption ->
            output += caption
            pacer.close()
        }, { fail(it) }, policy(), time, { time.now })
        assertTrue(pacer.offer(listOf(h.caption(1, "定稿。"), h.caption(2, "未来草稿").copy(isFinal = false))))
        time.advance(0)
        assertEquals(listOf("定稿。"), output.map { it.zh })
        assertTrue(time.closed)
    }

    /** 用可调策略缩短虚拟时间，只保留与生产一致的时间计算规则。 */
    private fun policy(maxPendingCharacters: Int = 2_048,
        maxPendingSegments: Int = 16) = OnlineCaptionPacer.Policy(minReadMs = 1_000,
        maxReadMs = 3_000, baseReadMs = 0, millisecondsPerCodePoint = 100,
        maxPendingCharacters = maxPendingCharacters,
        maxPendingSegments = maxPendingSegments)

    /** 把真实适配器与字幕板串起来，检查对用户可见的行为而非私有队列结构。 */
    private inner class Harness(policy: OnlineCaptionPacer.Policy = this@OnlineCaptionPacerTest.policy()) {
        val time = VirtualScheduler()
        val output = mutableListOf<Caption>()
        val times = mutableListOf<Long>()
        val failures = mutableListOf<String>()
        val board = CaptionBoard().apply { setRetention(CaptionLimitMode.SENTENCES, 3, 100) }
        val adapter = OnlineCaptionAdapter()
        val pacer = OnlineCaptionPacer({ c ->
            output += c
            times += time.now
            if (!board.accept(c)) board.updateOnlineFinal(c)
        }, { failures += it }, policy, time, { time.now })

        /** 输入同一响应的完整快照，覆盖生产中的稳定编号与全文修订。 */
        fun text(value: String, source: String = "", final: Boolean = true,
            confirmed: String = if (final) value else "") {
            pacer.offer(adapter.acceptSegmented(LiveTranslationText("response", source, value, final, confirmed)))
        }

        /** 构造独立子段，以便精确验证阅读时间与严格容量边界。 */
        fun caption(id: Long, value: String, source: String = "", revision: Int = 1) =
            Caption(id, revision, true, source, value, Timing(0, 0, 0, 0, 0, 0, 0))
    }

    /** 只保存待执行任务的确定性调度器；推进时间时按截止时间运行，跳时则模拟线程调度延迟。 */
    private class VirtualScheduler : OnlineCaptionPacer.Scheduler {
        private data class Task(val due: Long, val order: Long, val run: () -> Unit, var cancelled: Boolean = false)
        var now = 0L
        var closed = false
        private var order = 0L
        private val tasks = mutableListOf<Task>()

        /** 只注册任务，绝不在调用方的网络线程内联执行。 */
        override fun schedule(delayMs: Long, task: () -> Unit): OnlineCaptionPacer.Cancellation {
            val entry = Task(now + delayMs, ++order, task)
            tasks += entry
            return OnlineCaptionPacer.Cancellation { entry.cancelled = true }
        }

        /** 推进虚拟时钟，执行区间内尚未取消的任务。 */
        fun advance(ms: Long) {
            val target = now + ms
            while (true) {
                val next = tasks.filter { !it.cancelled && it.due <= target }
                    .minWithOrNull(compareBy<Task> { it.due }.thenBy { it.order }) ?: break
                tasks.remove(next)
                now = maxOf(now, next.due)
                next.run()
            }
            now = target
        }

        /** 只改变时钟，不执行任务，模拟进程短暂挂起。 */
        fun jump(ms: Long) { now += ms }

        /** 停止后所有任务均失效。 */
        override fun close() { closed = true; tasks.clear() }
    }
}
