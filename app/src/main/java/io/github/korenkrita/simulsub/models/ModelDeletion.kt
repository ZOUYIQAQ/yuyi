package io.github.korenkrita.simulsub.models

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** deleted 仅在全部范围清理成功后为真；部分失败保留真实错误与选择清理结果。 */
data class ModelDeleteResult(val id: String, val deleted: Boolean, val selectionCleared: Boolean = false, val error: String? = null)

/** 只清理固定模型的安装、断点暂存和损坏备份，绝不递归删除整个模型根目录。 */
internal class ModelDeletion(private val root: File, private val remove: (Path) -> Unit = { Files.delete(it) }) {
    /** 目录存在不代表模型完整，但正式、暂存或备份中的残留均应允许用户请求清理。 */
    fun hasLocalFiles(spec: ModelSpec): Boolean {
        val directory = File(root, spec.id)
        if (Files.isSymbolicLink(root.toPath()) || Files.isSymbolicLink(directory.toPath())) return true
        if (!directory.exists()) return false
        if (!directory.isDirectory) return true
        return children(directory).any { owned(it.name) }
    }

    /** 预检整个范围后先清除被删模型的选择，再逐项删除；任何异常都不能伪报成功。 */
    fun delete(spec: ModelSpec, selectedId: String?, clearSelection: () -> Unit): ModelDeleteResult {
        var selectionCleared = false
        return try {
            val directory = checkedDirectory(spec)
            val paths = if (!Files.exists(directory, NOFOLLOW_LINKS)) emptyList() else
                children(directory.toFile()).filter { owned(it.name) }.flatMap { collect(it.toPath(), directory) }
            if (selectedId == spec.id) {
                clearSelection()
                selectionCleared = true
            }
            paths.forEach { path ->
                validate(path, directory)
                remove(path)
            }
            if (hasLocalFiles(spec)) throw IOException("删除后仍存在模型文件：$directory")
            ModelDeleteResult(spec.id, deleted = true, selectionCleared = selectionCleared)
        } catch (error: Exception) {
            ModelDeleteResult(spec.id, deleted = false, selectionCleared = selectionCleared,
                error = error.message ?: "${error.javaClass.simpleName}：删除模型失败")
        }
    }

    /** 固定删除范围与安装器的目录规则一致，供正式确认弹窗逐项展示。 */
    fun scope(spec: ModelSpec): String = "models/${spec.id}/installed/（识别模型和 VAD）\n" +
        "models/${spec.id}/staging-*/（下载暂存及 .part 文件）\n" +
        "models/${spec.id}/invalid-*/（损坏旧版备份）"

    /** 拒绝模型根链接和非目录节点，父目录采用真实路径避免系统目录别名干扰。 */
    private fun checkedDirectory(spec: ModelSpec): Path {
        if (Files.isSymbolicLink(root.toPath())) throw IOException("模型存储根目录不能是符号链接：$root")
        val directory = File(root.canonicalFile, spec.id).toPath()
        if (Files.isSymbolicLink(directory)) throw IOException("模型目录不能是符号链接：$directory")
        if (Files.exists(directory, NOFOLLOW_LINKS) && !Files.isDirectory(directory, NOFOLLOW_LINKS)) {
            throw IOException("模型目录不是有效目录：$directory")
        }
        return directory
    }

    /** 只有安装器定义的三种子目录属于模型删除范围，其他文件一律保留。 */
    private fun owned(name: String): Boolean = name == "installed" ||
        (name.startsWith("staging-") && name.length > "staging-".length) ||
        (name.startsWith("invalid-") && name.length > "invalid-".length)

    /** 目录枚举失败必须报告，不把权限或磁盘异常解释成空目录。 */
    private fun children(directory: File): List<File> = directory.listFiles()?.toList()
        ?: throw IOException("无法读取模型目录：$directory")

    /** 不跟随链接遍历并生成子项在前的删除计划，预检失败时尚未修改任何模型文件。 */
    private fun collect(start: Path, directory: Path): List<Path> {
        val paths = mutableListOf<Path>()
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            /** 每次进入目录先验证边界，禁止把外部目录纳入删除计划。 */
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                validate(dir, directory)
                return FileVisitResult.CONTINUE
            }

            /** 普通文件也逐项检查符号链接，不能通过文件链接绕过目录边界。 */
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                validate(file, directory)
                paths.add(file)
                return FileVisitResult.CONTINUE
            }

            /** 子项枚举完成后再加入目录，保证清理顺序并传播枚举异常。 */
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                paths.add(dir)
                return FileVisitResult.CONTINUE
            }
        })
        return paths
    }

    /** 在预检与真正删除时重复校验路径，拒绝中途变成链接或跨模型目录的节点。 */
    private fun validate(path: Path, directory: Path) {
        val relative = directory.relativize(path.normalize())
        if (!path.normalize().startsWith(directory) || relative.nameCount == 0 || !owned(relative.getName(0).toString())) {
            throw IOException("文件超出模型删除范围：$path")
        }
        if (Files.isSymbolicLink(path) || path.toFile().canonicalFile.toPath() != path.normalize()) {
            throw IOException("删除范围包含符号链接：$path")
        }
    }
}
