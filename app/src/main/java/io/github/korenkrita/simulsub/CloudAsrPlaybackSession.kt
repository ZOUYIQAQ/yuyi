package io.github.korenkrita.simulsub

import android.media.projection.MediaProjection
import io.github.korenkrita.simulsub.online.CloudAsrConfig
import io.github.korenkrita.simulsub.online.QwenStreamingAsrClient

/** 系统内录到千问听写，再以独立文字接口翻译，不下载或加载本地识别模型。 */
internal class CloudAsrPlaybackSession(
    config: CloudAsrConfig,
    private val projection: MediaProjection,
    service: ByokService,
    scene: Scene,
    source: Lang?,
    target: Target,
    variant: ByokTranslator.ThinkingOff?,
    contextSentences: Int,
    private val onCaption: (Caption) -> Unit,
    private val onStatus: (String) -> Unit,
    private val costs: UsageCostRepository? = null,
    private val costSessionId: String? = null,
) {
    @Volatile private var stopped = false
    @Volatile private var failure: String? = null
    private val translator = ByokTranslator(service, byokSystem(scene, source, target), variant,
        maxTokens = mtMaxTokens(target),
        onUsage = { costs?.recordTranslation(costSessionId, CostCategory.TRANSLATION, service, it) })
    private val pipeline = CloudAsrTranslationPipeline(translator, { if (!stopped) onCaption(it) },
        { failure = it }, contextSentences)
    private val costStreamId = java.util.UUID.randomUUID().toString()
    private val asr = QwenStreamingAsrClient(config,
        onText = { if (!stopped) pipeline.accept(it) }, onStatus = {}, onFailure = { failure = it },
        onUsage = { usage -> costs?.recordObserved(costSessionId, CostCategory.ASR, config.endpoint, config.model,
            MeteredUsage(inputTokens = usage.inputTokens, outputTokens = usage.outputTokens,
                audioSeconds = usage.duration?.toDouble()),
            eventId = costStreamId, cumulative = true) })

    /** 完成听写握手后开始录音；异常和正常退出都取消两个供应商连接。 */
    fun run() {
        try {
            if (stopped) return
            onStatus("正在连接在线听写…")
            if (!asr.start() || !asr.awaitReady(15_000)) {
                if (!stopped) onStatus("⚠ ${failure ?: "在线听写连接未就绪"}")
                return
            }
            if (stopped) return
            pipeline.start()
            PlaybackPcmCapture(projection, { stopped }, { failure }, { failure = it }, onStatus).run(asr::sendPcm16)
        } catch (_: Exception) {
            if (!stopped) onStatus("⚠ ${failure ?: "在线听写已中断，请检查授权和网络后重试"}")
        } finally {
            if (asr.finishAudio()) asr.awaitFinished(2_000)
            asr.close()
            pipeline.close()
        }
    }

    /** 界面停止不等待翻译线程，避免网络请求阻塞主线程；会话线程负责最终回收。 */
    fun stop() {
        stopped = true
        if (!asr.finishAudio()) asr.close()
        translator.cancel()
    }
}
