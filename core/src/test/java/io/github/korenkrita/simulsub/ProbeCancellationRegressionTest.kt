package io.github.korenkrita.simulsub

import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

/** 连接测试取消必须具有终止语义，后续探测不得继续发请求。 */
class ProbeCancellationRegressionTest {
    /** 已取消的翻译器只返回一次取消结果，不能继续尝试剩余关闭思考参数。 */
    @Test fun cancelledTranslatorDoesNotContinueProbing() {
        val translator = ByokTranslator(ByokService("http://127.0.0.1:1/v1/chat/completions", "测试模型", ""), "测试", null)
        translator.cancel()
        repeat(2) {
            val steps = mutableListOf<ByokTranslator.Step>()
            assertNull(translator.probe(steps))
            assertEquals("取消后只能返回一个取消步骤", 1, steps.size)
            assertEquals("cancelled", steps.single().result.error)
            assertFalse("取消不能视为已穷尽参数", translator.exhausted(steps))
        }
    }

    /** 注册请求后立即取消应跳过域名解析与实际发送，取消状态不能被后续请求重置。 */
    @Test fun cancellationBeforeEnqueuePreventsEveryLaterRequest() {
        val lookups = AtomicInteger()
        val dns = object : Dns {
            /** 仅统计调用，不访问外部网络。 */
            override fun lookup(hostname: String): List<InetAddress> {
                lookups.incrementAndGet()
                return listOf(InetAddress.getByName("127.0.0.1"))
            }
        }
        val calls = Calls(1_000, dns, java.net.Proxy.NO_PROXY)
        val first = calls.post("http://probe.invalid:1", "{}", emptyMap(), beforeSend = calls::cancel)
        val next = calls.post("http://probe.invalid:1", "{}", emptyMap())
        assertEquals("cancelled", first.error)
        assertEquals("cancelled", next.error)
        assertEquals("取消后不能进入域名解析", 0, lookups.get())
    }
}
