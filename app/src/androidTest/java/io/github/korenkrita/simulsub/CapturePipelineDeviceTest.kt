package io.github.korenkrita.simulsub

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.korenkrita.simulsub.models.ModelRepository
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 本地 HTTPS 翻译夹具配合真实系统播放捕获，验证完整字幕链路。 */
class CapturePipelineDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    /** 显式传入 runCapture=true 才执行；系统捕获弹窗仍需本地验收操作确认。 */
    @Test fun playbackCaptureReachesTranslatedOverlay() {
        assumeTrue("需本地 HTTPS 夹具及系统捕获确认", InstrumentationRegistry.getArguments().getString("runCapture") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = Prefs(context)
        val previous = Triple(prefs.byokUrl, prefs.byokModel, prefs.byokKey)
        val headers = prefs.byokHeaders
        val agent = prefs.byokUserAgent
        val keepLogs = prefs.keepLogs
        val jev = prefs.jevOn
        val jevCheck = prefs.jevCheck
        val url = "https://10.0.2.2:18443/v1/chat/completions"
        var track: AudioTrack? = null
        try {
            prefs.saveByok(url, "本地链路验收", "", "", emptyList())
            prefs.setByokVariant(url, "本地链路验收", "none")
            prefs.keepLogs = true
            prefs.jevOn = false
            prefs.jevCheck = false
            val repository = ModelRepository.get(context)
            waitUntil("模型库恢复", 30_000) { repository.selectedPaths() != null }
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithText("开始同传").performClick()
                Log.i("系统链路验收", "请在专用模拟器确认本次捕获整个屏幕；不操作任何远程设备")
                waitUntil("系统捕获授权", 90_000) { CaptionService.running }
                Thread.sleep(4_000)
                val wav = instrumentation.context.assets.open("ja.wav").use { input -> input.readBytes() }
                val pcm = pcmData(wav)
                track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size).build()
                assertEquals(pcm.size, track!!.write(pcm, 0, pcm.size))
                track!!.play()
                waitUntil("内录识别与翻译", 45_000) {
                    CaptionService.state.value.captions.any { caption -> caption.isFinal && caption.zh.contains("五十日元") && caption.ja.contains("中学") }
                }
                val snapshot = CaptionService.state.value
                snapshot.captions.forEach { caption -> Log.i("系统链路验收", "捕获原文：${caption.ja}；受控译文：${caption.zh}") }
                instrumentation.waitForIdleSync()
                Thread.sleep(350)
                val screenshot = File(context.getExternalFilesDir(null), "capture-pipeline.png")
                screenshot.outputStream().use { output -> instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, output) }
                Log.i("系统链路验收", "真实系统捕获→VAD→ASR→HTTPS流式翻译夹具→悬浮字幕已通过；截图：${screenshot.path}")
            }
        } finally {
            track?.stop(); track?.release()
            CaptionService.stop(context)
            waitUntil("服务停止并释放原生会话", 15_000) {
                !CaptionService.running && Thread.getAllStackTraces().keys.none { thread -> thread.name == "jpsub-session" && thread.isAlive }
            }
            instrumentation.waitForIdleSync()
            prefs.saveByok(previous.first, previous.second, previous.third ?: "", agent, headers)
            prefs.keepLogs = keepLogs
            prefs.jevOn = jev
            prefs.jevCheck = jevCheck
        }
    }

    /** 使用明确超时等待系统和异步管线，不无限阻塞测试。 */
    private fun waitUntil(name: String, timeout: Long, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (!condition()) {
            assertTrue("等待$name 超时：${CaptionService.state.value}", System.currentTimeMillis() < end)
            Thread.sleep(300)
        }
    }

    /** 从测试 WAV 中提取 PCM16 数据供系统媒体流播放。 */
    private fun pcmData(bytes: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val size = buffer.getInt(offset + 4)
            require(size >= 0 && offset + 8L + size <= bytes.size)
            if (String(bytes, offset, 4, Charsets.US_ASCII) == "data") return bytes.copyOfRange(offset + 8, offset + 8 + size)
            offset += 8 + size + size % 2
        }
        error("验收音频缺少数据")
    }
}
