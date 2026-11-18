package io.github.korenkrita.simulsub.models

import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import io.github.korenkrita.simulsub.Lang
import io.github.korenkrita.simulsub.SherpaAsr
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** 仅验证模型目录、语言约束与配置，不启动原生推理、网络或告警。 */
class AdditionalOfflineModelsTest {
    /** 取得指定目录项，缺失时给出模型标识。 */
    private fun model(id: String): ModelSpec = requireNotNull(ModelCatalog.entries.find { it.id == id }) { "缺少模型：$id" }

    /** 最少加入多语种 Whisper 与英语 Moonshine，保持已有模型标识。 */
    @Test fun catalogContainsAdditionalArchitectures() {
        assertEquals(ModelKind.WHISPER, model("whisper-tiny").kind)
        assertEquals(ModelKind.MOONSHINE, model("moonshine-tiny-en").kind)
        assertEquals(ModelKind.REAZON, model("reazonspeech").kind)
        assertEquals(ModelKind.SENSEVOICE, model("sensevoice").kind)
        assertEquals(ModelCatalog.entries.size, ModelCatalog.entries.map { it.id }.distinct().size)
    }

    /** 单语模型不得向界面提供其他语言或自动语言选择。 */
    @Test fun languageCapabilitiesMatchActualModelScope() {
        assertEquals(listOf(Lang.EN), model("moonshine-tiny-en").sourceLanguages)
        assertFalse(model("moonshine-tiny-en").supportsAutoLanguage)
        assertEquals(Lang.entries.toSet(), model("whisper-tiny").sourceLanguages.toSet())
        assertTrue(model("whisper-tiny").supportsAutoLanguage)
        assertEquals(listOf(Lang.JA, Lang.EN), model("reazonspeech").sourceLanguages)
        assertEquals(Lang.entries.toSet(), model("sensevoice").sourceLanguages.toSet())
    }

    /** 旧位置参数构造继续有效，并为已有架构提供保守默认信息。 */
    @Test fun oldConstructorKeepsWorking() {
        val existing = model("reazonspeech")
        val legacy = ModelSpec(existing.id, existing.name, existing.description, existing.languages,
            existing.sizeBytes, existing.kind, existing.files)
        assertEquals(listOf(Lang.JA, Lang.EN), legacy.sourceLanguages)
        assertTrue(legacy.supportsTokenTimestamps)
        assertTrue(legacy.introduction.isNotBlank())
        assertTrue(legacy.license.isNotBlank())
    }

    /** 所有新文件的源固定到具体提交，目录总长度覆盖 VAD。 */
    @Test fun additionalDownloadsArePinnedAndComplete() {
        val revisions = mapOf(
            "whisper-tiny" to "65176e2deb88badc814a94058666cadccc29b61c",
            "moonshine-tiny-en" to "bf2b762c076d8ea61e2af0b3851c9564fb77552e",
            "moonshine-tiny-ja" to "550e2eb0a8b33092f3b394f64649ee1b3d9eb506",
        )
        revisions.forEach { (id, revision) ->
            val spec = model(id)
            assertEquals(spec.files.sumOf { it.sizeBytes }, spec.sizeBytes)
            assertTrue(spec.files.any { it.path == "vad/silero_vad.onnx" })
            spec.files.filter { it.path.startsWith("asr/") }.forEach { file ->
                assertTrue(file.sources.any { it.startsWith("https://huggingface.co/") && it.contains("/resolve/$revision/") })
                assertTrue(file.sources.all { it.contains(Regex("/resolve/[a-f0-9]{40}/")) })
            }
            assertTrue(spec.introduction.isNotBlank())
            assertTrue(spec.license.isNotBlank())
            assertTrue(spec.licenseUrl.startsWith("https://"))
            assertNotNull(ModelComparisons[id])
        }
    }

    /** 日语国内副本只使用已逐字节核对的固定提交，并保留同一导出的官方后备。 */
    @Test fun japaneseMoonshinePrefersVerifiedDomesticArtifacts() {
        val domestic = "https://modelscope.cn/models/liaowenbin/sherpa-onnx-moonshine-tiny-ja-quantized/resolve/23edc3631598ff4ac5bef6acf0db465443a56451"
        val official = "https://huggingface.co/csukuangfj2/sherpa-onnx-moonshine-tiny-ja-quantized-2026-02-27/resolve/550e2eb0a8b33092f3b394f64649ee1b3d9eb506"
        model("moonshine-tiny-ja").files.filter { it.path.startsWith("asr/") }.forEach { file ->
            val name = file.path.substringAfter('/')
            assertEquals(listOf("$domestic/$name", "$official/$name"), file.sources)
        }
    }

    /** 英语尚无通过验证的独立国内文件源，不加入仅跳回官方站点的镜像地址。 */
    @Test fun englishMoonshineKeepsOnlyVerifiedIndependentSource() {
        val official = "https://huggingface.co/csukuangfj/sherpa-onnx-moonshine-tiny-en-int8/resolve/bf2b762c076d8ea61e2af0b3851c9564fb77552e"
        model("moonshine-tiny-en").files.filter { it.path.startsWith("asr/") }.forEach { file ->
            assertEquals(listOf("$official/${file.path.substringAfter('/')}"), file.sources)
        }
    }

    /** 以本次完整下载后的真实摘要和长度锁定 Whisper 文件，不用元数据代替校验。 */
    @Test fun whisperManifestMatchesVerifiedFiles() {
        val files = model("whisper-tiny").files.associateBy { it.path }
        assertEquals(12_937_772L, files.getValue("asr/encoder.int8.onnx").sizeBytes)
        assertEquals("d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434", files.getValue("asr/encoder.int8.onnx").sha256)
        assertEquals(89_855_401L, files.getValue("asr/decoder.int8.onnx").sizeBytes)
        assertEquals("d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925", files.getValue("asr/decoder.int8.onnx").sha256)
        assertEquals(816_730L, files.getValue("asr/tokens.txt").sizeBytes)
        assertEquals("b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126", files.getValue("asr/tokens.txt").sha256)
    }

    /** 多语种 Whisper 使用经设备对照的默认尾补与转写任务，不开启未经导出支持的时间戳。 */
    @Test fun whisperConfigUsesMultilingualTranscription() {
        val paths = ModelPaths(model("whisper-tiny"), File("/模型/whisper"))
        val auto = SherpaAsr.modelConfig(paths, 2)
        assertEquals("/模型/whisper/asr/encoder.int8.onnx", auto.whisper.encoder)
        assertEquals("/模型/whisper/asr/decoder.int8.onnx", auto.whisper.decoder)
        assertEquals("/模型/whisper/asr/tokens.txt", auto.tokens)
        assertEquals(2, auto.numThreads)
        assertEquals("", auto.whisper.language)
        assertEquals("transcribe", auto.whisper.task)
        assertEquals(-1, auto.whisper.tailPaddings)
        assertFalse(auto.whisper.enableTokenTimestamps)
        assertFalse(auto.whisper.enableSegmentTimestamps)
        assertEquals("ja", SherpaAsr.modelConfig(paths, 2, Lang.JA).whisper.language)
    }

    /** 英语 Moonshine v1 必须完整配置预处理、编码器及两个解码器。 */
    @Test fun moonshineConfigUsesFourV1Files() {
        val paths = ModelPaths(model("moonshine-tiny-en"), File("/模型/moonshine"))
        val config = SherpaAsr.modelConfig(paths, 3, Lang.EN)
        assertEquals("/模型/moonshine/asr/preprocess.onnx", config.moonshine.preprocessor)
        assertEquals("/模型/moonshine/asr/encode.int8.onnx", config.moonshine.encoder)
        assertEquals("/模型/moonshine/asr/uncached_decode.int8.onnx", config.moonshine.uncachedDecoder)
        assertEquals("/模型/moonshine/asr/cached_decode.int8.onnx", config.moonshine.cachedDecoder)
        assertEquals("", config.moonshine.mergedDecoder)
        assertEquals(3, config.numThreads)
        assertThrows(IllegalArgumentException::class.java) { SherpaAsr.modelConfig(paths, 2, Lang.JA) }
        assertThrows(IllegalArgumentException::class.java) { SherpaAsr.modelConfig(paths, 0) }
    }

    /** 日语 Moonshine 使用当前 AAR 已提供的合并解码器，不能混入英语 v1 文件。 */
    @Test fun japaneseMoonshineUsesV2MergedDecoder() {
        val spec = model("moonshine-tiny-ja")
        assertEquals(listOf(Lang.JA), spec.sourceLanguages)
        assertFalse(spec.supportsAutoLanguage)
        val config = SherpaAsr.modelConfig(ModelPaths(spec, File("/模型/日语")), 2, Lang.JA)
        assertEquals("/模型/日语/asr/encoder_model.ort", config.moonshine.encoder)
        assertEquals("/模型/日语/asr/decoder_model_merged.ort", config.moonshine.mergedDecoder)
        assertEquals("", config.moonshine.preprocessor)
        assertEquals("", config.moonshine.cachedDecoder)
        assertEquals("", config.moonshine.uncachedDecoder)
        assertTrue(spec.license.contains("Community"))
    }

    /** 缺少正确架构文件时在 Kotlin 层失败，避免将不完整配置传入原生引擎。 */
    @Test fun missingRequiredArchitectureFileIsRejected() {
        val spec = model("moonshine-tiny-en")
        val files = spec.files.filterNot { it.path == "asr/cached_decode.int8.onnx" }
        val incomplete = spec.copy(files = files, sizeBytes = files.sumOf { it.sizeBytes })
        assertThrows(IllegalArgumentException::class.java) { SherpaAsr.modelConfig(ModelPaths(incomplete, File("/模型")), 2) }
    }

    /** 未验证时间戳的架构即使收到时间数组，也不能套用 Reazon 字节词元对齐。 */
    @Test fun unsupportedTimestampModelsReturnTextOnly() {
        val result = OfflineRecognizerResult("<|en|>hello", arrayOf("hello"), floatArrayOf(0f), "", "", "", floatArrayOf())
        listOf("whisper-tiny", "moonshine-tiny-en", "moonshine-tiny-ja", "sensevoice").forEach { id ->
            val spec = model(id)
            assertFalse(spec.supportsTokenTimestamps)
            val recognition = SherpaAsr.recognition(spec, result)
            assertEquals("hello", recognition.text)
            assertNull(recognition.pieces)
        }
    }

    /** Reazon 继续扣除前置静音，并拒绝空值或倒序时间戳。 */
    @Test fun reazonTimestampCompatibilityIsPreserved() {
        val result = OfflineRecognizerResult("ab", arrayOf("a", "b"), floatArrayOf(0.5f, 0.8f), "", "", "", floatArrayOf())
        val spec = model("reazonspeech")
        val recognition = SherpaAsr.recognition(spec, result)
        assertEquals("ab", recognition.text)
        assertEquals(0.2f, requireNotNull(recognition.pieces)[0].t, 0.0001f)
        assertNull(SherpaAsr.recognition(spec, result.copy(timestamps = floatArrayOf(Float.NaN, 0.8f))).pieces)
        assertNull(SherpaAsr.recognition(spec, result.copy(timestamps = floatArrayOf(0.8f, 0.5f))).pieces)
        assertNull(SherpaAsr.recognition(spec, result.copy(timestamps = floatArrayOf())).pieces)
    }
}
