package io.github.korenkrita.simulsub.models

import android.content.Context
import androidx.work.Data
import androidx.work.workDataOf
import java.io.IOException
import java.util.UUID

/** 补充系统在重试等待期间清空的进度数据，仅由 IO 线程读取和写入。 */
internal class ModelDownloadJournal(context: Context) {
    private val preferences = context.getSharedPreferences("model_download_progress", Context.MODE_PRIVATE)

    /** 每个模型仅保留一个任务快照，工作 ID 防止新任务误用旧任务进度。 */
    fun write(modelId: String, workId: UUID, snapshot: ModelTaskSnapshot) {
        val saved = preferences.edit()
            .putString("$modelId.work", workId.toString())
            .putString("$modelId.status", snapshot.status.name)
            .putLong("$modelId.bytes", snapshot.downloadedBytes)
            .putString("$modelId.error", snapshot.error)
            .putString("$modelId.source", snapshot.source)
            .commit()
        if (!saved) throw IOException("无法保存模型下载进度")
    }

    /** 仅恢复与系统当前工作 ID 完全一致的持久进度。 */
    fun read(modelId: String, workId: UUID): Data {
        if (preferences.getString("$modelId.work", null) != workId.toString()) return Data.EMPTY
        return workDataOf(
            ModelRepository.STATUS to preferences.getString("$modelId.status", null),
            ModelRepository.BYTES to preferences.getLong("$modelId.bytes", 0),
            ModelRepository.ERROR to preferences.getString("$modelId.error", null),
            ModelRepository.SOURCE to preferences.getString("$modelId.source", null),
        )
    }
}
