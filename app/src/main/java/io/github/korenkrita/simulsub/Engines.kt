package io.github.korenkrita.simulsub

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import io.github.korenkrita.simulsub.models.ModelKind
import io.github.korenkrita.simulsub.models.ModelPaths
import io.github.korenkrita.simulsub.models.ModelSpec
import java.io.File

/** 从已校验的私有目录加载用户选择的识别模型，不读取安装包 assets。 */
class SherpaAsr(private val paths: ModelPaths, threads: Int, sourceLanguage: Lang? = null) : Asr {
    private val rec = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = modelConfig(paths, threads, sourceLanguage),
            decodingMethod = "greedy_search",
        ),
    )

    /** 普通识别沿用同一解码结果，避免为了时间戳重复执行推理。 */
    override fun recognize(samples: FloatArray): String = recognizeTimed(samples).text

    /** 仅对已验证的词元时间戳做漏词修复，其他架构保留原文而不伪造时间信息。 */
    override fun recognizeTimed(samples: FloatArray): Recognition {
        // 当前 1.13.8 的 Moonshine v2 在约九秒以上有注意力掩码缺陷；分段覆盖全部输入。
        // 上游复现：https://github.com/k2-fsa/sherpa-onnx/issues/3471。
        if (paths.spec.kind == ModelKind.MOONSHINE_V2 && samples.size > 8 * 16_000) {
            val text = recognitionAudioChunks(samples).map { recognizeOne(it).text }.joinToString("")
            return Recognition(text, null)
        }
        return recognizeOne(samples)
    }

    /** 单个受支持长度的音频直接解码，独立释放流，不改变其他模型的时间戳路径。 */
    private fun recognizeOne(samples: FloatArray): Recognition {
        val stream = rec.createStream()
        try {
            val reazon = paths.spec.kind == ModelKind.REAZON
            stream.acceptWaveform(if (reazon) padded(samples) else samples, 16_000)
            rec.decode(stream)
            val result = rec.getResult(stream)
            return recognition(paths.spec, result)
        } finally {
            stream.release()
        }
    }

    /** 会话线程退出识别后释放运行时。 */
    fun release() = rec.release()

    companion object {
        private const val PAD = 4_800

        /** 日英 ReazonSpeech 在音频两端增加 0.3 秒静音，保留原项目的句首补偿。 */
        fun padded(samples: FloatArray): FloatArray = FloatArray(samples.size + 2 * PAD).also {
            System.arraycopy(samples, 0, it, PAD, samples.size)
        }

        /** 只对 Reazon 的已验证分词格式使用时间戳；损坏或倒序时间信息回退为纯文本。 */
        internal fun recognition(spec: ModelSpec, result: OfflineRecognizerResult): Recognition {
            val text = result.text.replace(Regex("<\\|[^|]*\\|>"), "").trim()
            val times = result.timestamps
            val validTimes = times.isNotEmpty() && times.size == result.tokens.size &&
                times.all { it.isFinite() && it >= 0f } && (1 until times.size).all { times[it - 1] <= times[it] }
            return if (spec.supportsTokenTimestamps && validTimes)
                Recognition.of(text, result.tokens, times, PAD / 16_000f)
            else Recognition(text, null)
        }

        /** 将可信目录映射为 AAR 1.13.8 的配置，在进入原生引擎前校验语言、线程与架构文件清单。 */
        internal fun modelConfig(paths: ModelPaths, threads: Int, sourceLanguage: Lang? = null): OfflineModelConfig {
            val root = paths.directory
            require(threads > 0) { "识别线程数必须大于零" }
            require(sourceLanguage == null || sourceLanguage in paths.spec.sourceLanguages) {
                "${paths.spec.name} 不支持源语言：${sourceLanguage?.zh}"
            }
            /** 只引用已纳入长度和摘要校验的模型文件，拒绝缺少架构部件的目录项。 */
            fun modelFile(path: String): String {
                require(paths.spec.files.any { it.path == path }) { "${paths.spec.name} 缺少必需文件：$path" }
                return File(root, path).path
            }
            return when (paths.spec.kind) {
                ModelKind.REAZON -> OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = modelFile("asr/encoder.int8.onnx"),
                        decoder = modelFile("asr/decoder.onnx"),
                        joiner = modelFile("asr/joiner.int8.onnx"),
                    ),
                    tokens = modelFile("asr/tokens.txt"),
                    numThreads = threads,
                    modelType = "transducer",
                )
                ModelKind.SENSEVOICE -> OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = modelFile("asr/model.int8.onnx"),
                        language = sourceLanguage?.code ?: "auto",
                        useInverseTextNormalization = true,
                    ),
                    tokens = modelFile("asr/tokens.txt"),
                    numThreads = threads,
                    modelType = "sense_voice",
                )
                ModelKind.WHISPER -> OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = modelFile("asr/encoder.int8.onnx"),
                        decoder = modelFile("asr/decoder.int8.onnx"),
                        language = sourceLanguage?.code ?: "",
                        task = "transcribe",
                        // 保持运行库默认尾补，真实录音对照显示 300 帧会在英语短句中重复整句。
                        // 来源：https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-whisper-model-config.h
                        tailPaddings = -1,
                        enableTokenTimestamps = false,
                        enableSegmentTimestamps = false,
                    ),
                    tokens = modelFile("asr/tokens.txt"),
                    numThreads = threads,
                    modelType = "whisper",
                )
                ModelKind.MOONSHINE -> OfflineModelConfig(
                    moonshine = OfflineMoonshineModelConfig(
                        preprocessor = modelFile("asr/preprocess.onnx"),
                        encoder = modelFile("asr/encode.int8.onnx"),
                        uncachedDecoder = modelFile("asr/uncached_decode.int8.onnx"),
                        cachedDecoder = modelFile("asr/cached_decode.int8.onnx"),
                    ),
                    tokens = modelFile("asr/tokens.txt"),
                    numThreads = threads,
                    modelType = "moonshine",
                )
                ModelKind.MOONSHINE_V2 -> OfflineModelConfig(
                    moonshine = OfflineMoonshineModelConfig(
                        encoder = modelFile("asr/encoder_model.ort"),
                        mergedDecoder = modelFile("asr/decoder_model_merged.ort"),
                    ),
                    tokens = modelFile("asr/tokens.txt"),
                    numThreads = threads,
                    modelType = "moonshine",
                )
            }
        }
    }
}

/** 从下载目录加载 Silero VAD，仅提供逐帧语音概率，分句逻辑保持原有实现。 */
class SileroProb(root: File) {
    private val vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = File(root, "vad/silero_vad.onnx").path,
                windowSize = 512,
            ),
            sampleRate = 16_000,
            numThreads = 1,
        ),
    )

    /** 计算一帧 16 kHz 单声道音频的语音概率。 */
    fun prob(frame: FloatArray): Float = vad.compute(frame)

    /** 会话停止后释放 VAD。 */
    fun release() = vad.release()
}
