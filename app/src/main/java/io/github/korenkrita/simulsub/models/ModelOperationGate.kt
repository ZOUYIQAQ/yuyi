package io.github.korenkrita.simulsub.models

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** 下载、校验与删除共享的进程内闸门；运行检查和开始删除必须在主线程原子完成。 */
internal class ModelOperationGate {
    private val lock = Any()
    private val deleting = MutableStateFlow(false)
    private var downloads = 0
    private var sessions = 0

    /** 会话取路径时读取此值，删除期间不能启动新的模型使用者。 */
    val isDeleting: Boolean get() = deleting.value

    /** 后台操作前重新检查真实任务与运行状态，返回具体禁止原因而不等待后偷偷删除。 */
    fun beginDeletion(sessionRunning: Boolean, workPending: Boolean): String? = synchronized(lock) {
        when {
            sessionRunning || sessions > 0 -> "同传正在运行或释放识别资源，请等待完全停止后再删除模型"
            workPending || downloads > 0 -> "模型正在下载或校验，请等待任务结束后再删除"
            deleting.value -> "正在删除模型，请等待当前操作完成"
            else -> { deleting.value = true; null }
        }
    }

    /** 成功、失败或取消均释放闸门，等待中的下载可以继续。 */
    fun endDeletion() = synchronized(lock) { deleting.value = false }

    /** 取模型路径与登记使用者在同一锁内完成，删除先获得闸门时不允许会话再读取模型。 */
    fun acquireSession(paths: () -> ModelPaths?): ModelSessionLease? = synchronized(lock) {
        if (deleting.value) return@synchronized null
        val selected = paths() ?: return@synchronized null
        sessions++
        ModelSessionLease(selected) { synchronized(lock) { sessions-- } }
    }

    /** 下载从创建暂存到校验转正全程登记；删除中的新任务等待，已有任务阻止删除。 */
    suspend fun <T> withDownload(block: suspend () -> T): T {
        while (true) {
            val entered = synchronized(lock) {
                if (deleting.value) false else { downloads++; true }
            }
            if (entered) break
            deleting.first { !it }
        }
        return try { block() } finally { synchronized(lock) { downloads-- } }
    }

    companion object {
        /** 仓库与 WorkManager 使用同一闸门，不受页面重建影响。 */
        val shared = ModelOperationGate()
    }
}

/** 会话在释放 ASR、VAD 及管线资源后关闭租约；关闭操作幂等，不能提前按页面状态释放。 */
class ModelSessionLease internal constructor(val paths: ModelPaths, private val release: () -> Unit) : AutoCloseable {
    private var closed = false

    /** 只释放一次使用计数，兼容启动失败与资源清理共用 finally。 */
    @Synchronized override fun close() {
        if (!closed) {
            closed = true
            release()
        }
    }
}
