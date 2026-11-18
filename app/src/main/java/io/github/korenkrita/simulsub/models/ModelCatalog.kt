package io.github.korenkrita.simulsub.models

/** 固定模型目录；新增模型的长度与摘要来自固定提交完整下载后的逐字节校验。 */
object ModelCatalog {
    private const val REAZON = "https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en/resolve/12b44671ff48b9aed622551d64e02fea9a2cf870"
    private const val REAZON_MIRROR = "https://hf-mirror.com/csukuangfj/reazonspeech-k2-v2-ja-en/resolve/12b44671ff48b9aed622551d64e02fea9a2cf870"
    private const val REAZON_MS = "https://modelscope.cn/models/yinannn/yinanyi-reazon-ja/resolve/ddfd04fb9a112a3fcfde1e05fc976a9bec07db7f"
    private const val SENSE_MS = "https://modelscope.cn/models/pengzhendong/sherpa-onnx-sense-voice-zh-en-ja-ko-yue/resolve/73eca47697f980daa3d16112404174b6b950b514"
    private const val SENSE_MIRROR = "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07"
    private const val SENSE = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07"
    private const val WHISPER = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/65176e2deb88badc814a94058666cadccc29b61c"
    private const val WHISPER_MS = "https://modelscope.cn/models/pengzhendong/sherpa-onnx-whisper-tiny/resolve/33a655645234f82ce833cf27b689d9c2212e693f"
    private const val MOONSHINE = "https://huggingface.co/csukuangfj/sherpa-onnx-moonshine-tiny-en-int8/resolve/bf2b762c076d8ea61e2af0b3851c9564fb77552e"
    private const val MOONSHINE_JA = "https://huggingface.co/csukuangfj2/sherpa-onnx-moonshine-tiny-ja-quantized-2026-02-27/resolve/550e2eb0a8b33092f3b394f64649ee1b3d9eb506"
    private const val MOONSHINE_JA_MS = "https://modelscope.cn/models/liaowenbin/sherpa-onnx-moonshine-tiny-ja-quantized/resolve/23edc3631598ff4ac5bef6acf0db465443a56451"
    private val silero = ModelFile(
        "vad/silero_vad.onnx", 643_854, "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
        listOf(
            "https://modelscope.cn/models/xnnehang/k2-fsa-silero-vad/resolve/e64bab140550f46d724405a77ce842d2095feeef/silero_vad.onnx",
            "https://hf-mirror.com/mobilebytesensei/betterflow-silero-vad/resolve/0ae41137f2cd00de5e8a286fe01bdd5e3644f2da/silero_vad.onnx",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
        ),
    )

    /** Reazon 的识别文件优先使用已逐字节验证的国内源，解码器不混用其他导出版本。 */
    private fun reazonSources(name: String, modelScope: Boolean = true): List<String> =
        (if (modelScope) listOf("$REAZON_MS/$name") else emptyList()) + listOf("$REAZON_MIRROR/$name", "$REAZON/$name")

    /** SenseVoice 所有受控源均对应相同的固定大小和摘要。 */
    private fun senseSources(name: String): List<String> = listOf("$SENSE_MS/$name", "$SENSE_MIRROR/$name", "$SENSE/$name")

    /** Whisper 国内固定提交副本与官方文件已逐字节比较一致，优先使用国内副本。 */
    private fun whisperSources(name: String): List<String> = listOf("$WHISPER_MS/$name", "$WHISPER/$name")

    /** 日语 Moonshine 的国内固定提交副本与官方文件已逐字节核对一致，失败时回退同一导出。 */
    private fun moonshineJapaneseSources(name: String): List<String> = listOf("$MOONSHINE_JA_MS/$name", "$MOONSHINE_JA/$name")

    val entries: List<ModelSpec> = listOf(
        ModelSpec(
            id = "reazonspeech", name = "ReazonSpeech",
            description = "日语与英语语音识别，包含独立的 Silero VAD。ReazonSpeech 上游项目采用 Apache-2.0，ONNX 仓库未附单独权重许可；Silero VAD 采用 MIT。",
            languages = listOf("日语", "英语"), sizeBytes = 77_745_395,
            kind = ModelKind.REAZON,
            introduction = "日英双语语音识别，支持词元时间戳与漏词修复。模型文件按需下载。",
            licenseUrl = "https://github.com/reazon-research/ReazonSpeech/blob/master/LICENSE",
            files = listOf(
                ModelFile("asr/encoder.int8.onnx", 70_876_409, "ead1579e118b821a767242a8eb9272634b0e63ba16f8dfc4d126732406eae268", reazonSources("encoder-epoch-35-avg-1.int8.onnx")),
                ModelFile("asr/decoder.onnx", 5_165_084, "90b60985672df5023e187e9d25aa2dc93abc4286bfacf71a35a78a6b76d88df5", reazonSources("decoder-epoch-35-avg-1.onnx", false)),
                ModelFile("asr/joiner.int8.onnx", 1_033_417, "c7f4ba40a8ae307a6c30b5c06e2570add04466bcb45bab62699f0ec5d00ed495", reazonSources("joiner-epoch-35-avg-1.int8.onnx")),
                ModelFile("asr/tokens.txt", 26_631, "144f8a4f639373a1bdf7eabb2437482ef64b0cc5db24ad27cce65f293e4faa24", reazonSources("tokens.txt")),
                silero,
            ),
        ),
        ModelSpec(
            id = "sensevoice", name = "SenseVoice Small",
            description = "中文、英语、日语、韩语与粤语识别，包含 Silero VAD。SenseVoice 权重采用 FunASR Model License v1.1 自定义协议，Silero VAD 采用 MIT；使用前请查阅上游许可。",
            languages = listOf("中文", "英语", "日语", "韩语", "粤语"), sizeBytes = 240_193_589,
            kind = ModelKind.SENSEVOICE,
            introduction = "支持中英日韩粤五种语言，提供标点与数字规整。当前应用源语言选项为中英日韩。",
            licenseUrl = "https://github.com/modelscope/FunASR/blob/58830eca4012644aac0c3218c3ccc7d98f003fda/MODEL_LICENSE",
            files = listOf(
                ModelFile("asr/model.int8.onnx", 239_233_841, "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51", senseSources("model.int8.onnx")),
                ModelFile("asr/tokens.txt", 315_894, "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc", senseSources("tokens.txt")),
                silero,
            ),
        ),
        ModelSpec(
            id = "whisper-tiny", name = "Whisper Tiny 多语种",
            description = "Whisper tiny 多语种 INT8 导出，用于原语言转写，支持自动语言判断；应用提供中英日韩源语言选项。此固定导出未接入词元时间戳，包含 Silero VAD。",
            languages = listOf("中文", "英语", "日语", "韩语"), sizeBytes = 104_253_757,
            kind = ModelKind.WHISPER,
            introduction = "多语小模型，支持中英日韩切换或自动判断；识别较慢，适合按需选择。",
            licenseUrl = "https://github.com/openai/whisper/blob/main/LICENSE",
            files = listOf(
                ModelFile("asr/encoder.int8.onnx", 12_937_772, "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434", whisperSources("tiny-encoder.int8.onnx")),
                ModelFile("asr/decoder.int8.onnx", 89_855_401, "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925", whisperSources("tiny-decoder.int8.onnx")),
                ModelFile("asr/tokens.txt", 816_730, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126", whisperSources("tiny-tokens.txt")),
                silero,
            ),
        ),
        ModelSpec(
            id = "moonshine-tiny-en", name = "Moonshine Tiny 英语",
            description = "Moonshine v1 tiny 英语 INT8 导出，仅识别英语，不提供词元时间戳，包含独立预处理、编码器、两个解码器与 Silero VAD。",
            languages = listOf("英语"), sizeBytes = 124_611_393,
            kind = ModelKind.MOONSHINE,
            introduction = "专识英语，适合英语视频和短语音；不识别其他原声语言。",
            licenseUrl = "$MOONSHINE/LICENSE",
            files = listOf(
                ModelFile("asr/preprocess.onnx", 6_800_738, "f33addce61a143460fe753b5ee5b7db255e5140b5b779c065b94f6c83ff0bf4e", listOf("$MOONSHINE/preprocess.onnx")),
                ModelFile("asr/encode.int8.onnx", 18_249_187, "8774dfba578de027ec6595c2c654a0836434489bc963a0db124a7f181f571acb", listOf("$MOONSHINE/encode.int8.onnx")),
                ModelFile("asr/uncached_decode.int8.onnx", 53_216_096, "216737000dd5881a17aa043f6bbd286add33e4c3b0ae257153e2ec15438bdc41", listOf("$MOONSHINE/uncached_decode.int8.onnx")),
                ModelFile("asr/cached_decode.int8.onnx", 45_264_830, "2aff28bba6a03d8dcf5c9feac45462629bae37317442299f28115ad09da773f6", listOf("$MOONSHINE/cached_decode.int8.onnx")),
                ModelFile("asr/tokens.txt", 436_688, "1165c2aeb9f72f457a83be2d459a09054f27490acd9b41bd43794dfd25e296ea", listOf("$MOONSHINE/tokens.txt")),
                silero,
            ),
        ),
        ModelSpec(
            id = "moonshine-tiny-ja", name = "Moonshine Tiny 日语",
            description = "Moonshine v2 tiny 日语量化导出（2026-02-27），仅识别日语，使用当前 sherpa-onnx AAR 已提供的合并解码器，不提供词元时间戳，包含 Silero VAD。权重采用 Moonshine Community License，商业使用须查看上游条款。",
            languages = listOf("日语"), sizeBytes = 72_758_660,
            kind = ModelKind.MOONSHINE_V2,
            introduction = "专识日语，下载体积最小；长语音会自动分段识别。",
            licenseUrl = "$MOONSHINE_JA/LICENSE",
            files = listOf(
                ModelFile("asr/encoder_model.ort", 13_238_184, "86ece73812604b9b5f1274b4d1e6eec0d783b96088ff46d49e30a53f881cad73", moonshineJapaneseSources("encoder_model.ort")),
                ModelFile("asr/decoder_model_merged.ort", 58_327_272, "9fcd9b71323a496b307e20dd305c4e9a1b533c7bedd6e4f660e974967dd60bb6", moonshineJapaneseSources("decoder_model_merged.ort")),
                ModelFile("asr/tokens.txt", 549_350, "2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049", moonshineJapaneseSources("tokens.txt")),
                silero,
            ),
        ),
    )
}
