package io.github.korenkrita.simulsub

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import io.github.korenkrita.simulsub.models.ModelKind
import io.github.korenkrita.simulsub.models.ModelPaths
import java.io.File

/** 从已校验的私有目录加载用户选择的识别模型，不读取安装包 assets。 */
class SherpaAsr(private val paths: ModelPaths, threads: Int) : Asr {
    private val rec = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = modelConfig(paths, threads),
            decodingMethod = "greedy_search",
        ),
    )

    /** 普通识别沿用同一解码结果，避免为了时间戳重复执行推理。 */
    override fun recognize(samples: FloatArray): String = recognizeTimed(samples).text

    /** Reazon提供分词时间戳修复漏词；SenseVoice保留标记清理且不伪造时间信息。 */
    override fun recognizeTimed(samples: FloatArray): Recognition {
        val stream = rec.createStream()
        try {
            val reazon = paths.spec.kind == ModelKind.REAZON
            stream.acceptWaveform(if (reazon) padded(samples) else samples, 16_000)
            rec.decode(stream)
            val result = rec.getResult(stream)
            val text = result.text.replace(Regex("<\\|[^|]*\\|>"), "").trim()
            return if (reazon) Recognition.of(text, result.tokens, result.timestamps, PAD / 16_000f)
                else Recognition(text, null)
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

        /** 将可信模型目录映射到不同模型架构所需的文件组合。 */
        private fun modelConfig(paths: ModelPaths, threads: Int): OfflineModelConfig {
            val root = paths.directory
            return when (paths.spec.kind) {
                ModelKind.REAZON -> OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = File(root, "asr/encoder.int8.onnx").path,
                        decoder = File(root, "asr/decoder.onnx").path,
                        joiner = File(root, "asr/joiner.int8.onnx").path,
                    ),
                    tokens = File(root, "asr/tokens.txt").path,
                    numThreads = threads,
                    modelType = "transducer",
                )
                ModelKind.SENSEVOICE -> OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = File(root, "asr/model.int8.onnx").path,
                        language = "auto",
                        useInverseTextNormalization = true,
                    ),
                    tokens = File(root, "asr/tokens.txt").path,
                    numThreads = threads,
                    modelType = "sense_voice",
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
