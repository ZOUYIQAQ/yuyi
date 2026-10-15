package io.github.korenkrita.simulsub.models

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 使用可控数据源覆盖坏摘要、截断、中断、源回退与重试复用，不请求外网。 */
class ModelDownloaderTest {
    private val content = "固定模型字节".toByteArray()

    /** 构造两个受控源和固定摘要的最小测试模型。 */
    private fun spec(): ModelSpec {
        val hash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
        val files = listOf("asr/model.onnx", "vad/silero_vad.onnx").map {
            ModelFile(it, content.size.toLong(), hash, listOf("https://first.example/$it", "https://backup.example/$it"))
        }
        return ModelSpec("sample", "测试模型", "测试", listOf("中文"), files.sumOf { it.sizeBytes }, ModelKind.SENSEVOICE, files)
    }

    /** 使用给定字节模拟响应，不提供 Content-Length 以检查真实流长度。 */
    private fun source(bytes: ByteArray) = ModelDownloadSource { _, receive -> receive(ByteArrayInputStream(bytes), -1L) }

    /** 正确长度但错误摘要的响应不能完成安装。 */
    @Test fun badHashCannotBecomeReady() = runBlocking {
        val installer = ModelInstaller(Files.createTempDirectory("下载坏摘要测试").toFile())
        val error = assertThrows(ModelDownloadException::class.java) {
            runBlocking { ModelDownloader(installer, source(ByteArray(content.size))).download(spec()) }
        }
        assertFalse(error.retryable)
        assertNull(installer.verifyInstalled(spec()))
    }

    /** 缺少长度头的截断与超长响应仍必须失败。 */
    @Test fun wrongActualLengthCannotBecomeReady() = runBlocking {
        for (bytes in listOf(content.copyOf(content.size - 1), content + byteArrayOf(0))) {
            val installer = ModelInstaller(Files.createTempDirectory("下载长度测试").toFile())
            assertThrows(ModelDownloadException::class.java) {
                runBlocking { ModelDownloader(installer, source(bytes)).download(spec()) }
            }
            assertNull(installer.verifyInstalled(spec()))
        }
    }

    /** 中断异常必须保留为取消，不尝试备用源，也不安装模型。 */
    @Test fun interruptionDoesNotFallbackOrInstall() = runBlocking {
        val installer = ModelInstaller(Files.createTempDirectory("下载中断测试").toFile())
        var calls = 0
        val source = ModelDownloadSource { _, receive ->
            calls++
            receive(object : InputStream() {
                /** 让网络读取发生真实中断，验证不会被网络错误分支吞掉。 */
                override fun read(): Int = throw CancellationException("测试取消下载")
            }, -1L)
        }
        assertThrows(CancellationException::class.java) { runBlocking { ModelDownloader(installer, source).download(spec()) } }
        assertEquals(1, calls)
        assertNull(installer.verifyInstalled(spec()))
    }

    /** 首源网络异常后应依序使用备用源并完成整个模型。 */
    @Test fun networkFailureFallsBackToVerifiedSource() = runBlocking {
        val installer = ModelInstaller(Files.createTempDirectory("下载源回退测试").toFile())
        val urls = mutableListOf<String>()
        val source = ModelDownloadSource { url, receive ->
            urls += url
            if (url.startsWith("https://first")) throw IOException("测试网络中断")
            receive(ByteArrayInputStream(content), content.size.toLong())
        }
        ModelDownloader(installer, source).download(spec())
        assertNotNull(installer.verifyInstalled(spec()))
        assertEquals(listOf("https://first.example/asr/model.onnx", "https://backup.example/asr/model.onnx", "https://first.example/vad/silero_vad.onnx", "https://backup.example/vad/silero_vad.onnx"), urls)
    }

    /** 错误摘要也应回退，但只有可信备用源的正确字节才能安装。 */
    @Test fun corruptSourceFallsBackWithoutInstallingCorruptFile() = runBlocking {
        val installer = ModelInstaller(Files.createTempDirectory("坏源回退测试").toFile())
        val source = ModelDownloadSource { url, receive ->
            val bytes = if (url.startsWith("https://first")) ByteArray(content.size) else content
            receive(ByteArrayInputStream(bytes), bytes.size.toLong())
        }
        ModelDownloader(installer, source).download(spec())
        assertNotNull(installer.verifyInstalled(spec()))
    }

    /** 所有源均网络失败时允许系统有限重试，依然不能启用。 */
    @Test fun unavailableSourcesAreRetryableButNotReady() = runBlocking {
        val installer = ModelInstaller(Files.createTempDirectory("下载重试测试").toFile())
        val source = ModelDownloadSource { _, _ -> throw IOException("测试网络不可用") }
        val error = assertThrows(ModelDownloadException::class.java) {
            runBlocking { ModelDownloader(installer, source).download(spec()) }
        }
        assertTrue(error.retryable)
        assertNull(installer.verifyInstalled(spec()))
    }

    /** 进程重启复用已验证文件和相同暂存目录，不重复下载已完成的识别文件。 */
    @Test fun restartReusesVerifiedFilesAndStaging() = runBlocking {
        val root = Files.createTempDirectory("下载恢复测试").toFile()
        val installer = ModelInstaller(root)
        val spec = spec()
        val first = ModelDownloadSource { url, receive ->
            if (url.contains("/vad/")) throw CancellationException("测试进程停止")
            receive(ByteArrayInputStream(content), content.size.toLong())
        }
        assertThrows(CancellationException::class.java) { runBlocking { ModelDownloader(installer, first).download(spec) } }
        val staging = installer.resumableStaging(spec)
        assertTrue(File(staging, "asr/model.onnx").isFile)
        assertNull(installer.verifyInstalled(spec))
        val resumed = ModelInstaller(root)
        assertEquals(staging, resumed.resumableStaging(spec))
        val urls = mutableListOf<String>()
        ModelDownloader(resumed, ModelDownloadSource { url, receive ->
            urls += url
            receive(ByteArrayInputStream(content), content.size.toLong())
        }).download(spec)
        assertEquals(listOf("https://first.example/vad/silero_vad.onnx"), urls)
        assertNotNull(resumed.verifyInstalled(spec))
    }
}
