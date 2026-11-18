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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用固定官方样例量设备解码速度；样例不用于计算标准错误率。 */
class AdditionalModelSampleBenchmarkDeviceTest {
    /** Whisper 多语种英语样例预热后解码三轮。 */
    @Test(timeout = 300_000) fun whisperEnglishThreeRoundMedian() = measure("whisper-tiny", Lang.EN, "en.wav")

    /** Whisper 多语种日语样例预热后解码三轮。 */
    @Test(timeout = 300_000) fun whisperJapaneseThreeRoundMedian() = measure("whisper-tiny", Lang.JA, "ja-16k.wav")

    /** Moonshine v1 英语固定导出预热后解码三轮。 */
    @Test(timeout = 300_000) fun moonshineEnglishThreeRoundMedian() = measure("moonshine-tiny-en", Lang.EN, "en.wav")

    /** Moonshine v2 日语量化导出预热后解码三轮。 */
    @Test(timeout = 300_000) fun moonshineJapaneseThreeRoundMedian() = measure("moonshine-tiny-ja", Lang.JA, "ja-16k.wav")

    /** 校验模型与音频后用同一识别器预热一次，再记录三轮完整输出和中位耗时。 */
    private fun measure(id: String, language: Lang, wavName: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(requireNotNull(context.getExternalFilesDir(null)), ".qa_models")
        val spec = requireNotNull(ModelCatalog.entries.find { it.id == id }) { "目录缺少模型：$id" }
        val directory = File(root, id)
        spec.files.forEach { file -> verifyFile(File(directory, file.path), file.sizeBytes, file.sha256) }
        val sample = File(root, "wavs/$wavName")
        val sampleHash = when (wavName) {
            "en.wav" -> "6bc58a4efdf20daac252b6b1502632601a71efe0308f6757dc1eda34891a7e4f"
            "ja-16k.wav" -> "b860062f60fbed09e6fc14373a90355673eec147d5cb258909579bf7239b549a"
            else -> error("未登记官方样例：$wavName")
        }
        verifyFile(sample, if (wavName == "en.wav") 212_044L else 265_274L, sampleHash)
        val samples = waveSamples(sample.readBytes())
        val recognizer = SherpaAsr(ModelPaths(spec, directory), 2, language)
        try {
            recognizer.recognizeTimed(samples)
            val elapsedMs = mutableListOf<Double>()
            val texts = mutableListOf<String>()
            repeat(3) {
                val started = System.nanoTime()
                val result = recognizer.recognizeTimed(samples)
                elapsedMs += (System.nanoTime() - started) / 1_000_000.0
                texts += result.text
                assertTrue("$id 第 ${it + 1} 轮没有识别文本", result.text.isNotBlank())
            }
            val median = elapsedMs.sorted()[1]
            Log.i("离线模型样例速度", "模型=$id；语言=${language.zh}；设备=${Build.MODEL}；系统API=${Build.VERSION.SDK_INT}；线程=2；样例=$wavName；SHA256=$sampleHash；预热=1轮；测量=3轮；单轮毫秒=$elapsedMs；中位毫秒=$median；识别原文=$texts")
        } finally {
            recognizer.release()
        }
    }

    /** 核对正式目录记录的长度和摘要，阻止夹具被替换后误记速度。 */
    private fun verifyFile(file: File, size: Long, expectedHash: String) {
        assertTrue("缺少设备夹具：${file.path}", file.isFile)
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

    /** 按 RIFF 块读取 16 kHz 单声道 PCM16，与现有设备验收使用相同音频格式。 */
    private fun waveSamples(bytes: ByteArray): FloatArray {
        require(bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "样例不是 RIFF/WAVE" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var validFormat = false
        while (offset + 8 <= bytes.size) {
            val name = String(bytes, offset, 4, Charsets.US_ASCII)
            val count = buffer.getInt(offset + 4)
            require(count >= 0 && offset + 8L + count <= bytes.size) { "样例块长度错误" }
            if (name == "fmt ") {
                require(count >= 16 && buffer.getShort(offset + 8).toInt() == 1 &&
                    buffer.getShort(offset + 10).toInt() == 1 && buffer.getInt(offset + 12) == 16_000 &&
                    buffer.getShort(offset + 22).toInt() == 16) { "样例必须为 16 kHz 单声道 PCM16" }
                validFormat = true
            }
            if (name == "data") {
                require(validFormat && count > 0 && count % 2 == 0) { "样例缺少 PCM 数据" }
                return FloatArray(count / 2) { index -> buffer.getShort(offset + 8 + index * 2) / 32768f }
            }
            offset += 8 + count + count % 2
        }
        error("样例缺少 PCM 数据")
    }
}
