package io.github.korenkrita.simulsub.models

/** 固定模型目录；ReazonSpeech 的长度和摘要逐项来自项目 setup.sh。 */
object ModelCatalog {
    private const val REAZON = "https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en/resolve/12b44671ff48b9aed622551d64e02fea9a2cf870"
    private const val REAZON_MIRROR = "https://hf-mirror.com/csukuangfj/reazonspeech-k2-v2-ja-en/resolve/12b44671ff48b9aed622551d64e02fea9a2cf870"
    private const val REAZON_MS = "https://modelscope.cn/models/yinannn/yinanyi-reazon-ja/resolve/ddfd04fb9a112a3fcfde1e05fc976a9bec07db7f"
    private const val SENSE_MS = "https://modelscope.cn/models/pengzhendong/sherpa-onnx-sense-voice-zh-en-ja-ko-yue/resolve/73eca47697f980daa3d16112404174b6b950b514"
    private const val SENSE_MIRROR = "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07"
    private const val SENSE = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07"
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

    val entries: List<ModelSpec> = listOf(
        ModelSpec(
            id = "reazonspeech", name = "ReazonSpeech",
            description = "日语与英语语音识别，包含独立的 Silero VAD。ReazonSpeech 上游项目采用 Apache-2.0，ONNX 仓库未附单独权重许可；Silero VAD 采用 MIT。",
            languages = listOf("日语", "英语"), sizeBytes = 77_745_395,
            kind = ModelKind.REAZON,
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
            files = listOf(
                ModelFile("asr/model.int8.onnx", 239_233_841, "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51", senseSources("model.int8.onnx")),
                ModelFile("asr/tokens.txt", 315_894, "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc", senseSources("tokens.txt")),
                silero,
            ),
        ),
    )
}
