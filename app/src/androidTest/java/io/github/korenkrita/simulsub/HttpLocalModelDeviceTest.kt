package io.github.korenkrita.simulsub

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

/** 使用设备自身的环回 HTTP 服务验收网络策略与真实翻译，不访问用户配置或外部服务。 */
@RunWith(AndroidJUnit4::class)
class HttpLocalModelDeviceTest {
    /** 检查有效网络策略，覆盖 debug 专用域名，防止其配置覆盖明文开关。 */
    @Test fun localAndLanHttpArePermitted() {
        val policy = NetworkSecurityPolicy.getInstance()
        for (host in listOf("localhost", "127.0.0.1", "10.0.2.2", "192.168.1.7", "::1"))
            assertTrue("应允许 HTTP：$host", policy.isCleartextTrafficPermitted(host))
    }

    /** 发布配置只引用系统证书，不因支持 HTTP 引入用户 CA 或调试 CA。 */
    @Test fun mainNetworkConfigurationTrustsOnlySystemCertificates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val anchors = mutableListOf<String>()
        context.resources.getXml(R.xml.network_security_config).use { xml ->
            while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG && xml.name == "certificates")
                    anchors += xml.getAttributeValue(null, "src")
                xml.next()
            }
        }
        assertEquals(listOf("system"), anchors)
    }

    /** 真实 HTTP 首址 404/405 后仅切换同源后缀，返回建议后由调用方决定是否保存。 */
    @Test fun realHttpSuffixProbeAndTranslationWork() {
        for (status in listOf(404, 405)) HttpFixture { path ->
            if (path == "/v1/chat/completions") status to "{}" else 200 to answer
        }.use { fixture ->
            val service = ByokService(ByokTranslator.chatUrl(fixture.base).second!!, "本地模型", "")
            val translator = ByokTranslator(service, "测试", null, deadlineMs = 3_000)
            try {
                val result = translator.probeConnection(fixture.base)
                assertEquals("${fixture.base}/chat/completions", result.url)
                assertEquals("你好", result.lastResult?.text)
                assertEquals(listOf("/v1/chat/completions", "/chat/completions"), fixture.paths.toList())
                assertEquals("${fixture.base}/v1/chat/completions", service.url)
                val confirmed = ByokTranslator(service.copy(url = result.url!!), "测试", null, deadlineMs = 3_000)
                try {
                    assertTrue(confirmed.translate("测试")!!.usable)
                    confirmed.cancel()
                    assertEquals("cancelled", confirmed.translate("取消后")?.error)
                    assertEquals(3, fixture.paths.size)
                } finally { confirmed.cancel() }
            } finally { translator.cancel() }
        }
    }

    /** 无需外部夹具，设备内提供有界 HTTP 服务并记录实际请求路径。 */
    private class HttpFixture(private val response: (String) -> Pair<Int, String>) : Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${server.localPort}"
        val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val failure = AtomicReference<Throwable?>()
        private val worker = Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                try { socket.use { serve(it) } }
                catch (error: Throwable) { failure.compareAndSet(null, error) }
            }
        }.apply { isDaemon = true; start() }

        /** 读完有界请求头与请求体再返回完整 SSE，避免仅验证策略而没有真正收到译文。 */
        private fun serve(socket: Socket) {
            socket.soTimeout = 3_000
            val input = socket.getInputStream()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                check(head.length < 16_384) { "请求头过长" }
                val byte = input.read()
                check(byte >= 0) { "请求头未读完" }
                head.append(byte.toChar())
            }
            val length = head.lines().firstOrNull { it.startsWith("Content-Length:", true) }
                ?.substringAfter(':')?.trim()?.toInt() ?: 0
            check(length in 0..65_536) { "请求体过长" }
            repeat(length) { check(input.read() >= 0) { "请求体未读完" } }
            val path = head.toString().substringBefore("\r\n").split(' ')[1]
            paths += path
            val (status, body) = response(path)
            val bytes = body.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                write(("HTTP/1.1 $status OK\r\nContent-Type: text/event-stream\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                write(bytes)
                flush()
            }
        }

        /** 关闭测试端口并限时等待服务线程，失败通过断言保留，不删除用户数据。 */
        override fun close() {
            server.close()
            worker.join(4_000)
            assertFalse("本地服务线程未结束", worker.isAlive)
            failure.get()?.let { throw AssertionError("本地 HTTP 服务失败", it) }
        }
    }

    companion object {
        private val answer = "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
    }
}
