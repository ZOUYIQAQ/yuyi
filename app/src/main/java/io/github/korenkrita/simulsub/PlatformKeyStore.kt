package io.github.korenkrita.simulsub

import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 一个平台下的命名凭据；密钥保持输入原文。 */
data class PlatformCredential(val id: String, val label: String, val key: String)

/** 首次升级时的旧服务凭据，迁移后由稳定槽位引用，旧密文不删除。 */
internal data class LegacyPlatformCredential(val platformId: String, val label: String, val slot: String, val key: String)

/** 音频连接使用协议、端点和模型共同组成稳定的凭据绑定槽位。 */
fun listeningCredentialSlot(mode: ListeningMode, endpoint: String, model: String): String =
    "listening:${mode.name}:${endpoint.trim()}:${model.trim()}"

/** 翻译连接的凭据槽位只随配置标识变化。 */
fun translationCredentialSlot(profileId: String): String = "translation:$profileId"

/** Jev 连接的凭据槽位与供应商枚举名称绑定。 */
fun jevCredentialSlot(provider: JevProvider): String = "jev:${provider.name}"

/** 在同一份偏好快照中解析默认指针、目录、绑定和密文；旧中央项保持原标识。 */
internal class PlatformKeyStore(
    private val sp: SharedPreferences,
    private val seal: (String) -> String,
    private val open: (String) -> String,
) {
    /** 自定义辅助服务的中央密钥变化也通知会话，其他平台不会触发重建。 */
    private fun isJevPlatform(platformId: String): Boolean = platformId == PlatformCredentials.TYPESAFE.id ||
        platformId == PlatformCredentials.OPENROUTER.id ||
        PlatformCredentials.forUrl(JevCustom.decode(sp.getString("jevCustom", null))?.endpoint()?.second.orEmpty())?.id == platformId

    /** 对任意来源和槽位编码，避免分隔符与偏好键冲突。 */
    private fun component(value: String): String = Base64.encodeToString(value.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /** 从偏好键还原平台标识，只用于列出已经保存的来源。 */
    private fun decoded(value: String): String? = runCatching {
        String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
    }.getOrNull()

    /** 命名目录键不与旧平台中央密钥键重叠。 */
    private fun listKey(platformId: String): String = "platformCredentialList:${component(platformId)}"

    /** 平台默认只保存标识，与所有密文和服务绑定分别存放。 */
    private fun defaultKey(platformId: String): String = "platformCredentialDefault:${component(platformId)}"

    /** 备用密钥单独加密保存；旧中央项继续使用原键，不随默认切换移动。 */
    private fun keyKey(platformId: String, credentialId: String): String =
        if (credentialId == DEFAULT_ID) "platformKey:$platformId"
        else "platformCredentialKey:${component(platformId)}:$credentialId"

    /** 删除标记避免旧中央占位和被删除标识在重新读取时复活。 */
    private fun deletedKey(platformId: String): String = "platformCredentialDeleted:${component(platformId)}"

    /** 仅认可由删除操作写入的集合，不将异常元数据当成有效凭据。 */
    private fun deleted(snapshot: Map<String, *>, platformId: String): Set<String> =
        (snapshot[deletedKey(platformId)] as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty()

    /** 区分真实存储条目与为兼容旧入口保留的空中央槽。 */
    fun isStored(snapshot: Map<String, *>, platformId: String, credentialId: String): Boolean =
        credentialId !in deleted(snapshot, platformId) && snapshot.containsKey(keyKey(platformId, credentialId))

    /** 绑定键同时限定平台和具体服务。 */
    private fun bindingKey(platformId: String, serviceSlot: String): String =
        "platformCredentialBinding:${component(platformId)}:${component(serviceSlot)}"

    /** 损坏与已删除的目录项不进入可选凭据，兼容旧中央项排序。 */
    private fun labels(snapshot: Map<String, *>, platformId: String): List<Pair<String, String>> {
        val array = runCatching { JSONArray(snapshot[listKey(platformId)] as? String ?: "[]") }.getOrNull()
        val extra = (0 until (array?.length() ?: 0)).mapNotNull { index ->
            val item = array?.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            val label = item.optString("label").trim()
            if (!validId(id) || label.isEmpty() || label.length > MAX_LABEL) null else id to label
        }.distinctBy { it.first }
        return (listOf(DEFAULT_ID to (extra.firstOrNull { it.first == DEFAULT_ID }?.second ?: DEFAULT_LABEL)) +
            extra.filterNot { it.first == DEFAULT_ID }).filterNot { it.first in deleted(snapshot, platformId) }
    }

    /** 中央项读取原有密文且不改写；备用项仅解密自己的存储键。 */
    private fun credential(snapshot: Map<String, *>, platformId: String, entry: Pair<String, String>): PlatformCredential {
        val ciphertext = snapshot[keyKey(platformId, entry.first)] as? String
        val key = ciphertext?.let { runCatching { open(it) }.getOrNull() }.orEmpty()
        return PlatformCredential(entry.first, entry.second, key)
    }

    /** 获取未删除凭据，未配置的旧中央槽由界面识别为占位。 */
    fun credentials(snapshot: Map<String, *>, platformId: String): List<PlatformCredential> =
        labels(snapshot, platformId).map { credential(snapshot, platformId, it) }

    /** 仅接受中央标识或本平台目录内的规范 UUID，原始标识不裁剪。 */
    private fun checkedId(snapshot: Map<String, *>, platformId: String, id: String): String {
        require(validId(id)) { "凭据标识无效" }
        require(labels(snapshot, platformId).any { it.first == id }) { "凭据不存在" }
        return id
    }

    /** 无元数据时兼容中央项；删除标记表示无默认，损坏指针拒绝回退。 */
    fun defaultId(snapshot: Map<String, *>, platformId: String): String {
        val storageKey = defaultKey(platformId)
        if (!snapshot.containsKey(storageKey)) return DEFAULT_ID
        val id = snapshot[storageKey]
        require(id is String) { "平台默认凭据标识无效" }
        if (id == NO_DEFAULT_ID && deleted(snapshot, platformId).isNotEmpty()) return ""
        return checkedId(snapshot, platformId, id)
    }

    /** 当前默认仅从给定快照读取；损坏指针返回空值，不借用旧中央项。 */
    fun defaultCredential(snapshot: Map<String, *>, platformId: String): PlatformCredential? = runCatching {
        val id = defaultId(snapshot, platformId)
        credential(snapshot, platformId, labels(snapshot, platformId).first { it.first == id })
    }.getOrNull()

    /** 原子切换默认指针，不改写目录、任何密文或服务绑定；空密钥项也可设为默认。 */
    fun setDefault(platformId: String, credentialId: String) = synchronized(sp) {
        val snapshot = sp.all
        val id = checkedId(snapshot, platformId, credentialId)
        val storageKey = defaultKey(platformId)
        if (snapshot[storageKey] == id || (!snapshot.containsKey(storageKey) && id == DEFAULT_ID))
            return@synchronized
        val editor = sp.edit().putString(storageKey, id)
        if (isJevPlatform(platformId))
            editor.putLong("jevKey:changed", System.nanoTime())
        check(editor.commit()) { "平台默认凭据切换失败，请重试" }
    }

    /** 命名凭据的自定义来源也出现在平台列表，即使当前没有连接引用它。 */
    fun storedPlatforms(snapshot: Map<String, *>): List<CredentialPlatform> = snapshot.keys.mapNotNull { storageKey ->
        val encoded = storageKey.removePrefix("platformCredentialList:")
        if (encoded == storageKey) return@mapNotNull null
        val id = decoded(encoded) ?: return@mapNotNull null
        if (id == PlatformCredentials.QWEN.id) PlatformCredentials.QWEN
        else if (id == PlatformCredentials.DEEPSEEK.id) PlatformCredentials.DEEPSEEK
        else if (id.startsWith("origin:")) {
            val origin = id.removePrefix("origin:")
            PlatformCredentials.forUrl(origin)?.takeIf { it.id == id } ?: CredentialPlatform(id, origin)
        } else null
    }.distinctBy { it.id }

    /** 缺失或空串表示跟随默认；损坏类型和非法、未知标识直接报错。 */
    fun selectedId(snapshot: Map<String, *>, platformId: String, serviceSlot: String): String? {
        val storageKey = bindingKey(platformId, serviceSlot)
        if (!snapshot.containsKey(storageKey)) return null
        val id = snapshot[storageKey]
        require(id is String) { "服务绑定的凭据标识无效" }
        if (id.isEmpty()) return null
        return checkedId(snapshot, platformId, id)
    }

    /** 显式绑定始终读取原目标；仅未绑定服务跟随当前默认，错误绑定不回退。 */
    fun selected(snapshot: Map<String, *>, platformId: String, serviceSlot: String): PlatformCredential? = runCatching {
        val id = selectedId(snapshot, platformId, serviceSlot) ?: defaultId(snapshot, platformId)
        credential(snapshot, platformId, labels(snapshot, platformId).first { it.first == id })
    }.getOrNull()

    /** 写入凭据及其名称；中央项沿用旧密文键，不清理任何旧字段。 */
    fun save(platformId: String, credentialId: String, label: String, key: String) = synchronized(sp) {
        require(validId(credentialId)) { "凭据标识无效" }
        require(credentialId !in deleted(sp.all, platformId)) { "凭据已删除，请重新添加" }
        val checkedLabel = label.trim()
        require(checkedLabel.isNotEmpty() && checkedLabel.length <= MAX_LABEL) { "凭据名称长度需为 1 到 40 字" }
        val snapshot = sp.all
        val entries = labels(snapshot, platformId)
        val updated = if (entries.any { it.first == credentialId })
            entries.map { if (it.first == credentialId) credentialId to checkedLabel else it }
        else entries + (credentialId to checkedLabel)
        val sealed = seal(key)
        val array = JSONArray().apply { updated.forEach { (id, name) -> put(JSONObject().put("id", id).put("label", name)) } }
        val editor = sp.edit().putString(listKey(platformId), array.toString())
            .putString(keyKey(platformId, credentialId), sealed)
        // 新平台的第一把密钥自动成为默认；已有其他密钥时不擅自改变默认引用。
        if (entries.none { isStored(snapshot, platformId, it.first) } &&
            runCatching { defaultId(snapshot, platformId) }.getOrNull() in listOf("", DEFAULT_ID))
            editor.putString(defaultKey(platformId), credentialId)
        if (isJevPlatform(platformId))
            editor.putLong("jevKey:changed", System.nanoTime())
        check(editor.commit()) { "平台凭据保存失败，请重试" }
    }

    /** 首次迁移将不同的旧密钥拆为命名凭据，与默认及迁移标志同次提交，避免服务串用账号。 */
    fun putLegacyBindings(editor: SharedPreferences.Editor, snapshot: Map<String, *>,
        defaults: Map<String, String>, bindings: List<LegacyPlatformCredential>) {
        bindings.groupBy { it.platformId }.forEach { (platformId, services) ->
            // 这些元数据来自新版显式操作；不能用更早的旧字段覆盖用户的新选择或清空意图。
            if (snapshot.containsKey(keyKey(platformId, DEFAULT_ID)) || snapshot.containsKey(listKey(platformId)) ||
                snapshot.containsKey(defaultKey(platformId)) || snapshot.containsKey(deletedKey(platformId))) return@forEach
            val known = mutableMapOf(defaults[platformId].orEmpty() to DEFAULT_ID)
            val entries = labels(snapshot, platformId).toMutableList()
            var added = false
            services.forEach serviceLoop@ { service ->
                val storageKey = bindingKey(platformId, service.slot)
                if (snapshot.containsKey(storageKey)) return@serviceLoop
                val id = known[service.key] ?: UUID.randomUUID().toString().also { fresh ->
                    known[service.key] = fresh
                    entries += fresh to service.label.take(MAX_LABEL).ifBlank { "升级保留密钥" }
                    editor.putString(keyKey(platformId, fresh), seal(service.key))
                    added = true
                }
                // 与旧默认相同的服务继续跟随默认，其余服务保留各自的独立引用，包括原本空密钥的服务。
                if (id != DEFAULT_ID) editor.putString(storageKey, id)
            }
            if (added) editor.putString(listKey(platformId), JSONArray().apply {
                entries.forEach { (id, label) -> put(JSONObject().put("id", id).put("label", label)) }
            }.toString())
        }
    }

    /** 保存服务绑定；空值恢复跟随默认，所有密文保持原样。 */
    fun select(platformId: String, serviceSlot: String, credentialId: String?) = synchronized(sp) {
        require(serviceSlot.startsWith("listening:") || serviceSlot.startsWith("translation:") ||
            serviceSlot.startsWith("jev:")) { "服务槽位无效" }
        if (credentialId != null) checkedId(sp.all, platformId, credentialId)
        val editor = sp.edit().putString(bindingKey(platformId, serviceSlot), credentialId.orEmpty())
        if (serviceSlot.startsWith("jev:")) editor.putLong("jevKey:changed", System.nanoTime())
        check(editor.commit()) {
            "服务凭据选择失败，请重试"
        }
    }

    /** 旧入口写入平台当前默认，保留中央旧项、命名目录和全部服务绑定。 */
    fun saveDefault(platformId: String, key: String) = synchronized(sp) {
        val id = defaultId(sp.all, platformId)
        if (id.isEmpty()) {
            val fresh = UUID.randomUUID().toString()
            save(platformId, fresh, DEFAULT_LABEL, key)
            setDefault(platformId, fresh)
            return@synchronized
        }
        val editor = sp.edit().putString(keyKey(platformId, id), seal(key))
        if (isJevPlatform(platformId))
            editor.putLong("jevKey:changed", System.nanoTime())
        check(editor.commit()) { "平台密钥保存失败，请重试" }
    }

    /** 连同连接字段原子写入所绑定凭据；未知绑定直接失败。 */
    fun putBoundKey(editor: SharedPreferences.Editor, snapshot: Map<String, *>, platformId: String,
        serviceSlot: String, key: String): SharedPreferences.Editor {
        var id = selectedId(snapshot, platformId, serviceSlot) ?: defaultId(snapshot, platformId)
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString()
            val entries = labels(snapshot, platformId) + (id to DEFAULT_LABEL)
            val array = JSONArray().apply { entries.forEach { (entryId, name) ->
                put(JSONObject().put("id", entryId).put("label", name))
            } }
            editor.putString(listKey(platformId), array.toString()).putString(defaultKey(platformId), id)
        }
        editor.putString(keyKey(platformId, id), seal(key))
        if (isJevPlatform(platformId))
            editor.putLong("jevKey:changed", System.nanoTime())
        return editor
    }

    /** 原子删除目录与密文；原服务绑定保留失效引用，不静默借用其他账号。 */
    fun delete(platformId: String, credentialId: String) = synchronized(sp) {
        val snapshot = sp.all
        checkedId(snapshot, platformId, credentialId)
        require(isStored(snapshot, platformId, credentialId)) { "凭据不存在" }
        val remaining = labels(snapshot, platformId).filterNot { it.first == credentialId }
        val array = JSONArray().apply { remaining.forEach { (id, name) ->
            put(JSONObject().put("id", id).put("label", name))
        } }
        val editor = sp.edit().remove(keyKey(platformId, credentialId))
            .putString(listKey(platformId), array.toString())
            .putStringSet(deletedKey(platformId), deleted(snapshot, platformId) + credentialId)
        if (runCatching { defaultId(snapshot, platformId) }.getOrNull() == credentialId)
            editor.putString(defaultKey(platformId), NO_DEFAULT_ID)
        if (isJevPlatform(platformId))
            editor.putLong("jevKey:changed", System.nanoTime())
        check(editor.commit()) { "密钥删除失败，请重试" }
    }

    /** 仅默认标识或规范 UUID 可写入目录。 */
    private fun validId(id: String): Boolean = id == DEFAULT_ID ||
        runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

    companion object {
        private const val NO_DEFAULT_ID = "deleted-default"
        const val DEFAULT_ID = "default"
        const val DEFAULT_LABEL = "默认密钥"
        const val MAX_LABEL = 40
    }
}
