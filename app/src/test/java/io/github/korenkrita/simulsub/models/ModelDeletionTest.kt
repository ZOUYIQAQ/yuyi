package io.github.korenkrita.simulsub.models

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 所有删除仅作用于新建的临时夹具，不读取应用目录或真实用户模型。 */
class ModelDeletionTest {
    private val spec = ModelCatalog.entries.first()

    /** 创建独立根目录和少量残留文件，不下载或复制真实模型。 */
    private fun fixture(): File = Files.createTempDirectory("模型删除夹具").toFile().also { root ->
        listOf("installed/asr/model.onnx", "staging-test/asr/model.onnx.part", "invalid-test/vad/silero_vad.onnx").forEach {
            File(root, "${spec.id}/$it").apply { parentFile!!.mkdirs(); writeText("临时模型内容") }
        }
    }

    /** 清理正式文件、暂存和备份，保留其他模型及未列入范围的用户文件。 */
    @Test fun deletesOnlyOwnedDirectories() {
        val root = fixture()
        val other = File(root, "sensevoice/installed/asr/model.onnx").apply { parentFile!!.mkdirs(); writeText("其他模型") }
        val note = File(root, "${spec.id}/备注.txt").apply { writeText("必须保留") }
        val result = ModelDeletion(root).delete(spec, null) { error("未选中时不应写偏好") }
        assertTrue(result.deleted)
        assertFalse(File(root, "${spec.id}/installed").exists())
        assertFalse(File(root, "${spec.id}/staging-test").exists())
        assertFalse(File(root, "${spec.id}/invalid-test").exists())
        assertEquals("其他模型", other.readText())
        assertEquals("必须保留", note.readText())
        assertFalse(ModelDeletion(root).hasLocalFiles(spec))
    }

    /** 当前选择必须在删除前持久清空，完成后不自动选择其他模型。 */
    @Test fun selectedModelIsClearedBeforeFilesAreRemoved() {
        val root = fixture()
        var selected: String? = spec.id
        val result = ModelDeletion(root).delete(spec, selected) {
            assertTrue(File(root, "${spec.id}/installed/asr/model.onnx").exists())
            selected = null
        }
        assertTrue(result.deleted)
        assertTrue(result.selectionCleared)
        assertNull(selected)
    }

    /** 删除非选中模型保留原选择。 */
    @Test fun deletingAnotherModelPreservesSelection() {
        val result = ModelDeletion(fixture()).delete(spec, "sensevoice") { error("不能清除其他选择") }
        assertTrue(result.deleted)
        assertFalse(result.selectionCleared)
    }

    /** 偏好保存失败时保留模型并返回实际错误，不伪称删除成功。 */
    @Test fun selectionPersistenceFailurePreservesFiles() {
        val root = fixture()
        val result = ModelDeletion(root).delete(spec, spec.id) { throw IOException("选择偏好写入失败：测试磁盘已满") }
        assertFalse(result.deleted)
        assertEquals("选择偏好写入失败：测试磁盘已满", result.error)
        assertTrue(ModelDeletion(root).hasLocalFiles(spec))
    }

    /** 磁盘删除失败保留真实错误，部分失败后的残留仍可发现。 */
    @Test fun fileFailureDoesNotReportSuccessAndCanBeRetried() {
        val root = fixture()
        var removed = 0
        val deletion = ModelDeletion(root) { path ->
            if (removed == 1) throw IOException("拒绝删除：$path")
            Files.delete(path)
            removed++
        }
        val result = deletion.delete(spec, spec.id) { }
        assertFalse(result.deleted)
        assertTrue(result.selectionCleared)
        assertTrue(result.error!!.startsWith("拒绝删除："))
        assertEquals(1, removed)
        assertTrue(deletion.hasLocalFiles(spec))
        assertTrue(ModelDeletion(root).delete(spec, null) { }.deleted)
        assertFalse(ModelDeletion(root).hasLocalFiles(spec))
    }

    /** 任意深度的外部链接必须在删除任何文件前被拒绝。 */
    @Test fun nestedSymlinkCannotDeleteOutsideOrPartiallyDelete() {
        val root = fixture()
        val outside = Files.createTempDirectory("模型范围外夹具").toFile()
        val evidence = File(outside, "保留.txt").apply { writeText("范围之外") }
        Files.createSymbolicLink(File(root, "${spec.id}/staging-test/outside").toPath(), outside.toPath())
        val result = ModelDeletion(root).delete(spec, null) { }
        assertFalse(result.deleted)
        assertTrue(result.error!!.contains("符号链接"))
        assertEquals("范围之外", evidence.readText())
        assertTrue(File(root, "${spec.id}/installed/asr/model.onnx").exists())
    }

    /** 模型根目录本身为链接时同样不能越界。 */
    @Test fun modelDirectorySymlinkCannotEscapeScope() {
        val root = Files.createTempDirectory("模型根链接夹具").toFile()
        val outside = fixture()
        Files.createSymbolicLink(File(root, spec.id).toPath(), File(outside, spec.id).toPath())
        assertFalse(ModelDeletion(root).delete(spec, null) { }.deleted)
        assertTrue(File(outside, "${spec.id}/installed/asr/model.onnx").exists())
    }

    /** 存储根目录的链接也不能让清理操作进入其他位置。 */
    @Test fun storageRootSymlinkCannotEscapeScope() {
        val parent = Files.createTempDirectory("存储根链接夹具").toFile()
        val outside = fixture()
        val root = File(parent, "models")
        Files.createSymbolicLink(root.toPath(), outside.toPath())
        assertFalse(ModelDeletion(root).delete(spec, null) { }.deleted)
        assertTrue(File(outside, "${spec.id}/installed/asr/model.onnx").exists())
    }

    /** 规格本身拒绝父目录和绝对路径，无法构造越界的删除标识。 */
    @Test fun modelIdCannotContainTraversal() {
        for (id in listOf("../sensevoice", "/tmp/models", "reazonspeech/../../outside")) {
            assertThrows(IllegalArgumentException::class.java) { spec.copy(id = id) }
        }
    }

    /** 缺失文件的重复删除保持幂等，但仍清除被删模型的旧选择。 */
    @Test fun missingFilesStillClearStaleSelection() {
        val root = Files.createTempDirectory("缺失模型夹具").toFile()
        var cleared = false
        assertTrue(ModelDeletion(root).delete(spec, spec.id) { cleared = true }.deleted)
        assertTrue(cleared)
    }

    /** 后台再次拒绝排队下载、运行和校验期间的删除。 */
    @Test fun activeWorkAndRunningSessionBlockDeletion() = runBlocking {
        val gate = ModelOperationGate()
        assertNotNull(gate.beginDeletion(sessionRunning = true, workPending = false))
        assertNotNull(gate.beginDeletion(sessionRunning = false, workPending = true))
        gate.withDownload {
            assertNotNull(gate.beginDeletion(sessionRunning = false, workPending = false))
        }
        assertNull(gate.beginDeletion(sessionRunning = false, workPending = false))
        gate.endDeletion()
    }

    /** 删除开始后，新下载须等待清理结束，不能在检查和删除之间重新写入。 */
    @Test fun downloadWaitsForDeletionAndReleasesAfterFailure() = runBlocking {
        val gate = ModelOperationGate()
        assertNull(gate.beginDeletion(sessionRunning = false, workPending = false))
        val entered = CompletableDeferred<Unit>()
        val job = async { gate.withDownload { entered.complete(Unit) } }
        kotlinx.coroutines.yield()
        assertFalse(entered.isCompleted)
        gate.endDeletion()
        job.await()
        try { gate.withDownload { throw IOException("测试下载失败") } } catch (_: IOException) { }
        assertNull(gate.beginDeletion(sessionRunning = false, workPending = false))
        gate.endDeletion()
    }

    /** 页面已停止但原生资源尚在清理时，租约仍然阻止删除；关闭后才允许清理文件。 */
    @Test fun sessionLeaseProtectsUntilNativeResourcesAreReleased() {
        val gate = ModelOperationGate()
        val paths = ModelPaths(spec, File("仅作内存路径，不访问磁盘"))
        val lease = requireNotNull(gate.acquireSession { paths })
        assertEquals(paths, lease.paths)
        assertNotNull(gate.beginDeletion(sessionRunning = false, workPending = false))
        lease.close()
        lease.close()
        assertNull(gate.beginDeletion(sessionRunning = false, workPending = false))
        assertNull(gate.acquireSession { paths })
        gate.endDeletion()
        requireNotNull(gate.acquireSession { paths }).close()
    }
}
