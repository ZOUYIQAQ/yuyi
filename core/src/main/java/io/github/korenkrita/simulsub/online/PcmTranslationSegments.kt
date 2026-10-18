package io.github.korenkrita.simulsub.online

import java.io.ByteArrayOutputStream

/** 省钱分段模式只用音量寻找上传边界，不加载本地识别模型；连续讲话最多每八秒提交一次。 */
class PcmTranslationSegments {
    private val buffer = ByteArrayOutputStream()
    private var pending = ByteArray(0)
    private var preRoll = ByteArray(0)
    private var speech = false
    private var silenceBytes = 0

    /** 接受任意完整 PCM16 样本，按百毫秒帧判断停顿，返回已完成且不重叠的片段。 */
    fun append(audio: ByteArray): List<ByteArray> {
        require(audio.size % 2 == 0 && audio.size <= 32_000) { "分段音频必须是有界 PCM16 样本" }
        pending += audio
        val completed = mutableListOf<ByteArray>()
        var offset = 0
        while (pending.size - offset >= FRAME_BYTES) {
            val frame = pending.copyOfRange(offset, offset + FRAME_BYTES)
            offset += FRAME_BYTES
            val voiced = hasSound(frame)
            if (!speech && !voiced) {
                preRoll = (preRoll + frame).takeLastBytes(PRE_ROLL_BYTES)
                continue
            }
            if (!speech) { speech = true; buffer.write(preRoll); preRoll = ByteArray(0) }
            buffer.write(frame)
            silenceBytes = if (voiced) 0 else silenceBytes + frame.size
            val pause = buffer.size() >= MIN_BYTES && silenceBytes >= END_SILENCE_BYTES
            val shortEnded = silenceBytes >= SHORT_SILENCE_BYTES
            if (buffer.size() >= MAX_BYTES || pause || shortEnded) take()?.let(completed::add)
        }
        pending = pending.copyOfRange(offset, pending.size)
        return completed
    }

    /** 录音自然结束时提交不足一帧的尾音；用户主动停止时调用方可直接丢弃未上传内容。 */
    fun finish(): ByteArray? {
        if (pending.isNotEmpty()) {
            if (speech || hasSound(pending)) {
                if (!speech) { buffer.write(preRoll); speech = true }
                buffer.write(pending)
            }
            pending = ByteArray(0)
        }
        return take()
    }

    /** 只抑制几乎无声的输入；背景音乐会进入模型，不能把音量边界冒充语义断句。 */
    private fun hasSound(audio: ByteArray): Boolean {
        if (audio.isEmpty()) return false
        var squares = 0.0
        for (index in audio.indices step 2) {
            val sample = ((audio[index].toInt() and 255) or (audio[index + 1].toInt() shl 8)).toShort().toInt()
            squares += sample.toDouble() * sample
        }
        return squares / (audio.size / 2) >= 80.0 * 80.0
    }

    /** 片段至少一百毫秒，提交后重置状态，下一段不会重复上传上一段音频。 */
    private fun take(): ByteArray? {
        val result = buffer.toByteArray().takeIf { speech && it.size >= FRAME_BYTES }
        buffer.reset(); speech = false; silenceBytes = 0; preRoll = ByteArray(0)
        return result
    }

    /** 静音前滚只保留最近两百毫秒，长时间空播不能积压内存或产生付费请求。 */
    private fun ByteArray.takeLastBytes(max: Int): ByteArray = copyOfRange((size - max).coerceAtLeast(0), size)

    companion object {
        private const val FRAME_BYTES = 3_200
        private const val PRE_ROLL_BYTES = 6_400
        private const val MIN_BYTES = 96_000
        private const val END_SILENCE_BYTES = 16_000
        private const val SHORT_SILENCE_BYTES = 48_000
        private const val MAX_BYTES = 256_000
    }
}
