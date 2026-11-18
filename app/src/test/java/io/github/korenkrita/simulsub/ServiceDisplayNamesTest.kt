package io.github.korenkrita.simulsub

import io.github.korenkrita.simulsub.online.LiveTranslateModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 服务标题只由实际调用配置决定，重复连接以稳定序号区分。 */
class ServiceDisplayNamesTest {
    /** 本地、在线听写和实时直译按真实协议显示，旧模式明确标注停用。 */
    @Test fun listeningTitlesFollowModeAndModel() {
        assertEquals("本地 · whisper-small", listeningServiceTitle(ListeningMode.LOCAL, "whisper-small"))
        assertEquals("千问 3.1 Streaming · 听写",
            listeningServiceTitle(ListeningMode.CLOUD_ASR, "qwen-audio-3.1-asr-flash-streaming"))
        assertEquals(LiveTranslateModels.realtime.label,
            listeningServiceTitle(ListeningMode.ONLINE, LiveTranslateModels.REALTIME_ID))
        assertEquals("已停用的分段听译",
            listeningServiceTitle(ListeningMode.ONLINE, LiveTranslateModels.ECONOMY_ID))
        assertEquals("千问语音直译 · qwen-future", listeningServiceTitle(ListeningMode.ONLINE, "qwen-future"))
    }

    /** 官方来源显示平台与模型；未知来源和空模型不把地址塞入主标题。 */
    @Test fun translationTitlesUseRecognizedProviderAndActualModel() {
        assertEquals("DeepSeek · deepseek-chat",
            translationServiceTitle("https://api.deepseek.com/v1/chat/completions", "deepseek-chat"))
        assertEquals("千问 · qwen-plus",
            translationServiceTitle("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-plus"))
        assertEquals("硅基流动 · Qwen/Qwen3", translationServiceTitle("https://api.siliconflow.cn/v1", "Qwen/Qwen3"))
        assertEquals("自定义接口 · local-model", translationServiceTitle("http://127.0.0.1:8000/v1", "local-model"))
        assertEquals("自定义接口", translationServiceTitle("https://other.example/v1", " "))
    }

    /** 同平台同模型的不同连接按目录顺序编号，不改变服务标识或独立标题。 */
    @Test fun duplicateTitlesKeepIdsAndStableOrder() {
        assertEquals(linkedMapOf("a" to "DeepSeek · chat（1）", "b" to "千问 · plus",
            "c" to "DeepSeek · chat（2）"), distinctServiceTitles(listOf(
                "a" to "DeepSeek · chat", "b" to "千问 · plus", "c" to "DeepSeek · chat")))
        assertEquals(linkedMapOf("a" to "服务（2）", "b" to "服务（1）", "c" to "服务（3）"),
            distinctServiceTitles(listOf("a" to "服务", "b" to "服务（1）", "c" to "服务")))
    }

    /** 重复标识不能静默覆盖目录中的另一项。 */
    @Test fun duplicateIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            distinctServiceTitles(listOf("same" to "服务 A", "same" to "服务 B"))
        }
    }
}
