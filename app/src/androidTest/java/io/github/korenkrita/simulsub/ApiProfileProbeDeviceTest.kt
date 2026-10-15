package io.github.korenkrita.simulsub

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.ui.HeaderUiState
import io.github.korenkrita.simulsub.ui.SimulSubActions
import io.github.korenkrita.simulsub.ui.TranslationApiUiState
import kotlinx.coroutines.Job
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 使用独立偏好和设备环回服务，验证旧配置的迟到探测不能覆盖新配置。 */
@RunWith(AndroidJUnit4::class)
class ApiProfileProbeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val token = UUID.randomUUID().toString()
    private val store = ViewModelStore()
    private val application = object : Application() {
        /** 系统能力沿用真实上下文，不替换应用身份。 */
        override fun getApplicationContext(): Context = context.applicationContext
        /** 保持真实包名，避免未绑定基础上下文的测试应用访问失败。 */
        override fun getPackageName(): String = context.packageName
        /** 每例使用独立命名空间，不访问用户保存的配置和凭据。 */
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            context.getSharedPreferences("配置探测-$token-$name", mode)
        /** 缓存仅指向本例路径，避免访问其他用例的数据。 */
        override fun getCacheDir(): File = File(context.cacheDir, "配置探测-$token")
        /** 记录扫描仅访问本例缓存下的目录，不读取真实会话记录。 */
        override fun getExternalFilesDir(type: String?): File = File(cacheDir, "records")
    }
    private lateinit var prefs: Prefs
    private lateinit var controller: AppController
    private lateinit var actions: SimulSubActions
    private lateinit var profileA: TranslationApiUiState
    private lateinit var profileB: TranslationApiUiState

    /** 创建真实控制器，全部业务动作在主线程执行，不启动字幕服务。 */
    @Before fun prepare() = onMain {
        assertFalse("请在字幕服务停止时运行配置探测回归", CaptionService.running)
        prefs = Prefs(application)
        controller = AppController(application)
        store.put("配置探测", controller)
        actions = controller.actions({}, {}, {}, {})
    }

    /** 释放本例协程和监听，保留隔离数据供失败排查，不删除文件。 */
    @After fun finish() = onMain { store.clear() }

    /** 已收到 A 的实际请求后切到 B，再释放旧响应，B 的字段、提示和存储必须保持不变。 */
    @Test fun switchingProfilesWhileRequestIsPendingRejectsLateResponse() {
        ProbeFixture().use { fixture ->
            configureProfiles(fixture, identicalService = false)
            val savedFields = savedProfileFields()
            val probeFinished = startProbe()
            assertRequestFromA(fixture.awaitRequest())

            lateinit var selected: TranslationApiUiState
            lateinit var saved: Map<String, *>
            onMain {
                // 页面在测试中禁用选择器；直接调用真实动作验证控制器的取消兜底。
                actions.onSelectApiProfile(profileB.profileId)
                assertSelected(profileB)
                selected = controller.state.value.settings.api
                assertNull(selected.message)
                assertFalse(selected.testing)
                assertEquals("", selected.suggestedUrl)
                saved = storedPreferences()
            }

            fixture.allowResponse(allowDisconnect = true)
            fixture.awaitFinished()
            awaitProbe(probeFinished)
            onMain {
                assertSelected(profileB)
                assertEquals("迟到响应不能改变 B 的界面状态或提示", selected, controller.state.value.settings.api)
                actions.onApplyTestedApiUrl()
                assertEquals("旧结果不能再次成为可保存建议", selected, controller.state.value.settings.api)
                assertEquals("应用旧建议不能写入配置或保存标记", saved, storedPreferences())
                assertEquals("A 和 B 的已保存字段均应保持原样", savedFields, savedProfileFields())
            }
        }
    }

    /** A/B 即使具有相同服务值，A 的成功建议也必须在切到 B 后撤销且不能应用。 */
    @Test fun switchingIdenticalServicesRevokesAlreadyReturnedSuggestion() {
        ProbeFixture().use { fixture ->
            configureProfiles(fixture, identicalService = true)
            val savedFields = savedProfileFields()
            val probeFinished = startProbe()
            assertRequestFromA(fixture.awaitRequest())
            fixture.allowResponse()
            fixture.awaitFinished()
            awaitProbe(probeFinished)

            onMain {
                assertSelected(profileA)
                val tested = controller.state.value.settings.api
                assertFalse(tested.testing)
                assertEquals(ByokTranslator.chatUrl(profileA.url).second, tested.suggestedUrl)
                assertTrue("必须先取得 A 的真实成功建议", tested.message?.text?.contains("连接测试通过") == true)
                assertFalse(tested.message!!.isError)
                assertEquals("连接测试本身不能保存地址或思考参数", savedFields, savedProfileFields())

                actions.onSelectApiProfile(profileB.profileId)
                assertSelected(profileB)
                val selected = controller.state.value.settings.api
                assertNotEquals(profileA.profileId, selected.profileId)
                assertNull(selected.message)
                assertFalse(selected.testing)
                assertEquals("", selected.suggestedUrl)
                val saved = storedPreferences()
                actions.onApplyTestedApiUrl()
                assertEquals("相同服务也不能保留另一个配置的建议", selected, controller.state.value.settings.api)
                assertEquals("旧建议不能改写地址、凭据或保存标记", saved, storedPreferences())
                assertEquals("两套配置的已保存字段均应保持原样", savedFields, savedProfileFields())
            }
        }
    }

    /** 先同步旧默认配置，再经真实动作新增并保存 B，最后切回 A 准备探测。 */
    private fun configureProfiles(fixture: ProbeFixture, identicalService: Boolean) = onMain {
        profileA = TranslationApiUiState(url = "${fixture.base}/profile-a", model = "model-a", key = "profile-a-key",
            userAgent = "ProfileProbe-A/1.0", headers = listOf(HeaderUiState("profile-a-header", "X-Profile-Key", "profile-a-header")))
        prefs.saveByok(profileA.url, profileA.model, profileA.key, profileA.userAgent,
            profileA.headers.map { it.name to it.value })
        actions.onDiscardApiDraft()
        assertSelected(profileA)
        actions.onAddApiProfile("备用配置")
        val empty = controller.state.value.settings.api
        assertNotEquals(profileA.profileId, empty.profileId)
        profileB = empty.copy(
            url = if (identicalService) profileA.url else "${fixture.base}/profile-b",
            model = if (identicalService) profileA.model else "model-b",
            key = if (identicalService) profileA.key else "profile-b-key",
            userAgent = if (identicalService) profileA.userAgent else "ProfileProbe-B/1.0",
            headers = listOf(HeaderUiState("profile-b-header", "X-Profile-Key",
                if (identicalService) "profile-a-header" else "profile-b-header")),
        )
        actions.onApiDraftChange(profileB)
        actions.onSaveApi()
        assertEquals("翻译配置已保存", controller.state.value.settings.api.message?.text)
        assertSelected(profileB)
        actions.onSelectApiProfile(profileA.profileId)
        assertSelected(profileA)
    }

    /** 订阅本次新建探测任务的完成信号，取消后也必须等其后台 IO 真正退出。 */
    private fun startProbe(): CountDownLatch {
        val finished = CountDownLatch(1)
        onMain {
            val scope = checkNotNull(controller.viewModelScope.coroutineContext[Job]) { "控制器缺少协程任务" }
            val existing = scope.children.toSet()
            actions.onTestApi()
            assertTrue("探测必须真实启动", controller.state.value.settings.api.testing)
            val probe = checkNotNull(scope.children.filterNot { it in existing }.singleOrNull()) { "本次探测未创建唯一协程任务" }
            probe.invokeOnCompletion { finished.countDown() }
        }
        return finished
    }

    /** 先等待整个探测任务结束，再等待主线程空闲，避免把服务端写完误当作客户端处理完成。 */
    private fun awaitProbe(finished: CountDownLatch) {
        assertTrue("探测协程及其后台 IO 未在期限内释放", finished.await(WAIT_SECONDS, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    /** 请求路径、模型和 ASCII 凭据必须来自 A，不能只凭界面测试状态推断已发出请求。 */
    private fun assertRequestFromA(request: ReceivedRequest) {
        assertEquals("/profile-a/v1/chat/completions", request.path)
        assertEquals("Bearer profile-a-key", request.headers["authorization"])
        assertEquals("ProfileProbe-A/1.0", request.headers["user-agent"])
        assertEquals("profile-a-header", request.headers["x-profile-key"])
        assertEquals("model-a", JSONObject(request.body).getString("model"))
        assertTrue(JSONObject(request.body).getBoolean("stream"))
    }

    /** 核对当前整套可编辑字段和持久凭据，忽略请求头行标识与操作提示的正常变化。 */
    private fun assertSelected(expected: TranslationApiUiState) {
        val actual = controller.state.value.settings.api
        assertEquals(expected.profileId, actual.profileId)
        assertEquals(expected.profileId, prefs.byokProfile.id)
        assertEquals(expected.url, actual.url)
        assertEquals(expected.url, prefs.byokUrl)
        assertEquals(expected.model, actual.model)
        assertEquals(expected.model, prefs.byokModel)
        assertEquals(expected.key, actual.key)
        assertEquals(expected.key, prefs.byokKey)
        assertEquals(expected.userAgent, actual.userAgent)
        assertEquals(expected.userAgent, prefs.byokUserAgent)
        val headers = expected.headers.map { it.name to it.value }
        assertEquals(headers, actual.headers.map { it.name to it.value })
        assertEquals(headers, prefs.byokHeaders)
        assertTrue(actual.hasSavedKey)
        assertTrue(actual.hasSavedConfiguration)
        assertFalse(actual.hasUnsavedChanges)
    }

    /** 复制整份隔离偏好，用于检查应用旧建议是否产生任何写入。 */
    private fun storedPreferences(): Map<String, *> = application.getSharedPreferences("jpsub", 0).all.toMap()

    /** 排除合法切换产生的当前标识和时间戳，完整比较两套保存内容及思考参数缓存。 */
    private fun savedProfileFields(): Map<String, *> = storedPreferences().filterKeys {
        it.startsWith("byok") && it != "byokActive" && it != "byokSaved"
    }

    /** 所有控制器动作及界面状态断言都串行进入主线程。 */
    private fun onMain(block: () -> Unit) { instrumentation.runOnMainSync { block() } }

    /** 服务端读完的单个真实请求，不保存或输出用户数据。 */
    private data class ReceivedRequest(val path: String, val headers: Map<String, String>, val body: String)

    /** 单次连接、有限报文和有界等待的设备环回夹具，收到请求后由测试显式放行 SSE。 */
    private class ProbeFixture : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = TimeUnit.SECONDS.toMillis(WAIT_SECONDS).toInt()
        }
        val base = "http://127.0.0.1:${server.localPort}"
        private val received = CountDownLatch(1)
        private val responseAllowed = CountDownLatch(1)
        private val finished = CountDownLatch(1)
        private val request = AtomicReference<ReceivedRequest?>()
        private val socket = AtomicReference<Socket?>()
        private val failure = AtomicReference<Throwable?>()
        private val closing = AtomicBoolean(false)
        private val allowDisconnect = AtomicBoolean(false)
        private val worker = Thread({
            try {
                server.accept().use { client ->
                    socket.set(client)
                    client.soTimeout = 3_000
                    request.set(readRequest(client))
                    received.countDown()
                    check(responseAllowed.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "等待放行 SSE 超时" }
                    try { writeResponse(client) }
                    catch (error: IOException) {
                        // 取消探测会关闭客户端连接，此时只允许发送阶段出现预期断开。
                        if (!allowDisconnect.get()) throw error
                    }
                }
            } catch (error: Throwable) {
                if (!closing.get()) failure.compareAndSet(null, error)
            } finally {
                socket.set(null)
                finished.countDown()
            }
        }, "配置探测环回服务").apply { isDaemon = true; start() }

        /** 等到请求头和请求体均已读完，后台异常优先报告，所有等待均有截止时间。 */
        fun awaitRequest(): ReceivedRequest {
            val arrived = received.await(WAIT_SECONDS, TimeUnit.SECONDS)
            checkFailure()
            assertTrue("设备环回服务未收到完整探测请求", arrived)
            return checkNotNull(request.get()) { "完整请求信号缺少请求内容" }
        }

        /** 仅在测试完成切换或准备接收成功建议后放行响应，取消场景允许对端已关闭连接。 */
        fun allowResponse(allowDisconnect: Boolean = false) {
            this.allowDisconnect.set(allowDisconnect)
            responseAllowed.countDown()
        }

        /** 等待发送尝试和服务端连接释放，不能替代对探测协程完成信号的等待。 */
        fun awaitFinished() {
            assertTrue("设备环回服务未在期限内结束响应", finished.await(WAIT_SECONDS, TimeUnit.SECONDS))
            checkFailure()
        }

        /** 有界读取原始 HTTP 报文，保留真实路径、请求头和 JSON 请求体用于断言。 */
        private fun readRequest(client: Socket): ReceivedRequest {
            val input = client.getInputStream()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                check(head.length < 16_384) { "探测请求头超过限制" }
                val byte = input.read()
                check(byte >= 0) { "探测请求头提前结束" }
                head.append(byte.toChar())
            }
            val lines = head.toString().split("\r\n")
            val first = lines.first().split(' ')
            check(first.size == 3 && first[0] == "POST") { "探测请求行无效" }
            val headers = lines.drop(1).filter { it.isNotEmpty() }.associate { line ->
                check(':' in line) { "探测请求头格式无效" }
                line.substringBefore(':').lowercase(Locale.ROOT) to line.substringAfter(':').trim()
            }
            val length = checkNotNull(headers["content-length"]?.toIntOrNull()) { "探测请求缺少有效长度" }
            check(length in 1..65_536) { "探测请求体长度超出限制" }
            val body = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(body, offset, length - offset)
                check(count > 0) { "探测请求体提前结束" }
                offset += count
            }
            return ReceivedRequest(first[1], headers, String(body, Charsets.UTF_8))
        }

        /** 返回无思考且正常结束的完整 SSE，使未取消探测能够真实生成成功建议。 */
        private fun writeResponse(client: Socket) {
            val body = ("data: {\"choices\":[{\"delta\":{\"content\":\"你好\"},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n").toByteArray(Charsets.UTF_8)
            client.getOutputStream().apply {
                write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                write(body)
                flush()
            }
        }

        /** 将后台异常转交测试线程，避免服务失败被误判为取消或无响应。 */
        private fun checkFailure() {
            failure.get()?.let { throw AssertionError("设备环回探测服务失败", it) }
        }

        /** 关闭本例端口和活动连接，并有界回收服务线程，不删除任何数据。 */
        override fun close() {
            closing.set(true)
            responseAllowed.countDown()
            server.close()
            socket.get()?.close()
            worker.join(4_000)
            assertFalse("设备环回探测线程未退出", worker.isAlive)
            checkFailure()
        }
    }

    companion object {
        private const val WAIT_SECONDS = 10L
    }
}
