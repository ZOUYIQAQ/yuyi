package io.github.korenkrita.simulsub

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 手动开启的一次真实供应商专项，仅读已授权的平台密钥，不改用户配置或回显凭据。 */
class TranslationSetupLiveDeviceTest {
    /** 从现有设备密钥验证目录和译文，不启动录音、下载模型或全面验收。 */
    @Test fun savedDeepseekKeyReturnsWorkingSetupAndModelOptions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("真实请求需显式开启专项参数", InstrumentationRegistry.getArguments().getString("runLiveSetupProbe") == "true")
        val context = instrumentation.targetContext
        val key = Prefs(context).platformKey("deepseek")
        assertTrue("设备需要已保存的DeepSeek测试密钥", key.isNotEmpty())
        val provider = TextServiceProviders.entries.first { it.id == "deepseek" }
        val usages = mutableListOf<MtResult>()
        val started = System.nanoTime()
        val client = TranslationSetupProbe(ByokService(provider.url, provider.model, key),
            byokSystem(Scene.VTUBER, Lang.JA, Target.ZH), onUsage = { _, usage -> usages += usage })
        val result = try { client.probe(TextModelCatalog.forProvider(provider.id)) } finally { client.cancel() }
        assertNotNull(result.error ?: "应返回完整可用地址", result.url)
        assertTrue(result.catalogAvailable)
        assertTrue(result.model in result.models)
        assertTrue("应实际产生翻译正文", usages.any { it.http == 200 && it.text.isNotBlank() })
        val proof = JSONObject().put("url", result.url).put("selectedModel", result.model)
            .put("models", JSONArray(result.models)).put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
            .put("translationRequests", usages.size).put("catalogAvailable", result.catalogAvailable)
        val directory = File(context.getExternalFilesDir(null), "qa-translation-setup").apply { mkdirs() }
        File(directory, "live-provider-result.json").writeText(proof.toString(2))
    }
}
