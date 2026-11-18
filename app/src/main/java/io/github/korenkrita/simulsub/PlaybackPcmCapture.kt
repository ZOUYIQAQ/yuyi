package io.github.korenkrita.simulsub

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection

/** 两种云端路径共用系统播放采集，不加载本地模型或保存录音。 */
internal class PlaybackPcmCapture(
    private val projection: MediaProjection,
    private val isStopped: () -> Boolean,
    private val failure: () -> String?,
    private val onFailure: (String) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    /** 直接采集十六位音频，不重采样、不存盘，每一百毫秒发送一个有界分片。 */
    @SuppressLint("MissingPermission")
    fun run(send: (ByteArray) -> Boolean) {
        val playback = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "系统不支持所需的录音格式" }
        val recorder = AudioRecord.Builder().setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min, 64_000)).setAudioPlaybackCaptureConfig(playback).build()
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "录音未就绪" }
            if (isStopped()) return
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "录音启动失败" }
            onStatus("")
            val samples = ShortArray(1_600)
            while (!isStopped() && failure() == null) {
                val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                if (count < 0) error("录音已中断")
                if (count == 0) { Thread.sleep(10); continue }
                val bytes = ByteArray(count * 2)
                for (index in 0 until count) {
                    bytes[index * 2] = samples[index].toByte()
                    bytes[index * 2 + 1] = (samples[index].toInt() shr 8).toByte()
                }
                if (!send(bytes)) {
                    if (!isStopped()) onFailure(failure() ?: "听译连接不可用，请检查网络后重新开始")
                    break
                }
            }
            if (!isStopped() && failure() != null) onStatus("⚠ ${failure()}")
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }
}
