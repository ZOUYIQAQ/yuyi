package io.github.korenkrita.simulsub.models

/** 从持久任务恢复的进度快照，独立于 Android，便于测试取消和进程恢复。 */
internal data class ModelTaskSnapshot(
    val status: ModelStatus,
    val downloadedBytes: Long = 0,
    val error: String? = null,
    val source: String? = null,
)

/** 唯一可用性依据是本次磁盘验证结果，任务成功标记本身不能启用模型。 */
internal object ModelLibraryReducer {
    /** 合并下载状态与经过验证的目录；从不自动选择新下载模型。 */
    fun reduce(
        specs: List<ModelSpec>,
        verifiedIds: Set<String>,
        selectedId: String?,
        tasks: Map<String, ModelTaskSnapshot>,
    ): ModelLibraryState {
        val entries = specs.map { spec ->
            val task = tasks[spec.id]
            val ready = spec.id in verifiedIds
            val status = if (ready) ModelStatus.READY else task?.status ?: ModelStatus.NOT_INSTALLED
            val safeStatus = if (!ready && status == ModelStatus.READY) ModelStatus.FAILED else status
            val bytes = if (ready) spec.sizeBytes else (task?.downloadedBytes ?: 0).coerceIn(0, spec.sizeBytes)
            ModelEntry(
                spec = spec, status = safeStatus, downloadedBytes = bytes,
                progress = bytes.toFloat() / spec.sizeBytes,
                error = if (ready) null else if (status == ModelStatus.READY) "模型文件未通过完整性校验，请重新下载" else task?.error,
                source = task?.source,
            )
        }
        return ModelLibraryState(entries, selectedId?.takeIf { id -> entries.any { it.spec.id == id && it.status == ModelStatus.READY } })
    }
}
