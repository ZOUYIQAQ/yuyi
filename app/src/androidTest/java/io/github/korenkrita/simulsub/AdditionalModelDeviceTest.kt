package io.github.korenkrita.simulsub

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.models.ModelCatalog
import io.github.korenkrita.simulsub.models.ModelPaths
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** 读取外部 files/.qa_models 中的独立夹具；不启动界面、不下载、不选择或删除模型，也不发送告警。 */
class AdditionalModelDeviceTest {
    /** Whisper 多语种固定导出应能完成官方英语样例的原生解码。 */
    @Test(timeout = 180_000) fun whisperTinyEnglishDecodesOfficialSample() {
        decode("whisper-tiny", Lang.EN, "en.wav", "night")
    }

    /** Whisper 应能识别同一目录的日语样例，不能误装英语专用导出。 */
    @Test(timeout = 180_000) fun whisperTinyJapaneseDecodesOfficialSample() {
        decode("whisper-tiny", Lang.JA, "ja-16k.wav", "国")
    }

    /** 生产会话的多语自动配置同样必须正确识别日语，而非只验证手动语言配置。 */
    @Test(timeout = 180_000) fun whisperTinyAutoLanguageDecodesJapaneseSample() {
        decode("whisper-tiny", null, "ja-16k.wav", "国")
    }

    /** 英语 Moonshine 必须实际运行预处理及两种解码器，而非只通过构造检查。 */
    @Test(timeout = 180_000) fun moonshineTinyEnglishDecodesOfficialSample() {
        decode("moonshine-tiny-en", Lang.EN, "en.wav", "night")
    }

    /** 日语 Moonshine 使用当前 AAR 的 v2 原生实现读取 ORT 与合并解码器。 */
    @Test(timeout = 180_000) fun moonshineTinyJapaneseDecodesOfficialSample() {
        decode("moonshine-tiny-ja", Lang.JA, "ja-16k.wav", "国")
    }

    /** 先逐文件验证真实目录，再执行识别和 VAD；失败时保留所有夹具与完整识别原文。 */
    private fun decode(id: String, language: Lang?, wav: String, expectedFragment: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(requireNotNull(context.getExternalFilesDir(null)), ".qa_models")
        val spec = requireNotNull(ModelCatalog.entries.find { it.id == id }) { "正式目录缺少模型：$id" }
        val directory = File(root, id)
        for (file in spec.files) verifyFile(File(directory, file.path), file.sizeBytes, file.sha256)
        val sampleFile = File(root, "wavs/$wav")
        val sampleHash = when (wav) {
            "en.wav" -> "6bc58a4efdf20daac252b6b1502632601a71efe0308f6757dc1eda34891a7e4f"
            "ja-16k.wav" -> "b860062f60fbed09e6fc14373a90355673eec147d5cb258909579bf7239b549a"
            else -> error("未登记测试音频：$wav")
        }
        verifyFile(sampleFile, if (wav == "en.wav") 212_044L else 265_274L, sampleHash)
        val samples = waveSamples(sampleFile.readBytes())
        val asr = SherpaAsr(ModelPaths(spec, directory), 2, language)
        try {
            val started = System.nanoTime()
            val result = asr.recognizeTimed(samples)
            Log.i("新增离线模型验收", "$id，源语言 ${language?.zh ?: "自动"}，实际原文：${result.text}；解码耗时 ${(System.nanoTime() - started) / 1_000_000} 毫秒")
            assertTrue("$id 未识别出样例关键词 $expectedFragment：${result.text}", result.text.contains(expectedFragment, ignoreCase = true))
            assertFalse("特殊标记未清理：${result.text}", result.text.contains("<|"))
            assertNull("未经验证的词元时间戳不得进入漏词修复", result.pieces)
        } finally {
            asr.release()
        }
        val vad = SileroProb(directory)
        try {
            var speechDetected = false
            var offset = 0
            while (offset + 512 <= samples.size) {
                val probability = vad.prob(samples.copyOfRange(offset, offset + 512))
                assertTrue("$id VAD 概率无效：$probability", probability.isFinite() && probability in 0f..1f)
                speechDetected = speechDetected || probability > 0.5f
                offset += 512
            }
            assertTrue("$id VAD 未检测到官方样例中的语音", speechDetected)
        } finally {
            vad.release()
        }
    }

    /** 使用流式摘要校验实际字节，不把目录存在或下载成功当作模型可用。 */
    private fun verifyFile(file: File, size: Long, hash: String) {
        assertTrue("请先推送完整夹具，缺少文件：${file.path}", file.isFile)
        assertEquals("实际文件长度不符：${file.path}", size, file.length())
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("实际文件 SHA-256 不符：${file.path}", hash, actual)
    }

    /** 按 RIFF 块读取 16 kHz 单声道 PCM16，明确拒绝未经转换的 44.1 kHz 官方日语原件。 */
    private fun waveSamples(bytes: ByteArray): FloatArray {
        require(bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "测试音频不是 RIFF/WAVE" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var validFormat = false
        while (offset + 8 <= bytes.size) {
            val name = String(bytes, offset, 4, Charsets.US_ASCII)
            val count = buffer.getInt(offset + 4)
            require(count >= 0 && offset + 8L + count <= bytes.size) { "测试音频块长度错误" }
            if (name == "fmt ") {
                require(count >= 16 && buffer.getShort(offset + 8).toInt() == 1 &&
                    buffer.getShort(offset + 10).toInt() == 1 && buffer.getInt(offset + 12) == 16_000 &&
                    buffer.getShort(offset + 22).toInt() == 16) { "测试音频必须为 16 kHz 单声道 PCM16" }
                validFormat = true
            }
            if (name == "data") {
                require(validFormat && count > 0 && count % 2 == 0) { "测试音频缺少有效 PCM 格式或数据" }
                return FloatArray(count / 2) { index -> buffer.getShort(offset + 8 + index * 2) / 32768f }
            }
            offset += 8 + count + count % 2
        }
        error("测试音频缺少 PCM 数据")
    }
}
