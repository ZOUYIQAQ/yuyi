package io.github.korenkrita.simulsub

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.models.ModelRepository
import io.github.korenkrita.simulsub.models.ModelStatus
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 专用空白 QA 应用验证取消与系统重调度，不清除已有应用或模型。 */
class ModelLifecycleDeviceTest {
    /** 分阶段运行，阶段之间由本机显式停止并重启 QA 应用。 */
    @Test fun downloadLifecycle() {
        val phase = InstrumentationRegistry.getArguments().getString("lifecyclePhase")
        assumeTrue("需显式选择生命周期验收阶段", phase != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName.endsWith(".qa")) { "生命周期验收只能使用隔离 QA 应用" }
        ActivityScenario.launch(MainActivity::class.java).use {
            val repository = ModelRepository.get(context)
            when (phase) {
                "cancel", "start" -> {
                    assertNull("全新 QA 应用不能自带模型", repository.selectedPaths())
                    repository.download("reazonspeech")
                    await("实际下载开始", 90_000) { repository.state.value.entries.first().let { entry -> entry.status == ModelStatus.DOWNLOADING && entry.downloadedBytes > 0 } }
                    if (phase == "cancel") {
                        repository.cancel("reazonspeech")
                        await("取消状态", 15_000) { repository.state.value.entries.first().status == ModelStatus.CANCELLED }
                        assertNull("取消后不能启用半成品", repository.selectedPaths())
                        assertFalse("取消后模型不能标记完整", repository.isReady("reazonspeech"))
                        Log.i("下载生命周期验收", "真实网络下载已取消，半成品不可启用")
                    } else Log.i("下载生命周期验收", "下载已开始，等待从外部停止并重启 QA 应用")
                }
                "complete" -> {
                    // 此阶段不再调用download，用于证明之前任务由WorkManager自行恢复。
                    await("系统恢复并完成原下载任务", 180_000) { repository.isReady("reazonspeech") }
                    assertNull("完成下载不能自动替用户启用", repository.state.value.selectedId)
                    repository.select("reazonspeech")
                    await("手动启用", 30_000) { repository.state.value.selectedId == "reazonspeech" }
                    assertNotNull(repository.selectedPaths())
                    Log.i("下载生命周期验收", "进程重启后原任务恢复并严格安装，随后显式启用成功")
                }
                "selected" -> {
                    await("重启后模型校验与选择恢复", 30_000) { repository.selectedPaths()?.spec?.id == "reazonspeech" }
                    Log.i("下载生命周期验收", "第二次进程重启后完整性和手动选择均恢复")
                }
                else -> error("未知验收阶段：$phase")
            }
        }
    }

    /** 带截止时间等待真实仓库状态，超时明确失败。 */
    private fun await(name: String, timeout: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (!condition()) {
            assertTrue("等待$name 超时", System.currentTimeMillis() < deadline)
            Thread.sleep(100)
        }
    }
}
