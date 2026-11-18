package io.github.korenkrita.simulsub

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 将在线字幕的接收速度与阅读速度分离；网络回调只更新有界状态，所有投递在独立线程串行执行。
 * 有定稿等待时只缓存最新草稿，防止未来内容超前覆盖；已显示定稿的校正仍立即投递。
 * 保留分段器的原始短句，不跨句拼接。每批先预检再原子入队，超过严格容量的整批不接收，
 * 不保留该批文字，异步明确报告本批未显示并要求停止采集；已有内容继续按原速读完。
 */
class OnlineCaptionPacer internal constructor(
    private val onCaption: (Caption) -> Unit,
    private val onFailure: (String) -> Unit,
    private val policy: Policy,
    private val scheduler: Scheduler,
    private val clock: () -> Long,
) : AutoCloseable {
    /** 创建会话独占的可取消调度器，不共享网络线程或安卓主线程。 */
    constructor(onCaption: (Caption) -> Unit, onFailure: (String) -> Unit) :
        this(onCaption, onFailure, Policy(), ExecutorScheduler(), { monoMs() })

    /** 每句至少可读 1.2 秒；最多排队十六条短句，满队不会缩短阅读间隔来追赶。 */
    internal data class Policy(
        val minReadMs: Long = 1_200,
        val maxReadMs: Long = 4_000,
        val baseReadMs: Long = 600,
        val millisecondsPerCodePoint: Long = 100,
        val maxPendingCharacters: Int = 2_048,
        val maxPendingSegments: Int = 16,
        val rememberedPages: Int = 32,
    ) {
        init {
            require(minReadMs > 0 && maxReadMs >= minReadMs && baseReadMs >= 0 && millisecondsPerCodePoint > 0) {
                "字幕阅读时间配置无效"
            }
            require(maxPendingCharacters > 0 && maxPendingSegments > 0 && rememberedPages > 0) {
                "字幕阅读队列容量配置无效"
            }
        }
    }

    /** 取消句柄只撤销尚未执行的任务。 */
    internal fun interface Cancellation {
        /** 幂等取消任务，不等待调度线程。 */
        fun cancel()
    }

    /** 调度必须异步且串行；虚拟实现可直接控制时间，不需要真实休眠。 */
    internal interface Scheduler : AutoCloseable {
        /** 注册延迟任务，禁止在调用线程内联执行。 */
        fun schedule(delayMs: Long, task: () -> Unit): Cancellation
    }

    private val lock = Any()
    private val deliveryLock = Any()
    private val drained = CountDownLatch(1)
    private val pending = linkedMapOf<Long, Caption>()
    private val shown = linkedMapOf<Long, Caption>()
    private val corrections = linkedMapOf<Long, Caption>()
    private var draft: Caption? = null
    private var draftDirty = false
    private var lastFinal = Long.MIN_VALUE
    private var lastDelivered = Long.MIN_VALUE
    private var nextFinalAt = Long.MIN_VALUE
    private var accepting = true
    private var finishing = false
    @Volatile private var closed = false
    private var failureNotice: String? = null
    private var task: Cancellation? = null
    private var wakeAt: Long? = null
    private var generation = 0L

    /**
     * 整批接收适配器的增量；同号排队定稿原位替换，已显示定稿只原位校正，重放不重复计句。
     * 返回 false 表示整批未接收；预检失败不更新任何字幕、编号或草稿，随后异步明确通知停止。
     */
    fun offer(captions: List<Caption>): Boolean = synchronized(lock) {
        if (!accepting || closed) return false
        if (captions.isEmpty()) return true
        // 在复制状态或保留输入引用之前检查原始批次，超大批次不能借撤回或合并绕过硬上限。
        if (captions.size.toLong() > policy.maxPendingSegments.toLong() + policy.rememberedPages + 1) {
            return rejectBatch("字幕批次段数超过容量")
        }
        var batchCharacters = 0L
        for (caption in captions) {
            batchCharacters += characters(caption)
            if (batchCharacters > policy.maxPendingCharacters) return rejectBatch("字幕批次字数超过容量")
        }
        val nextPending = LinkedHashMap(pending)
        val nextShown = LinkedHashMap(shown)
        val nextCorrections = LinkedHashMap(corrections)
        var nextDraft = draft
        var nextDraftDirty = draftDirty
        var nextLastFinal = lastFinal
        for (caption in captions) {
            if (!caption.isFinal) {
                val old = nextDraft
                if (caption.segmentId > nextLastFinal && (old == null || caption.segmentId > old.segmentId ||
                        (caption.segmentId == old.segmentId && caption.revision > old.revision))) {
                    nextDraft = caption
                    nextDraftDirty = true
                }
                continue
            }
            val old = nextPending[caption.segmentId] ?: nextShown[caption.segmentId]
            if (old != null) {
                if (caption.revision >= old.revision && caption != old) {
                    if (caption.segmentId in nextPending) nextPending[caption.segmentId] = caption
                    else {
                        nextShown[caption.segmentId] = caption
                        nextCorrections[caption.segmentId] = caption
                    }
                }
            } else if (caption.segmentId > nextLastFinal) {
                if (nextPending.size >= policy.maxPendingSegments) return rejectBatch("字幕阅读队列已满")
                nextLastFinal = caption.segmentId
                nextPending[caption.segmentId] = caption
            }
            if (nextDraft?.segmentId?.let { it <= nextLastFinal } == true) {
                nextDraft = null
                nextDraftDirty = false
            }
        }
        if (nextPending.size + (if (nextDraft == null) 0 else 1) > policy.maxPendingSegments ||
            nextPending.values.sumOf { characters(it) } + nextCorrections.values.sumOf { characters(it) } +
                (nextDraft?.let { characters(it) } ?: 0) > policy.maxPendingCharacters) {
            return rejectBatch("字幕阅读积压超过容量")
        }
        pending.clear(); pending.putAll(nextPending)
        shown.clear(); shown.putAll(nextShown)
        corrections.clear(); corrections.putAll(nextCorrections)
        draft = nextDraft
        draftDirty = nextDraftDirty
        lastFinal = nextLastFinal
        wake(0)
        true
    }

    /** 预算按实际字符串字元计数，原文、预测和校正文字均不能绕过内存限制。 */
    private fun characters(caption: Caption): Long = caption.zh.length.toLong() + caption.ja.length

    /** 返回仍持有文字引用的长度上界，供验证拒绝批次没有进入待投递、校正、草稿或历史缓存。 */
    internal fun retainedCharacterCount(): Long = synchronized(lock) {
        pending.values.sumOf { characters(it) } + shown.values.sumOf { characters(it) } +
            corrections.values.sumOf { characters(it) } + (draft?.let { characters(it) } ?: 0)
    }

    /** 拒绝整批且不保存输入引用；只通知一次，已接收队列继续保持原来的阅读速度。 */
    private fun rejectBatch(reason: String): Boolean {
        accepting = false
        failureNotice = "$reason，本批字幕未接收、未显示；已停止继续听译，正在读完已接收字幕。请暂停播放后重新开始"
        wake(0)
        return false
    }

    /** 正常收尾拒收新字幕，但保留排队内容及最后一屏的最短阅读时间。 */
    fun finish() = synchronized(lock) {
        if (!closed) {
            accepting = false
            finishing = true
            wake(0)
        }
    }

    /** 只允许会话后台线程等待；用户停止通过 close 立即唤醒，禁止从投递或网络回调中等待。 */
    fun awaitDrained(timeoutMs: Long): Boolean {
        require(timeoutMs >= 0) { "字幕阅读收尾等待时间无效" }
        return try { drained.await(timeoutMs, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
    }

    /** 一个会话最多保留一个唤醒任务；新草稿可提前唤醒，但不能突破下一屏的阅读截止时间。 */
    private fun wake(delayMs: Long) {
        if (closed) return
        val due = clock() + delayMs
        if (wakeAt?.let { it <= due } == true) return
        task?.cancel()
        val ticket = ++generation
        wakeAt = due
        task = scheduler.schedule(delayMs) { publish(ticket) }
    }

    /** 独立线程投递；调度晚到时从实际投递重新计时，不追赶补发已经过期的屏。 */
    private fun publish(ticket: Long): Unit = synchronized(deliveryLock) {
        val output = mutableListOf<Caption>()
        var readingMs: Long? = null
        val notice: String?
        synchronized(lock) {
            if (closed || ticket != generation) return
            task = null
            wakeAt = null
            notice = failureNotice
            failureNotice = null
            output += corrections.values
            corrections.clear()
            val caption = pending.values.firstOrNull()
            if (caption != null && (clock() >= nextFinalAt || caption.zh.isEmpty())) {
                pending.remove(caption.segmentId)
                shown[caption.segmentId] = caption
                while (shown.size > policy.rememberedPages) shown.remove(shown.keys.first())
                lastDelivered = caption.segmentId
                output += caption
                if (caption.zh.isNotEmpty()) {
                    val count = caption.zh.codePointCount(0, caption.zh.length).toLong()
                    readingMs = (policy.baseReadMs + count * policy.millisecondsPerCodePoint)
                        .coerceIn(policy.minReadMs, policy.maxReadMs)
                    nextFinalAt = clock() + requireNotNull(readingMs)
                }
                if (draft?.segmentId?.let { it <= lastDelivered } == true) {
                    draft = null
                    draftDirty = false
                }
            }
            // 草稿只能跟在已经显示的定稿之后；排队期间继续更新缓存，不能提前显示未来尾部。
            if (draftDirty && pending.isEmpty()) {
                draft?.takeIf { it.segmentId > lastDelivered }?.let { output += it }
                draftDirty = false
            }
        }
        try {
            if (!closed && notice != null) onFailure(notice)
            for (caption in output) {
                if (closed) break
                onCaption(caption)
            }
        } catch (_: Exception) {
            runCatching { if (!closed) onFailure("字幕投递失败，已停止听译，请重新开始") }
            close()
            return
        }
        synchronized(lock) {
            if (closed) return
            readingMs?.let { nextFinalAt = clock() + it }
            val delay = if (nextFinalAt == Long.MIN_VALUE) 0 else (nextFinalAt - clock()).coerceAtLeast(0)
            if (pending.isNotEmpty()) {
                wake(if (pending.values.first().zh.isEmpty()) 0 else delay)
            } else if (finishing) {
                if (delay > 0) wake(delay) else drained.countDown()
            }
        }
    }

    /** 幂等取消且不等待外部回调；已进入的回调自行返回，后续投递和所有阅读等待立即失效。 */
    override fun close() {
        synchronized(lock) {
            closed = true
            accepting = false
            ++generation
            task?.cancel()
            task = null
            pending.clear()
            shown.clear()
            corrections.clear()
            draft = null
            drained.countDown()
        }
        scheduler.close()
    }

    /** 每会话一个后台线程；取消任务立即移出定时队列，关闭后不再执行延迟任务。 */
    private class ExecutorScheduler : Scheduler {
        private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "在线字幕阅读节奏").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true; setExecuteExistingDelayedTasksAfterShutdownPolicy(false) }

        /** 调度器只执行短任务，阅读等待交给定时队列。 */
        override fun schedule(delayMs: Long, task: () -> Unit): Cancellation {
            val future = executor.schedule(Runnable { task() }, delayMs, TimeUnit.MILLISECONDS)
            return Cancellation { future.cancel(false) }
        }

        /** 不打断正在执行的回调，也不等待任何延迟任务。 */
        override fun close() { executor.shutdown() }
    }
}
