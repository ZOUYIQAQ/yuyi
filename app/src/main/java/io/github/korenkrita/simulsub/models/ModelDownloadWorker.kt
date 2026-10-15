package io.github.korenkrita.simulsub.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 系统持久前台下载任务；Activity 旋转、离开页面和进程重启不拥有其生命周期。 */
class ModelDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private var lastReportTime = 0L
    private var lastReport: ModelTaskSnapshot? = null

    /** 在 IO 协程中恢复暂存、受控源回退、严格校验并原子安装。 */
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(ModelRepository.MODEL_ID)
        val spec = ModelCatalog.entries.find { it.id == modelId }
            ?: return@withContext Result.failure(workDataOf(ModelRepository.ERROR to "未知模型，无法下载"))
        val journal = ModelDownloadJournal(applicationContext)
        try {
            // 重试与取消后的新任务可能短暂重叠，互斥保护共享的固定暂存目录。
            modelLocks.getOrPut(spec.id) { Mutex() }.withLock {
                currentCoroutineContext().ensureActive()
                setForeground(foregroundInfo(spec, ModelTaskSnapshot(ModelStatus.QUEUED)))
                val downloader = ModelDownloader(ModelInstaller(ModelRepository.storageRoot(applicationContext)), OkHttpModelSource())
                downloader.download(spec) { progress -> report(spec, progress, journal) }
                val complete = ModelTaskSnapshot(ModelStatus.READY, spec.sizeBytes)
                journal.write(spec.id, id, complete)
                Result.success(data(complete))
            }
        } catch (error: CancellationException) {
            // 取消由 WorkManager 记录；网络约束中断可重新入队，不能误标永久取消。
            throw error
        } catch (error: IOException) {
            val retry = (error !is ModelDownloadException || error.retryable) && runAttemptCount < MAX_ATTEMPTS - 1
            val message = if (retry) "网络或文件读写异常，等待第 ${runAttemptCount + 2} 次尝试" else "模型下载失败，请检查网络后重试；未通过大小及 SHA-256 校验的文件不能启用"
            val snapshot = (lastReport ?: ModelTaskSnapshot(ModelStatus.FAILED)).copy(
                status = if (retry) ModelStatus.QUEUED else ModelStatus.FAILED,
                error = "$message。${if (error is ModelDownloadException) error.message else "本次连接或文件读写未完成"}",
            )
            Log.e("模型下载", "模型下载未完成：${spec.id}，尝试次数：${runAttemptCount + 1}", error)
            try { journal.write(spec.id, id, snapshot) } catch (journalError: IOException) {
                Log.e("模型下载", "保存失败状态时发生异常：${spec.id}", journalError)
            }
            if (retry) Result.retry() else Result.failure(data(snapshot))
        } catch (error: Exception) {
            Log.e("模型下载", "无法启动或完成模型前台下载：${spec.id}", error)
            Result.failure(workDataOf(ModelRepository.ERROR to "无法启动或完成模型下载，请检查通知及前台服务权限后重试"))
        }
    }

    /** 限制持久进度和通知刷新频率，状态、源切换与错误则立即反馈。 */
    private suspend fun report(spec: ModelSpec, snapshot: ModelTaskSnapshot, journal: ModelDownloadJournal) {
        currentCoroutineContext().ensureActive()
        val now = System.nanoTime()
        val previous = lastReport
        if (previous != null && snapshot.status == previous.status && snapshot.source == previous.source && snapshot.error == previous.error &&
            snapshot.downloadedBytes != spec.sizeBytes && now - lastReportTime < 500_000_000L) return
        lastReportTime = now
        lastReport = snapshot
        journal.write(spec.id, id, snapshot)
        setProgress(data(snapshot))
        setForeground(foregroundInfo(spec, snapshot))
    }

    /** 生成持久系统进度字段，失败结果保留错误与最后使用的源。 */
    private fun data(snapshot: ModelTaskSnapshot) = workDataOf(
        ModelRepository.STATUS to snapshot.status.name,
        ModelRepository.BYTES to snapshot.downloadedBytes,
        ModelRepository.ERROR to snapshot.error,
        ModelRepository.SOURCE to snapshot.source,
    )

    /** 通知提供真实下载进度与系统取消 PendingIntent，并声明 dataSync 服务类型。 */
    private fun foregroundInfo(spec: ModelSpec, snapshot: ModelTaskSnapshot): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "模型下载", NotificationManager.IMPORTANCE_LOW))
        val progress = (snapshot.downloadedBytes * 100L / spec.sizeBytes).toInt().coerceIn(0, 100)
        val label = when (snapshot.status) {
            ModelStatus.VERIFYING -> "正在校验模型完整性"
            ModelStatus.QUEUED -> "正在准备模型下载"
            else -> "已下载 $progress%"
        }
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载 ${spec.name}")
            .setContentText(snapshot.error ?: label)
            .setProgress(100, progress, snapshot.status == ModelStatus.VERIFYING || snapshot.status == ModelStatus.QUEUED)
            .setOnlyAlertOnce(true).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "取消下载", cancel).build())
            .build()
        val notificationId = 6_000 + ModelCatalog.entries.indexOfFirst { it.id == spec.id }
        return ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val CHANNEL = "model_downloads"
        private const val MAX_ATTEMPTS = 3
        private val modelLocks = ConcurrentHashMap<String, Mutex>()
    }
}
