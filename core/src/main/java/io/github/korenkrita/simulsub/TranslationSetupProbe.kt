package io.github.korenkrita.simulsub

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 引导测试的实际可用配置；目录条目只表示供应商已返回，选中模型还必须通过翻译请求。 */
data class TranslationSetupResult(
    val url: String? = null, val model: String? = null, val variantName: String? = null,
    val models: List<String> = emptyList(), val catalogAvailable: Boolean = false, val error: String? = null,
)

/** 引导控制器只依赖可取消的探测边界，测试可隔离付费网络调用。 */
interface TranslationSetupDiscovery {
    /** 返回实际验证的配置及供应商目录，不修改用户存储。 */
    fun probe(preferredModels: List<String>): TranslationSetupResult
    /** 终止当前与后续请求。 */
    fun cancel()
}

/** 有界探测同源模型目录、接口后缀和真实翻译能力；取消后不得再发送请求。 */
class TranslationSetupProbe(
    private val service: ByokService,
    private val system: String,
    private val maxTokens: Int = mtMaxTokens(Target.ZH),
    private val onUsage: (ByokService, MtResult) -> Unit = { _, _ -> },
    private val timeoutMs: Long = 30_000,
) : TranslationSetupDiscovery {
    private val lock = Any()
    @Volatile private var cancelled = false
    @Volatile private var timedOut = false
    private var catalogCall: Call? = null
    private var translator: ByokTranslator? = null

    /** 同时取消目录请求和翻译探测，注册与取消在同一把锁内，避免取消后遗漏新请求。 */
    override fun cancel(): Unit = synchronized(lock) {
        cancelled = true
        catalogCall?.cancel()
        translator?.cancel()
        Unit
    }

    /** 保留当前选择优先，失败才尝试下一项；最多验证四个模型，不逐个收费遍历整个目录。 */
    override fun probe(preferredModels: List<String>): TranslationSetupResult {
        if (cancelled) return stopped()
        val timer = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "翻译配置探测超时").apply { isDaemon = true }
        }
        val timeout = timer.schedule({ timedOut = true; cancel() }, timeoutMs, TimeUnit.MILLISECONDS)
        try {
            // 引导隐藏地址，由模板负责基础路径；完整预设也需要尝试有／无v1两种同源后缀。
            val entered = service.url.trim().trimEnd('/')
            val base = if (entered.endsWith("/v1/chat/completions")) entered.removeSuffix("/chat/completions") else entered
            val (error, endpoints) = ByokTranslator.chatCandidates(base)
            if (error != null) return TranslationSetupResult(error = error)
            var listed: List<String>? = null
            for (endpoint in endpoints) {
                if (cancelled) return stopped()
                val catalog = readCatalog(endpoint.removeSuffix("/chat/completions") + "/models")
                if (cancelled) return stopped()
                if (catalog.status == 401) return TranslationSetupResult(error = "密钥无效或未获授权，请检查后重试")
                if (catalog.models != null) { listed = catalog.models; break }
                if (catalog.status !in listOf(404, 405)) break
            }
            val fallback = (listOf(service.model) + preferredModels).filter { it.isNotBlank() }.distinct()
            val candidates = if (listed == null) fallback else (fallback.filter { it in listed } + listed).distinct()
            if (candidates.isEmpty()) return TranslationSetupResult(models = listed.orEmpty(), catalogAvailable = listed != null,
                error = "没有找到可用于文字翻译的模型，请检查账户权限或选择其他供应商")
            var last: ByokTranslator.ConnectionProbeResult? = null
            for (model in candidates.take(MAX_MODEL_ATTEMPTS)) {
                val selected = service.copy(model = model)
                val client = ByokTranslator(selected, system, null, maxTokens = maxTokens,
                    onUsage = { onUsage(selected, it) })
                synchronized(lock) { if (cancelled) client.cancel() else translator = client }
                if (cancelled) return stopped(candidates, listed != null)
                val result = try { client.probeConnection(base) } finally {
                    synchronized(lock) { if (translator === client) translator = null }
                    client.cancel()
                }
                if (cancelled) return stopped(candidates, listed != null)
                val reply = result.lastResult
                val hasTranslation = reply?.http == 200 && reply.text.isNotBlank() && reply.stop in listOf('e', 'l')
                if (result.url != null && hasTranslation)
                    return TranslationSetupResult(result.url, model, result.variantName, candidates, listed != null)
                last = result
                // 只有模型不存在、不兼容或始终不出译文才换模型；鉴权、限流和网络故障立即停止。
                if (reply?.http !in listOf(400, 404, 405, 422) && !(result.url != null && reply?.http == 200)) break
            }
            return TranslationSetupResult(models = candidates, catalogAvailable = listed != null,
                error = last?.error ?: "连接测试未通过，请选择其他模型后重试")
        } catch (_: Exception) {
            return if (cancelled) stopped() else TranslationSetupResult(error = "无法完成连接测试，请检查密钥和网络后重试")
        } finally {
            timeout.cancel(false)
            timer.shutdownNow()
        }
    }

    /** 中断原因只含本地说明，不回显供应商原始报文或请求凭据。 */
    private fun stopped(models: List<String> = emptyList(), catalog: Boolean = false) =
        TranslationSetupResult(models = models, catalogAvailable = catalog,
            error = if (timedOut) "测试超时，请稍后重试" else "连接测试已取消")

    /** 目录解析失败可退回模板，但明确为空的有效目录不能伪装成已发现可用模型。 */
    private data class Catalog(val status: Int, val models: List<String>? = null)

    /** 仅GET同源目录，不跟随重定向；响应大小和条目数均有界。 */
    private fun readCatalog(url: String): Catalog {
        val request = Request.Builder().url(url).apply {
            service.requestHeaders().forEach { (name, value) -> header(name, value) }
        }.build()
        val call = HTTP.newCall(request)
        synchronized(lock) { if (cancelled) call.cancel() else catalogCall = call }
        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) return Catalog(response.code)
                val source = response.body?.source() ?: return Catalog(response.code)
                source.request(MAX_CATALOG_BYTES + 1)
                if (source.buffer.size > MAX_CATALOG_BYTES) return Catalog(response.code)
                val data = JSONObject(source.readUtf8()).optJSONArray("data") ?: return Catalog(response.code)
                val models = (0 until minOf(data.length(), 4096)).mapNotNull { index ->
                    val item = data.optJSONObject(index) ?: return@mapNotNull null
                    val id = item.opt("id") as? String ?: return@mapNotNull null
                    id.takeIf { textModel(item, it) }
                }.distinct().take(256)
                Catalog(response.code, models)
            }
        } catch (_: Exception) { Catalog(0) }
        finally { synchronized(lock) { if (catalogCall === call) catalogCall = null } }
    }

    /** 有模态元数据时遵从元数据；否则只排除明确的非文字生成模型，最终能力由实请求验证。 */
    private fun textModel(item: JSONObject, id: String): Boolean {
        if (id.isBlank() || id.length > 256 || id.any { it.isISOControl() }) return false
        for (key in listOf("input_modalities", "output_modalities")) {
            val modes = item.optJSONArray(key) ?: continue
            if ((0 until modes.length()).none { modes.optString(it) == "text" }) return false
        }
        return !NON_TEXT.containsMatchIn(id.lowercase())
    }

    companion object {
        private const val MAX_MODEL_ATTEMPTS = 4
        private const val MAX_CATALOG_BYTES = 512L * 1024
        private val NON_TEXT = Regex("embedding|rerank|whisper|(?:^|[-_/])(?:tts|asr|audio|image|video|moderation|realtime)(?:$|[-_/])|dall-e|live.?translate")
        // 全进程复用连接池；目录请求禁用跳转，避免凭据被带到其他来源或降级协议。
        private val HTTP = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).callTimeout(5, TimeUnit.SECONDS).build()
    }
}
