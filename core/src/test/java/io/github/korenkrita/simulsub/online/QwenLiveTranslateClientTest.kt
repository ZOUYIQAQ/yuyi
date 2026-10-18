package io.github.korenkrita.simulsub.online

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.decodeBase64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class QwenLiveTranslateClientTest {
    /** 响应终帧按 response.id 去重，并在字幕解析失败前保存真实用量。 */
    @Test fun responseUsageIsReportedOnceAndKeepsZero() = Harness().use { h ->
        h.start()
        val done = """{"type":"response.done","response":{"id":"bill-1","status":"completed","output":[],"usage":{"input_tokens":47,"output_tokens":0,"input_tokens_details":{"audio_tokens":27}}}}"""
        h.send(done)
        h.send(done)
        val usage = requireNotNull(h.usages.poll(3, TimeUnit.SECONDS))
        assertEquals("bill-1", usage.first)
        assertEquals(47L, usage.second.inputTokens)
        assertEquals(0L, usage.second.outputTokens)
        assertEquals(27L, usage.second.audioInputTokens)
        assertNull(usage.second.cachedInputTokens)
        assertNull(h.usages.poll(100, TimeUnit.MILLISECONDS))
    }

    /** response.done 未附 usage 时只上报一次未知值，delta 不产生计量。 */
    @Test fun responseWithoutUsageStaysUnknown() = Harness().use { h ->
        h.start()
        h.send(delta("partial", "译文", ""))
        assertNull(h.usages.poll(100, TimeUnit.MILLISECONDS))
        h.send(done("译文"))
        assertEquals(io.github.korenkrita.simulsub.MeteredUsage(), h.usages.poll(3, TimeUnit.SECONDS)?.second)
    }

    /** 真实接口会在尾部逐字生成时重发已确认前缀，不能把同一前缀重复追加到字幕。 */
    @Test fun repeatedConfirmedSnapshotsDoNotDuplicateThePrefix() = Harness().use { h ->
        h.start()
        h.send(delta("snapshot-1", "我们中学实行便当制，", "如果"))
        h.nextText { it.translatedText == "我们中学实行便当制，如果" }
        h.send(delta("snapshot-2", "我们中学实行便当制，", "如果没带"))
        assertEquals("我们中学实行便当制，如果没带", requireNotNull(h.texts.poll(3, TimeUnit.SECONDS)).translatedText)
        h.send(delta("snapshot-3", "我们中学实行便当制，如果没带便当的话，", "就买面包"))
        assertEquals("我们中学实行便当制，如果没带便当的话，就买面包", requireNotNull(h.texts.poll(3, TimeUnit.SECONDS)).translatedText)
    }

    /** 实际升级 WebSocket 并验证独立模型、Bearer 头、仅文本配置及精确 PCM 字节。 */
    @Test fun realHandshakeHeaderAndPcm() = Harness(source = "ja").use { h ->
        assertTrue(h.client.start())
        assertFalse(h.client.start())
        assertTrue(h.client.awaitReady())
        val request = requireNotNull(h.server.takeRequest(3, TimeUnit.SECONDS))
        assertEquals("Bearer test-private-key", request.getHeader("Authorization"))
        assertEquals(LiveTranslateConfig.DEFAULT_MODEL, request.requestUrl!!.queryParameter("model"))
        assertEquals("/api-ws/v1/realtime", request.requestUrl!!.encodedPath)
        val session = h.next("session.update").getJSONObject("session")
        assertEquals("[\"text\"]", session.getJSONArray("modalities").toString())
        assertEquals("pcm", session.getString("input_audio_format"))
        assertEquals(16000, session.getInt("sample_rate"))
        assertEquals("ja", session.getJSONObject("input_audio_transcription").getString("language"))
        assertEquals("qwen3-asr-flash-realtime", session.getJSONObject("input_audio_transcription").getString("model"))
        assertEquals("zh", session.getJSONObject("translation").getString("language"))
        assertFalse(session.has("voice"))
        val pcm = byteArrayOf(0, 0, -1, 127, 0, -128, -1, -1)
        assertTrue(h.client.sendPcm16(pcm))
        val audio = h.next("input_audio_buffer.append").getString("audio").decodeBase64()!!.toByteArray()
        assertArrayEquals(pcm, audio)
        assertEquals(listOf(LiveTranslateState.CONNECTING, LiveTranslateState.CONFIGURING, LiveTranslateState.READY), h.states.toList())
    }

    /** 探测只握手不传音频，自动源语种不发送 null 或空语言参数。 */
    @Test fun connectionProbeDoesNotSendAudio() = Harness().use { h ->
        assertTrue(h.client.start())
        assertTrue(h.client.awaitReady(15_000))
        assertFalse(h.next("session.update").getJSONObject("session").getJSONObject("input_audio_transcription").has("language"))
        h.client.close()
        assertTrue(h.client.awaitClosed(500))
        assertNull(h.received.poll(100, TimeUnit.MILLISECONDS))
        assertEquals(LiveTranslateState.CLOSED, h.client.state)
        assertTrue(h.errors.isEmpty())
    }

    /** session.updated 以前不收音频，也不在之后补发早期录音。 */
    @Test fun waitsForSessionUpdatedBeforeAudio() = Harness(autoReady = false).use { h ->
        assertFalse(h.client.sendPcm16(byteArrayOf(0, 0)))
        assertTrue(h.client.start())
        h.next("session.update")
        assertEquals(LiveTranslateState.CONFIGURING, h.client.state)
        assertFalse(h.client.sendPcm16(byteArrayOf(0, 0)))
        assertNull(h.received.poll(100, TimeUnit.MILLISECONDS))
        h.send("""{"type":"session.updated"}""")
        assertTrue(h.client.awaitReady())
        assertNull(h.received.poll(100, TimeUnit.MILLISECONDS))
    }

    /** 已确认前缀与预测尾部都按快照替换，完成全文覆盖；事件重放不重复通知。 */
    @Test fun replacesConfirmedSnapshotAndStash() = Harness().use { h ->
        h.start()
        h.send("""{"type":"conversation.item.created","event_id":"link","previous_item_id":"source","item":{"id":"translation","role":"assistant"}}""")
        h.send("""{"type":"conversation.item.input_audio_transcription.text","event_id":"asr1","item_id":"source","text":"今日は","stash":"晴れ"}""")
        h.send(delta("one", "你好", "世界"))
        h.nextText { it.translatedText == "你好世界" }
        h.send(delta("one", "你好", "世界"))
        h.send(delta("two", "你好，", "朋友"))
        val partial = h.nextText { it.translatedText == "你好，朋友" }
        assertEquals("你好，", partial.confirmedText)
        assertEquals("今日は晴れ", partial.sourceText)
        assertFalse(partial.isFinal)
        h.send(textDone("你好，朋友。"))
        assertFalse(h.nextText { it.translatedText == "你好，朋友。" }.isFinal)
        h.send(done("你好，朋友。"))
        val final = h.nextText { it.isFinal }
        assertEquals("translation", final.segmentId)
        assertEquals("你好，朋友。", final.translatedText)
        h.send(delta("late", "不应追加", ""))
        h.send(done("你好，朋友。"))
        h.send("""{"type":"conversation.item.input_audio_transcription.completed","event_id":"asr-final","item_id":"source","transcript":"今日は晴れです。"}""")
        val sourceFinal = h.nextText { it.sourceText == "今日は晴れです。" }
        assertTrue(sourceFinal.isFinal)
        assertEquals(final.segmentId, sourceFinal.segmentId)
        h.send("""{"type":"conversation.item.input_audio_transcription.text","event_id":"late-asr","item_id":"source","text":"迟到","stash":""}""")
        assertNull(h.texts.poll(100, TimeUnit.MILLISECONDS))
    }

    /** 原文快照覆盖而非累加，完成重放与快照重复均不产生重复字幕。 */
    @Test fun sourceSnapshotsAndOutOfOrderAssociation() = Harness().use { h ->
        h.start()
        h.send(delta("first", "译文", ""))
        assertEquals("", h.nextText { it.translatedText == "译文" }.sourceText)
        h.send("""{"type":"conversation.item.input_audio_transcription.text","event_id":"s1","item_id":"source","text":"今日は","stash":"晴れ"}""")
        h.send("""{"type":"conversation.item.input_audio_transcription.text","event_id":"s2","item_id":"source","text":"今日は晴れ","stash":"です"}""")
        h.send("""{"type":"conversation.item.created","event_id":"link","previous_item_id":"source","item":{"id":"translation","role":"assistant"}}""")
        val linked = h.nextText { it.sourceText.isNotEmpty() }
        assertEquals("今日は晴れです", linked.sourceText)
        assertEquals("translation", linked.segmentId)
        h.send("""{"type":"conversation.item.input_audio_transcription.completed","event_id":"s3","item_id":"source","transcript":"今日は晴れです"}""")
        h.send("""{"type":"conversation.item.input_audio_transcription.completed","event_id":"s4","item_id":"source","transcript":"今日は晴れです"}""")
        assertNull(h.texts.poll(100, TimeUnit.MILLISECONDS))
    }

    /** 仅 response.done 到达时也能返回最终全文，且不把未确认 stash 当作最终字幕。 */
    @Test fun responseDoneCanSupplyFinalText() = Harness().use { h ->
        h.start()
        h.send(delta("d", "确认", "猜测"))
        h.nextText { it.translatedText == "确认猜测" }
        h.send(done("最终全文"))
        assertEquals("最终全文", h.nextText { it.isFinal }.translatedText)
    }

    /** 已完成响应即使尚无字幕段，也不能被迟到增量重新创建。 */
    @Test fun completedResponseCannotBeResurrectedByLateText() = Harness().use { h ->
        h.start()
        h.send("""{"type":"response.done","response":{"id":"response","status":"completed","output":[]}}""")
        h.send(delta("late-first", "迟到文本", ""))
        val marker = JSONObject(done("后续响应")).apply {
            getJSONObject("response").put("id", "next-response")
                .getJSONArray("output").getJSONObject(0).put("id", "next-translation")
        }
        h.send(marker.toString())
        val first = h.texts.poll(3, TimeUnit.SECONDS) ?: throw AssertionError("未收到后续响应")
        assertEquals("next-translation", first.segmentId)
        assertTrue(first.isFinal)
        assertNull(h.texts.poll(100, TimeUnit.MILLISECONDS))
    }

    /** 最终响应没有确认文本时清除预测尾部，不把曾经显示的 stash 留作最终字幕。 */
    @Test fun finalWithoutConfirmedTextClearsStash() = Harness().use { h ->
        h.start()
        h.send(delta("only-stash", "", "临时预测"))
        h.nextText { it.translatedText == "临时预测" }
        h.send("""{"type":"response.done","response":{"id":"response","status":"completed","output":[]}}""")
        val final = h.nextText { it.isFinal }
        assertEquals("", final.translatedText)
        assertEquals("translation", final.segmentId)
    }

    /** 真正的叠词由完整快照保留，不能为了解决前缀重复而删除原文中的重复词语。 */
    @Test fun confirmedSnapshotPreservesRepeatedWords() = Harness().use { h ->
        h.start()
        h.send(delta("repeat-one", "好", ""))
        h.nextText { it.translatedText == "好" }
        h.send(delta("repeat-two", "好好", ""))
        assertEquals("好好", h.nextText { it.translatedText == "好好" }.translatedText)
    }

    /** 不同协议模型在发起请求前失败，避免连接成功后一直没有字幕。 */
    @Test fun unsupportedModelNeverConnects() = Harness(model = "qwen3.8-livetranslate-flash-realtime").use { h ->
        assertFalse(h.client.start())
        val error = h.nextError()
        assertEquals(LiveTranslateError.Code.INVALID_CONFIG, error.code)
        assertTrue(error.message.contains("接入"))
        assertEquals(0, h.server.requestCount)
        assertTrue(h.transportClosed)
    }

    /** 不完整或取消响应不能因为 text.done 到达就误报成功。 */
    @Test fun incompleteResponseIsNotFinal() = Harness().use { h ->
        h.start()
        h.send(textDone("部分文本"))
        assertFalse(h.nextText { it.translatedText == "部分文本" }.isFinal)
        h.send("""{"type":"response.done","response":{"id":"response","status":"incomplete","output":[]}}""")
        assertEquals(LiveTranslateError.Code.RESPONSE_FAILED, h.nextError().code)
        assertTrue(h.client.awaitClosed(1000))
        assertNull(h.texts.poll(100, TimeUnit.MILLISECONDS))
    }

    /** finish 只发一次并保留尾段，session.finished 后完成真实关闭握手。 */
    @Test fun finishesAndClosesWithoutLeakingTail() = Harness().use { h ->
        h.start()
        assertTrue(h.client.finish())
        assertTrue(h.client.finish())
        assertFalse(h.client.sendPcm16(byteArrayOf(0, 0)))
        h.next("session.finish")
        h.send(done("尾段译文"))
        assertEquals("尾段译文", h.nextText { it.isFinal }.translatedText)
        h.send("""{"type":"session.finished"}""")
        assertTrue(h.client.awaitClosed(3000))
        assertEquals(LiveTranslateState.CLOSED, h.client.state)
        assertNull(h.received.poll(100, TimeUnit.MILLISECONDS))
        assertTrue(h.errors.isEmpty())
        assertTrue(h.transportClosed)
    }

    /** 结束超时强制取消，不允许服务端保持一个无限等待的连接。 */
    @Test fun finishTimeoutCancels() = Harness(options = LiveTranslateOptions(finishTimeoutMs = 150)).use { h ->
        h.start()
        assertTrue(h.client.finish())
        h.next("session.finish")
        assertEquals(LiveTranslateError.Code.FINISH_TIMEOUT, h.nextError().code)
        assertTrue(h.client.awaitClosed(1000))
        assertTrue(h.transportClosed)
    }

    /** 握手超时后取消且不重连，未发音频也不会残留请求。 */
    @Test fun handshakeTimeoutCancels() = Harness(autoReady = false, options = LiveTranslateOptions(handshakeTimeoutMs = 150)).use { h ->
        assertTrue(h.client.start())
        h.next("session.update")
        assertFalse(h.client.awaitReady(1000))
        assertEquals(LiveTranslateError.Code.HANDSHAKE_TIMEOUT, h.nextError().code)
        assertTrue(h.client.awaitClosed(1000))
        assertFalse(h.client.start())
        assertEquals(1, h.server.requestCount)
        assertTrue(h.transportClosed)
    }

    /** 等待方法自己的短超时同样释放握手连接，避免探测任务退出后仍占用云端资源。 */
    @Test fun callerTimeoutCancelsHandshake() = Harness(autoReady = false).use { h ->
        assertTrue(h.client.start())
        h.next("session.update")
        assertFalse(h.client.awaitReady(10))
        assertEquals(LiveTranslateError.Code.HANDSHAKE_TIMEOUT, h.nextError().code)
        assertTrue(h.transportClosed)
    }

    /** 强制关闭会唤醒等待者，关闭后的 socket 回调、字幕和错误不再通知用户。 */
    @Test fun closeCancelsWaitAndIgnoresLateCallbacks() = Harness(autoReady = false).use { h ->
        assertTrue(h.client.start())
        h.next("session.update")
        val result = LinkedBlockingQueue<Boolean>()
        val waiter = Thread { result.put(h.client.awaitReady()) }
        waiter.start()
        h.client.close()
        assertEquals(false, result.poll(2, TimeUnit.SECONDS))
        waiter.join(1000)
        assertFalse(waiter.isAlive)
        val states = h.states.toList()
        val socket = requireNotNull(h.clientSocket.get())
        val listener = requireNotNull(h.clientListener.get())
        listener.onMessage(socket, """{"type":"session.updated"}""")
        listener.onMessage(socket, done("关闭后文本"))
        listener.onFailure(socket, IOException("test-private-key http://user:password@host?token=value"), null)
        listener.onClosed(socket, 1000, "test-private-key")
        h.client.close()
        assertEquals(states, h.states.toList())
        assertTrue(h.errors.isEmpty())
        assertTrue(h.texts.isEmpty())
        assertTrue(h.transportClosed)
    }

    /** 用户在 CONNECTING 回调中取消时不会发出任何网络请求。 */
    @Test fun cancellationFromStateCallbackPreventsNetwork() = Harness(cancelOnConnecting = true).use { h ->
        assertFalse(h.client.start())
        assertEquals(0, h.server.requestCount)
        assertTrue(h.client.awaitClosed(100))
        assertEquals(LiveTranslateState.CLOSED, h.client.state)
    }

    /** 大于有界发送缓冲的编码音频不会发出，随后立即释放连接。 */
    @Test fun boundedBufferRejectsAudioWithoutSendingIt() = Harness(options = LiveTranslateOptions(maxQueuedBytes = 1024)).use { h ->
        h.start()
        assertFalse(h.client.sendPcm16(ByteArray(3200)))
        assertEquals(LiveTranslateError.Code.BUFFER_OVERFLOW, h.nextError().code)
        assertTrue(h.client.awaitClosed(1000))
        assertNull(h.received.poll(100, TimeUnit.MILLISECONDS))
    }

    /** 奇数字节和过大的音频块必须停止，不自动补零或截断语音。 */
    @Test fun malformedPcmIsRejected() {
        for (audio in listOf(ByteArray(0), ByteArray(3), ByteArray(32_002))) Harness().use { h ->
            h.start()
            assertFalse(h.client.sendPcm16(audio))
            assertEquals(LiveTranslateError.Code.INVALID_AUDIO, h.nextError().code)
            assertNull(h.received.poll(100, TimeUnit.MILLISECONDS))
        }
    }

    /** 服务端错误正文可以包含凭据，但向调用方只发本地固定中文错误。 */
    @Test fun serverErrorsNeverExposePayloads() = Harness().use { h ->
        h.start()
        h.send("""{"type":"error","error":{"message":"test-private-key https://user:password@host?token=value audio=AAAA"}}""")
        val error = h.nextError()
        assertEquals(LiveTranslateError.Code.SERVER, error.code)
        assertFalse(error.toString().contains("test-private-key"))
        assertFalse(error.toString().contains("password"))
        assertFalse(error.toString().contains("AAAA"))
        assertTrue(h.client.awaitClosed(1000))
        assertEquals(1, h.server.requestCount)
    }

    /** 识别失败和无效 JSON 都会关闭连接，并保留有区分度的本地错误码。 */
    @Test fun transcriptionAndMalformedEventFailSafely() {
        for ((event, code) in listOf(
            """{"type":"conversation.item.input_audio_transcription.failed","error":{"message":"private"}}""" to LiveTranslateError.Code.TRANSCRIPTION_FAILED,
            "invalid-json-private" to LiveTranslateError.Code.PROTOCOL
        )) Harness().use { h ->
            h.start()
            h.send(event)
            assertEquals(code, h.nextError().code)
            assertTrue(h.client.awaitClosed(1000))
        }
    }

    /** 真实 HTTP 握手失败不重试、不跟随跳转，也不把响应体传给用户。 */
    @Test fun failedUpgradeDoesNotRetryOrFollowRedirects() {
        for (response in listOf(MockResponse().setResponseCode(401).setBody("private-key"),
            MockResponse().setResponseCode(302).setHeader("Location", "https://other.example/private"))) {
            Harness(upgradeFailure = response).use { h ->
                assertTrue(h.client.start())
                assertFalse(h.client.awaitReady(3000))
                assertEquals(LiveTranslateError.Code.NETWORK, h.nextError().code)
                assertEquals(1, h.server.requestCount)
                assertTrue(h.transportClosed)
            }
        }
    }

    /** 鉴权和配额失败给出可操作提示，不把包含凭据的服务端正文交给用户。 */
    @Test fun handshakeErrorsExplainCredentialsAndQuotaWithoutEchoingResponseBody() {
        for ((status, hint) in listOf(401 to "密钥", 403 to "权限", 429 to "配额")) {
            Harness(upgradeFailure = MockResponse().setResponseCode(status).setBody("private-response-secret")).use { h ->
                assertTrue(h.client.start())
                assertFalse(h.client.awaitReady(3000))
                val error = h.nextError()
                assertTrue(error.message.contains(hint))
                assertFalse(error.message.contains("private-response-secret"))
                assertEquals(1, h.server.requestCount)
            }
        }
    }

    /** 构造确认增量和临时尾部，事件号可用于模拟协议重放。 */
    private fun delta(id: String, text: String, stash: String): String = JSONObject()
        .put("type", "response.text.text").put("event_id", id).put("item_id", "translation")
        .put("response_id", "response").put("text", text).put("stash", stash).toString()

    /** 构造完整文本事件，最终成功标记仍由 response.done 决定。 */
    private fun textDone(text: String): String = JSONObject().put("type", "response.text.done")
        .put("item_id", "translation").put("response_id", "response").put("text", text).toString()

    /** 构造已完成响应的完整文本输出。 */
    private fun done(text: String): String = """{"type":"response.done","response":{"id":"response","status":"completed","output":[{"id":"translation","role":"assistant","content":[{"type":"text","text":${JSONObject.quote(text)}}]}]}}"""

    /** 本地真实 WebSocket 场景，安全地址校验后只在测试传输层改写至回环地址。 */
    private class Harness(
        autoReady: Boolean = true,
        source: String? = null,
        model: String = LiveTranslateConfig.DEFAULT_MODEL,
        options: LiveTranslateOptions = LiveTranslateOptions(),
        cancelOnConnecting: Boolean = false,
        upgradeFailure: MockResponse? = null
    ) : AutoCloseable {
        val server = MockWebServer()
        private val network = OkHttpClient.Builder().retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).readTimeout(0, TimeUnit.SECONDS).build()
        private val peer = AtomicReference<WebSocket>()
        val clientSocket = AtomicReference<WebSocket>()
        val clientListener = AtomicReference<WebSocketListener>()
        val received = LinkedBlockingQueue<JSONObject>()
        val texts = LinkedBlockingQueue<LiveTranslationText>()
        val errors = LinkedBlockingQueue<LiveTranslateError>()
        val usages = LinkedBlockingQueue<Pair<String, io.github.korenkrita.simulsub.MeteredUsage>>()
        val states = CopyOnWriteArrayList<LiveTranslateState>()
        @Volatile var transportClosed = false
        val client: QwenLiveTranslateClient

        init {
            server.start()
            server.enqueue(upgradeFailure ?: MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                /** 服务端首先发出默认会话，不能据此开始音频发送。 */
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    peer.set(webSocket)
                    webSocket.send("""{"type":"session.created","session":{}}""")
                }
                /** 捕获客户端真实帧，按测试场景决定是否确认配置。 */
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = JSONObject(text)
                    received.put(message)
                    if (autoReady && message.getString("type") == "session.update") {
                        webSocket.send("""{"type":"session.updated","session":{}}""")
                    }
                }
                /** 回应真实关闭握手，允许验证优雅结束。 */
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, "测试结束")
                }
            }))
            client = QwenLiveTranslateClient(LiveTranslateConfig(apiKey = "test-private-key", sourceLang = source, model = model), object : LiveTranslateListener {
                /** 收集真实响应计量。 */
                override fun onUsage(eventId: String, usage: io.github.korenkrita.simulsub.MeteredUsage) { usages.put(eventId to usage) }
                /** 记录状态，部分场景在连接前同步取消。 */
                override fun onState(state: LiveTranslateState) {
                    states += state
                    if (cancelOnConnecting && state == LiveTranslateState.CONNECTING) cancelClient()
                }
                /** 捕获完整字幕快照，用队列确定事件处理顺序。 */
                override fun onText(text: LiveTranslationText) { texts.put(text) }
                /** 捕获错误，验证原始异常与响应正文不会逸出。 */
                override fun onError(error: LiveTranslateError) { errors.put(error) }
            }, options, object : LiveTranslateTransport {
                /** 真实联网至本地服务器，保留原请求头、路径及模型参数。 */
                override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                    clientListener.set(listener)
                    val target = server.url(request.url.encodedPath).newBuilder().encodedQuery(request.url.encodedQuery).build()
                    return network.newWebSocket(request.newBuilder().url(target).build(), listener).also { clientSocket.set(it) }
                }
                /** 记录资源清理并取消本地传输，隔离其他测试。 */
                override fun close() {
                    transportClosed = true
                    network.dispatcher.cancelAll()
                    network.connectionPool.evictAll()
                    network.dispatcher.executorService.shutdown()
                }
            })
        }

        /** 避免初始化捕获的监听器直接引用尚未赋值的属性。 */
        private fun cancelClient() { client.close() }

        /** 启动并消费握手请求，剩余队列只包含业务帧。 */
        fun start() {
            assertTrue(client.start())
            assertTrue(client.awaitReady(3000))
            next("session.update")
        }

        /** 通过服务端真实连接发送事件，发送失败立即让测试失败。 */
        fun send(text: String) { assertTrue(requireNotNull(peer.get()).send(text)) }

        /** 有界等待下一条客户端帧，不使用无限阻塞。 */
        fun next(type: String): JSONObject {
            val message = received.poll(3, TimeUnit.SECONDS) ?: throw AssertionError("未收到客户端事件：$type")
            assertEquals(type, message.getString("type"))
            return message
        }

        /** 有界等待指定字幕，跳过在途的前序快照。 */
        fun nextText(matches: (LiveTranslationText) -> Boolean): LiveTranslationText {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (System.nanoTime() < until) {
                val text = texts.poll(50, TimeUnit.MILLISECONDS) ?: continue
                if (matches(text)) return text
            }
            throw AssertionError("未收到预期字幕")
        }

        /** 有界等待错误回调。 */
        fun nextError(): LiveTranslateError = errors.poll(3, TimeUnit.SECONDS) ?: throw AssertionError("未收到预期错误")

        /** 清理当前测试全部网络资源，不触碰任何云端服务或文件。 */
        override fun close() {
            client.close()
            // MockWebServer 的服务端 WebSocket 没有客户端 Call，不能调用 cancel。
            peer.get()?.close(1000, "测试结束")
            network.dispatcher.cancelAll()
            network.connectionPool.evictAll()
            network.dispatcher.executorService.shutdown()
            server.close()
        }
    }
}
