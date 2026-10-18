package io.github.korenkrita.simulsub.online

import org.json.JSONObject
import io.github.korenkrita.simulsub.online.LiveTranslateError.Code

/**
 * 千问 3.5 事件归并器，仅由客户端锁内调用。
 * 译文 text 是已确认前缀快照，stash 是可改写尾部，两者每次替换；response.done 验证成功状态。
 * 原文 text 是已确认识别快照，completed 覆盖为最终全文，不把同一原文重复追加。
 * 通过 assistant.previous_item_id 关联原文，绝不猜测最新一条原文属于哪个译文。
 * 最多保留 256 个字幕段、512 个原文、4096 个事件号和已淘汰消息号，限制长会话内存。
 */
internal class LiveTranslateEvents {
    private data class Source(var text: String = "", var complete: Boolean = false)
    private data class Segment(
        val id: String,
        var sourceId: String? = null,
        var responseId: String? = null,
        var confirmed: String = "",
        var stash: String = "",
        var textDone: Boolean = false,
        var final: Boolean = false,
        var last: LiveTranslationText? = null
    )
    internal data class Result(val texts: List<LiveTranslationText> = emptyList(), val error: LiveTranslateError? = null)
    private val sources = linkedMapOf<String, Source>()
    private val segments = linkedMapOf<String, Segment>()
    private val seen = linkedSetOf<String>()
    private val retired = linkedSetOf<String>()
    private val finishedResponses = linkedSetOf<String>()

    /** 处理单条事件并返回有变化的完整字幕；重放事件与已完成段的迟到增量直接忽略。 */
    fun accept(event: JSONObject): Result {
        val type = event.getString("type")
        val eventId = event.string("event_id")
        if (eventId.isNotEmpty() && !remember(seen, eventId)) return Result()
        val changed = mutableListOf<LiveTranslationText>()
        when (type) {
            "conversation.item.created" -> {
                val item = event.getJSONObject("item")
                if (item.string("role") == "assistant") {
                    val segment = segment(item.getString("id")) ?: return Result()
                    val sourceId = event.string("previous_item_id")
                    if (sourceId.isNotEmpty()) checkId(sourceId)
                    if (sourceId.isNotEmpty()) segment.sourceId = sourceId
                    emit(segment, changed)
                }
            }
            "response.output_item.added" -> {
                val responseId = event.getString("response_id")
                checkId(responseId)
                if (responseId in finishedResponses) return Result()
                val item = event.getJSONObject("item")
                val segment = segment(item.getString("id")) ?: return Result()
                segment.responseId = responseId
            }
            "conversation.item.input_audio_transcription.text",
            "conversation.item.input_audio_transcription.completed" -> {
                val sourceId = event.getString("item_id")
                checkId(sourceId)
                if (sourceId in retired) return Result()
                val source = sources.getOrPut(sourceId) { Source() }
                if (source.complete) return Result()
                if (type.endsWith(".completed")) {
                    source.text = event.getString("transcript")
                    source.complete = true
                } else {
                    source.text = event.getString("text") + event.string("stash")
                }
                checkText(source.text)
                for (segment in segments.values) if (segment.sourceId == sourceId) emit(segment, changed)
            }
            "conversation.item.input_audio_transcription.failed" -> {
                if (sources[event.string("item_id")]?.complete == true) return Result()
                return Result(error = LiveTranslateError(Code.TRANSCRIPTION_FAILED, "听译原文识别失败，会话已停止"))
            }
            "response.text.text", "response.text.done" -> {
                val responseId = event.getString("response_id")
                checkId(responseId)
                if (responseId in finishedResponses) return Result()
                val segment = segment(event.getString("item_id")) ?: return Result()
                if (segment.final) return Result()
                segment.responseId = responseId
                if (type.endsWith(".done")) {
                    if (segment.textDone) return Result()
                    segment.confirmed = event.getString("text")
                    segment.stash = ""
                    segment.textDone = true
                } else {
                    require(eventId.isNotEmpty()) { "听译增量缺少事件号" }
                    if (segment.textDone) return Result()
                    segment.confirmed = event.getString("text")
                    segment.stash = event.string("stash")
                }
                checkText(segment.confirmed + segment.stash)
                emit(segment, changed)
            }
            "response.done" -> {
                val response = event.getJSONObject("response")
                val responseId = response.getString("id")
                checkId(responseId)
                if (responseId in finishedResponses) return Result()
                if (response.string("status") != "completed") return Result(error =
                    LiveTranslateError(Code.RESPONSE_FAILED, "听译响应未完整完成，会话已停止"))
                val output = response.optJSONArray("output")
                if (output != null) for (i in 0 until output.length()) {
                    val item = output.getJSONObject(i)
                    if (item.string("role").let { it.isNotEmpty() && it != "assistant" }) continue
                    val segment = segment(item.getString("id")) ?: continue
                    if (segment.final) continue
                    segment.responseId = responseId
                    val content = item.optJSONArray("content")
                    val parts = mutableListOf<String>()
                    if (content != null) for (j in 0 until content.length()) {
                        val part = content.getJSONObject(j)
                        if (part.string("type") == "text") parts += part.getString("text")
                    }
                    if (parts.isNotEmpty()) {
                        segment.confirmed = parts.joinToString("")
                        segment.textDone = true
                    }
                }
                for (segment in segments.values) if (segment.responseId == responseId && !segment.final) {
                    segment.stash = ""
                    segment.final = true
                    checkText(segment.confirmed)
                    emit(segment, changed)
                }
                remember(finishedResponses, responseId)
            }
        }
        trim()
        return Result(changed)
    }

    /** 清除会话字幕、关联和去重记录，不保存识别内容到磁盘。 */
    fun clear() {
        sources.clear()
        segments.clear()
        seen.clear()
        retired.clear()
        finishedResponses.clear()
    }

    /** 创建稳定译文段号，已淘汰消息在有限回放窗口内不可重新创建。 */
    private fun segment(id: String): Segment? {
        checkId(id)
        if (id in retired) return null
        return segments.getOrPut(id) { Segment(id) }
    }

    /** 只发变化后的完整快照，原文迟到时沿用已经完成的译文段号与最终标记。 */
    private fun emit(segment: Segment, changed: MutableList<LiveTranslationText>) {
        val text = LiveTranslationText(segment.id, sources[segment.sourceId]?.text.orEmpty(),
            segment.confirmed + segment.stash, segment.final, segment.confirmed)
        if ((text.sourceText.isNotEmpty() || text.translatedText.isNotEmpty() || segment.last != null) && segment.last != text) {
            segment.last = text
            changed += text
        }
    }

    /** 有界事件号集合去重，相同文本但不同事件号仍然允许构成重复词语。 */
    private fun remember(set: MutableSet<String>, id: String): Boolean {
        require(id.length <= 512) { "听译事件号过长" }
        if (!set.add(id)) return false
        if (set.size > 4096) set.remove(set.first())
        return true
    }

    /** 限制字幕长度，异常服务端不能无限增长客户端的文本缓冲。 */
    private fun checkText(text: String) {
        require(text.length <= 65_536) { "听译字幕过长" }
    }

    /** 限制关联键大小，避免超长服务端消息号占用会话缓存。 */
    private fun checkId(id: String) {
        require(id.isNotEmpty() && id.length <= 512) { "听译消息号无效" }
    }

    /** 优先淘汰已完成段；异常并发段过多时终止，避免丢失正在生成的字幕。 */
    private fun trim() {
        while (segments.size > 256) {
            val oldest = segments.values.firstOrNull { it.final }
                ?: throw IllegalStateException("听译并发段过多")
            segments.remove(oldest.id)
            remember(retired, oldest.id)
        }
        while (sources.size > 512) {
            val oldest = sources.keys.firstOrNull { id -> segments.values.none { it.sourceId == id && !it.final } }
                ?: throw IllegalStateException("听译原文段过多")
            sources.remove(oldest)
            remember(retired, oldest)
        }
    }

    /** 读取可选文本，不把 JSON null 或对象意外转成字幕文本。 */
    private fun JSONObject.string(name: String): String = opt(name) as? String ?: ""
}
