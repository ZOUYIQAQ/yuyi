package io.github.korenkrita.simulsub.models

import org.junit.Assert.*
import org.junit.Test

/** 验证持久任务恢复不会把半成品或虚假完成状态转成可选模型。 */
class ModelLibraryReducerTest {
    private val spec = ModelCatalog.entries.first()

    /** 坏摘要、截断和取消后无有效目录时都不能恢复选择。 */
    @Test fun incompleteTasksCannotRestoreSelection() {
        for (status in listOf(ModelStatus.FAILED, ModelStatus.CANCELLED, ModelStatus.DOWNLOADING, ModelStatus.VERIFYING, ModelStatus.READY)) {
            val state = ModelLibraryReducer.reduce(listOf(spec), emptySet(), spec.id, mapOf(spec.id to ModelTaskSnapshot(status, spec.sizeBytes)))
            assertNull(state.selectedId)
            assertNotEquals(ModelStatus.READY, state.entries.single().status)
        }
    }

    /** 新模型完成后保留现有选择，首次下载完成也不自动启用。 */
    @Test fun completionNeverChangesSelection() {
        val second = spec.copy(id = "second")
        val ready = setOf(spec.id, second.id)
        assertNull(ModelLibraryReducer.reduce(listOf(spec, second), ready, null, emptyMap()).selectedId)
        assertEquals(spec.id, ModelLibraryReducer.reduce(listOf(spec, second), ready, spec.id, emptyMap()).selectedId)
    }

    /** 重启必须经过本轮验证后才恢复之前用户明确选择的模型。 */
    @Test fun restartRestoresOnlyVerifiedSelection() {
        val pending = ModelLibraryReducer.reduce(listOf(spec), emptySet(), spec.id, emptyMap())
        assertNull(pending.selectedId)
        val restored = ModelLibraryReducer.reduce(listOf(spec), setOf(spec.id), spec.id, emptyMap())
        assertEquals(spec.id, restored.selectedId)
        assertEquals(1f, restored.entries.single().progress)
    }

    /** 取消的重复下载不应使已验证的正式模型失效。 */
    @Test fun cancelledTaskPreservesPreviouslyInstalledModel() {
        val state = ModelLibraryReducer.reduce(listOf(spec), setOf(spec.id), spec.id, mapOf(spec.id to ModelTaskSnapshot(ModelStatus.CANCELLED)))
        assertEquals(ModelStatus.READY, state.entries.single().status)
        assertEquals(spec.id, state.selectedId)
    }

    /** 恢复进度保持合理范围，不能显示负数或超过完整模型。 */
    @Test fun recoveredProgressIsBounded() {
        for (bytes in listOf(-5L, Long.MAX_VALUE)) {
            val state = ModelLibraryReducer.reduce(listOf(spec), emptySet(), null, mapOf(spec.id to ModelTaskSnapshot(ModelStatus.DOWNLOADING, bytes)))
            assertTrue(state.entries.single().progress in 0f..1f)
        }
    }
}
