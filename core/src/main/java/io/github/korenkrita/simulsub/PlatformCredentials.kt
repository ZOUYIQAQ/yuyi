package io.github.korenkrita.simulsub

import java.net.URI

/** 凭据所属平台；标识稳定且可从自定义地址还原。 */
data class CredentialPlatform(val id: String, val label: String)

/** 官方服务按精确主机共享密钥，其他服务按规范化来源隔离。 */
object PlatformCredentials {
    val QWEN = CredentialPlatform("qwen", "阿里百炼 · 千问")
    val DEEPSEEK = CredentialPlatform("deepseek", "DeepSeek 官方")
    val SILICONFLOW = CredentialPlatform("origin:https://api.siliconflow.cn", "硅基流动")
    val OPENROUTER = CredentialPlatform("origin:https://openrouter.ai", "OpenRouter")
    val TYPESAFE = CredentialPlatform("origin:https://api.typesafe.ai", "TypeSafe 官方")
    val defaults: List<CredentialPlatform> = (listOf(QWEN, DEEPSEEK, SILICONFLOW, OPENROUTER, TYPESAFE) +
        TextServiceProviders.entries.map { CredentialPlatform(it.platformId, it.title) }).distinctBy { it.id }

    private val qwenHosts = setOf("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com",
        "dashscope-us.aliyuncs.com", "cn-hongkong.dashscope.aliyuncs.com")
    private val qwenWorkspaceRegions = setOf("cn-beijing", "cn-shanghai", "ap-southeast-1",
        "cn-hongkong", "us-east-1", "eu-central-1", "ap-northeast-1")

    /** 只接受已知地域下单层业务空间域名，避免其他阿里云主机继承千问密钥。 */
    private fun isQwenHost(host: String): Boolean {
        if (host in qwenHosts) return true
        return qwenWorkspaceRegions.any { region ->
            val suffix = ".$region.maas.aliyuncs.com"
            if (!host.endsWith(suffix)) return@any false
            val workspace = host.removeSuffix(suffix)
            workspace.isNotEmpty() && workspace.first().isLetterOrDigit() &&
                workspace.last().isLetterOrDigit() && workspace.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
        }
    }

    /** 仅官方 HTTPS/WSS 主机使用官方槽位；其余有效地址只共享相同来源的槽位。 */
    fun forUrl(url: String): CredentialPlatform? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in setOf("http", "https", "ws", "wss") || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null) return null
        val host = uri.host?.lowercase()?.removeSuffix(".")?.takeIf { it.isNotEmpty() } ?: return null
        val port = uri.port
        if (port == 0 || port > 65535) return null
        val secure = scheme == "https" || scheme == "wss"
        val defaultPort = if (secure) 443 else 80
        if (secure && (port == -1 || port == 443)) {
            if (isQwenHost(host)) return QWEN
            if (host == "api.deepseek.com") return DEEPSEEK
        }
        val protocol = if (secure) "https" else "http"
        val authority = if (host.startsWith("[") && host.endsWith("]")) host
            else if (host.contains(':')) "[$host]" else host
        val origin = "$protocol://$authority${if (port == -1 || port == defaultPort) "" else ":$port"}"
        val id = "origin:$origin"
        return defaults.firstOrNull { it.id == id } ?: CredentialPlatform(id, origin)
    }
}
