package io.github.korenkrita.simulsub.models

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** 只负责磁盘完整性和原子安装；调用者必须在 IO 线程执行。 */
internal class ModelInstaller(private val root: File) {
    /** 为本次安装分配独立暂存目录，重启时不会误认为已经可用。 */
    fun newStaging(spec: ModelSpec): File = File(root, "${spec.id}/staging-${UUID.randomUUID()}").apply {
        if (!mkdirs()) throw IOException("无法创建模型暂存目录")
    }

    /** 同一可信清单使用固定暂存区，重试和进程恢复复用已验证文件而不累积目录。 */
    fun resumableStaging(spec: ModelSpec): File {
        val manifest = spec.files.joinToString("\n") { "${it.path}:${it.sizeBytes}:${it.sha256}" }
        val hash = MessageDigest.getInstance("SHA-256").digest(manifest.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(root, "${spec.id}/staging-$hash").apply {
            if (!isDirectory && !mkdirs()) throw IOException("无法创建模型暂存目录")
        }
    }

    /** 单个临时文件通过长度和摘要校验后，才原子转入暂存清单路径。 */
    fun installFile(part: File, destination: File, spec: ModelFile, checkActive: () -> Unit = {}) {
        checkActive()
        if (!verifyFile(part, spec, checkActive)) throw ModelIntegrityException("文件长度或 SHA-256 校验失败：${spec.path}")
        checkActive()
        Files.move(part.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** 重新验证每个正式文件，不信任目录存在、完成标志或上次下载状态。 */
    fun verifyInstalled(spec: ModelSpec, checkActive: () -> Unit = {}): ModelPaths? {
        val directory = File(root, "${spec.id}/installed")
        return if (spec.files.all { checkActive(); verifyFile(File(directory, it.path), it, checkActive) }) ModelPaths(spec, directory) else null
    }

    /** 逐个校验完整暂存模型后整体原子转正，失败不会覆盖有效的旧模型。 */
    fun commit(spec: ModelSpec, staging: File, checkActive: () -> Unit = {}): ModelPaths {
        require(staging.canonicalFile.parentFile == File(root, spec.id).canonicalFile && staging.name.startsWith("staging-")) {
            "暂存目录不属于此模型"
        }
        for (file in spec.files) {
            checkActive()
            if (!verifyFile(File(staging, file.path), file, checkActive)) throw IOException("模型文件校验失败：${file.path}")
        }
        checkActive()
        verifyInstalled(spec, checkActive)?.let { return it }
        val destination = File(root, "${spec.id}/installed")
        if (destination.exists() && !destination.renameTo(File(destination.parentFile, "invalid-${UUID.randomUUID()}"))) {
            throw IOException("无法隔离损坏的旧模型")
        }
        if (!staging.renameTo(destination)) throw IOException("模型原子安装失败")
        return ModelPaths(spec, destination)
    }

    companion object {
        /** 严格核对固定长度和流式 SHA-256，不将读文件异常当作验证成功。 */
        fun verifyFile(file: File, spec: ModelFile, checkActive: () -> Unit = {}): Boolean {
            if (Files.isSymbolicLink(file.toPath()) || !file.isFile || file.length() != spec.sizeBytes) return false
            val parent = file.absoluteFile.parentFile ?: return false
            val directory = parent.parentFile ?: return false
            if (Files.isSymbolicLink(parent.toPath()) || Files.isSymbolicLink(directory.toPath())) return false
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        if (count > spec.sizeBytes) return false
                        digest.update(buffer, 0, read)
                    }
                }
                count == spec.sizeBytes && digest.digest().joinToString("") { "%02x".format(it) } == spec.sha256
            } catch (_: IOException) {
                false
            }
        }
    }
}

/** 完整性错误与暂时的网络错误分开，所有受控源均不匹配时停止自动重试。 */
internal class ModelIntegrityException(message: String) : IOException(message)
