package io.github.korenkrita.simulsub

import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.models.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 仅允许删除主代理为新包复制的公开验收模型，必须显式参数与标记同时存在。 */
class ModelDeletionRepositoryDeviceTest {
    /** 真实仓库删除选中模型并重启复验，不借用旧应用数据或偷偷下载恢复。 */
    @Test fun selectedFixtureDeletionPersistsAcrossRestart() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("modelDeletePhase")
        assumeTrue("仅执行指定验收阶段", phase != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "io.github.zouyiqaq.yuyi") { "必须是本轮羽译新包" }
        require(File(context.filesDir, "qa-model-delete-authorized").readText().contains("公开模型验收副本")) { "必须存在本轮测试副本标记" }
        val repository = ModelRepository.get(context)
        when (phase) {
            "delete" -> {
                waitFor("两套公开模型副本通过校验") { repository.state.value.entries.all { it.status == ModelStatus.READY } }
                repository.select("sensevoice")
                waitFor("显式启用测试模型") { repository.state.value.selectedId == "sensevoice" }
                val protected = repository.acquireSelectedForSession()!!
                try {
                    val blocked = repository.delete("sensevoice")
                    assertFalse("原生租约未释放时删除必须失败", blocked.deleted)
                    assertTrue(blocked.error!!.contains("同传"))
                    assertNotNull(repository.selectedPaths())
                } finally { protected.close() }
                val deleted = repository.delete("sensevoice")
                assertTrue(deleted.error, deleted.deleted)
                assertTrue(deleted.selectionCleared)
                assertNull(repository.state.value.selectedId)
                assertNull(context.getSharedPreferences("model_library", 0).getString("selected_id", null))
                assertNull(repository.selectedPaths())
                assertEquals(ModelStatus.NOT_INSTALLED, repository.state.value.entries.first { it.spec.id == "sensevoice" }.status)
                assertFalse(File(context.filesDir, "models/sensevoice/installed").exists())
                assertEquals(ModelStatus.READY, repository.state.value.entries.first { it.spec.id == "reazonspeech" }.status)
            }
            "restored" -> {
                waitFor("重启后的其他模型验证") { repository.state.value.entries.first { it.spec.id == "reazonspeech" }.status == ModelStatus.READY }
                assertNull(repository.state.value.selectedId)
                assertNull(repository.selectedPaths())
                assertEquals(ModelStatus.NOT_INSTALLED, repository.state.value.entries.first { it.spec.id == "sensevoice" }.status)
                assertFalse(repository.state.value.entries.first { it.spec.id == "sensevoice" }.hasLocalFiles)
                repository.select("reazonspeech")
                waitFor("手动启用保留的模型") { repository.selectedPaths()?.spec?.id == "reazonspeech" }
            }
            else -> error("未知删除验收阶段：$phase")
        }
    }

    /** 每次等待有明确上限，后台异常会报告失败而不是一直阻塞。 */
    private fun waitFor(label: String, ready: () -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!ready()) {
            assertTrue("等待$label 超时", System.nanoTime() < deadline)
            Thread.sleep(50)
        }
    }
}
