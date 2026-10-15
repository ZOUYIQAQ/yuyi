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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 应用级模型库；所有磁盘验证和偏好读写均在 IO 协程中，界面可直接观察状态。 */
class ModelRepository private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val manager = WorkManager.getInstance(context)
    private val installer = ModelInstaller(storageRoot(context))
    private val mutableState = MutableStateFlow(ModelLibraryState(ModelCatalog.entries.map { ModelEntry(it) }))
    val state: StateFlow<ModelLibraryState> = mutableState.asStateFlow()
    @Volatile private var verified: Map<String, ModelPaths> = emptyMap()
    private var selectedId: String? = null
    private var restored = false
    private var tasks: Map<String, ModelTaskSnapshot> = emptyMap()
    private var terminalStates: Map<String, String> = emptyMap()

    init {
        scope.launch {
            mutex.withLock { scanInstalled(); publish() }
            manager.getWorkInfosByTagFlow(WORK_TAG).collect { work ->
                mutex.withLock {
                    val latest = ModelCatalog.entries.associate { spec ->
                        spec.id to work.filter { it.tags.contains(modelTag(spec.id)) }
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
    fun selectedPaths(): ModelPaths? = state.value.selectedId?.let { verified[it] }

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
        mutableState.value = ModelLibraryReducer.reduce(ModelCatalog.entries, verified.keys, selectedId, tasks)
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
