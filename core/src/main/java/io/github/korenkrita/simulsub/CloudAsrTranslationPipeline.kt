package io.github.korenkrita.simulsub

import io.github.korenkrita.simulsub.online.CloudAsrText
import io.github.korenkrita.simulsub.online.LiveTranslationText
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** 只翻译已经定稿的在线听写；串行、有界排队，避免草稿反复收费和译文乱序。 */
class CloudAsrTranslationPipeline(
    private val translator: Mt,
    private val onCaption: (Caption) -> Unit,
    private val onFailure: (String) -> Unit,
    private val contextSentences: Int = 3,
    private val onResult: (MtResult, Long) -> Unit = { _, _ -> },
) : AutoCloseable {
    private data class Job(val id: String, val text: String, val queued: Long)
    private val queue = ArrayBlockingQueue<Job>(12)
    private val seen = linkedSetOf<String>()
    private val output = OnlineCaptionAdapter()
    private val previous = ArrayDeque<String>()
    private val lock = Any()
    @Volatile private var stopped = false
    @Volatile private var finished = false
    @Volatile private var started = false
    private val worker = Thread(::translateLoop, "在线听写文字翻译")

    /** 显式启动唯一翻译线程，同一实例不能重复运行或在关闭后重新启动。 */
    @Synchronized fun start() {
        check(!started && !stopped)
        started = true
        worker.start()
    }

    /** 草稿不收费；翻译保留完整句意，显示阶段再将译文拆成短字幕。 */
    @Synchronized fun accept(text: CloudAsrText) {
        if (stopped || finished || !text.isFinal || text.text.isBlank() || text.segmentId in seen) return
        seen += text.segmentId
        while (seen.size > 512) seen.remove(seen.first())
        val now = monoMs()
        val parts = translationUnits(text.text)
        for ((index, value) in parts.withIndex()) {
            if (value.isNotEmpty() && !queue.offer(Job("${text.segmentId}/$index", value, now))) {
                fail("文字翻译速度跟不上播放，请检查网络或改用直接在线听译")
                return
            }
        }
    }

    /** 音频结束后允许队列正常排空，真实会话停止则由关闭直接取消。 */
    fun finishInput() { finished = true }

    /** 单次保留至多一百二十字语义上下文，超长定稿优先在标点处切开。 */
    private fun translationUnits(text: String): List<String> {
        val parts = mutableListOf<String>()
        var tail = text.trim()
        while (tail.codePointCount(0, tail.length) > 120) {
            val limit = tail.offsetByCodePoints(0, 120)
            val boundary = tail.substring(0, limit).indexOfLast { it in "。！？!?；;，,、\n" }
            val end = if (boundary >= limit / 2) boundary + 1 else limit
            parts += tail.substring(0, end).trim()
            tail = tail.substring(end).trim()
        }
        if (tail.isNotEmpty()) parts += tail
        return parts
    }

    /** 验收和正常结束都用有限等待，不阻塞界面线程。 */
    fun awaitCompletion(timeoutMs: Long): Boolean {
        if (started) worker.join(timeoutMs.coerceAtLeast(1))
        return !worker.isAlive
    }

    /** 保存短前文帮助理解指代；一次失败立即停止，不自动重试或重复计费。 */
    private fun translateLoop() {
        try {
            while (!stopped && !(finished && queue.isEmpty())) {
                val job = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                val begin = monoMs()
                if (begin - job.queued > 30_000) { fail("文字翻译等待过久，请检查网络后重新开始"); break }
                val result = translator.translate(byokUser(job.text, previous = previous.toList()))
                if (stopped) break
                if (result == null || result.stop != 'e' || result.text.isBlank()) {
                    fail("文字翻译失败${result?.http?.takeIf { it != 200 }?.let { "（HTTP $it）" }.orEmpty()}，请检查翻译服务后重试")
                    break
                }
                synchronized(lock) {
                    if (!stopped) {
                        onResult(result, monoMs() - begin)
                    }
                }
                val mtMs = monoMs() - begin
                val captions = output.acceptSegmented(LiveTranslationText(job.id, job.text, result.text, true)).map {
                    it.copy(timing = it.timing.copy(mtQueueMs = begin - job.queued, mtMs = mtMs))
                }
                for ((index, caption) in captions.withIndex()) {
                    if (index > 0) Thread.sleep(700)
                    synchronized(lock) { if (!stopped) onCaption(caption) }
                    if (stopped) break
                }
                previous.addLast(job.text)
                while (previous.size > contextSentences.coerceIn(0, 10)) previous.removeFirst()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            if (!stopped) fail("在线听写的文字翻译已中断，请重新开始")
        } finally {
            translator.cancel()
            queue.clear()
        }
    }

    /** 失败只通知一次，并取消正在进行与尚未执行的请求。 */
    private fun fail(message: String) {
        synchronized(lock) {
            if (stopped) return
            stopped = true
            queue.clear()
            translator.cancel()
            onFailure(message)
        }
    }

    /** 关闭后不再发送网络请求或字幕回调；不会等待自己的工作线程。 */
    override fun close() {
        synchronized(lock) { stopped = true; queue.clear(); translator.cancel() }
        worker.interrupt()
        if (started && Thread.currentThread() !== worker) worker.join(2_000)
    }
}
