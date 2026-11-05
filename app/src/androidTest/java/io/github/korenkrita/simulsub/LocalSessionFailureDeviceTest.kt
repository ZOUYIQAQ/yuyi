package io.github.korenkrita.simulsub

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

/** 使用生产错误回调与真实悬浮窗，不开启录音、不加载模型、不调用网络，偏好按用例隔离。 */
class LocalSessionFailureDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private lateinit var service: CaptionService
    private var destroyed = false
    private val previous = CaptionService.state.value

    /** 仅附着隔离上下文并建立生产悬浮窗，不启动Android前台服务或媒体投影。 */
    @Before fun prepare() = main {
        assertFalse("已有同传会话时不执行此测试", CaptionService.running)
        val token = UUID.randomUUID().toString()
        val isolated = object : ContextWrapper(inst.targetContext) {
            /** 测试样式和位置不写入真实用户配置。 */
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                baseContext.getSharedPreferences("会话错误-$token-$name", mode)
        }
        service = CaptionService()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(service, isolated)
        invoke("showOverlay")
    }

    /** 释放本例窗口及监听，并恢复测试前的只读页面快照；不删除用户文件。 */
    @After fun finish() = main {
        if (::service.isInitialized && !destroyed) service.onDestroy()
        val state = CaptionService::class.java.getDeclaredField("sessionState").apply { isAccessible = true }.get(null)
        state.javaClass.getDeclaredMethod("setValue", Any::class.java).apply { isAccessible = true }.invoke(state, previous)
    }

    /** 工作线程失败后保留原因和窗口，模型清理后的完成动作不能空转等待用户关闭。 */
    @Test fun failureRemainsVisibleAfterTheSessionThreadFinishes() {
        main { invoke("failLocalSession", IllegalStateException("受控工作线程失败")) }
        awaitError()
        main {
            val view = overlay()
            assertTrue(view.isAttachedToWindow)
            val status = CaptionOverlayView::class.java.getDeclaredField("stats").apply { isAccessible = true }.get(view) as TextView
            assertTrue(status.text.contains("受控工作线程失败"))
            assertTrue(status.text.contains("结束后重试"))
            val start = SystemClock.elapsedRealtime()
            invoke("finishLocalSession", true)
            assertTrue("保留错误窗口不应阻塞会话线程", SystemClock.elapsedRealtime() - start < 500)
        }
        Thread.sleep(100)
        main { assertTrue("清理后仍应显示错误，等待用户主动关闭", overlay().isAttachedToWindow) }
    }

    /** 用户关闭后迟到的线程异常不能重开窗口或覆盖已结束的原因。 */
    @Test fun lateFailureCannotReopenOrReplaceAStoppedSession() {
        main { invoke("failLocalSession", IllegalStateException("首次失败")) }
        awaitError()
        main {
            val view = overlay()
            service.onDestroy(); destroyed = true
            val ended = CaptionService.state.value
            invoke("failLocalSession", IllegalStateException("迟到失败"))
            assertFalse(view.isAttachedToWindow)
            assertEquals(ended, CaptionService.state.value)
            assertFalse(CaptionService.running)
        }
    }

    /** 按生产帧调度等待错误实际发布，等待时间有明确上限。 */
    private fun awaitError() {
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (!CaptionService.state.value.status.startsWith("出错：") && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
        assertTrue("错误必须同步到页面状态", CaptionService.state.value.status.startsWith("出错："))
    }

    /** 直接读取本例的生产字幕视图，断言实际附着与实际提示文本。 */
    private fun overlay() = CaptionService::class.java.getDeclaredField("overlay").apply { isAccessible = true }.get(service) as CaptionOverlayView

    /** 调用生产私有入口以复验生命周期，不为了测试暴露应用调试接口。 */
    private fun invoke(name: String, argument: Any? = null) {
        val parameters = when (argument) { is Throwable -> arrayOf(Throwable::class.java); is Boolean -> arrayOf(Boolean::class.javaPrimitiveType!!); else -> emptyArray() }
        val method = CaptionService::class.java.getDeclaredMethod(name, *parameters).apply { isAccessible = true }
        if (argument == null) method.invoke(service) else method.invoke(service, argument)
    }

    /** Android视图和服务状态统一由主线程操作。 */
    private fun main(block: () -> Unit) = inst.runOnMainSync(block)
}
