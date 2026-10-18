package io.github.korenkrita.simulsub

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 显式启用的系统内录到 WSS 受控字幕验收，只允许连接模拟器宿主机，不调用付费服务。 */
class OnlineCapturePipelineDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    /** 使用真实录音、TLS、WebSocket 与生产字幕服务，系统捕获授权由验收脚本确认。 */
    @Test fun playbackAudioReachesCloudProtocolAndSharedCaptionBoard() {
        assumeTrue("仅在独立模拟器启用受控系统内录验收", InstrumentationRegistry.getArguments().getString("runOnlineCapture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = context.getSharedPreferences("jpsub", Context.MODE_PRIVATE)
        val keys = listOf("listeningMode", "onlineEndpoint", "onlineModel", "onlineKey", "sourceLang", "targetLang", "keepLogs")
        val previous = storage.all.filterKeys { it in keys }
        val prefs = Prefs(context)
        val root = requireNotNull(context.getExternalFilesDir(null))
        val records = SessionLog.list(root).map { it.name }.toSet()
        var track: AudioTrack? = null
        try {
            prefs.listeningMode = ListeningMode.ONLINE
            prefs.sourceLang = Lang.JA
            prefs.targetLang = Target.ZH
            prefs.keepLogs = false
            prefs.saveOnline("wss://10.0.2.2:18445/realtime", "qwen3.5-livetranslate-flash-realtime", "fixture-only-no-provider-access")
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithText("开始同传").performClick()
                Log.i("在线系统链路验收", "等待在独立模拟器确认本次系统播放捕获")
                waitFor("系统捕获与云端握手", 90_000) { CaptionService.running && CaptionService.state.value.status.isEmpty() }
                val wave = InstrumentationRegistry.getInstrumentation().context.assets.open("ja.wav").use { stream -> stream.readBytes() }
                val pcm = pcmData(wave)
                track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size).build()
                assertEquals(pcm.size, track!!.write(pcm, 0, pcm.size))
                track!!.play()
                waitFor("非静音内录产生受控定稿", 30_000) {
                    val finals = CaptionService.state.value.captions.filter { caption -> caption.isFinal }
                    finals.joinToString("") { caption -> caption.zh } == "这个v是什么人？真是令人在意的v" &&
                        finals.joinToString("") { caption -> caption.ja } == "このV何者気になるV、他とは違うVだから"
                }
                assertEquals(2, CaptionService.state.value.captions.count { caption -> caption.isFinal })
                assertEquals(records, SessionLog.list(root).map { file -> file.name }.toSet())
                Log.i("在线系统链路验收", "真实播放内录 → PCM16 → 本机 TLS/WSS 协议夹具 → 生产字幕板通过；不是供应商听译准确率测试")
            }
        } finally {
            runCatching { track?.stop() }; track?.release()
            CaptionService.stop(context)
            waitFor("在线会话资源释放", 15_000) {
                !CaptionService.running && Thread.getAllStackTraces().keys.none { thread -> thread.name == "jpsub-session" && thread.isAlive }
            }
            val restore = storage.edit()
            keys.forEach { key ->
                when (val value = previous[key]) {
                    is String -> restore.putString(key, value)
                    is Boolean -> restore.putBoolean(key, value)
                    null -> restore.remove(key)
                    else -> error("测试快照包含未支持类型")
                }
            }
            assertTrue(restore.commit())
        }
    }

    /** 所有系统和网络等待均有截止时间，失败保留诊断。 */
    private fun waitFor(label: String, timeoutMs: Long, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue("等待$label 超时：${CaptionService.state.value.status}", System.currentTimeMillis() < end)
            Thread.sleep(100)
        }
    }

    /** 提取已验证测试 WAV 的 PCM16 数据，避免将 RIFF 文件头当作播放音频。 */
    private fun pcmData(bytes: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val size = buffer.getInt(offset + 4)
            require(size >= 0 && offset + 8L + size <= bytes.size)
            if (String(bytes, offset, 4, Charsets.US_ASCII) == "data") return bytes.copyOfRange(offset + 8, offset + 8 + size)
            offset += 8 + size + size % 2
        }
        error("验收音频缺少 PCM 数据")
    }
}
