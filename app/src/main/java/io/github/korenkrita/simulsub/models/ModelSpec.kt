package io.github.korenkrita.simulsub.models

import java.io.File

/** 识别引擎类型，由集成层据此创建对应的 sherpa 配置。 */
enum class ModelKind { REAZON, SENSEVOICE }

/** 固定版本模型的简短特点与同条件识别结果；错误率保留原口径，不换算为准确率。 */
internal data class ModelComparison(
    val introduction: String,
    val japaneseErrorRate: String,
    val englishErrorRate: String,
    val japaneseDecodeMs: Int,
    val englishDecodeMs: Int,
)

/** 比较资料不参与下载、校验或引擎配置，数值来自现有固定版本评测记录。 */
internal object ModelComparisons {
    private val entries = mapOf(
        "reazonspeech" to ModelComparison(
            "电视节目语音训练的小模型，体积小、识别快，适合日语；英语表现较弱。",
            "8.48%", "22.66%", 93, 71,
        ),
        "sensevoice" to ModelComparison(
            "支持中英日韩粤五种语言，日语和英语识别效果较好，体积较大。",
            "7.52%", "8.33%", 203, 157,
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
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9_-]*"))) { "模型标识不合法" }
        require(files.isNotEmpty() && files.map { it.path }.distinct().size == files.size) { "模型文件清单不能为空或重复" }
        require(sizeBytes == files.sumOf { it.sizeBytes }) { "模型总大小与文件清单不符" }
        require(files.any { it.path == "vad/silero_vad.onnx" }) { "每个模型必须自带 Silero VAD" }
    }
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
