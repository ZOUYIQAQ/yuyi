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

    /** 按模型处理输入与结果，确保原生流在成功和异常时都能释放。 */
    override fun recognize(samples: FloatArray): String {
        val stream = rec.createStream()
        try {
            val input = if (paths.spec.kind == ModelKind.REAZON) padded(samples) else samples
            stream.acceptWaveform(input, 16_000)
            rec.decode(stream)
            return rec.getResult(stream).text.replace(Regex("<\\|[^|]*\\|>"), "").trim()
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
