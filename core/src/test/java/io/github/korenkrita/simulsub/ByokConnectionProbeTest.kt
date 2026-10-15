package io.github.korenkrita.simulsub

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

/** 连接探测仅注入本地结果，不访问外部服务。 */
class ByokConnectionProbeTest {
    private val base = "https://api.example.com:8443/api"
    private val primary = "$base/v1/chat/completions"
    private val alternate = "$base/chat/completions"

    /** 创建独立探测实例，配置地址故意与输入不同以验证使用输入地址。 */
    private fun translator() = ByokTranslator(ByokService("https://saved.example.com/v1/chat/completions", "模型", ""), "测试", null)

    /** 构造完整无思考回复或指定 HTTP 错误。 */
    private fun result(http: Int = 200, thinking: Boolean = false) =
        MtResult(if (http == 200) "你好" else "", if (http == 200) 'e' else 'f', null, null,
            http = http, error = if (http == 200) null else "HTTP $http", thinking = thinking)

    /** 用户明确填写的完整接口路径不得再插入 v1，包括自定义版本和编码路径。 */
    @Test fun explicitChatEndpointKeepsItsEntirePath() {
        for (path in listOf("/chat/completions", "/api/v3/chat/completions", "/api/openai/chat/completions", "/a%2Fb/chat/completions")) {
            val endpoint = "https://h.io:8443$path"
            assertEquals(endpoint, ByokTranslator.chatUrl(" $endpoint/ ").second)
            assertEquals(listOf(endpoint), ByokTranslator.chatCandidates(endpoint).second)
        }
    }

    /** 普通基础地址仍补一次 v1，末尾已有 v1 时沿用该路径。 */
    @Test fun basesRetainTheExistingV1Default() {
        for (input in listOf(base, "$base/", "$base/v1", "$base/v1/")) {
            assertEquals(primary, ByokTranslator.chatUrl(input).second)
            assertEquals(listOf(primary, alternate), ByokTranslator.chatCandidates(input).second)
        }
        assertEquals(listOf("https://h.io/v1/chat/completions", "https://h.io/chat/completions"),
            ByokTranslator.chatCandidates("https://h.io").second)
    }

    /** 候选最多两个且去重，路径拼接始终保留协议、域名和有效端口。 */
    @Test fun candidatesAreOrderedDistinctAndSameOrigin() {
        for (input in listOf(base, "$base/v1", "HTTPS://H.io:443/team/v1/", "https://[::1]:8443/team")) {
            val (error, candidates) = ByokTranslator.chatCandidates(input)
            assertNull(error)
            assertEquals(2, candidates.size)
            assertEquals(candidates.size, candidates.distinct().size)
            val origin = URI(input)
            for (candidate in candidates) {
                val uri = URI(candidate)
                assertEquals(origin.scheme.lowercase(), uri.scheme)
                assertEquals(origin.host.lowercase(), uri.host)
                assertEquals(origin.port, uri.port)
                assertNull(uri.rawUserInfo)
                assertNull(uri.rawQuery)
                assertNull(uri.rawFragment)
            }
            assertTrue(candidates.first().endsWith("/v1/chat/completions"))
        }
    }

    /** 非 HTTP(S)、带认证信息或参数及非法端口等输入不得生成任何候选或发请求。 */
    @Test fun invalidInputsHaveAnExplanationAndSendNothing() {
        val inputs = listOf("", "api.example.com", "ftp://h.io", "https:///api", "https://u:p@h.io/api",
            "https://@h.io/api", "https://h.io/api?", "https://h.io/api?x=1", "https://h.io/api#",
            "https://h.io/api#x", "https://h.io:0", "https://h.io:65536", "https://h.io:-2", "https://h.io:bad")
        for (input in inputs) {
            val (error, candidates) = ByokTranslator.chatCandidates(input)
            assertNotNull(input, error)
            assertTrue(input, candidates.isEmpty())
            assertNull(input, ByokTranslator.chatUrl(input).second)
            val probe = translator().probeConnection(input) { _, _ -> fail("非法输入不得发请求：$input"); result() }
            assertNull(probe.url)
            assertNull(probe.variantName)
            assertNull(probe.lastResult)
            assertFalse(probe.error.isNullOrBlank())
        }
    }

    /** 404 和 405 都立即结束当前后缀，并从首个思考参数探测备用后缀。 */
    @Test fun onlyMissingOrUnsupportedPathsFallBackToTheSecondCandidate() {
        for (status in listOf(404, 405)) {
            val requests = mutableListOf<Pair<String, String>>()
            val success = result()
            val probe = translator().probeConnection(base) { endpoint, variant ->
                requests += endpoint to variant.name
                if (endpoint == primary) result(status) else success
            }
            assertEquals(listOf(primary to "thinking", alternate to "thinking"), requests)
            assertEquals(alternate, probe.url)
            assertEquals("thinking", probe.variantName)
            assertSame(success, probe.lastResult)
            assertNull(probe.error)
        }
    }

    /** 已给完整路径时只尝试原址，404 也不得猜测其他路径。 */
    @Test fun explicitEndpointNeverGuessesAnAlternative() {
        val endpoint = "$base/v3/chat/completions"
        val requests = mutableListOf<String>()
        val probe = translator().probeConnection(endpoint) { url, _ -> requests += url; result(404) }
        assertEquals(listOf(endpoint), requests)
        assertNull(probe.url)
        assertNull(probe.variantName)
        assertEquals(404, probe.lastResult?.http)
        assertFalse(probe.error.isNullOrBlank())
    }

    /** 认证、限流、服务故障及超时必须立即停止，不能继续思考参数或备用地址。 */
    @Test fun authenticationRateLimitsServerErrorsAndTimeoutsStopImmediately() {
        val failures = listOf(401, 403, 429, 500, 503, 599).map { result(it) } +
            listOf(MtResult("", 'f', null, null, http = 0, error = "deadline 8000ms"),
                MtResult("", 'f', null, null, http = 200, error = "deadline 8000ms"),
                MtResult("", 'f', null, null, http = 404, error = "deadline 8000ms"),
                MtResult("", 'f', null, null, http = 405, error = "deadline 8000ms"),
                MtResult("", 'f', null, null, http = 0, error = "java.net.UnknownHostException"),
                MtResult("", 'f', null, null, http = 0, error = "cancelled"))
        for (failure in failures) {
            val requests = mutableListOf<String>()
            val probe = translator().probeConnection(base) { url, _ -> requests += url; failure }
            assertEquals("${failure.http} ${failure.error}", listOf(primary), requests)
            assertNull(probe.url)
            assertNull(probe.variantName)
            assertSame(failure, probe.lastResult)
            assertFalse(probe.error.isNullOrBlank())
        }
    }

    /** 六种参数的既有顺序保留，参数不兼容时可继续同址探测。 */
    @Test fun parameterRejectionsKeepTheExistingThinkingOrder() {
        val variants = mutableListOf<String>()
        val success = result()
        val probe = translator().probeConnection(base) { url, variant ->
            assertEquals(primary, url)
            variants += variant.name
            if (variant.name == "none") success else result(422)
        }
        assertEquals(ByokTranslator.THINKING_OFF.map { it.name }, variants)
        assertEquals(primary, probe.url)
        assertEquals("none", probe.variantName)
        assertSame(success, probe.lastResult)
    }

    /** 六种开关都仍思考时连接已成功，返回 NONE_WORKED，不再猜测后缀。 */
    @Test fun exhaustedThinkingVariantsAreAConnectionSuccess() {
        val requests = mutableListOf<String>()
        val thought = result(thinking = true)
        val probe = translator().probeConnection(base) { endpoint, _ -> requests += endpoint; thought }
        assertEquals(List(6) { primary }, requests)
        assertEquals(primary, probe.url)
        assertEquals(ByokTranslator.NONE_WORKED, probe.variantName)
        assertSame(thought, probe.lastResult)
        assertNull(probe.error)
    }

    /** 全部仅参数拒绝不能证明接口可用，也不应触发路径回退。 */
    @Test fun parameterFailuresAloneDoNotCountAsSuccessOrPathFallback() {
        val requests = mutableListOf<String>()
        val probe = translator().probeConnection(base) { endpoint, _ -> requests += endpoint; result(400) }
        assertEquals(List(6) { primary }, requests)
        assertNull(probe.url)
        assertNull(probe.variantName)
        assertEquals(400, probe.lastResult?.http)
    }

    /** 两个后缀均不可用时到第二个为止，并保留最后一次结果。 */
    @Test fun twoMissingCandidatesReturnTheFinalFailure() {
        val requests = mutableListOf<String>()
        val probe = translator().probeConnection(base) { endpoint, _ ->
            requests += endpoint
            result(if (endpoint == primary) 404 else 405)
        }
        assertEquals(listOf(primary, alternate), requests)
        assertNull(probe.url)
        assertEquals(405, probe.lastResult?.http)
        assertFalse(probe.error.isNullOrBlank())
    }

    /** 每址至多六次，最后一个参数才出现 405 时两址总共也不能超过十二次。 */
    @Test fun atMostTwelveRequestsAreSentAcrossBothCandidates() {
        val requests = mutableListOf<Pair<String, String>>()
        val probe = translator().probeConnection(base) { endpoint, variant ->
            requests += endpoint to variant.name
            when {
                variant.name != "none" -> result(400)
                endpoint == primary -> result(405)
                else -> result()
            }
        }
        val names = ByokTranslator.THINKING_OFF.map { it.name }
        assertEquals(names.map { primary to it } + names.map { alternate to it }, requests)
        assertEquals(12, requests.size)
        assertEquals(alternate, probe.url)
        assertEquals("none", probe.variantName)
    }

    /** 取消状态不可复位，公开连接 API 与旧探测 API 都继续返回取消。 */
    @Test fun cancellingTheTranslatorIsTerminalForBothProbeApis() {
        val translator = translator()
        translator.cancel()
        repeat(2) {
            val probe = translator.probeConnection(base)
            assertNull(probe.url)
            assertNull(probe.variantName)
            assertEquals("cancelled", probe.lastResult?.error)
            val steps = mutableListOf<ByokTranslator.Step>()
            assertNull(translator.probe(steps))
            assertEquals(1, steps.size)
            assertEquals("cancelled", steps.single().result.error)
        }
        translator.probeConnection(base) { _, _ -> fail("已取消实例不得继续发送请求"); result() }
    }

    /** 首址失败与取消同时发生时，取消优先，备用后缀不得继续发出。 */
    @Test fun cancellationDuringTheFirstCandidatePreventsFallback() {
        val translator = translator()
        val requests = mutableListOf<String>()
        val probe = translator.probeConnection(base) { endpoint, _ ->
            requests += endpoint
            translator.cancel()
            result(404)
        }
        assertEquals(listOf(primary), requests)
        assertNull(probe.url)
        assertNull(probe.variantName)
        assertEquals("cancelled", probe.lastResult?.error)
    }

    /** 收到成功回复的同时发生取消时，也不能把已终止的测试作为成功返回。 */
    @Test fun cancellationTakesPriorityOverASuccessfulReply() {
        val translator = translator()
        val probe = translator.probeConnection(base) { _, _ -> translator.cancel(); result() }
        assertNull(probe.url)
        assertNull(probe.variantName)
        assertEquals("cancelled", probe.lastResult?.error)
    }
}
