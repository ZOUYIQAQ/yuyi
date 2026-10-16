package io.github.korenkrita.simulsub

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.models.ModelRepository
import io.github.korenkrita.simulsub.models.ModelStatus
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 真机/模拟器的大型测试：真实下载目录中的模型必须能够完成实际离线解码。 */
class ModelDeviceTest {
    /** 按参数选择模型，验证下载、严格安装、显式选择、VAD与实际音频识别。 */
    @Test fun downloadSelectAndRecognize() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = InstrumentationRegistry.getArguments().getString("model") ?: "reazonspeech"
        ActivityScenario.launch(MainActivity::class.java).use {
            val repository = ModelRepository.get(context)
            repository.download(id)
            val deadline = System.currentTimeMillis() + 600_000
            var previous = ""
            while (true) {
                val entry = repository.state.value.entries.first { model -> model.spec.id == id }
                val status = "${entry.status} ${entry.downloadedBytes}/${entry.totalBytes} ${entry.error.orEmpty()}"
                if (status != previous) { Log.i("模型设备验收", "$id：$status"); previous = status }
                if (entry.status == ModelStatus.READY) break
                if (entry.status == ModelStatus.FAILED) fail("真实模型下载失败：${entry.error}")
                assertTrue("下载超过测试时间：$status", System.currentTimeMillis() < deadline)
                Thread.sleep(500)
            }
            repository.select(id)
            val selectionDeadline = System.currentTimeMillis() + 30_000
            while (repository.state.value.selectedId != id && System.currentTimeMillis() < selectionDeadline) Thread.sleep(100)
            assertEquals("必须由用户显式选择模型", id, repository.state.value.selectedId)
            val paths = requireNotNull(repository.selectedPaths())
            assertEquals(id, paths.spec.id)
            val samples = waveSamples(instrumentation.context.assets.open("ja.wav").use { stream -> stream.readBytes() })
            val asr = SherpaAsr(paths, CaptionService.ASR_THREADS)
            val vad = SileroProb(paths.directory)
            try {
                val started = System.nanoTime()
                val text = asr.recognize(samples)
                Log.i("模型设备验收", "$id 实际识别原文：$text；解码耗时 ${(System.nanoTime() - started) / 1_000_000} 毫秒")
                assertTrue("模型未识别出日语文本：$text", text.contains(Regex("[\\u3040-\\u30ff\\u4e00-\\u9fff]")))
                val probabilities = samples.toList().chunked(512).filter { frame -> frame.size == 512 }.map { frame -> vad.prob(frame.toFloatArray()) }
                assertTrue("VAD 未检测到示例音频中的语音", probabilities.any { probability -> probability > 0.5f })
            } finally { vad.release(); asr.release() }
        }
    }

    /** 使用正式识别线程配置读取已装Reazon模型，验证原生时间戳映射，不下载或切换模型。 */
    @Test fun installedReazonProvidesNativeTokenTimings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ActivityScenario.launch(MainActivity::class.java).use {
            val repository = ModelRepository.get(context)
            val deadline = System.currentTimeMillis() + 30_000
            while (repository.selectedPaths() == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
            val paths = requireNotNull(repository.selectedPaths()) { "需已有启用的Reazon模型，测试不下载或切换模型" }
            assertEquals("reazonspeech", paths.spec.id)
            val samples = waveSamples(instrumentation.context.assets.open("ja.wav").use { stream -> stream.readBytes() })
            val asr = SherpaAsr(paths, CaptionService.ASR_THREADS)
            try {
                val result = asr.recognizeTimed(samples)
                val pieces = requireNotNull(result.pieces) { "Reazon原生结果缺少可映射的分词时间戳：${result.text}" }
                assertTrue("必须实际识别出示例日语", result.text.contains("中学"))
                assertTrue("时间片段不能为空", pieces.isNotEmpty())
                assertEquals("按时间片段拼接必须还原完整原文", result.text, pieces.joinToString("") { piece -> piece.text })
                assertTrue("时间戳必须有限、按序且落在补偿后的音频范围", pieces.all { piece ->
                    piece.t.isFinite() && piece.t >= -0.3f && piece.t <= samples.size / 16_000f + 0.3f
                } && pieces.zipWithNext().all { (a, b) -> a.t <= b.t })
                Log.i("模型设备验收", "真实Reazon时间戳已映射：${pieces.size}个片段；原文：${result.text}")
            } finally { asr.release() }
        }
    }

    /** 读取测试仓库提供的16kHz单声道PCM16 WAV，按RIFF块定位而非假定44字节头。 */
    private fun waveSamples(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val name = String(bytes, offset, 4, Charsets.US_ASCII)
            val count = buffer.getInt(offset + 4)
            require(count >= 0 && offset + 8L + count <= bytes.size)
            if (name == "fmt ") {
                require(buffer.getShort(offset + 8).toInt() == 1)
                require(buffer.getShort(offset + 10).toInt() == 1)
                require(buffer.getInt(offset + 12) == 16_000)
                require(buffer.getShort(offset + 22).toInt() == 16)
            }
            if (name == "data") return FloatArray(count / 2) { i -> buffer.getShort(offset + 8 + i * 2) / 32768f }
            offset += 8 + count + count % 2
        }
        error("测试音频缺少PCM数据")
    }
}
