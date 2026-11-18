package io.github.korenkrita.simulsub

/** 自定义 Jev 支持的两种决策协议，仍发送文字判断请求。 */
enum class JevProtocol(val label: String, val path: String) {
    SYSTEMONE("TypeSafe System One", "/v1/systemone"),
    DECISIONS("OpenRouter Decisions", "/alpha/decisions"),
}

/** 连接信息与密钥分开持久化，密钥统一由平台凭据管理。 */
data class JevCustom(val protocol: JevProtocol, val url: String, val model: String) {
    /** 接受基础地址或完整端点；已有 v1 不重复追加。 */
    fun endpoint(): Pair<String?, String?> = ByokTranslator.httpUrl(url) { path ->
        if (path.endsWith("/systemone") || path.endsWith("/decisions")) path
        else (if (protocol == JevProtocol.SYSTEMONE) path.removeSuffix("/v1") else path.removeSuffix("/alpha")) + protocol.path
    }

    /** 配置整体写入，避免读到不同次保存的协议、地址与模型。 */
    fun encode(): String = Json.encode(mapOf("protocol" to protocol.name, "url" to url, "model" to model))

    companion object {
        /** 空值或损坏配置保留为未配置，不推断其他服务地址。 */
        fun decode(value: String?): JevCustom? = runCatching {
            val map = Json.parse(value ?: return null) as Map<*, *>
            JevCustom(JevProtocol.valueOf(map["protocol"] as String), map["url"] as String, map["model"] as String)
        }.getOrNull()
    }
}

/** 从同一偏好快照解析运行连接；空密钥代表免密，null 代表配置不可用。 */
class JevConnection(val provider: JevProvider, val url: String, val model: String, val key: String?) {
    /** 比较配置以丢弃旧连接的迟到测试结果，密钥不写进界面或日志。 */
    fun sameAs(other: JevConnection): Boolean = provider == other.provider && url == other.url && model == other.model && key == other.key
}
