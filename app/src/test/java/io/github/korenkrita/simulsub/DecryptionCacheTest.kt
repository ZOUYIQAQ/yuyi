package io.github.korenkrita.simulsub

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 不依赖安卓环境，直接验证缓存的容量、重试、完整密文和并发约束。 */
class DecryptionCacheTest {
    /** 重复读取三百次只解密一次，成功的空明文也必须命中。 */
    @Test fun repeatedReadsAndEmptyPlaintextAreCached() {
        val calls = AtomicInteger()
        val cache = DecryptionCache { ciphertext ->
            calls.incrementAndGet()
            if (ciphertext == "空密文") "" else "  密钥\n原文  "
        }
        repeat(300) { assertEquals("  密钥\n原文  ", cache.open("完整密文")) }
        repeat(3) { assertEquals("", cache.open("空密文")) }
        assertEquals(2, calls.get())
    }

    /** 容量恰好用满时不淘汰，命中更新顺序，溢出后仅淘汰最久未访问项。 */
    @Test fun leastRecentlyUsedEntryIsEvictedAtTheBoundary() {
        val calls = mutableMapOf<String, Int>()
        val cache = DecryptionCache(2) { ciphertext ->
            calls[ciphertext] = calls.getOrDefault(ciphertext, 0) + 1
            "明文:$ciphertext"
        }
        cache.open("甲")
        cache.open("乙")
        cache.open("甲")
        assertEquals(mapOf("甲" to 1, "乙" to 1), calls)
        cache.open("丙")
        cache.open("甲")
        cache.open("乙")
        assertEquals(mapOf("甲" to 1, "乙" to 2, "丙" to 1), calls)
        cache.open("丙")
        assertEquals(2, calls["丙"])
    }

    /** 连续失败均重新尝试且保留原异常，不缓存失败也不挤掉已有成功项。 */
    @Test fun failuresAreRetriedWithoutEvictingSuccessfulEntries() {
        val calls = mutableMapOf<String, Int>()
        val failure = IllegalStateException("设备密钥暂不可用")
        var unavailable = true
        val cache = DecryptionCache(1) { ciphertext ->
            calls[ciphertext] = calls.getOrDefault(ciphertext, 0) + 1
            if (ciphertext == "待恢复" && unavailable) throw failure
            "明文:$ciphertext"
        }
        cache.open("已有")
        repeat(2) {
            assertSame(failure, assertThrows(IllegalStateException::class.java) { cache.open("待恢复") })
            assertEquals("明文:已有", cache.open("已有"))
        }
        assertEquals(1, calls["已有"])
        unavailable = false
        repeat(2) { assertEquals("明文:待恢复", cache.open("待恢复")) }
        assertEquals(3, calls["待恢复"])
    }

    /** 同哈希的不同完整密文各自解密，新旧密文交替读取仍返回各自版本。 */
    @Test fun fullCiphertextSeparatesVersionsEvenWhenHashesCollide() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val calls = AtomicInteger()
        val cache = DecryptionCache { ciphertext ->
            calls.incrementAndGet()
            if (ciphertext == "Aa") "旧快照密钥" else "新快照密钥"
        }
        repeat(3) {
            assertEquals("旧快照密钥", cache.open("Aa"))
            assertEquals("新快照密钥", cache.open("BB"))
        }
        assertEquals(2, calls.get())
    }

    /** 十二路冷读取共享同一次成功解密；等待均有超时，失败时释放线程。 */
    @Test fun concurrentReadsMergeSuccessfulDecryption() {
        val workers = 12
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val attempting = CountDownLatch(workers)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val cache = DecryptionCache { _: String ->
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "等待解密释放超时" }
            "并发密钥"
        }
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val results = (1..workers).map {
                pool.submit(Callable {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS)) { "等待并发起点超时" }
                    attempting.countDown()
                    cache.open("共享完整密文")
                })
            }
            assertTrue("工作线程未就绪", ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertTrue("未进入实际解密", entered.await(5, TimeUnit.SECONDS))
            assertTrue("并发读取未全部开始", attempting.await(5, TimeUnit.SECONDS))
            release.countDown()
            results.forEach { assertEquals("并发密钥", it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, calls.get())
        } finally {
            start.countDown()
            release.countDown()
            pool.shutdownNow()
            assertTrue("工作线程未退出", pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    /** 容量一仍可复用并在替换后重新解密，零和负容量必须拒绝。 */
    @Test fun singleEntryCacheAndInvalidCapacities() {
        for (capacity in listOf(0, -1)) {
            assertThrows(IllegalArgumentException::class.java) { DecryptionCache(capacity) { it } }
        }
        val calls = AtomicInteger()
        val cache = DecryptionCache(1) { calls.incrementAndGet(); it }
        listOf("甲", "甲", "乙", "乙", "甲").forEach { assertEquals(it, cache.open(it)) }
        assertEquals(3, calls.get())
    }

    /** 默认容量最多保留六十四项，第六十五项写入后最旧项需重新解密。 */
    @Test fun defaultCapacityIsBounded() {
        val calls = AtomicInteger()
        val cache = DecryptionCache { calls.incrementAndGet(); it }
        repeat(64) { cache.open("密文$it") }
        repeat(64) { cache.open("密文$it") }
        assertEquals(64, calls.get())
        cache.open("密文64")
        cache.open("密文0")
        assertEquals(66, calls.get())
    }
}
