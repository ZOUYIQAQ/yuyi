package io.github.korenkrita.simulsub.updates

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Proxy
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 仅描述发行信息，由调用方决定版本比较和打开哪个链接。 */
internal data class AvailableUpdate(
    val tag: String,
    val versionName: String,
    val notes: String,
    val releaseUrl: String,
    val downloadUrl: String? = null,
)

internal interface ReleaseUpdateSource {
    /** 获取最新稳定发行；仓库尚无发行时返回空值。 */
    suspend fun latest(): AvailableUpdate?
}

/** 只请求本仓库发行元数据，不下载安装包，也不复用应用的认证或代理。 */
internal class GithubReleaseUpdateSource(client: OkHttpClient = OkHttpClient()) : ReleaseUpdateSource {
    private val http = client.newBuilder().apply {
        interceptors().clear()
        networkInterceptors().clear()
    }
        .proxy(Proxy.NO_PROXY)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 协程取消立即取消底层请求；回调线程读取有限响应，不阻塞主线程。 */
    override suspend fun latest(): AvailableUpdate? = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Yuyi-Android-Update")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build())
        continuation.invokeOnCancellation { call.cancel() }
        if (continuation.isActive) call.enqueue(object : Callback {
            /** 网络故障转换为用户可读异常，取消后的请求不再交付结果。 */
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWith(Result.failure(IOException("检查更新失败，请检查网络后重试。", e)))
                }
            }

            /** 始终关闭响应，并将状态错误和超限错误交给调用方处理。 */
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (!continuation.isActive) return@use null
                        when {
                            it.code == 404 -> null
                            !it.isSuccessful -> throw IOException("检查更新失败：GitHub 返回 HTTP ${it.code}，请稍后重试。")
                            else -> parseRelease(readLimited(it))
                        }
                    }
                }.recoverCatching { error ->
                    throw IOException("检查更新失败，请稍后重试。${error.message.orEmpty()}", error)
                }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
    }

    companion object {
        private const val LATEST_URL = "https://api.github.com/repos/ZOUYIQAQ/yuyi/releases/latest"
        private const val REPO_PATH = "/ZOUYIQAQ/yuyi/releases"
        private const val MAX_RESPONSE_BYTES = 256 * 1024
        private const val MAX_NOTES = 2_000
        private val incompatible = Regex("x86|armeabi|32[-_ ]?bit|armv[5-7]|arm32|aarch32|mips|riscv", RegexOption.IGNORE_CASE)

        /** 纯解析入口与网络读取使用同一字节上限，不依赖 Android 或当前版本。 */
        fun parseRelease(text: String): AvailableUpdate? {
            if (text.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) {
                throw IOException("更新信息超过 256 KiB，已停止读取。")
            }
            try {
                val json = JSONObject(text)
                val draft = json.get("draft")
                val prerelease = json.get("prerelease")
                if (draft !is Boolean || prerelease !is Boolean) throw JSONException("发行状态格式错误")
                if (draft || prerelease) return null
                val tag = json.getString("tag_name")
                if (!ReleaseVersions.isStable(tag)) return null
                val releaseUrl = json.getString("html_url")
                if (trustedPath(releaseUrl) != "$REPO_PATH/tag/$tag") {
                    throw IOException("更新信息中的发行页不属于本仓库，请稍后重试。")
                }
                val assets = json.optJSONArray("assets")
                val downloads = buildList {
                    if (assets != null) for (index in 0 until assets.length()) {
                        val asset = assets.optJSONObject(index) ?: continue
                        val name = asset.optString("name")
                        val url = asset.optString("browser_download_url")
                        val path = trustedPath(url) ?: continue
                        val prefix = "$REPO_PATH/download/$tag/"
                        if (!path.startsWith(prefix)) continue
                        val filename = path.removePrefix(prefix)
                        if (filename.isEmpty() || '/' in filename || '\\' in filename ||
                            !isCompatibleApk(name) || !isCompatibleApk(filename)) continue
                        add(Triple(architectureRank(name), name, url))
                    }
                }
                val download = downloads.sortedWith(compareBy<Triple<Int, String, String>> { it.first }
                    .thenBy { it.second }.thenBy { it.third }).firstOrNull()?.third
                return AvailableUpdate(tag, tag.removePrefix("v").removePrefix("V"),
                    readableNotes(json.optString("body", "")), releaseUrl, download)
            } catch (error: JSONException) {
                throw IOException("更新信息格式不正确，请稍后重试。", error)
            }
        }

        /** 只信任 HTTPS GitHub 原站，无认证、端口、查询、片段及路径逃逸。 */
        private fun trustedPath(value: String): String? {
            val uri = try { URI(value) } catch (_: Exception) { return null }
            if (uri.scheme != "https" || uri.host != "github.com" || uri.rawUserInfo != null ||
                uri.port != -1 || uri.rawQuery != null || uri.rawFragment != null) return null
            val path = uri.path ?: return null
            if ('\\' in path || path.any { it.isISOControl() } ||
                path.split('/').any { it == "." || it == ".." } ||
                Regex("%2f|%5c|%25", RegexOption.IGNORE_CASE).containsMatchIn(uri.rawPath)) return null
            return path
        }

        /** 排除不支持的架构及路径字符，只保留 APK 文件名。 */
        private fun isCompatibleApk(name: String): Boolean = name.endsWith(".apk", ignoreCase = true) &&
            '/' !in name && '\\' !in name && !name.any { it.isISOControl() } &&
            !incompatible.containsMatchIn(name)

        /** 专用 arm64-v8a 优先，然后 arm64，最后无架构标记的通用包。 */
        private fun architectureRank(name: String): Int {
            val lower = name.lowercase(Locale.ROOT)
            return when {
                "arm64-v8a" in lower || "arm64_v8a" in lower -> 0
                "arm64" in lower || "aarch64" in lower -> 1
                else -> 2
            }
        }

        /** 流式读取最多上限加一字节，兼容缺少或伪造 Content-Length 的响应。 */
        private fun readLimited(response: Response): String {
            val body = response.body ?: throw IOException("更新服务没有返回内容，请稍后重试。")
            if (body.contentLength() > MAX_RESPONSE_BYTES) throw IOException("更新信息超过 256 KiB，已停止读取。")
            return body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val count = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    if (output.size() > MAX_RESPONSE_BYTES) throw IOException("更新信息超过 256 KiB，已停止读取。")
                }
                output.toString(Charsets.UTF_8.name())
            }
        }

        /** 将有限长度的说明作为纯文本展示，不执行 HTML 或 Markdown。 */
        private fun readableNotes(markdown: String): String = markdown.take(MAX_NOTES)
            .replace(Regex("<[^>\\n]{0,500}>"), "")
            .replace(Regex("!?\\[([^\\[\\]\\n]{0,200})]\\([^()\\n]{0,500}\\)"), "$1")
            .replace(Regex("(?m)^[\\t ]{0,3}#{1,6}[\\t ]+"), "")
            .replace(Regex("(?m)^[\\t ]*[-*+][\\t ]+"), "• ")
            .replace("**", "").replace("__", "").replace("`", "")
            .filter { !it.isISOControl() || it == '\n' || it == '\t' }
            .trim().take(MAX_NOTES).ifBlank { "这个版本暂时没有填写更新内容。" }
    }
}

internal object ReleaseVersions {
    private val stableVersion = Regex("[vV]?[0-9]+(?:\\.[0-9]+)*")

    /** 仅接受稳定版本的数字组件，任意长度组件比较时不转换为整数。 */
    fun isNewer(candidate: String, installed: String): Boolean {
        if (!isStable(candidate) || !isStable(installed)) return false
        val next = components(candidate)
        val current = components(installed)
        for (index in 0 until maxOf(next.size, current.size)) {
            val left = next.getOrElse(index) { "0" }
            val right = current.getOrElse(index) { "0" }
            val compared = if (left.length != right.length) left.length.compareTo(right.length) else left.compareTo(right)
            if (compared != 0) return compared > 0
        }
        return false
    }

    /** 排除预发行、空组件、符号和其他不明确的版本名称。 */
    internal fun isStable(version: String): Boolean = stableVersion.matches(version)

    /** 去掉前缀和无意义的前导零，使组件可按长度及字典序比较。 */
    private fun components(version: String): List<String> = version.removePrefix("v").removePrefix("V")
        .split('.').map { it.trimStart('0').ifEmpty { "0" } }
}
