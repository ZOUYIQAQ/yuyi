package io.github.korenkrita.simulsub

import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.models.ModelCatalog
import io.github.korenkrita.simulsub.models.ModelPaths
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 使用固定的 FLEURS validation 前20句评估指定导出；每组独立执行且不跳过缺失夹具。 */
class AdditionalModelFleursBenchmarkDeviceTest {
    /** Whisper 在相同日语20句上记录字错误率和单句耗时。 */
    @Test(timeout = 1_800_000) fun whisperJapanese() = measure("whisper-tiny", Lang.JA, "ja_jp")

    /** Whisper 在相同英语20句上记录词错误率和单句耗时。 */
    @Test(timeout = 1_800_000) fun whisperEnglish() = measure("whisper-tiny", Lang.EN, "en_us")

    /** 英语 Moonshine 在固定英语20句上运行一次逐句解码。 */
    @Test(timeout = 1_800_000) fun moonshineEnglish() = measure("moonshine-tiny-en", Lang.EN, "en_us")

    /** 日语 Moonshine 在固定日语20句上运行一次逐句解码。 */
    @Test(timeout = 1_800_000) fun moonshineJapanese() = measure("moonshine-tiny-ja", Lang.JA, "ja_jp")

    /** ReazonSpeech 在同一批日语20句上输出可比的设备结果。 */
    @Test(timeout = 1_800_000) fun reazonJapanese() = measure("reazonspeech", Lang.JA, "ja_jp")

    /** ReazonSpeech 在同一批英语20句上输出可比的设备结果。 */
    @Test(timeout = 1_800_000) fun reazonEnglish() = measure("reazonspeech", Lang.EN, "en_us")

    /** SenseVoice 在同一批日语20句上输出可比的设备结果。 */
    @Test(timeout = 1_800_000) fun senseVoiceJapanese() = measure("sensevoice", Lang.JA, "ja_jp")

    /** SenseVoice 在同一批英语20句上输出可比的设备结果。 */
    @Test(timeout = 1_800_000) fun senseVoiceEnglish() = measure("sensevoice", Lang.EN, "en_us")

    /** 校验样本和模型后预热一次，逐条单次解码并保存完整明细和总体统计。 */
    private fun measure(id: String, language: Lang, config: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val external = requireNotNull(context.getExternalFilesDir(null))
        val modelRoot = File(external, ".qa_models")
        val standardRoot = File(modelRoot, "model-standard")
        val manifestFile = File(standardRoot, "manifest.json")
        assertTrue("缺少 FLEURS 样本清单：${manifestFile.path}", manifestFile.isFile)
        val manifestBytes = manifestFile.readBytes()
        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
        assertEquals("google/fleurs", manifest.getString("dataset"))
        assertEquals("validation", manifest.getString("split"))
        assertEquals(0, manifest.getInt("offset"))
        assertEquals(20, manifest.getInt("length_per_language"))
        val entries = manifest.getJSONArray("entries")
        assertEquals("清单应同时包含日英各20句", 40, entries.length())
        val rows = (0 until entries.length()).map { entries.getJSONObject(it) }
            .filter { it.getString("config") == config }.sortedBy { it.getInt("row_idx") }
        assertEquals("$config 应有20句", 20, rows.size)
        assertEquals((0 until 20).toList(), rows.map { it.getInt("row_idx") })
        val spec = requireNotNull(ModelCatalog.entries.find { it.id == id }) { "目录缺少模型：$id" }
        val directory = File(modelRoot, id)
        spec.files.forEach { file -> verifyFile(File(directory, file.path), file.sizeBytes, file.sha256) }
        val recognizer = SherpaAsr(ModelPaths(spec, directory), 2, language)
        try {
            val warmup = rows.minBy { it.getDouble("duration_seconds") }
            recognizer.recognizeTimed(readWave(verifiedAudio(standardRoot, warmup)))
            var errors = 0
            var referenceUnits = 0
            var emptyCount = 0
            var failedCount = 0
            val times = mutableListOf<Double>()
            val details = JSONArray()
            rows.forEach { row ->
                val audio = verifiedAudio(standardRoot, row)
                val samples = readWave(audio)
                val started = System.nanoTime()
                var decodeError: String? = null
                val hypothesis = try {
                    recognizer.recognizeTimed(samples).text
                } catch (error: Exception) {
                    decodeError = error.toString()
                    failedCount++
                    ""
                }
                if (hypothesis.isBlank()) emptyCount++
                val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
                val reference = row.getString("reference")
                val referenceTokens = units(reference, language)
                val hypothesisTokens = units(hypothesis, language)
                assertTrue("参考文本规整后为空：$config/${row.getInt("row_idx")}", referenceTokens.isNotEmpty())
                val distance = editDistance(referenceTokens, hypothesisTokens)
                errors += distance
                referenceUnits += referenceTokens.size
                times += elapsedMs
                details.put(JSONObject().apply {
                    put("row_idx", row.getInt("row_idx"))
                    put("audio_file", row.getString("audio_file"))
                    put("audio_sha256", row.getString("audio_sha256"))
                    put("reference", reference)
                    put("hypothesis", hypothesis)
                    put("reference_normalized", referenceTokens.joinToString(if (language == Lang.JA) "" else " "))
                    put("hypothesis_normalized", hypothesisTokens.joinToString(if (language == Lang.JA) "" else " "))
                    put("edit_distance", distance)
                    put("reference_units", referenceTokens.size)
                    put("decode_ms", elapsedMs)
                    put("decode_error", decodeError ?: JSONObject.NULL)
                })
            }
            val output = JSONObject().apply {
                put("model_id", id)
                put("model_file_sha256", JSONObject().apply { spec.files.forEach { put(it.path, it.sha256) } })
                put("device", Build.MODEL)
                put("android_api", Build.VERSION.SDK_INT)
                put("threads", 2)
                put("dataset", "google/fleurs")
                put("split", "validation")
                put("config", config)
                put("row_start", 0)
                put("row_end_inclusive", 19)
                put("sample_count", 20)
                put("manifest_sha256", sha256(manifestBytes))
                put("normalization", "NFKC、小写、去标点；日语去空白按字，英语按空白分词")
                put("metric", if (language == Lang.JA) "CER" else "WER")
                put("edit_errors", errors)
                put("reference_units", referenceUnits)
                put("empty_result_count", emptyCount)
                put("decode_failure_count", failedCount)
                put("failed_rows_count_as_full_deletions", true)
                put("error_rate_percent", errors * 100.0 / referenceUnits)
                put("median_decode_ms", times.sorted()[(times.size - 1) / 2].let { lower ->
                    (lower + times.sorted()[times.size / 2]) / 2.0
                })
                put("warmup_count", 1)
                put("warmup_row_idx", warmup.getInt("row_idx"))
                put("decode_count_per_sample", 1)
                put("samples", details)
            }
            val outputRoot = File(external, "qa-model-standard")
            assertTrue("无法创建结果目录：${outputRoot.path}", outputRoot.isDirectory || outputRoot.mkdirs())
            val outputFile = File(outputRoot, "$id-$config-${System.currentTimeMillis()}.json")
            outputFile.writeText(output.toString(2) + "\n", Charsets.UTF_8)
            Log.i("离线模型标准小样本", "模型=$id；语言=${language.zh}；FLEURS validation 0至19；${output.getString("metric")}=${output.getDouble("error_rate_percent")}%；单句中位耗时=${output.getDouble("median_decode_ms")}毫秒；完整明细=${outputFile.path}")
        } finally {
            recognizer.release()
        }
    }

    /** 在读取前校验相对路径、字节数与摘要，确保每个模型看到相同音频。 */
    private fun verifiedAudio(root: File, row: JSONObject): ByteArray {
        val name = row.getString("audio_file")
        require(name.matches(Regex("(ja_jp|en_us)/[0-9]{3}\\.wav"))) { "样本路径不合法：$name" }
        val file = File(root, name)
        verifyFile(file, row.getLong("audio_bytes"), row.getString("audio_sha256"))
        return file.readBytes()
    }

    /** 校验固定模型文件或样本的完整长度和 SHA-256。 */
    private fun verifyFile(file: File, size: Long, expectedHash: String) {
        assertTrue("缺少夹具：${file.path}", file.isFile)
        assertEquals("夹具长度错误：${file.path}", size, file.length())
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val block = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(block)
                if (count < 0) break
                digest.update(block, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("夹具摘要错误：${file.path}", expectedHash, actual)
    }

    /** 计算字节摘要以把设备结果绑定到本次完整样本清单。 */
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** 对参考和识别文本使用同一规整规则，日语按字、英语按词切分。 */
    private fun units(text: String, language: Lang): List<String> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val withoutPunctuation = normalized.filter { it.isLetterOrDigit() || it.isWhitespace() }
        return if (language == Lang.JA) withoutPunctuation.filter { !it.isWhitespace() }.map { it.toString() }
        else withoutPunctuation.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    /** 用标准动态规划求替换、删除、插入总次数。 */
    private fun editDistance(reference: List<String>, hypothesis: List<String>): Int {
        var previous = IntArray(hypothesis.size + 1) { it }
        for (index in reference.indices) {
            val current = IntArray(hypothesis.size + 1)
            current[0] = index + 1
            for (column in hypothesis.indices) {
                current[column + 1] = minOf(
                    previous[column + 1] + 1,
                    current[column] + 1,
                    previous[column] + if (reference[index] == hypothesis[column]) 0 else 1,
                )
            }
            previous = current
        }
        return previous.last()
    }

    /** 按 RIFF 块读取统一转换后的 16 kHz 单声道 PCM16。 */
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
