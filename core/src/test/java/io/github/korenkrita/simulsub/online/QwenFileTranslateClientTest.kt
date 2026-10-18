package io.github.korenkrita.simulsub.online

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 用本地 HTTP 服务验证文件听译协议，不接触付费接口。 */
class QwenFileTranslateClientTest {
    /** 空 choices 的尾帧也要上报真实音频用量，重复尾帧只记一个片段。 */
    @Test fun usageTailIsReportedOnce() = Harness().use { h ->
        val tail = "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":415,\"completion_tokens\":51," +
            "\"prompt_tokens_details\":{\"audio_tokens\":415,\"cached_tokens\":0}}}\n\n"
        h.server.enqueue(MockResponse().setBody(sse("译文") + finish("stop") + tail + tail + "data: [DONE]\n\n"))
        assertTrue(h.client.translatePcm16(ByteArray(3200), "segment-usage"))
        assertEquals("segment-usage", h.usages.single().first)
        assertEquals(415L, h.usages.single().second.inputTokens)
        assertEquals(51L, h.usages.single().second.outputTokens)
        assertEquals(415L, h.usages.single().second.audioInputTokens)
        assertEquals(0L, h.usages.single().second.cachedInputTokens)
        val request = JSONObject(h.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
        assertTrue(request.getJSONObject("stream_options").getBoolean("include_usage"))
    }

    /** 早期空值或零值快照不能遮住尾帧真实用量；重复累计帧不相加，缺失字段保留已知值。 */
    @Test fun laterUsageSnapshotsReplaceEmptyAndMergeKnownFieldsOnce() = Harness().use { h ->
        val early = "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0}}\n\n"
        val partial = "data: {\"choices\":[],\"usage\":{\"prompt_tokens_details\":{\"audio_tokens\":75,\"cached_tokens\":0}}}\n\n"
        val final = "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":30," +
            "\"prompt_tokens_details\":{\"audio_tokens\":100}}}\n\n"
        h.server.enqueue(MockResponse().setBody("data: {\"choices\":[],\"usage\":{}}\n\n" +
            early + partial + sse("译文") + finish("stop") + final + final + "data: [DONE]\n\n"))
        assertTrue(h.client.translatePcm16(ByteArray(3200), "segment-merged"))
        assertEquals(1, h.usages.size)
        val usage = h.usages.single().second
        assertEquals(120L, usage.inputTokens)
        assertEquals(30L, usage.outputTokens)
        assertEquals(100L, usage.audioInputTokens)
        assertEquals(0L, usage.cachedInputTokens)
    }

    /** 服务端省略 usage 时仍留下该请求的未知用量。 */
    @Test fun absentUsageStaysUnknown() = Harness().use { h ->
        h.server.enqueue(MockResponse().setBody(finish("stop") + "data: [DONE]\n\n"))
        assertTrue(h.client.translatePcm16(ByteArray(3200), "unknown"))
        assertEquals(io.github.korenkrita.simulsub.MeteredUsage(), h.usages.single().second)
    }
    /** 探测仅 GET 同一基础地址的模型目录，不发送音频，并核对所选模型。 */
    @Test fun probeChecksModelWithoutAudio() = Harness().use { h ->
        h.server.enqueue(MockResponse().setBody("""{"data":[{"id":"qwen3-livetranslate-flash"}]}"""))
        assertTrue(h.client.probe())
        val request = h.server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/compatible-mode/v1/models", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertEquals(0L, request.body.size)
        assertTrue(h.texts.isEmpty())
    }

    /** 缺少所选模型时探测失败，仍不得调用付费生成接口。 */
    @Test fun probeRejectsMissingModel() = Harness().use { h ->
        h.server.enqueue(MockResponse().setBody("""{"data":[{"id":"other-model"}]}"""))
        assertFalse(h.client.probe())
        assertEquals(1, h.server.requestCount)
        assertEquals(LiveTranslateError.Code.RESPONSE_FAILED, h.errors.single().code)
    }

    /** 请求是单条用户音频、规范 WAV 与语言参数，SSE 增量累加后才确认定稿。 */
    @Test fun sendsWavAndAccumulatesSse() = Harness(source = "ja").use { h ->
        h.server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(sse("你") + sse("好") + finish("stop") + "data: [DONE]\n\n"))
        val pcm = ByteArray(3200) { it.toByte() }
        assertTrue(h.client.translatePcm16(pcm, "seg-1"))
        val request = h.server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/compatible-mode/v1/chat/completions", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        val json = JSONObject(request.body.readUtf8())
        assertEquals("qwen3-livetranslate-flash", json.getString("model"))
        assertTrue(json.getBoolean("stream"))
        assertEquals("text", json.getJSONArray("modalities").getString(0))
        assertEquals(1, json.getJSONArray("modalities").length())
        assertFalse(json.has("system"))
        val messages = json.getJSONArray("messages")
        assertEquals(1, messages.length())
        val user = messages.getJSONObject(0)
        assertEquals("user", user.getString("role"))
        val part = user.getJSONArray("content").getJSONObject(0)
        assertEquals("input_audio", part.getString("type"))
        val input = part.getJSONObject("input_audio")
        assertEquals("wav", input.getString("format"))
        val data = input.getString("data")
        assertTrue(data.startsWith("data:audio/wav;base64,"))
        val wav = Base64.getDecoder().decode(data.removePrefix("data:audio/wav;base64,"))
        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + pcm.size, header.getInt(4))
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(16000, header.getInt(24))
        assertEquals(32000, header.getInt(28))
        assertEquals(16, header.getShort(34).toInt())
        assertEquals(pcm.size, header.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
        val languages = json.getJSONObject("translation_options")
        assertEquals("ja", languages.getString("source_lang"))
        assertEquals("zh", languages.getString("target_lang"))
        assertEquals(listOf("你", "你好", "你好"), h.texts.map { it.translatedText })
        assertEquals(listOf(false, false, true), h.texts.map { it.isFinal })
        assertTrue(h.texts.all { it.segmentId == "seg-1" && it.sourceText.isEmpty() && it.confirmedText == it.translatedText })
    }

    /** 自动识别源语言时省略字段；连续片段复用同一客户端并串行请求。 */
    @Test fun autoLanguageAndSequentialSegments() = Harness().use { h ->
        repeat(2) { h.server.enqueue(MockResponse().setBody(sse("译文") + finish("stop") + "data: [DONE]\n\n")) }
        assertTrue(h.client.translatePcm16(ByteArray(3200), "a"))
        assertTrue(h.client.translatePcm16(ByteArray(3200), "b"))
        repeat(2) {
            val json = JSONObject(h.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            assertFalse(json.getJSONObject("translation_options").has("source_lang"))
        }
        assertEquals(listOf("a", "a", "b", "b"), h.texts.map { it.segmentId })
    }

    /** 背景音乐等有效请求可能没有可翻译文字，完整空响应不能错误中止整场听译。 */
    @Test fun emptySuccessfulResponseDoesNotStopListening() = Harness().use { h ->
        h.server.enqueue(MockResponse().setBody(finish("stop") + "data: [DONE]\n\n"))
        assertTrue(h.client.translatePcm16(ByteArray(3200), "music"))
        assertTrue(h.errors.isEmpty())
        assertTrue(h.texts.single().isFinal)
        assertEquals("", h.texts.single().translatedText)
    }

    /** 未收到 stop 与 DONE、长度截断及服务端错误均不得产生最终字幕。 */
    @Test fun incompleteStreamsNeverBecomeFinal() {
        for (body in listOf(sse("部分"), sse("部分") + finish("stop"),
            sse("部分") + finish("length") + "data: [DONE]\n\n",
            "data: {\"error\":{\"message\":\"private\"}}\n\n")) Harness().use { h ->
            h.server.enqueue(MockResponse().setBody(body))
            assertFalse(h.client.translatePcm16(ByteArray(3200), "seg"))
            assertTrue(h.texts.none { it.isFinal })
            assertEquals(1, h.errors.size)
            assertFalse(h.errors.single().message.contains("private"))
        }
    }

    /** 格式错误及过长响应都要有界失败，不将任何临时字幕标记为最终结果。 */
    @Test fun malformedAndOversizedEventsFailSafely() {
        for (body in listOf("data: {invalid-json}\n\n", ":" + "x".repeat(65_536) + "\n\n")) Harness().use { h ->
            h.server.enqueue(MockResponse().setBody(body))
            assertFalse(h.client.translatePcm16(ByteArray(3200), "seg"))
            assertTrue(h.texts.none { it.isFinal })
            assertEquals(1, h.errors.size)
        }
    }

    /** 无效片段不发网络请求；跳转不能跟随，避免密钥跨主机泄露。 */
    @Test fun rejectsBadAudioAndRedirect() = Harness().use { h ->
        for (audio in listOf(ByteArray(3198), ByteArray(3), ByteArray(384_002))) {
            assertFalse(h.client.translatePcm16(audio, "seg"))
        }
        assertEquals(0, h.server.requestCount)
        h.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://other.example/"))
        assertFalse(h.client.translatePcm16(ByteArray(3200), "seg"))
        assertEquals(1, h.server.requestCount)
    }

    /** close 取消阻塞请求；返回后不会再收到字幕或错误。 */
    @Test fun closeCancelsPendingCallAndSuppressesCallbacks() = Harness().use { h ->
        val waiting = CountDownLatch(1)
        h.server.enqueue(MockResponse().setHeadersDelay(2, TimeUnit.SECONDS).setBody(sse("迟到") + finish("stop") + "data: [DONE]\n\n"))
        val worker = Thread { waiting.countDown(); assertFalse(h.client.translatePcm16(ByteArray(3200), "seg")) }
        worker.start()
        assertTrue(waiting.await(2, TimeUnit.SECONDS))
        assertNotNull(h.server.takeRequest(2, TimeUnit.SECONDS))
        h.client.close()
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertTrue(h.texts.isEmpty())
        assertTrue(h.errors.isEmpty())
        assertEquals(io.github.korenkrita.simulsub.MeteredUsage(), h.usages.single().second)
    }

    /** 构造一条增量事件。 */
    private fun sse(text: String) = "data: " + JSONObject().put("choices", org.json.JSONArray()
        .put(JSONObject().put("delta", JSONObject().put("content", text)))).toString() + "\n\n"

    /** 构造结束事件，仍需随后收到 DONE 才可定稿。 */
    private fun finish(reason: String) = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"$reason\"}]}\n\n"

    /** 将原始安全地址在测试客户端中改写为本地 MockWebServer。 */
    private class Harness(source: String? = null) : AutoCloseable {
        val server = MockWebServer().apply { start() }
        val texts = CopyOnWriteArrayList<LiveTranslationText>()
        val errors = CopyOnWriteArrayList<LiveTranslateError>()
        val usages = CopyOnWriteArrayList<Pair<String, io.github.korenkrita.simulsub.MeteredUsage>>()
        private val network = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("https", chain.request().url.scheme)
            val local = server.url(chain.request().url.encodedPath)
            chain.proceed(chain.request().newBuilder().url(local).build())
        }.build()
        val client = QwenFileTranslateClient(
            LiveTranslateConfig(endpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                model = "qwen3-livetranslate-flash", apiKey = "test-key", sourceLang = source),
            object : LiveTranslateListener {
                /** 保存片段计量。 */
                override fun onUsage(eventId: String, usage: io.github.korenkrita.simulsub.MeteredUsage) { usages += eventId to usage }
                /** 保存字幕快照。 */
                override fun onText(text: LiveTranslationText) { texts += text }
                /** 保存本地错误说明。 */
                override fun onError(error: LiveTranslateError) { errors += error }
            }, network
        )

        /** 关闭本次测试持有的资源。 */
        override fun close() {
            client.close()
            network.dispatcher.cancelAll()
            network.connectionPool.evictAll()
            network.dispatcher.executorService.shutdown()
            server.close()
        }
    }
}
