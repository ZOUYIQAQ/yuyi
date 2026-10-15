package io.github.korenkrita.simulsub.models

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** 使用真实临时文件验证安装边界，不访问网络或发送告警，不删除目录。 */
class ModelInstallerTest {
    private val content = "经过验证的模型内容".toByteArray()

    /** 构造具有真实摘要的小模型，同时覆盖识别文件与 VAD。 */
    private fun spec(): ModelSpec {
        val hash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
        val files = listOf("asr/model.onnx", "vad/silero_vad.onnx").map {
            ModelFile(it, content.size.toLong(), hash, listOf("https://example.com/$it"))
        }
        return ModelSpec("test", "测试模型", "测试模型", listOf("中文"), files.sumOf { it.sizeBytes }, ModelKind.SENSEVOICE, files)
    }

    /** 写入完整暂存内容，测试结束保留临时证据。 */
    private fun populate(staging: File, spec: ModelSpec) {
        spec.files.forEach { File(staging, it.path).apply { requireNotNull(parentFile).mkdirs(); writeBytes(content) } }
    }

    /** 所有文件严格通过后才允许整体安装，并能在新实例恢复。 */
    @Test fun completeModelSurvivesRestart() {
        val root = Files.createTempDirectory("模型安装测试").toFile()
        val installer = ModelInstaller(root)
        val spec = spec()
        val staging = installer.newStaging(spec)
        populate(staging, spec)
        assertNull(installer.verifyInstalled(spec))
        val installed = installer.commit(spec, staging)
        assertEquals(File(root, "test/installed"), installed.directory)
        assertNotNull(ModelInstaller(root).verifyInstalled(spec))
    }

    /** 长度相同但摘要错误的文件绝不能进入正式目录。 */
    @Test fun wrongHashCannotBeEnabled() {
        val installer = ModelInstaller(Files.createTempDirectory("坏摘要测试").toFile())
        val spec = spec()
        val staging = installer.newStaging(spec)
        populate(staging, spec)
        File(staging, spec.files.first().path).writeBytes(ByteArray(content.size))
        assertThrows(IOException::class.java) { installer.commit(spec, staging) }
        assertNull(installer.verifyInstalled(spec))
        assertTrue(staging.exists())
    }

    /** 截断和超长内容都必须校验失败。 */
    @Test fun truncatedAndOversizedFilesCannotBeEnabled() {
        val installer = ModelInstaller(Files.createTempDirectory("长度测试").toFile())
        val spec = spec()
        for (bytes in listOf(content.copyOf(content.size - 1), content + byteArrayOf(0))) {
            val staging = installer.newStaging(spec)
            populate(staging, spec)
            File(staging, spec.files.last().path).writeBytes(bytes)
            assertThrows(IOException::class.java) { installer.commit(spec, staging) }
            assertNull(installer.verifyInstalled(spec))
        }
    }

    /** 取消必须向上传播，不能留下可启用的半成品。 */
    @Test fun interruptionCannotBeEnabled() {
        val installer = ModelInstaller(Files.createTempDirectory("中断测试").toFile())
        val spec = spec()
        val staging = installer.newStaging(spec)
        populate(staging, spec)
        assertThrows(CancellationException::class.java) {
            installer.commit(spec, staging) { throw CancellationException("测试中断") }
        }
        assertNull(installer.verifyInstalled(spec))
        assertTrue(staging.exists())
    }

    /** 已有完整模型不会被后续失败安装破坏。 */
    @Test fun failedInstallPreservesExistingModel() {
        val installer = ModelInstaller(Files.createTempDirectory("旧模型保留测试").toFile())
        val spec = spec()
        installer.commit(spec, installer.newStaging(spec).also { populate(it, spec) })
        val failed = installer.newStaging(spec)
        populate(failed, spec)
        File(failed, spec.files.first().path).writeText("截断")
        assertThrows(IOException::class.java) { installer.commit(spec, failed) }
        assertNotNull(installer.verifyInstalled(spec))
    }

    /** 不接受跨出模型目录的暂存目录。 */
    @Test fun foreignStagingIsRejected() {
        val installer = ModelInstaller(Files.createTempDirectory("目录约束测试").toFile())
        val staging = Files.createTempDirectory("外部暂存测试").toFile()
        val spec = spec()
        populate(staging, spec)
        assertThrows(IllegalArgumentException::class.java) { installer.commit(spec, staging) }
    }

    /** 摘要匹配的符号链接也不能让正式文件指向目录之外。 */
    @Test fun symbolicLinkCannotBeEnabled() {
        val installer = ModelInstaller(Files.createTempDirectory("链接测试").toFile())
        val spec = spec()
        val staging = installer.newStaging(spec)
        populate(staging, spec)
        val outside = Files.createTempFile("外部模型", ".onnx").toFile().apply { writeBytes(content) }
        val candidate = File(staging, "asr/linked.onnx")
        Files.createSymbolicLink(candidate.toPath(), outside.toPath())
        val linkedSpec = spec.copy(files = listOf(spec.files.first().copy(path = "asr/linked.onnx"), spec.files.last()))
        assertThrows(IOException::class.java) { installer.commit(linkedSpec, staging) }
        assertNull(installer.verifyInstalled(linkedSpec))
    }
}
