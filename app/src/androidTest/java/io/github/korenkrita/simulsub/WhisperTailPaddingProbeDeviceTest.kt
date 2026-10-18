package io.github.korenkrita.simulsub

import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import io.github.korenkrita.simulsub.models.ModelCatalog
import io.github.korenkrita.simulsub.models.ModelPaths
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 单独比较 Whisper 尾补帧数，不改生产引擎，也不将诊断结果写入模型卡片。 */
class WhisperTailPaddingProbeDeviceTest {
    /** 对英语重复坏例及官方日语样例逐项保留原始识别文本，供人工对照。 */
    @Test(timeout = 600_000) fun compareTailPaddings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val external = requireNotNull(context.getExternalFilesDir(null))
        val root = File(external, ".qa_models")
        val spec = requireNotNull(ModelCatalog.entries.find { it.id == "whisper-tiny" })
        val directory = File(root, spec.id)
        spec.files.forEach { verifyFile(File(directory, it.path), it.sizeBytes, it.sha256) }

        val standardRoot = File(root, "model-standard")
        val manifestFile = File(standardRoot, "manifest.json")
        assertTrue("缺少样本清单：${manifestFile.path}", manifestFile.isFile)
        val manifestBytes = manifestFile.readBytes()
        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
        assertEquals("google/fleurs", manifest.getString("dataset"))
        assertEquals("validation", manifest.getString("split"))
        val entries = manifest.getJSONArray("entries")
        val cases = listOf(6, 9, 19).map { index ->
            val row = (0 until entries.length()).map { entries.getJSONObject(it) }
                .single { it.getString("config") == "en_us" && it.getInt("row_idx") == index }
            val wav = File(standardRoot, row.getString("audio_file"))
            verifyFile(wav, row.getLong("audio_bytes"), row.getString("audio_sha256"))
            ProbeCase("en_us/$index", Lang.EN, row.getString("reference"), readWave(wav.readBytes()))
        } + run {
            val wav = File(root, "wavs/ja-16k.wav")
            verifyFile(wav, 265_274L, "b860062f60fbed09e6fc14373a90355673eec147d5cb258909579bf7239b549a")
            listOf(ProbeCase("official/ja-16k.wav", Lang.JA, null, readWave(wav.readBytes())))
        }

        val results = JSONArray()
        for (padding in listOf(300, 50, 0, -1)) {
            for (language in listOf(Lang.EN, Lang.JA)) {
                val config = SherpaAsr.modelConfig(ModelPaths(spec, directory), 2, language)
                val whisper = config.whisper.copy(tailPaddings = padding)
                val recognizer = OfflineRecognizer(
                    config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                        modelConfig = config.copy(whisper = whisper),
                        decodingMethod = "greedy_search",
                    ),
                )
                try {
                    cases.filter { it.language == language }.forEach { sample ->
                        val stream = recognizer.createStream()
                        try {
                            val started = System.nanoTime()
                            var failure: String? = null
                            val rawText = try {
                                stream.acceptWaveform(sample.samples, 16_000)
                                recognizer.decode(stream)
                                recognizer.getResult(stream).text
                            } catch (error: Exception) {
                                failure = error.toString()
                                ""
                            }
                            val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
                            results.put(JSONObject().apply {
                                put("case", sample.id)
                                put("language", language.code)
                                put("reference", sample.reference ?: JSONObject.NULL)
                                put("requested_tail_paddings", padding)
                                put("effective_tail_paddings", if (padding > 0) padding else 1000)
                                put("raw_hypothesis", rawText)
                                put("decode_ms", elapsedMs)
                                put("decode_error", failure ?: JSONObject.NULL)
                            })
                            Log.i("Whisper尾补诊断", "样本=${sample.id}；尾补=$padding；原文=$rawText；错误=${failure ?: "无"}")
                        } finally {
                            stream.release()
                        }
                    }
                } finally {
                    recognizer.release()
                }
            }
        }
        assertEquals("四种尾补设置，每种四条样本", 16, results.length())
        val output = JSONObject().apply {
            put("purpose", "Whisper 尾补帧数与重复识别诊断；非正式 FLEURS 统计")
            put("model_id", spec.id)
            put("model_file_sha256", JSONObject().apply { spec.files.forEach { put(it.path, it.sha256) } })
            put("manifest_sha256", sha256(manifestBytes))
            put("device", Build.MODEL)
            put("android_api", Build.VERSION.SDK_INT)
            put("threads", 2)
            put("upstream_note", "sherpa-onnx 1.13.8 将尾补 0 和 -1 均映射为 1000 帧")
            put("results", results)
        }
        val outputRoot = File(external, "qa-model-standard")
        assertTrue("无法创建结果目录：${outputRoot.path}", outputRoot.isDirectory || outputRoot.mkdirs())
        val outputFile = File(outputRoot, "whisper-tail-padding-${System.currentTimeMillis()}.json")
        outputFile.writeText(output.toString(2) + "\n", Charsets.UTF_8)
        Log.i("Whisper尾补诊断", "完整结果：${outputFile.path}")
    }

    /** 固定样本在构造识别器前读入，避免文件读取影响对照结果。 */
    private data class ProbeCase(val id: String, val language: Lang, val reference: String?, val samples: FloatArray)

    /** 校验夹具字节数和摘要，确保试验使用同一固定导出及音频。 */
    private fun verifyFile(file: File, expectedSize: Long, expectedHash: String) {
        assertTrue("缺少夹具：${file.path}", file.isFile)
        assertEquals("夹具长度错误：${file.path}", expectedSize, file.length())
        assertEquals("夹具摘要错误：${file.path}", expectedHash, sha256(file))
    }

    /** 计算文件 SHA-256，避免一次性加载大模型。 */
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val block = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(block)
                if (count < 0) break
                digest.update(block, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** 计算清单 SHA-256，将诊断绑定到对应的 FLEURS 样本。 */
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** 读取 16 kHz 单声道 PCM16 WAV，与标准小样本测试保持相同输入。 */
    private fun readWave(bytes: ByteArray): FloatArray {
        require(bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "样本不是 RIFF/WAVE" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var validFormat = false
        while (offset + 8 <= bytes.size) {
            val name = String(bytes, offset, 4, Charsets.US_ASCII)
            val count = buffer.getInt(offset + 4)
            require(count >= 0 && offset + 8L + count <= bytes.size) { "样本块长度错误" }
            if (name == "fmt ") {
                require(count >= 16 && buffer.getShort(offset + 8).toInt() == 1 &&
                    buffer.getShort(offset + 10).toInt() == 1 && buffer.getInt(offset + 12) == 16_000 &&
                    buffer.getShort(offset + 22).toInt() == 16) { "样本必须为 16 kHz 单声道 PCM16" }
                validFormat = true
            }
            if (name == "data") {
                require(validFormat && count > 0 && count % 2 == 0) { "样本缺少 PCM 数据" }
                return FloatArray(count / 2) { index -> buffer.getShort(offset + 8 + index * 2) / 32768f }
            }
            offset += 8 + count + count % 2
        }
        error("样本缺少 PCM 数据")
    }
}
