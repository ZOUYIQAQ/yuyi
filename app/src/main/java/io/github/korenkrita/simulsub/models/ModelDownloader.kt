package io.github.korenkrita.simulsub.models

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 下载源负责连接生命周期，接收方在连接关闭前流式消费内容。 */
internal fun interface ModelDownloadSource {
    /** 打开受控地址并在取消时中止底层连接，未知响应长度使用负数表示。 */
    suspend fun transfer(url: String, receive: suspend (InputStream, Long) -> Unit)
}

/** 向系统调度层区分可重试网络失败与不可接受的完整性失败。 */
internal class ModelDownloadException(message: String, val retryable: Boolean) : IOException(message)

/** 只进行 IO 的下载核心；调用方负责 IO 调度与同一模型的任务互斥。 */
internal class ModelDownloader(private val installer: ModelInstaller, private val source: ModelDownloadSource) {
    /** 逐文件复用、下载与验证，最后整体原子安装，不修改模型选择。 */
    suspend fun download(spec: ModelSpec, report: suspend (ModelTaskSnapshot) -> Unit = {}): ModelPaths {
        val coroutine = currentCoroutineContext()
        val checkActive = { coroutine.ensureActive() }
        installer.verifyInstalled(spec, checkActive)?.let { return it }
        val staging = installer.resumableStaging(spec)
        var completed = 0L
        for (file in spec.files) {
            coroutine.ensureActive()
            val destination = File(staging, file.path)
            report(ModelTaskSnapshot(ModelStatus.VERIFYING, completed))
            if (!ModelInstaller.verifyFile(destination, file, checkActive)) {
                val parent = requireNotNull(destination.parentFile) { "模型文件缺少父目录：${file.path}" }
                if (!parent.isDirectory && !parent.mkdirs()) throw IOException("无法创建模型文件目录：${file.path}")
                var installed = false
                var retryable = false
                for (url in file.sources) {
                    coroutine.ensureActive()
                    val part = File(parent, "${destination.name}.part")
                    try {
                        report(ModelTaskSnapshot(ModelStatus.DOWNLOADING, completed, source = url))
                        source.transfer(url) { input, length ->
                            if (length >= 0 && length != file.sizeBytes) throw ModelIntegrityException("响应长度不符合固定清单：${file.path}")
                            var received = 0L
                            FileOutputStream(part, false).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    coroutine.ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    received += read
                                    if (received > file.sizeBytes) throw ModelIntegrityException("响应文件超出固定长度：${file.path}")
                                    output.write(buffer, 0, read)
                                    report(ModelTaskSnapshot(ModelStatus.DOWNLOADING, completed + received, source = url))
                                }
                                coroutine.ensureActive()
                                if (received != file.sizeBytes) throw ModelIntegrityException("响应文件被截断：${file.path}")
                                output.fd.sync()
                            }
                        }
                        report(ModelTaskSnapshot(ModelStatus.VERIFYING, completed + file.sizeBytes, source = url))
                        installer.installFile(part, destination, file, checkActive)
                        installed = true
                        break
                    } catch (error: IOException) {
                        coroutine.ensureActive()
                        if (error !is ModelIntegrityException) retryable = true
                        report(ModelTaskSnapshot(ModelStatus.DOWNLOADING, completed, "下载源失败，正在尝试备用源：${file.path}", url))
                    }
                }
                if (!installed) throw ModelDownloadException("所有下载源均失败或未通过固定大小及 SHA-256 校验：${file.path}", retryable)
            }
            completed += file.sizeBytes
            report(ModelTaskSnapshot(ModelStatus.VERIFYING, completed))
        }
        coroutine.ensureActive()
        return installer.commit(spec, staging, checkActive)
    }
}
