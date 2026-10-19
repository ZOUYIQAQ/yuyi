package io.github.korenkrita.simulsub

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 本地HTTP夹具覆盖目录、后缀、候选、超时与取消；不连接付费平台。 */
class TranslationSetupProbeTest {
    /** 目录按当前选择排序，实际翻译成功才返回可保存配置，并保留其他候选供用户切换。 */
    @Test fun discoversCatalogAndChoosesFirstActuallyWorkingModelAndSuffix() {
        MockWebServer().use { server ->
            val models = mutableListOf<String>()
            server.dispatcher = object : Dispatcher() {
                /** v1接口不存在，根接口中第一个模型不存在，第二个模型真实成功。 */
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/v1/models" -> MockResponse().setResponseCode(404)
                    "/models" -> catalog("missing", "usable", "other", "text-embedding-3-small", "qwen-audio-model")
                    "/v1/chat/completions" -> MockResponse().setResponseCode(404)
                    "/chat/completions" -> {
                        val model = org.json.JSONObject(request.body.readUtf8()).getString("model")
                        models += model
                        if (model == "usable") answer() else MockResponse().setResponseCode(404)
                    }
                    else -> MockResponse().setResponseCode(500)
                }
            }
            val result = TranslationSetupProbe(ByokService(server.url("/v1/chat/completions").toString(), "missing", "fake-provider-key"), "字幕翻译")
                .probe(listOf("missing", "usable"))
            assertEquals(server.url("/chat/completions").toString(), result.url)
            assertEquals("usable", result.model)
            assertEquals(listOf("missing", "usable", "other"), result.models)
            assertTrue(result.catalogAvailable)
            assertEquals(listOf("missing", "usable"), models)
            assertNull(result.error)
        }
    }

    /** 不支持目录的服务仍能按预设验证，不把模板列表宣称为已发现目录。 */
    @Test fun unavailableCatalogKeepsPresetsAndCanStillTranslate() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(405))
            server.enqueue(answer())
            val result = TranslationSetupProbe(ByokService(server.url("/").toString(), "preset", ""), "字幕翻译").probe(listOf("next"))
            assertEquals("preset", result.model)
            assertFalse(result.catalogAvailable)
            assertEquals(listOf("preset", "next"), result.models)
        }
    }

    /** 列表列出模型不代表账户有调用权限，鉴权失败不能自动应用任何配置或继续收费遍历。 */
    @Test fun advertisedModelWithoutPermissionNeverBecomesWorkingConfiguration() {
        MockWebServer().use { server ->
            server.enqueue(catalog("one", "two"))
            server.enqueue(MockResponse().setResponseCode(403))
            val result = TranslationSetupProbe(ByokService(server.url("/").toString(), "one", "fake-provider-key"), "字幕翻译").probe(emptyList())
            assertNull(result.url)
            assertNull(result.model)
            assertEquals(2, server.requestCount)
            assertNotNull(result.error)
        }
    }

    /** 完整预设也会尝试同源后缀，但目录重定向绝不跟随到其他主机或端口。 */
    @Test fun catalogRedirectDoesNotLeakCredentialsToAnotherOrigin() {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/models")))
            server.enqueue(answer())
            val result = TranslationSetupProbe(ByokService(server.url("/v1/chat/completions").toString(), "one", "fake-provider-key"), "字幕翻译").probe(emptyList())
            assertEquals("one", result.model)
            assertEquals(0, other.requestCount)
        } }
    }

    /** 已取消的测试不发目录请求；正在等待的目录也能立即停止，停止后不会开始翻译。 */
    @Test fun cancellationStopsCatalogAndAllFutureRequests() {
        MockWebServer().use { server ->
            val entered = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                /** 延迟正文模拟实际网络等待，取消必须中断而非等待完成。 */
                override fun dispatch(request: RecordedRequest): MockResponse {
                    entered.countDown()
                    return catalog("one").setBodyDelay(2, TimeUnit.SECONDS)
                }
            }
            val client = TranslationSetupProbe(ByokService(server.url("/").toString(), "one", ""), "字幕翻译")
            val result = AtomicReference<TranslationSetupResult>()
            val worker = Thread { result.set(client.probe(emptyList())) }.apply { start() }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            client.cancel(); worker.join(1500)
            assertFalse(worker.isAlive)
            assertEquals("连接测试已取消", result.get().error)
            assertEquals(1, server.requestCount)
            client.probe(emptyList())
            assertEquals(1, server.requestCount)
        }
    }

    /** 总时限覆盖目录和全部模型探测，慢响应不能让引导一直转圈。 */
    @Test fun totalDeadlineCancelsSlowCatalog() {
        MockWebServer().use { server ->
            server.enqueue(catalog("one").setBodyDelay(2, TimeUnit.SECONDS))
            val result = TranslationSetupProbe(ByokService(server.url("/").toString(), "one", ""), "字幕翻译", timeoutMs = 150)
                .probe(emptyList())
            assertNull(result.url)
            assertEquals("测试超时，请稍后重试", result.error)
            assertEquals(1, server.requestCount)
        }
    }

    /** 有效空目录不偷偷改用模板，更不会把非文字模型选为翻译服务。 */
    @Test fun emptyTextCatalogDoesNotFallBackToUnadvertisedModel() {
        MockWebServer().use { server ->
            server.enqueue(catalog("text-embedding-3-small", "qwen-audio-model"))
            val result = TranslationSetupProbe(ByokService(server.url("/").toString(), "preset", ""), "字幕翻译").probe(emptyList())
            assertTrue(result.catalogAvailable)
            assertTrue(result.models.isEmpty())
            assertNull(result.url)
            assertEquals(1, server.requestCount)
        }
    }

    /** v4等供应商明确路径必须原样使用，不能机械追加v1而破坏可用地址。 */
    @Test fun explicitNonV1ProviderPathRemainsIntact() {
        MockWebServer().use { server ->
            server.enqueue(catalog("one")); server.enqueue(answer())
            val endpoint = server.url("/api/paas/v4/chat/completions").toString()
            val result = TranslationSetupProbe(ByokService(endpoint, "one", ""), "字幕翻译").probe(emptyList())
            assertEquals(endpoint, result.url)
            assertEquals("/api/paas/v4/models", server.takeRequest().path)
            assertEquals("/api/paas/v4/chat/completions", server.takeRequest().path)
        }
    }

    /** 所有参数均被拒绝时不能仅凭探测结束就宣称模型可用。 */
    @Test fun rejectedParametersWithoutAnyTranslationNeverPass() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                /** 目录合法，但所有真实翻译请求均失败。 */
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.method == "GET") catalog("one") else MockResponse().setResponseCode(400)
            }
            val result = TranslationSetupProbe(ByokService(server.url("/").toString(), "one", ""), "字幕翻译").probe(emptyList())
            assertNull(result.url)
            assertNull(result.model)
            assertNotNull(result.error)
        }
    }

    /** 模型一直只有思考内容而没有译文时，即使HTTP 200且正常结束也不适合自动启用。 */
    @Test fun thinkingWithoutTranslationNeverPasses() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                /** 每次翻译仅返回推理，不返回用户能够阅读的正文。 */
                override fun dispatch(request: RecordedRequest): MockResponse = if (request.method == "GET") catalog("one") else
                    MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                        "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"仍在思考\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
            }
            val result = TranslationSetupProbe(ByokService(server.url("/").toString(), "one", ""), "字幕翻译").probe(emptyList())
            assertNull(result.url)
            assertNull(result.model)
            assertNotNull(result.error)
        }
    }

    /** 账户目录很大时也只验证有限模型，避免测试按钮无界地产生收费请求。 */
    @Test fun unavailableModelsRespectAttemptBudget() {
        MockWebServer().use { server ->
            val tested = mutableSetOf<String>()
            server.dispatcher = object : Dispatcher() {
                /** 每个模型都不存在；集合记录实际收费尝试范围。 */
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "GET") return catalog(*(1..30).map { "model-$it" }.toTypedArray())
                    tested += org.json.JSONObject(request.body.readUtf8()).getString("model")
                    return MockResponse().setResponseCode(404)
                }
            }
            val result = TranslationSetupProbe(ByokService(server.url("/").toString(), "model-1", ""), "字幕翻译").probe(emptyList())
            assertNull(result.url)
            assertEquals(4, tested.size)
            assertEquals(30, result.models.size)
        }
    }

    /** 夹具使用标准data/id目录；所有模型名都只是测试数据。 */
    private fun catalog(vararg models: String): MockResponse = MockResponse().setHeader("Content-Type", "application/json")
        .setBody(org.json.JSONObject().put("data", org.json.JSONArray(models.map { mapOf("id" to it) })).toString())

    /** 流式回复包含正文和结束原因，不能拿HTTP 200或模型目录冒充测试通过。 */
    private fun answer(): MockResponse = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"花朵绽放出来吧\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
}
