package io.github.korenkrita.simulsub

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import io.github.korenkrita.simulsub.online.*

/** 云端会话只录制应用播放声音；后台线程持有录音对象，不加载任何本地模型。 */
internal class OnlinePlaybackSession(
    private val config: LiveTranslateConfig,
    private val projection: MediaProjection,
    private val onCaption: (Caption) -> Unit,
    private val onStatus: (String) -> Unit,
    private val costs: UsageCostRepository? = null,
    private val costSessionId: String? = null,
) {
    @Volatile private var stopped = false
    @Volatile private var failure: String? = null
    private val adapter = OnlineCaptionAdapter()
    private val costStreamId = java.util.UUID.randomUUID().toString()
    @Volatile private var audioSent = false
    @Volatile private var meteredResponse = false
    private val listener = object : LiveTranslateListener {
        /** 只把仍在运行的字幕交给正式字幕板，停止后的网络回调不再绘制。 */
        override fun onText(text: LiveTranslationText) {
            if (!stopped) adapter.acceptSegmented(text).forEach(onCaption)
        }
        /** 连接失败即停止采集，不积压音频或自动重连。 */
        override fun onError(error: LiveTranslateError) {
            failure = error.message
        }
        /** 响应 ID 保持稳定，重复的服务端用量不会重复收费。 */
        override fun onUsage(eventId: String, usage: MeteredUsage) {
            meteredResponse = true
            costs?.recordObserved(costSessionId, CostCategory.DIRECT, config.endpoint, config.model, usage,
                eventId = "$costStreamId:$eventId")
        }
    }
    private val client = if (config.isRealtime) QwenLiveTranslateClient(config, listener) else null
    private val fileClient = if (!config.isRealtime) QwenFileTranslateClient(config, listener) else null
    private val segments = PcmTranslationSegments()
    private val uploads = java.util.concurrent.ArrayBlockingQueue<ByteArray>(2)
    private var uploadWorker: Thread? = null

    /** 在服务线程完成握手和录音循环，所有出口都释放录音、套接字与超时线程。 */
    fun run() {
        try {
            if (stopped) return
            if (!config.isRealtime) {
                runSegmented()
                return
            }
            val realtime = requireNotNull(client)
            onStatus("正在连接千问听译…")
            if (!realtime.start() || !realtime.awaitReady()) {
                if (!stopped) onStatus("⚠ ${failure ?: "在线听译连接未就绪"}")
                return
            }
            if (!stopped) capture { pcm -> realtime.sendPcm16(pcm).also { if (it) audioSent = true } }
        } catch (_: Exception) {
            if (!stopped) onStatus("⚠ 无法录制播放音频，请检查系统授权后重试")
        } finally {
            if (client?.state == LiveTranslateState.FINISHING) client.awaitClosed(2_000)
            client?.close()
            if (config.isRealtime && audioSent && (!meteredResponse || client?.completed != true)) costs?.recordObserved(costSessionId,
                CostCategory.DIRECT, config.endpoint, config.model, MeteredUsage(), eventId = "$costStreamId:incomplete")
            fileClient?.close()
            uploads.clear()
            uploadWorker?.interrupt()
            uploadWorker?.join(2_000)
        }
    }

    /** 前台停止只设置标志并取消网络，录音对象仍由会话线程自己释放。 */
    fun stop() {
        stopped = true
        if (client?.state == LiveTranslateState.READY) client.finish()
        else if (client?.state != LiveTranslateState.FINISHING) client?.close()
        fileClient?.close()
        uploads.clear()
        uploadWorker?.interrupt()
    }

    /** 上传线程与采集分开；最多两段等待，服务跟不上时明确停止，不能静默丢音频或无限延迟。 */
    private fun runSegmented() {
        val translator = requireNotNull(fileClient)
        uploadWorker = Thread({
            var sequence = 0L
            try {
                while (!stopped && failure == null) {
                    val pcm = uploads.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    if (!translator.translatePcm16(pcm, "file_${++sequence}") && !stopped) {
                        failure = failure ?: "分段听译失败，请检查配置与网络后重新开始"
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                if (!stopped) failure = "分段听译已中断，请重新开始"
            }
        }, "在线分段上传").also { it.start() }
        capture { pcm ->
            segments.append(pcm).all { complete ->
                if (uploads.offer(complete)) true else {
                    failure = "分段听译速度跟不上播放，请检查网络或切换实时听译"
                    false
                }
            }
        }
    }

    /** 采集对象仅在会话线程创建和释放，停止操作只改变标志。 */
    private fun capture(send: (ByteArray) -> Boolean) = PlaybackPcmCapture(projection,
        { stopped }, { failure }, { failure = it }, onStatus).run(send)
}
