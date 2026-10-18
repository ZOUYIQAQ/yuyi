package io.github.korenkrita.simulsub.online

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 本地 WebSocket 协议测试，不连接云端或使用真实凭据。 */
class QwenStreamingAsrClientTest {
    /** 官方 result-generated 是任务累计值；重复、缺字段和倒退值不重复计费。 */
    @Test fun cumulativeUsageKeepsKnownValuesUntilCancellation() = Harness().use { h ->
        assertTrue(h.client.start())
        val task = h.nextTextFrame().getJSONObject("header").getString("task_id")
        h.send("task-started", task)
        assertTrue(h.client.awaitReady(3000))
        assertEquals(CloudAsrUsage(null, null, null), h.usages.poll(3, TimeUnit.SECONDS))
        h.send("result-generated", task, """{"usage":{"input_tokens":10,"output_tokens":0,"duration":0}}""")
        assertEquals(CloudAsrUsage(0, 10, 0), h.usages.poll(3, TimeUnit.SECONDS))
        h.send("result-generated", task, """{"usage":{"input_tokens":10,"output_tokens":0}}""")
        h.send("result-generated", task, """{"usage":{"input_tokens":12}}""")
        assertEquals(CloudAsrUsage(0, 12, 0), h.usages.poll(3, TimeUnit.SECONDS))
        h.send("result-generated", task, """{"usage":{"input_tokens":5,"output_tokens":1}}""")
        assertEquals(CloudAsrUsage(0, 12, 1), h.usages.poll(3, TimeUnit.SECONDS))
        h.client.close()
        h.listener.get()?.onMessage(requireNotNull(h.clientSocket.get()), JSONObject()
            .put("header", JSONObject().put("task_id", task).put("event", "task-finished"))
            .put("payload", JSONObject().put("usage", JSONObject().put("input_tokens", 99)
                .put("output_tokens", 99))).toString())
        assertNull(h.usages.poll(100, TimeUnit.MILLISECONDS))
    }

    /** 配置只开放已实现型号，并校验 WSS、上下文和即时热词。 */
    @Test fun configValidationAndSafeString() {
        val valid = CloudAsrConfig(apiKey = "test-private-key", context = "领域词", vocabulary = mapOf("专有名词" to 5))
        assertTrue(valid.validationErrors().isEmpty())
        assertTrue(valid.copy(sourceLanguage = "auto").validationErrors().isEmpty())
        assertFalse(valid.toString().contains("test-private-key"))
        assertEquals(listOf(CloudAsrConfig.DEFAULT_MODEL), CloudAsrConfig.SUPPORTED_MODELS)
        val invalid = valid.copy(endpoint = "ws://example.com/api-ws/v1/inference", model = "other",
            sourceLanguage = "xx", context = "字".repeat(401), vocabulary = mapOf("词" to 0))
        assertEquals(5, invalid.validationErrors().size)
    }

    /** task-started 前不发 PCM；确认后发送二进制，最终句使用整句 text 与稳定 sentence_id。 */
    @Test fun protocolAndSentenceUpdates() = Harness().use { h ->
        assertTrue(h.client.start())
        assertFalse(h.client.sendPcm16(byteArrayOf(1, 0)))
        val start = h.nextTextFrame()
        val header = start.getJSONObject("header")
        assertEquals("run-task", header.getString("action"))
        assertEquals("duplex", header.getString("streaming"))
        val payload = start.getJSONObject("payload")
        assertEquals(CloudAsrConfig.DEFAULT_MODEL, payload.getString("model"))
        val parameters = payload.getJSONObject("parameters")
        assertEquals("pcm", parameters.getString("format"))
        assertEquals(16000, parameters.getInt("sample_rate"))
        assertEquals(400, parameters.getInt("max_sentence_silence"))
        assertTrue(parameters.getBoolean("multi_threshold_mode_enabled"))
        assertEquals("ja", parameters.getJSONArray("language_hints").getString(0))
        assertTrue(parameters.getBoolean("heartbeat"))
        assertEquals(5, parameters.getJSONObject("vocabulary").getInt("固有名詞"))
        assertEquals("アニメ", payload.getJSONObject("input").getJSONArray("context")
            .getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"))
        val request = h.server.takeRequest(3, TimeUnit.SECONDS)
        assertEquals("/api-ws/v1/inference", request?.path)
        assertEquals("Bearer test-private-key", request?.getHeader("Authorization"))

        val task = header.getString("task_id")
        h.send("task-started", task)
        assertTrue(h.client.awaitReady(3000))
        assertEquals(CloudAsrUsage(null, null, null), h.usages.poll(3, TimeUnit.SECONDS))
        assertTrue(h.client.sendPcm16(byteArrayOf(1, 0, 2, 0)))
        assertArrayEquals(byteArrayOf(1, 0, 2, 0), h.binary.poll(3, TimeUnit.SECONDS)?.toByteArray())
        h.send("result-generated", task, """{"output":{"sentence":{"heartbeat":true,"sentence_id":0,"text":""}}}""")
        h.send("result-generated", task, """{"output":{"sentence":{"sentence_id":1,"text":"こん","sentence_end":false,"words":[{"text":"ん"}]}}}""")
        h.send("result-generated", task, """{"output":{"sentence":{"sentence_id":1,"text":"こんにちは。","sentence_end":true,"words":[{"text":"は","punctuation":"。"}]}},"usage":{"duration":2,"input_tokens":85,"output_tokens":4}}""")
        val partial = h.texts.poll(3, TimeUnit.SECONDS) ?: throw AssertionError("缺少中间结果")
        val final = h.texts.poll(3, TimeUnit.SECONDS) ?: throw AssertionError("缺少最终结果")
        assertEquals("こん", partial.text)
        assertFalse(partial.isFinal)
        assertEquals("こんにちは。", final.text)
        assertTrue(final.isFinal)
        assertEquals(partial.segmentId, final.segmentId)
        assertNull(h.texts.poll(100, TimeUnit.MILLISECONDS))
        assertEquals(CloudAsrUsage(2, 85, 4), h.usages.poll(3, TimeUnit.SECONDS))

        assertFalse(h.client.finished)
        assertTrue(h.client.finishAudio())
        assertTrue(h.client.finishAudio())
        assertFalse(h.client.sendPcm16(byteArrayOf(0, 0)))
        val finish = h.nextTextFrame()
        assertEquals("finish-task", finish.getJSONObject("header").getString("action"))
        assertEquals(task, finish.getJSONObject("header").getString("task_id"))
        assertEquals(0, finish.getJSONObject("payload").getJSONObject("input").length())
        h.send("result-generated", task, """{"output":{"sentence":{"sentence_id":2,"text":"尾段","sentence_end":true}}}""")
        assertEquals("尾段", h.texts.poll(3, TimeUnit.SECONDS)?.text)
        h.send("task-finished", task, """{"usage":{"duration":9,"input_tokens":365,"output_tokens":12}}""")
        assertTrue(h.client.awaitFinished(3000))
        assertTrue(h.client.finished)
        assertEquals(CloudAsrUsage(9, 365, 12), h.usages.poll(3, TimeUnit.SECONDS))
        assertTrue(h.awaitStatus("听写已完成"))
        assertTrue(h.errors.isEmpty())
    }

    /** auto 不发送语种提示；非心跳的 sentence_id=0 仍是合法句子。 */
    @Test fun autoLanguageAndZeroSentenceId() = Harness(config = CloudAsrConfig(
        apiKey = "test-private-key", sourceLanguage = "auto")).use { h ->
        assertTrue(h.client.start())
        val start = h.nextTextFrame()
        val parameters = start.getJSONObject("payload").getJSONObject("parameters")
        assertFalse(parameters.has("language_hints"))
        assertEquals(400, parameters.getInt("max_sentence_silence"))
        assertTrue(parameters.getBoolean("multi_threshold_mode_enabled"))
        val task = start.getJSONObject("header").getString("task_id")
        h.send("task-started", task)
        assertTrue(h.client.awaitReady(3000))
        h.send("result-generated", task, """{"output":{"sentence":{"sentence_id":0,"text":"心跳内容","heartbeat":true,"sentence_end":false}}}""")
        h.send("result-generated", task, """{"output":{"sentence":{"sentence_id":0,"text":"最初","sentence_end":false}}}""")
        h.send("result-generated", task, """{"output":{"sentence":{"sentence_id":0,"text":"最初の文。","sentence_end":true}}}""")
        val partial = h.texts.poll(3, TimeUnit.SECONDS) ?: throw AssertionError("缺少首句中间结果")
        val final = h.texts.poll(3, TimeUnit.SECONDS) ?: throw AssertionError("缺少首句最终结果")
        assertEquals("最初", partial.text)
        assertEquals("最初の文。", final.text)
        assertEquals(partial.segmentId, final.segmentId)
        assertTrue(final.isFinal)
        assertNull(h.texts.poll(100, TimeUnit.MILLISECONDS))
    }

    /** 服务端失败、短等待超时和主动取消均停止会话；迟到事件不触发回调。 */
    @Test fun failureTimeoutAndCancellation() {
        Harness().use { h ->
            assertTrue(h.client.start())
            val task = h.nextTextFrame().getJSONObject("header").getString("task_id")
            h.send("task-started", task)
            assertTrue(h.client.awaitReady(3000))
            assertEquals(CloudAsrUsage(null, null, null), h.usages.poll(3, TimeUnit.SECONDS))
            assertTrue(h.client.finishAudio())
            h.nextTextFrame()
            h.send("task-failed", task)
            assertFalse(h.client.awaitFinished(3000))
            assertFalse(h.client.finished)
            assertTrue((h.errors.poll(3, TimeUnit.SECONDS) ?: "").contains("听写任务失败"))
            assertFalse(h.client.sendPcm16(byteArrayOf(0, 0)))
        }
        Harness().use { h ->
            assertTrue(h.client.start())
            h.nextTextFrame()
            assertFalse(h.client.awaitReady(30))
            assertTrue((h.errors.poll(3, TimeUnit.SECONDS) ?: "").contains("超时"))
            assertTrue(h.usages.isEmpty())
        }
        Harness().use { h ->
            assertTrue(h.client.start())
            val task = h.nextTextFrame().getJSONObject("header").getString("task_id")
            h.client.close()
            val previous = h.statuses.size
            h.listener.get()?.onMessage(requireNotNull(h.clientSocket.get()),
                JSONObject().put("header", JSONObject().put("task_id", task).put("event", "task-started")).toString())
            assertFalse(h.client.awaitReady(10))
            assertEquals(previous, h.statuses.size)
            assertTrue(h.errors.isEmpty())
            assertTrue(h.texts.isEmpty())
            assertTrue(h.usages.isEmpty())
        }
    }

    /** 结束等待超时会报告失败并释放连接，关闭后迟到用量不会回调。 */
    @Test fun finishTimeoutAndLateUsage() = Harness().use { h ->
        assertTrue(h.client.start())
        val task = h.nextTextFrame().getJSONObject("header").getString("task_id")
        h.send("task-started", task)
        assertTrue(h.client.awaitReady(3000))
        assertEquals(CloudAsrUsage(null, null, null), h.usages.poll(3, TimeUnit.SECONDS))
        assertTrue(h.client.finishAudio())
        h.nextTextFrame()
        assertFalse(h.client.awaitFinished(30))
        assertFalse(h.client.finished)
        assertTrue((h.errors.poll(3, TimeUnit.SECONDS) ?: "").contains("结束超时"))
        val statusCount = h.statuses.size
        h.listener.get()?.onMessage(requireNotNull(h.clientSocket.get()), JSONObject()
            .put("header", JSONObject().put("task_id", task).put("event", "task-finished"))
            .put("payload", JSONObject().put("usage", JSONObject().put("duration", 10))).toString())
        assertEquals(statusCount, h.statuses.size)
        assertTrue(h.usages.isEmpty())
    }

    /** 主动取消正在结束的会话会唤醒完成等待，并保持 finished=false。 */
    @Test fun cancellationWakesFinishedWaiter() = Harness().use { h ->
        assertTrue(h.client.start())
        val task = h.nextTextFrame().getJSONObject("header").getString("task_id")
        h.send("task-started", task)
        assertTrue(h.client.awaitReady(3000))
        assertEquals(CloudAsrUsage(null, null, null), h.usages.poll(3, TimeUnit.SECONDS))
        assertTrue(h.client.finishAudio())
        h.nextTextFrame()
        val waiting = LinkedBlockingQueue<Boolean>()
        val waiter = Thread { waiting.put(h.client.awaitFinished(3000)) }
        waiter.start()
        h.client.close()
        assertEquals(false, waiting.poll(3, TimeUnit.SECONDS))
        waiter.join(1000)
        assertFalse(waiter.isAlive)
        assertFalse(h.client.finished)
        assertTrue(h.errors.isEmpty())
        assertTrue(h.usages.isEmpty())
    }

    /** close 等正在执行的失败回调结束，返回后迟到事件不能新增回调。 */
    @Test fun closeWaitsForFailureCallbackAndBlocksLateCallbacks() {
        val entered = CountDownLatch(1)
        val allowReturn = CountDownLatch(1)
        Harness(onFailureHook = {
            entered.countDown()
            allowReturn.await(3, TimeUnit.SECONDS)
        }).use { h ->
            assertTrue(h.client.start())
            val task = h.nextTextFrame().getJSONObject("header").getString("task_id")
            h.send("task-started", task)
            assertTrue(h.client.awaitReady(3000))
            assertEquals(CloudAsrUsage(null, null, null), h.usages.poll(3, TimeUnit.SECONDS))
            h.send("task-failed", task)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val closeReturned = CountDownLatch(1)
            val closer = Thread { h.client.close(); closeReturned.countDown() }
            closer.start()
            try {
                assertFalse(closeReturned.await(50, TimeUnit.MILLISECONDS))
            } finally {
                allowReturn.countDown()
            }
            assertTrue(closeReturned.await(3, TimeUnit.SECONDS))
            closer.join(1000)
            assertEquals(1, h.errors.size)
            val statusCount = h.statuses.size
            h.listener.get()?.onMessage(requireNotNull(h.clientSocket.get()), JSONObject()
                .put("header", JSONObject().put("task_id", task).put("event", "result-generated"))
                .put("payload", JSONObject().put("usage", JSONObject().put("duration", 99))).toString())
            assertEquals(statusCount, h.statuses.size)
            assertTrue(h.usages.isEmpty())
            assertTrue(h.texts.isEmpty())
            assertEquals(1, h.errors.size)
        }
    }

    /** HTTP 401 握手失败应给出中文鉴权错误。 */
    @Test fun authenticationFailure() = Harness(MockResponse().setResponseCode(401)).use { h ->
        assertTrue(h.client.start())
        assertFalse(h.client.awaitReady(3000))
        assertTrue((h.errors.poll(3, TimeUnit.SECONDS) ?: "").contains("鉴权失败"))
    }

    /** 真实 MockWebServer WebSocket 传输保留生产请求路径和认证头。 */
    private class Harness(
        response: MockResponse? = null,
        config: CloudAsrConfig = CloudAsrConfig(apiKey = "test-private-key",
            context = "アニメ", vocabulary = mapOf("固有名詞" to 5)),
        onFailureHook: ((String) -> Unit)? = null
    ) : AutoCloseable {
        val server = MockWebServer()
        private val network = OkHttpClient.Builder().retryOnConnectionFailure(false)
            .readTimeout(0, TimeUnit.SECONDS).build()
        private val peer = AtomicReference<WebSocket>()
        val clientSocket = AtomicReference<WebSocket>()
        val listener = AtomicReference<WebSocketListener>()
        val frames = LinkedBlockingQueue<String>()
        val binary = LinkedBlockingQueue<ByteString>()
        val texts = LinkedBlockingQueue<CloudAsrText>()
        val statuses = LinkedBlockingQueue<String>()
        val errors = LinkedBlockingQueue<String>()
        val usages = LinkedBlockingQueue<CloudAsrUsage>()
        val client: QwenStreamingAsrClient

        init {
            server.start()
            server.enqueue(response ?: MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                /** 保存服务端连接，用于按协议顺序推送事件。 */
                override fun onOpen(webSocket: WebSocket, response: Response) { peer.set(webSocket) }
                /** 捕获客户端控制帧。 */
                override fun onMessage(webSocket: WebSocket, text: String) { frames.put(text) }
                /** 捕获真正的二进制 PCM 帧。 */
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) { binary.put(bytes) }
                /** 响应正常关闭握手。 */
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, "测试完成")
                }
            }))
            client = QwenStreamingAsrClient(config,
                { texts.put(it) }, { statuses.put(it) }, { onFailureHook?.invoke(it); errors.put(it) }, object : CloudAsrTransport {
                    /** 用本地服务器替换目标，保留生产路径与请求头。 */
                    override fun newWebSocket(request: Request, socketListener: WebSocketListener): WebSocket {
                        listener.set(socketListener)
                        val target = server.url(request.url.encodedPath)
                        return network.newWebSocket(request.newBuilder().url(target).build(), socketListener)
                            .also { clientSocket.set(it) }
                    }
                    /** 关闭本地独占网络资源。 */
                    override fun close() {
                        network.dispatcher.cancelAll()
                        network.connectionPool.evictAll()
                        network.dispatcher.executorService.shutdown()
                    }
                }, { usages.put(it) })
        }

        /** 获取客户端下一条 JSON 控制帧。 */
        fun nextTextFrame(): JSONObject = JSONObject(frames.poll(3, TimeUnit.SECONDS)
            ?: throw AssertionError("未收到客户端控制帧"))

        /** 发送带任务 ID 的官方服务端事件。 */
        fun send(event: String, task: String, payload: String = "{}") {
            val frame = JSONObject().put("header", JSONObject().put("task_id", task).put("event", event))
                .put("payload", JSONObject(payload)).toString()
            assertTrue(requireNotNull(peer.get()).send(frame))
        }

        /** 在有界时间内等待特定状态。 */
        fun awaitStatus(expected: String): Boolean {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (System.nanoTime() < until) {
                if (statuses.poll(50, TimeUnit.MILLISECONDS) == expected) return true
            }
            return false
        }

        /** 释放本地测试连接与 MockWebServer。 */
        override fun close() {
            client.close()
            peer.get()?.close(1000, "测试完成")
            network.dispatcher.cancelAll()
            network.connectionPool.evictAll()
            network.dispatcher.executorService.shutdown()
            server.close()
        }
    }
}
