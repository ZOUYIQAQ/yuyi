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
    private val captions = OnlineCaptionPacer({ caption ->
        if (!stopped) onCaption(caption)
    }, { message -> failure = failure ?: message })
    private val costStreamId = java.util.UUID.randomUUID().toString()
    @Volatile private var audioSent = false
    @Volatile private var meteredResponse = false
    private val listener = object : LiveTranslateListener {
        /** 网络回调只入队；草稿与校正异步更新，新定稿按阅读时间投递，超载后不再接收。 */
        override fun onText(text: LiveTranslationText) {
            if (!stopped && failure == null && !captions.offer(adapter.acceptSegmented(text)) && !stopped) {
                // 同步停止后续入队，详细原因由节奏线程异步通知；不能让后续快照继续扩张适配器缓存。
                failure = failure ?: "字幕容量超限，本批字幕未接收、未显示；正在读完已接收字幕，请暂停播放后重新开始"
            }
        }
        /** 连接失败即停止采集，不积压音频或自动重连。 */
        override fun onError(error: LiveTranslateError) {
            failure = error.message
        }
        /** 同一连接只有一份累计计量基线，响应号改变也只入账增长量。 */
        override fun onCumulativeUsage(eventId: String, usage: MeteredUsage) {
            meteredResponse = true
            costs?.recordObserved(costSessionId, CostCategory.DIRECT, config.endpoint, config.model, usage,
                eventId = "$costStreamId:usage", cumulative = true)
        }
    }
    private val client = QwenLiveTranslateClient(config, listener)

    /** 在服务线程完成握手和录音循环，所有出口都释放录音、套接字与超时线程。 */
    fun run() {
        try {
            if (stopped) return
            val realtime = client
            onStatus("正在连接千问听译…")
            if (!realtime.start() || !realtime.awaitReady()) {
                if (!stopped) onStatus("⚠ ${failure ?: "在线听译连接未就绪"}")
                return
            }
            if (!stopped) capture { pcm -> realtime.sendPcm16(pcm).also { if (it) audioSent = true } }
        } catch (_: Exception) {
            if (!stopped) onStatus("⚠ 无法录制播放音频，请检查系统授权后重试")
        } finally {
            try {
                if (client.state == LiveTranslateState.FINISHING) client.awaitClosed(2_000)
                client.close()
                if (audioSent && (!meteredResponse || !client.completed)) costs?.recordObserved(costSessionId,
                    CostCategory.DIRECT, config.endpoint, config.model, MeteredUsage(), eventId = "$costStreamId:incomplete")
                // 先关闭所有字幕生产者，再在会话后台线程读完尾部；人工停止会立即取消并唤醒等待。
                if (!stopped) {
                    captions.finish()
                    if (!captions.awaitDrained(70_000) && !stopped) onStatus("⚠ 字幕阅读收尾超时，请重新开始")
                }
            } finally { captions.close() }
        }
    }

    /** 前台停止取消阅读任务和网络，录音对象由会话线程释放，不等待阅读倒计时或字幕日志回调。 */
    fun stop() {
        stopped = true
        captions.close()
        if (client.state == LiveTranslateState.READY) client.finish()
        else if (client.state != LiveTranslateState.FINISHING) client.close()
    }

    /** 采集对象仅在会话线程创建和释放，停止操作只改变标志。 */
    private fun capture(send: (ByteArray) -> Boolean) = PlaybackPcmCapture(projection,
        { stopped }, { failure }, { failure = it }, onStatus).run(send)
}
