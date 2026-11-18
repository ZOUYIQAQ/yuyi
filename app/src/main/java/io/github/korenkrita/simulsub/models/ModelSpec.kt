package io.github.korenkrita.simulsub.models

import io.github.korenkrita.simulsub.Lang
import java.io.File

/** 识别引擎类型，由集成层据此创建对应的 sherpa 配置。 */
enum class ModelKind { REAZON, SENSEVOICE, WHISPER, MOONSHINE, MOONSHINE_V2 }

/** 模型卡片使用公开错误率参考；解码耗时单独保留本机测量值。 */
internal data class ModelComparison(
    val introduction: String,
    val japaneseErrorRate: String,
    val englishErrorRate: String,
    val japaneseDecodeMs: Int?,
    val englishDecodeMs: Int?,
    val evaluationNote: String = "错误率为官方或论文参考，具体数据集见来源；耗时为本机20段录音中位数。",
    val benchmarkLabel: String = "错误率采用公开基准参考",
)

/** 参考资料不参与下载或引擎配置；公开错误率与本机耗时分别注明来源。 */
internal object ModelComparisons {
    private val entries = mapOf(
        "reazonspeech" to ModelComparison(
            "电视节目语音训练的小模型，体积小、识别快，适合日语；英语表现较弱。",
            "8.48%", "22.66%", 145, 110,
            evaluationNote = "原项目实测：FLEURS日语650句、英语647句；耗时为本机20段录音中位数。",
            benchmarkLabel = "原项目实测（FLEURS）",
        ),
        "sensevoice" to ModelComparison(
            "支持中英日韩粤五种语言，日语和英语识别效果较好，体积较大。",
            "11.96%", "14.71%", 223, 154,
            evaluationNote = "官方论文表6：Common Voice，日语按字、英语按词；耗时为本机20段录音中位数。",
        ),
        "whisper-tiny" to ModelComparison(
            "多语小模型，支持中英日韩切换或自动判断；识别较慢，适合按需选择。",
            "37.0%", "12.4%", 2969, 1687,
            evaluationNote = "OpenAI官方论文表13：FLEURS，多语种Tiny，日语按字、英语按词；耗时为本机20段录音中位数。",
        ),
        "moonshine-tiny-en" to ModelComparison(
            "专识英语，适合英语视频和短语音；不识别其他原声语言。",
            "不支持日语", "4.52%", null, 100,
            evaluationNote = "官方论文表3：英语v1 Tiny，LibriSpeech test-clean词错误率；耗时为本机20段录音中位数。",
        ),
        "moonshine-tiny-ja" to ModelComparison(
            "专识日语，下载体积最小；长语音会自动分段识别。",
            "17.87%", "不支持英语", 137, null,
            evaluationNote = "官方论文表8：日语Tiny最终模型，FLEURS字错误率；耗时为本机20段录音中位数。",
        ),
    )

    /** 未收录的模型沿用自身介绍，不虚构比较数据。 */
    operator fun get(id: String): ModelComparison? = entries[id]
}

/** 下载和安装状态；只有 READY 允许启用。 */
enum class ModelStatus { NOT_INSTALLED, QUEUED, DOWNLOADING, VERIFYING, READY, FAILED, CANCELLED }

/** 文件的固定长度、摘要与按优先级排列的受控下载源。 */
data class ModelFile(val path: String, val sizeBytes: Long, val sha256: String, val sources: List<String>) {
    init {
        require(path.matches(Regex("(?:asr|vad)/[a-zA-Z0-9_.-]+"))) { "模型文件路径不合法：$path" }
        require(path.substringAfter('/') !in setOf(".", "..")) { "模型文件不能指向父目录" }
        require(sizeBytes > 0) { "模型文件长度必须大于零" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "模型文件缺少有效的 SHA-256" }
        require(sources.isNotEmpty() && sources.all { it.startsWith("https://") }) { "模型下载源必须使用 HTTPS" }
    }
}

/** 内置可信目录，不接受网络下发的未经确认的大小或摘要。 */
data class ModelSpec(
    val id: String,
    val name: String,
    val description: String,
    val languages: List<String>,
    val sizeBytes: Long,
    val kind: ModelKind,
    val files: List<ModelFile>,
    /** 当前应用可供选择的源语言；不等同于上游模型的全部语言。 */
    val sourceLanguages: List<Lang> = when (kind) {
        ModelKind.REAZON -> listOf(Lang.JA, Lang.EN)
        ModelKind.SENSEVOICE, ModelKind.WHISPER -> Lang.entries.toList()
        ModelKind.MOONSHINE -> listOf(Lang.EN)
        ModelKind.MOONSHINE_V2 -> listOf(Lang.JA)
    },
    val introduction: String = description,
    val license: String = when (kind) {
        ModelKind.REAZON -> "ReazonSpeech 上游 Apache-2.0，ONNX 仓库未附单独权重许可 · VAD：MIT"
        ModelKind.SENSEVOICE -> "FunASR 模型协议 v1.1 · VAD：MIT"
        ModelKind.WHISPER, ModelKind.MOONSHINE -> "MIT · VAD：MIT"
        ModelKind.MOONSHINE_V2 -> "Moonshine Community License（含商业使用条件）· VAD：MIT"
    },
    val licenseUrl: String = "",
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9_-]*"))) { "模型标识不合法" }
        require(files.isNotEmpty() && files.map { it.path }.distinct().size == files.size) { "模型文件清单不能为空或重复" }
        require(sizeBytes == files.sumOf { it.sizeBytes }) { "模型总大小与文件清单不符" }
        require(files.any { it.path == "vad/silero_vad.onnx" }) { "每个模型必须自带 Silero VAD" }
        require(sourceLanguages.isNotEmpty() && sourceLanguages.distinct().size == sourceLanguages.size) { "模型源语言不能为空或重复" }
    }

    /** 单语模型无需显示自动语言选项；多语模型允许自动识别或交由翻译层判断。 */
    val supportsAutoLanguage: Boolean get() = sourceLanguages.size > 1

    /** 仅 Reazon 的固定导出及字节词元格式已接入漏词修复，其他模型不伪造时间戳。 */
    val supportsTokenTimestamps: Boolean get() = kind == ModelKind.REAZON
}

/** 单个模型的可观察状态，下载完成不会自动改变当前选择。 */
data class ModelEntry(
    val spec: ModelSpec,
    val status: ModelStatus = ModelStatus.NOT_INSTALLED,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = spec.sizeBytes,
    val error: String? = null,
    val source: String? = null,
    val hasLocalFiles: Boolean = false,
)

/** 模型库快照；恢复校验完成前 selectedId 保持为空。 */
data class ModelLibraryState(val entries: List<ModelEntry>, val selectedId: String? = null, val deletingId: String? = null)

/** 完整校验后的模型目录，包含 asr 文件与 vad/silero_vad.onnx。 */
data class ModelPaths(val spec: ModelSpec, val directory: File)
