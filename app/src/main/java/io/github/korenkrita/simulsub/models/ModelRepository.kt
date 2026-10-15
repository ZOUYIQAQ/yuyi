package io.github.korenkrita.simulsub.models

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.TimeUnit
import io.github.korenkrita.simulsub.CaptionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 应用级模型库；所有磁盘验证和偏好读写均在 IO 协程中，界面可直接观察状态。 */
class ModelRepository private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val manager = WorkManager.getInstance(context)
    private val installer = ModelInstaller(storageRoot(context))
    private val deletion = ModelDeletion(storageRoot(context))
    private val operationGate = ModelOperationGate.shared
    private val mutableState = MutableStateFlow(ModelLibraryState(ModelCatalog.entries.map { ModelEntry(it) }))
    val state: StateFlow<ModelLibraryState> = mutableState.asStateFlow()
    @Volatile private var verified: Map<String, ModelPaths> = emptyMap()
    private var selectedId: String? = null
    private var restored = false
    private var tasks: Map<String, ModelTaskSnapshot> = emptyMap()
    private var terminalStates: Map<String, String> = emptyMap()
    private var deletedBefore: Map<String, Long> = emptyMap()
    private var deleteErrors: Map<String, String> = emptyMap()
    private var deletingId: String? = null

    init {
        scope.launch {
            mutex.withLock { scanInstalled(); publish() }
            manager.getWorkInfosByTagFlow(WORK_TAG).collect { work ->
                mutex.withLock {
                    val latest = ModelCatalog.entries.associate { spec ->
                        spec.id to work.filter { it.tags.contains(modelTag(spec.id)) &&
                            (!it.state.isFinished || deletedBefore[spec.id].let { cutoff -> cutoff == null || cutoff == 0L || createdAt(it) > cutoff }) }
                            .maxWithOrNull(compareBy<WorkInfo> { !it.state.isFinished }.thenBy { createdAt(it) })
                    }
                    tasks = latest.mapNotNull { (id, info) -> info?.let { id to taskSnapshot(id, it) } }.toMap()
                    val terminal = latest.mapNotNull { (id, info) ->
                        info?.takeIf { it.state.isFinished }?.let { id to "${it.id}:${it.state}" }
                    }.toMap()
                    if (terminal != terminalStates) {
                        terminalStates = terminal
                        scanInstalled()
                    }
                    publish()
                }
            }
        }
    }

    /** 将唯一任务交给系统持久调度；重复点击不替换正在下载的任务或当前选择。 */
    fun download(id: String) {
        require(ModelCatalog.entries.any { it.id == id }) { "未知模型：$id" }
        scope.launch {
            mutex.withLock {
                if (id in verified) return@withLock
                deleteErrors = deleteErrors - id
                try {
                    val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                        .setInputData(workDataOf(MODEL_ID to id))
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                        .addTag(WORK_TAG).addTag(modelTag(id)).addTag("$CREATED_TAG${System.currentTimeMillis()}")
                        .build()
                    manager.enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, request).result.get(30, TimeUnit.SECONDS)
                    tasks = tasks + (id to ModelTaskSnapshot(ModelStatus.QUEUED))
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    tasks = tasks + (id to ModelTaskSnapshot(ModelStatus.FAILED, error = "无法安排模型下载，请重试"))
                    Log.e("模型库", "无法安排模型下载：$id", error)
                }
                publish()
            }
        }
    }

    /** 取消系统任务并保留暂存文件，后续重试复用完整且验证通过的文件。 */
    fun cancel(id: String) {
        require(ModelCatalog.entries.any { it.id == id }) { "未知模型：$id" }
        scope.launch {
            mutex.withLock {
                try {
                    manager.cancelUniqueWork(workName(id)).result.get(30, TimeUnit.SECONDS)
                    tasks = tasks + (id to ModelTaskSnapshot(ModelStatus.CANCELLED))
                    publish()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Log.e("模型库", "取消模型下载失败：$id", error)
                }
            }
        }
    }

    /** 重新校验完整文件后持久保存用户选择；未安装、损坏或半成品均无法启用。 */
    fun select(id: String) {
        val spec = ModelCatalog.entries.find { it.id == id } ?: throw IllegalArgumentException("未知模型：$id")
        scope.launch {
            mutex.withLock {
                val paths = installer.verifyInstalled(spec)
                verified = if (paths == null) verified - id else verified + (id to paths)
                if (paths != null) {
                    deleteErrors = deleteErrors - id
                    if (preferences().edit().putString(SELECTED_KEY, id).commit()) selectedId = id
                    else Log.e("模型库", "保存选中模型失败：$id")
                } else if (selectedId == id) {
                    selectedId = null
                    preferences().edit().putString(SELECTED_KEY, "").commit()
                }
                publish()
            }
        }
    }

    /** 返回经过后台完整验证的选中模型；会话应持有此快照，不跟随后续选择切换。 */
    fun selectedPaths(): ModelPaths? = if (operationGate.isDeleting) null else state.value.selectedId?.let { verified[it] }

    /** 服务启动时取得租约，原生识别和管线完全释放后关闭；页面预检继续使用 selectedPaths。 */
    fun acquireSelectedForSession(): ModelSessionLease? = operationGate.acquireSession {
        state.value.selectedId?.let { verified[it] }
    }

    /** 正式确认弹窗直接使用安装器对应的固定范围，未知标识不能拼接任意路径。 */
    fun deletionScope(id: String): String = deletion.scope(ModelCatalog.entries.first { it.id == id })

    /**
     * 删除前重新读取系统任务，并在主线程与服务取路径原子互斥。
     * 服务的取路径到设置 running 均同步在主线程；闸门开启后 selectedPaths 返回空，避免检查后启动竞态。
     * 删除仅处理本模型范围，失败保留实际错误，最终重扫磁盘；调用者只能在页面正式确认后调用。
     */
    suspend fun delete(id: String): ModelDeleteResult = withContext(NonCancellable + Dispatchers.IO) {
        val spec = ModelCatalog.entries.find { it.id == id }
            ?: return@withContext ModelDeleteResult(id, false, error = "未知模型：$id")
        mutex.withLock {
            val pending = try {
                if (!restored) scanInstalled()
                manager.getWorkInfosByTag(WORK_TAG).get(30, TimeUnit.SECONDS).any { !it.state.isFinished }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                return@withLock deletionFailure(id, error)
            }
            val blocked = withContext(Dispatchers.Main.immediate) {
                operationGate.beginDeletion(CaptionService.running, pending)
            }
            if (blocked != null) {
                deleteErrors = deleteErrors + (id to blocked)
                publish()
                return@withLock ModelDeleteResult(id, false, error = blocked)
            }
            // 一旦进入实际删除，就完成选择持久化和最终刷新，页面离开不能打断到一半。
            withContext(NonCancellable) {
                deletingId = id
                deleteErrors = deleteErrors - id
                var result = ModelDeleteResult(id, false, error = "模型删除未完成")
                try {
                    publish()
                    result = deletion.delete(spec, selectedId) {
                        val prefs = preferences()
                        if (!prefs.edit().remove(SELECTED_KEY).commit() || prefs.getString(SELECTED_KEY, null) != null) {
                            throw java.io.IOException("无法持久清除当前模型选择：$id")
                        }
                        selectedId = null
                    }
                    if (result.deleted) {
                        val cutoff = System.currentTimeMillis()
                        deletedBefore = deletedBefore + (id to cutoff)
                        tasks = tasks - id
                        if (!preferences().edit().putLong("$DELETED_PREFIX$id", cutoff).commit()) {
                            result = result.copy(deleted = false, error = "模型文件已清理，但无法保存删除记录：$id")
                        }
                    }
                    if (!result.deleted) deleteErrors = deleteErrors + (id to requireNotNull(result.error))
                } catch (error: Exception) {
                    result = result.copy(deleted = false, error = error.message ?: error.javaClass.simpleName)
                    deleteErrors = deleteErrors + (id to requireNotNull(result.error))
                } finally {
                    try {
                        scanInstalled()
                    } catch (error: Exception) {
                        verified = verified - id
                        result = result.copy(deleted = false, error = "刷新模型状态失败：${error.message ?: error.javaClass.simpleName}")
                        deleteErrors = deleteErrors + (id to requireNotNull(result.error))
                    } finally {
                        deletingId = null
                        try { publish() } finally { operationGate.endDeletion() }
                    }
                }
                result
            }
        }
    }

    /** 初始化或系统任务查询失败时停止删除并公开原始原因，不把未知后台状态视为可删除。 */
    private fun deletionFailure(id: String, error: Exception): ModelDeleteResult {
        val message = "无法确认模型后台状态：${error.message ?: error.javaClass.simpleName}"
        deleteErrors = deleteErrors + (id to message)
        publish()
        return ModelDeleteResult(id, false, error = message)
    }

    /** 只查询后台验证结果，不在主线程读取大文件或计算摘要。 */
    fun isReady(id: String): Boolean = state.value.entries.any { it.spec.id == id && it.status == ModelStatus.READY }

    /** 后台重验磁盘并恢复用户选择，绝不把完成标记直接视为模型可用。 */
    fun refresh() {
        scope.launch { mutex.withLock { scanInstalled(); publish() } }
    }

    /** 在后台读取小型选择偏好，避免构造单例时触发磁盘工作。 */
    private fun preferences() = context.getSharedPreferences("model_library", Context.MODE_PRIVATE)

    /** 在 IO 线程重新计算每个正式文件摘要，恢复只信任当前磁盘内容。 */
    private fun scanInstalled() {
        if (!restored) {
            selectedId = preferences().getString(SELECTED_KEY, null)
            deletedBefore = ModelCatalog.entries.associate { it.id to preferences().getLong("$DELETED_PREFIX${it.id}", 0L) }
            restored = true
        }
        verified = ModelCatalog.entries.mapNotNull { spec -> installer.verifyInstalled(spec)?.let { spec.id to it } }.toMap()
        // 损坏或缺失的旧选择失效后需要用户重新启用，补下载不能隐式恢复选择。
        if (selectedId != null && selectedId !in verified) {
            selectedId = null
            preferences().edit().putString(SELECTED_KEY, "").commit()
        }
    }

    /** 使用不可变快照发布状态，只有经过验证的用户选择能够对外出现。 */
    private fun publish() {
        val reduced = ModelLibraryReducer.reduce(ModelCatalog.entries, verified.keys, selectedId, tasks)
        mutableState.value = reduced.copy(deletingId = deletingId, entries = reduced.entries.map { entry ->
            val local = runCatching { deletion.hasLocalFiles(entry.spec) }
            entry.copy(hasLocalFiles = local.getOrDefault(true),
                error = deleteErrors[entry.spec.id] ?: local.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName } ?: entry.error)
        })
    }

    /** 恢复系统任务状态和上次持久进度，不依赖已销毁页面或旧进程的内存。 */
    private fun taskSnapshot(modelId: String, info: WorkInfo): ModelTaskSnapshot {
        val journal = ModelDownloadJournal(context).read(modelId, info.id)
        val data = when {
            info.state.isFinished && info.state != WorkInfo.State.CANCELLED -> info.outputData
            info.progress.getString(STATUS) != null -> info.progress
            else -> journal
        }
        val status = when (info.state) {
            WorkInfo.State.SUCCEEDED -> ModelStatus.READY
            WorkInfo.State.FAILED -> ModelStatus.FAILED
            WorkInfo.State.CANCELLED -> ModelStatus.CANCELLED
            WorkInfo.State.RUNNING -> if (data.getString(STATUS) == ModelStatus.VERIFYING.name) ModelStatus.VERIFYING else ModelStatus.DOWNLOADING
            else -> ModelStatus.QUEUED
        }
        return ModelTaskSnapshot(status, data.getLong(BYTES, 0), if (status == ModelStatus.CANCELLED) null else data.getString(ERROR), data.getString(SOURCE))
    }

    companion object {
        internal const val WORK_TAG = "model-library-download"
        internal const val MODEL_ID = "model_id"
        internal const val STATUS = "status"
        internal const val BYTES = "bytes"
        internal const val ERROR = "error"
        internal const val SOURCE = "source"
        private const val CREATED_TAG = "model-created:"
        private const val SELECTED_KEY = "selected_id"
        private const val DELETED_PREFIX = "deleted_before_"
        @Volatile private var instance: ModelRepository? = null

        /** 返回仅持有应用 Context 的单例，旋转 Activity 不会重建模型库或下载。 */
        fun get(context: Context): ModelRepository = instance ?: synchronized(this) {
            instance ?: ModelRepository(context.applicationContext).also { instance = it }
        }

        /** 模型使用应用持久私有目录，缓存回收不会清除已安装模型。 */
        internal fun storageRoot(context: Context): File = File(context.filesDir, "models")

        /** 固定唯一工作名称，界面取消与通知取消操作相同的持久任务。 */
        internal fun workName(id: String): String = "model-download-$id"

        /** 将目录标识与系统任务关联，不依赖 Activity 内存中的任务引用。 */
        internal fun modelTag(id: String): String = "model-id:$id"

        /** 从持久标签读取创建顺序，避免旧失败任务覆盖新下载的状态。 */
        private fun createdAt(info: WorkInfo): Long = info.tags.firstOrNull { it.startsWith(CREATED_TAG) }
            ?.removePrefix(CREATED_TAG)?.toLongOrNull() ?: 0L

    }
}
