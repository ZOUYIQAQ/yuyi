package io.github.korenkrita.simulsub

import android.content.Context
import android.content.SharedPreferences
import io.github.korenkrita.simulsub.online.CloudAsrConfig
import io.github.korenkrita.simulsub.online.LiveTranslateModels
import org.json.JSONArray
import org.json.JSONObject

/** 文字翻译配置的只读摘要；已配置只表示对应地址与模型可以使用。 */
data class TranslationServiceSummary(
    val id: String,
    val name: String,
    val model: String,
    val url: String,
    val configured: Boolean,
)

/** 具名听写服务单独保存连接字段；旧偏好及中央密钥不由此仓库修改。 */
class ListeningServiceRepository(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("listening_services", 0)
    private val legacySp: SharedPreferences = context.getSharedPreferences("jpsub", 0)

    init {
        migrateRetiredServices(context)
    }

    /** 原位升级可安全转换的旧分段条目，保留标识、活动选择、原始备份及无法迁移的原记录。 */
    private fun migrateRetiredServices(context: Context) = synchronized(sp) {
        val array = runCatching { JSONArray(sp.getString(PROFILES_KEY, null) ?: "[]") }.getOrNull()
            ?: return@synchronized
        var prefs: Prefs? = null
        val editor = sp.edit()
        var changed = false
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optString("mode") != ListeningMode.ONLINE.name ||
                !LiveTranslateModels.isRetired(item.optString("model"))) continue
            val original = item.toString()
            val old = runCatching { ListeningServiceProfile(item.getString("id"), item.getString("name"),
                ListeningMode.ONLINE, item.getString("endpoint"), item.getString("model")) }.getOrNull() ?: continue
            val converted = runCatching { old.migratedRealtime() }.getOrNull() ?: continue
            val store = prefs ?: Prefs(context).also { prefs = it }
            if (store.migrateRetiredOnlineConnection(old.endpoint, old.model) == null) continue
            val backupKey = "retiredOnlineProfileV1:${old.id}"
            if (!sp.contains(backupKey)) editor.putString(backupKey, original)
            if (sp.getString(ACTIVE_KEY, null) == old.id) editor.putString(RETIRED_ACTIVE_KEY, old.id)
            item.put("endpoint", converted.endpoint).put("model", converted.model)
            changed = true
        }
        if (changed) check(editor.putString(PROFILES_KEY, array.toString()).commit()) { "旧分段听译目录迁移失败，请重试" }
    }

    /** 按创建顺序列出可用服务；损坏的条目不会冒充有效配置。 */
    val profiles: List<ListeningServiceProfile> get() = readProfiles()

    /** 仅返回仍存在的活动服务标识。 */
    val activeId: String?
        get() = sp.getString(ACTIVE_KEY, null)?.takeIf { id ->
            sp.getString(RETIRED_ACTIVE_KEY, null) != id && profiles.any { it.id == id }
        }

    /** 保存一套已验证连接字段；编辑已有服务不改变当前选择或旧运行配置。 */
    fun save(profile: ListeningServiceProfile) = synchronized(sp) {
        val checked = profile.validated()
        val old = readProfiles()
        val next = if (old.any { it.id == checked.id }) old.map { if (it.id == checked.id) checked else it }
            else old + checked
        check(sp.edit().putString(PROFILES_KEY, encode(next)).commit()) { "听写服务保存失败，请重试" }
    }

    /** 只标记活动服务；空标识表示暂不选择，不写旧模式或连接字段。 */
    fun select(id: String?) = synchronized(sp) {
        require(id == null || readProfiles().any { it.id == id }) { "听写服务不存在" }
        val editor = sp.edit().putString(ACTIVE_KEY, id)
        if (sp.contains(RETIRED_ACTIVE_KEY)) editor.putString(RETIRED_ACTIVE_KEY, "")
        check(editor.commit()) { "听写服务选择失败，请重试" }
    }

    /** 首次将已保存且就绪的旧听写连接复制为具名服务，保留旧字段与密文。 */
    fun migrateLegacy(prefs: Prefs) = synchronized(sp) {
        if (!sp.getBoolean(MIGRATED_KEY, false)) {
            val current = readProfiles()
            val added = buildList {
                if (prefs.cloudAsrReady) {
                    val old = ListeningServiceProfile(ListeningServiceProfile.newId(), "千问在线听写", ListeningMode.CLOUD_ASR,
                        prefs.cloudAsrEndpoint, prefs.cloudAsrModel).validated()
                    if (current.none { it.sameConnection(old) }) add(old)
                }
                if (prefs.onlineReady) {
                    val old = ListeningServiceProfile(ListeningServiceProfile.newId(), "千问在线听译", ListeningMode.ONLINE,
                        prefs.onlineEndpoint, prefs.onlineModel).validated()
                    if (current.none { it.sameConnection(old) }) add(old)
                }
            }
            val selected = (current + added).firstOrNull { it.mode == prefs.listeningMode &&
                ((it.mode == ListeningMode.CLOUD_ASR && prefs.cloudAsrReady &&
                    it.endpoint == prefs.cloudAsrEndpoint && it.model == prefs.cloudAsrModel) ||
                    (it.mode == ListeningMode.ONLINE && prefs.onlineReady &&
                        it.endpoint == prefs.onlineEndpoint && it.model == prefs.onlineModel)) }?.id
            val editor = sp.edit().putBoolean(MIGRATED_KEY, true)
            if (added.isNotEmpty()) editor.putString(PROFILES_KEY, encode(current + added))
            if (selected != null && activeId == null) editor.putString(ACTIVE_KEY, selected)
            check(editor.commit()) { "旧听写服务迁移失败，请重试" }
        }
        ensureBuiltIns()
    }

    /** 只在首次补入缺失的听写和实时听译连接；已有同配置和活动选择保持原样。 */
    fun ensureBuiltIns() = synchronized(sp) {
        if (sp.getBoolean(BUILT_INS_KEY, false)) return@synchronized
        val current = readProfiles()
        val added = BUILT_INS.filter { builtin -> current.none { it.id == builtin.id || it.sameConnection(builtin) } }
        val editor = sp.edit().putBoolean(BUILT_INS_KEY, true)
        if (added.isNotEmpty()) editor.putString(PROFILES_KEY, encode(current + added))
        check(editor.commit()) { "预制听写服务保存失败，请重试" }
    }

    /** 连接身份只由协议、端点和模型决定，旧名称与标识不参与去重。 */
    private fun ListeningServiceProfile.sameConnection(other: ListeningServiceProfile): Boolean =
        mode == other.mode && endpoint == other.endpoint && model == other.model

    /** 从同一份旧偏好快照读取全部文字翻译配置，不切换当前配置。 */
    @Suppress("UNUSED_PARAMETER")
    fun translationServices(prefs: Prefs): List<TranslationServiceSummary> {
        val snapshot = legacySp.all
        return LlmProfile.all(snapshot["byokProfiles"] as? String).map { profile ->
            val suffix = profile.suffix
            val url = (snapshot["byokUrl$suffix"] as? String).orEmpty()
            val model = (snapshot["byokModel$suffix"] as? String).orEmpty()
            TranslationServiceSummary(profile.id, profile.name, model, url,
                snapshot.containsKey("byokUrl$suffix") && snapshot.containsKey("byokModel$suffix") &&
                    model.isNotBlank() && ByokTranslator.chatUrl(url).second != null)
        }
    }

    /** 解码后仅隐藏与原生条目同连接的迁移副本，不改存储或合并用户原生服务。 */
    private fun readProfiles(): List<ListeningServiceProfile> {
        val snapshot = sp.all
        val array = runCatching { JSONArray(snapshot[PROFILES_KEY] as? String ?: "[]") }.getOrNull() ?: return emptyList()
        val profiles = (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            runCatching {
                ListeningServiceProfile(
                    item.getString("id"), item.getString("name"), ListeningMode.valueOf(item.getString("mode")),
                    item.getString("endpoint"), item.getString("model"),
                ).validated()
            }.getOrNull()
        }.distinctBy { it.id }
        val original = profiles.filterNot { snapshot.containsKey("retiredOnlineProfileV1:${it.id}") }
        return profiles.filter { profile ->
            !snapshot.containsKey("retiredOnlineProfileV1:${profile.id}") ||
                original.none { it.sameConnection(profile) }
        }
    }

    /** 序列化仅写五个公开连接字段，不复制凭据或会话内容。 */
    private fun encode(items: List<ListeningServiceProfile>): String = JSONArray().apply {
        items.forEach { profile ->
            put(JSONObject().put("id", profile.id).put("name", profile.name).put("mode", profile.mode.name)
                .put("endpoint", profile.endpoint).put("model", profile.model))
        }
        // 被绑定冲突阻止迁移的条目只退出可选目录，后续保存其他服务也不能抹掉它们。
        val previous = runCatching { JSONArray(sp.getString(PROFILES_KEY, null) ?: "[]") }.getOrNull()
        for (index in 0 until (previous?.length() ?: 0)) {
            val item = previous?.optJSONObject(index) ?: continue
            if (item.optString("mode") == ListeningMode.ONLINE.name &&
                LiveTranslateModels.isRetired(item.optString("model")) &&
                items.none { it.id == item.optString("id") }) put(item)
        }
    }.toString()

    internal companion object {
        private const val PROFILES_KEY = "profiles"
        private const val ACTIVE_KEY = "activeId"
        private const val RETIRED_ACTIVE_KEY = "retiredActiveIdV1"
        private const val MIGRATED_KEY = "legacyMigratedV1"
        private const val BUILT_INS_KEY = "builtInsSeededV1"
        val BUILT_INS = listOf(
            ListeningServiceProfile("5ad3f686-6e9e-4f96-8479-092ea780a904", "千问在线听写", ListeningMode.CLOUD_ASR,
                CloudAsrConfig.DEFAULT_ENDPOINT, CloudAsrConfig.DEFAULT_MODEL),
            ListeningServiceProfile("a73b1888-6539-47b5-82f8-3b10347f3e9a", "千问实时听译", ListeningMode.ONLINE,
                LiveTranslateModels.REALTIME_ENDPOINT, LiveTranslateModels.REALTIME_ID),
        ).map { it.validated() }
    }
}
