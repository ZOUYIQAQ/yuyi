package io.github.korenkrita.simulsub

import java.util.UUID
import java.util.concurrent.Executors

/** 用量回调只入队，磁盘写入和历史聚合不占用网络或字幕事件线程。 */
private val costWrites = Executors.newSingleThreadExecutor { task ->
    Thread(task, "费用明细写入").apply { isDaemon = true }
}

/** 按收到回调的顺序保存观测，结束后的排队记录仍归原会话。 */
internal fun UsageCostRepository.recordObserved(sessionId: String?, category: CostCategory, endpoint: String,
    model: String, usage: MeteredUsage, eventId: String = UUID.randomUUID().toString(), cumulative: Boolean = false) {
    val observedAt = System.currentTimeMillis()
    costWrites.execute {
        try { record(sessionId, category, endpoint, model, usage, eventId, cumulative, observedAtMs = observedAt) }
        catch (_: Exception) { android.util.Log.e("费用统计", "本次用量明细未能保存") }
    }
}

/** 仅在真正发出的文字请求返回时调用，缓存重用不会再次写入账本。 */
internal fun UsageCostRepository.recordTranslation(sessionId: String?, category: CostCategory,
    service: ByokService, result: MtResult) {
    recordObserved(sessionId, category, service.url, service.model, MeteredUsage(
        inputTokens = result.promptTokens?.toLong(), outputTokens = result.outputTokens?.toLong(),
        cachedInputTokens = result.cachedTokens?.toLong()))
}
