package io.github.korenkrita.simulsub

import android.content.Context
import io.github.korenkrita.simulsub.online.LiveTranslateConfig
import io.github.korenkrita.simulsub.online.LiveTranslateModels
import io.github.korenkrita.simulsub.online.CloudAsrConfig

/** Subtitle look, shared by the settings screen (live preview) and the overlay. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("jpsub", Context.MODE_PRIVATE)
    private val listeningServicesSp = ctx.getSharedPreferences("listening_services", Context.MODE_PRIVATE)
    private val platformKeys = PlatformKeyStore(sp, KeyBox::seal, KeyBox::open)
    private val migrationKey = "platformCredentialsMigratedV1"
    private val qwenHostMigrationKey = "platformQwenHostsMigratedV1"
    private val jevMigrationKey = "platformJevCredentialsMigratedV1"

    /** 从单次快照解密平台当前默认；空串明确清空，错误默认指针不回退。 */
    private fun keyFrom(snapshot: Map<String, *>, id: String): String? =
        platformKeys.defaultCredential(snapshot, id)?.key

    /** 平台标识需来自已知平台或有效来源，旧业务空间来源保留可编辑性。 */
    private fun checkedPlatformId(id: String): String {
        require(id == PlatformCredentials.QWEN.id || id == PlatformCredentials.DEEPSEEK.id ||
            (id.startsWith("origin:") && PlatformCredentials.forUrl(id.removePrefix("origin:"))?.let {
                it.id == id || sp.contains("platformKey:$id") || platformKeys.storedPlatforms(sp.all).any { stored -> stored.id == id }
            } == true)) { "平台标识无效" }
        return id
    }

    /** 同一快照解析服务专用密钥，显式绑定缺失时不借用默认项。 */
    private fun boundKey(snapshot: Map<String, *>, endpoint: String, slot: String): String =
        PlatformCredentials.forUrl(endpoint)?.let { platformKeys.selected(snapshot, it.id, slot)?.key }.orEmpty()

    /** 兼容旧可空读取：默认槽位尚未保存时返回空值，已保存空串仍是明确选择。 */
    private fun boundKeyOrNull(snapshot: Map<String, *>, platformId: String, slot: String): String? {
        val selected = platformKeys.selected(snapshot, platformId, slot) ?: return null
        if (selected.id == PlatformKeyStore.DEFAULT_ID && !snapshot.containsKey("platformKey:$platformId")) return null
        return selected.key
    }

    /** 首次升级按平台保留默认，按服务保留不同密钥；默认、命名目录和绑定原子迁移。 */
    private fun migratePlatformKeys() = synchronized(sp) {
        val snapshot = sp.all
        if (snapshot[migrationKey] == true) return@synchronized
        val profiles = LlmProfile.all(snapshot["byokProfiles"] as? String)
        val active = profiles.firstOrNull { it.id == snapshot["byokActive"] } ?: profiles.first()
        val ordered = listOf(active) + profiles.filterNot { it.id == active.id }
        val legacy = linkedMapOf<String, String>()
        for (profile in ordered) {
            val url = (snapshot["byokUrl${profile.suffix}"] as? String).orEmpty()
            val platform = PlatformCredentials.forUrl(url) ?: continue
            val sealed = snapshot["byokKey${profile.suffix}"] as? String ?: continue
            val raw = runCatching { KeyBox.open(sealed) }.getOrNull() ?: continue
            legacy.putIfAbsent(platform.id, raw)
        }
        val mode = snapshot["listeningMode"] as? String
        val qwenOrder = if (mode == ListeningMode.CLOUD_ASR.name) listOf("cloudAsrKey", "onlineKey")
            else listOf("onlineKey", "cloudAsrKey")
        var oldQwen: String? = null
        for (field in qwenOrder) {
            val endpointField = if (field == "onlineKey") "onlineEndpoint" else "cloudAsrEndpoint"
            val defaultEndpoint = if (field == "onlineKey") LiveTranslateConfig.DEFAULT_ENDPOINT else CloudAsrConfig.DEFAULT_ENDPOINT
            val endpoint = snapshot[endpointField] as? String ?: defaultEndpoint
            val platform = PlatformCredentials.forUrl(endpoint) ?: continue
            val sealed = snapshot[field] as? String ?: continue
            val raw = runCatching { KeyBox.open(sealed) }.getOrNull() ?: continue
            if (platform == PlatformCredentials.QWEN) {
                if (oldQwen == null) oldQwen = raw
            } else legacy.putIfAbsent(platform.id, raw)
        }
        val activePlatform = PlatformCredentials.forUrl((snapshot["byokUrl${active.suffix}"] as? String).orEmpty())
        val activeQwen = if (activePlatform == PlatformCredentials.QWEN)
            (snapshot["byokKey${active.suffix}"] as? String)?.let { runCatching { KeyBox.open(it) }.getOrNull() }
            else null
        val chosenQwen = if (mode == ListeningMode.LOCAL.name || mode == null) activeQwen ?: oldQwen
            else oldQwen ?: activeQwen
        if (chosenQwen != null) legacy[PlatformCredentials.QWEN.id] = chosenQwen
        val bindings = mutableListOf<LegacyPlatformCredential>()
        for (profile in ordered) {
            val url = (snapshot["byokUrl${profile.suffix}"] as? String).orEmpty()
            val platform = PlatformCredentials.forUrl(url) ?: continue
            val ciphertext = snapshot["byokKey${profile.suffix}"] as? String
            val raw = ciphertext?.let { runCatching { KeyBox.open(it) }.getOrNull() }.orEmpty()
            bindings += LegacyPlatformCredential(platform.id, "${profile.name} · 升级保留", translationCredentialSlot(profile.id), raw)
        }
        for ((field, audioMode) in listOf("onlineKey" to ListeningMode.ONLINE, "cloudAsrKey" to ListeningMode.CLOUD_ASR)) {
            val endpointField = if (audioMode == ListeningMode.ONLINE) "onlineEndpoint" else "cloudAsrEndpoint"
            if (!snapshot.containsKey(field) && !snapshot.containsKey(endpointField)) continue
            val endpoint = snapshot[endpointField] as? String ?: if (audioMode == ListeningMode.ONLINE)
                LiveTranslateConfig.DEFAULT_ENDPOINT else CloudAsrConfig.DEFAULT_ENDPOINT
            val platform = PlatformCredentials.forUrl(endpoint) ?: continue
            val model = snapshot[if (audioMode == ListeningMode.ONLINE) "onlineModel" else "cloudAsrModel"] as? String
                ?: if (audioMode == ListeningMode.ONLINE) LiveTranslateConfig.DEFAULT_MODEL else CloudAsrConfig.DEFAULT_MODEL
            val raw = (snapshot[field] as? String)?.let { runCatching { KeyBox.open(it) }.getOrNull() }.orEmpty()
            bindings += LegacyPlatformCredential(platform.id, if (audioMode == ListeningMode.ONLINE) "听译 · 升级保留" else "听写 · 升级保留",
                listeningCredentialSlot(audioMode, endpoint, model), raw)
        }
        for (provider in JevProvider.entries.filter { it.needsKey }) {
            val field = "jevKey:${provider.name}"
            val active = snapshot["jevProvider"] == provider.name && (snapshot["jevOn"] == true || snapshot["jevCheck"] == true)
            if (!snapshot.containsKey(field) && !active) continue
            val platform = PlatformCredentials.forUrl(provider.url) ?: continue
            val raw = (snapshot[field] as? String)?.let { runCatching { KeyBox.open(it) }.getOrNull() }.orEmpty()
            if (raw.isNotEmpty()) legacy.putIfAbsent(platform.id, raw)
            bindings += LegacyPlatformCredential(platform.id, "Jev · 升级保留", jevCredentialSlot(provider), raw)
        }
        val editor = sp.edit()
        platformKeys.putLegacyBindings(editor, snapshot, legacy, bindings)
        for ((id, raw) in legacy) {
            if (!snapshot.containsKey("platformKey:$id")) editor.putString("platformKey:$id", KeyBox.seal(raw))
        }
        check(editor.putBoolean(migrationKey, true).commit()) { "平台密钥迁移失败，请重试" }
    }

    /** 旧版按来源保存的官方业务空间密钥，只在千问中央槽位缺失时补入。 */
    private fun migrateQwenHostKeys() = synchronized(sp) {
        val snapshot = sp.all
        if (snapshot[qwenHostMigrationKey] == true) return@synchronized
        val editor = sp.edit()
        if (!snapshot.containsKey("platformKey:${PlatformCredentials.QWEN.id}")) {
            val profiles = LlmProfile.all(snapshot["byokProfiles"] as? String)
            val active = profiles.firstOrNull { it.id == snapshot["byokActive"] } ?: profiles.first()
            val ordered = listOf(active) + profiles.filterNot { it.id == active.id }
            for (profile in ordered) {
                val url = (snapshot["byokUrl${profile.suffix}"] as? String).orEmpty()
                if (PlatformCredentials.forUrl(url) != PlatformCredentials.QWEN) continue
                val host = runCatching { java.net.URI(url).host?.lowercase()?.removeSuffix(".") }.getOrNull() ?: continue
                val oldId = "origin:https://$host"
                val sealed = snapshot["platformKey:$oldId"] as? String ?: continue
                val raw = runCatching { KeyBox.open(sealed) }.getOrNull() ?: continue
                editor.putString("platformKey:${PlatformCredentials.QWEN.id}", KeyBox.seal(raw))
                break
            }
        }
        check(editor.putBoolean(qwenHostMigrationKey, true).commit()) { "千问业务空间密钥迁移失败，请重试" }
    }

    /** 已有中央密钥优先；仅把加密的旧 Jev 凭据补入尚未使用的官方来源槽位。 */
    private fun migrateJevKeys() = synchronized(sp) {
        val snapshot = sp.all
        if (snapshot[jevMigrationKey] == true) return@synchronized
        val editor = sp.edit()
        for (provider in JevProvider.entries.filter { it.needsKey }) {
            val platform = PlatformCredentials.forUrl(provider.url) ?: continue
            if (snapshot.containsKey("platformKey:${platform.id}")) continue
            val sealed = snapshot["jevKey:${provider.name}"] as? String ?: continue
            val raw = runCatching { KeyBox.open(sealed) }.getOrNull() ?: continue
            editor.putString("platformKey:${platform.id}", KeyBox.seal(raw))
        }
        check(editor.putBoolean(jevMigrationKey, true).commit()) { "辅助服务密钥迁移失败，请重试" }
    }

    /** 返回平台当前默认密钥原文；空串和损坏指针都不借用其他项。 */
    fun platformKey(id: String): String = keyFrom(sp.all, id).orEmpty()

    /** 列出中央项和命名凭据，顺序及标识不随平台默认切换改变。 */
    fun platformCredentials(platformId: String): List<PlatformCredential> =
        platformKeys.credentials(sp.all, checkedPlatformId(platformId))

    /** 判断凭据是否确实保存，兼容占位不作为钥匙扣显示。 */
    fun hasStoredPlatformCredential(platformId: String, credentialId: String): Boolean =
        platformKeys.isStored(sp.all, checkedPlatformId(platformId), credentialId)

    /** 删除用户确认的凭据；服务引用失效后需重新选择。 */
    fun deletePlatformCredential(platformId: String, credentialId: String) =
        platformKeys.delete(checkedPlatformId(platformId), credentialId)

    /** 读取平台当前默认标识；缺少元数据时为中央标识，损坏标识直接报错。 */
    fun defaultPlatformCredentialId(platformId: String): String =
        platformKeys.defaultId(sp.all, checkedPlatformId(platformId))

    /** 只切换平台默认指针，不移动密钥、不修改绑定或已取得的会话快照。 */
    fun setDefaultPlatformCredential(platformId: String, credentialId: String) =
        platformKeys.setDefault(checkedPlatformId(platformId), credentialId)

    /** 保存命名凭据，密钥原文不裁剪；旧默认密文和其他项均保留。 */
    fun savePlatformCredential(platformId: String, credentialId: String, label: String, key: String) =
        platformKeys.save(checkedPlatformId(platformId), credentialId, label, key)

    /** 获取服务显式绑定标识；空值表示跟随默认，损坏标识直接报错。 */
    fun selectedCredentialId(platformId: String, serviceSlot: String): String? =
        platformKeys.selectedId(sp.all, checkedPlatformId(platformId), serviceSlot)

    /** 绑定命名凭据或恢复默认，其他服务的绑定不改变。 */
    fun selectCredential(platformId: String, serviceSlot: String, credentialId: String?) =
        platformKeys.select(checkedPlatformId(platformId), serviceSlot, credentialId)

    /** 读取某服务实际绑定的凭据；损坏的绑定不回退其他密钥。 */
    fun credentialFor(platformId: String, serviceSlot: String): PlatformCredential? =
        platformKeys.selected(sp.all, checkedPlatformId(platformId), serviceSlot)

    /** 根据连接来源和服务槽位取运行密钥，不跨来源共享。 */
    fun keyForService(endpoint: String, serviceSlot: String): String = boundKey(sp.all, endpoint, serviceSlot)

    /** 原样加密写入平台当前默认，空串仅清空当前目标，不改写其他凭据。 */
    fun savePlatformKey(id: String, key: String) = synchronized(sp) {
        platformKeys.saveDefault(checkedPlatformId(id), key)
    }

    /** 官方平台、两路音频地址、文字配置和已保存的来源密钥都列入凭据页。 */
    val credentialPlatforms: List<CredentialPlatform>
        get() {
            val snapshot = sp.all
            val storedOrigins = snapshot.keys.mapNotNull { storageKey ->
                if (!storageKey.startsWith("platformKey:origin:")) return@mapNotNull null
                val id = storageKey.removePrefix("platformKey:")
                val origin = id.removePrefix("origin:")
                val platform = PlatformCredentials.forUrl(origin) ?: return@mapNotNull null
                if (platform.id == id) platform else CredentialPlatform(id, origin)
            }
            return (listOf(PlatformCredentials.QWEN, PlatformCredentials.DEEPSEEK, PlatformCredentials.SILICONFLOW,
                PlatformCredentials.OPENROUTER, PlatformCredentials.TYPESAFE) + JevProvider.entries.filter { it.needsKey }.mapNotNull {
                PlatformCredentials.forUrl(it.url)
            } + listOfNotNull(
                PlatformCredentials.forUrl(JevCustom.decode(snapshot["jevCustom"] as? String)?.endpoint()?.second.orEmpty()),
                PlatformCredentials.forUrl(snapshot["onlineEndpoint"] as? String ?: LiveTranslateConfig.DEFAULT_ENDPOINT),
                PlatformCredentials.forUrl(snapshot["cloudAsrEndpoint"] as? String ?: CloudAsrConfig.DEFAULT_ENDPOINT),
            ) + LlmProfile.all(snapshot["byokProfiles"] as? String).mapNotNull {
                PlatformCredentials.forUrl((snapshot["byokUrl${it.suffix}"] as? String).orEmpty())
            } + storedOrigins + platformKeys.storedPlatforms(snapshot)).distinctBy { it.id }
        }

    /** 旧用户和跳过选择的新用户继续使用原有本地识别路径。 */
    var listeningMode: ListeningMode
        get() = runCatching { ListeningMode.valueOf(sp.getString("listeningMode", "LOCAL").orEmpty()) }.getOrDefault(ListeningMode.LOCAL)
        set(value) = sp.edit().putString("listeningMode", value.name).apply()

    /** 云端听译地址与文字翻译配置分开保存，切换模式不会覆盖另一套设置。 */
    val onlineEndpoint: String get() = sp.getString("onlineEndpoint", LiveTranslateConfig.DEFAULT_ENDPOINT).orEmpty()
    val onlineModel: String get() = sp.getString("onlineModel", LiveTranslateConfig.DEFAULT_MODEL).orEmpty()
    val onlineKey: String get() {
        val snapshot = sp.all
        val endpoint = snapshot["onlineEndpoint"] as? String ?: LiveTranslateConfig.DEFAULT_ENDPOINT
        val model = snapshot["onlineModel"] as? String ?: LiveTranslateConfig.DEFAULT_MODEL
        return boundKey(snapshot, endpoint, listeningCredentialSlot(ListeningMode.ONLINE, endpoint, model))
    }

    /** 使用与凭据仓库相同的编码定位绑定，只复制标识，不读取或重写任何密文。 */
    private fun onlineBindingKey(platformId: String, endpoint: String, model: String): String {
        val flags = android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        val platform = android.util.Base64.encodeToString(platformId.toByteArray(Charsets.UTF_8), flags)
        val slot = listeningCredentialSlot(ListeningMode.ONLINE, endpoint, model)
        val encodedSlot = android.util.Base64.encodeToString(slot.toByteArray(Charsets.UTF_8), flags)
        return "platformCredentialBinding:$platform:$encodedSlot"
    }

    /** 已有实时服务即使跟随默认也受保护，迁移不能改变另一服务实际使用的凭据。 */
    private fun hasRealtimeConnection(snapshot: Map<String, *>, endpoint: String, model: String): Boolean {
        if (snapshot["onlineEndpoint"] == endpoint && snapshot["onlineModel"] == model) return true
        val array = runCatching { org.json.JSONArray(listeningServicesSp.getString("profiles", null) ?: "[]") }
            .getOrNull() ?: return false
        return (0 until array.length()).any { index ->
            val item = array.optJSONObject(index) ?: return@any false
            item.optString("mode") == ListeningMode.ONLINE.name &&
                item.optString("endpoint") == endpoint && item.optString("model") == model
        }
    }

    /** 为重新选择准备实时绑定；当前旧连接保持待选择，冲突时不覆盖任何凭据。 */
    internal fun migrateRetiredOnlineConnection(endpoint: String, model: String,
        preserveCurrent: Boolean = false): Pair<String, String>? = synchronized(sp) {
        val migrated = LiveTranslateModels.migratedConnection(endpoint, model)
            ?: return@synchronized null
        val platform = PlatformCredentials.forUrl(endpoint)?.id ?: return@synchronized null
        if (PlatformCredentials.forUrl(migrated.first)?.id != platform) return@synchronized null
        val snapshot = sp.all
        val sourceKey = onlineBindingKey(platform, endpoint, model)
        val targetKey = onlineBindingKey(platform, migrated.first, migrated.second)
        val sourceId = runCatching { platformKeys.selectedId(snapshot, platform,
            listeningCredentialSlot(ListeningMode.ONLINE, endpoint, model)) }.getOrElse { return@synchronized null }
        val targetId = runCatching { platformKeys.selectedId(snapshot, platform,
            listeningCredentialSlot(ListeningMode.ONLINE, migrated.first, migrated.second)) }.getOrElse { return@synchronized null }
        if ((snapshot.containsKey(targetKey) || hasRealtimeConnection(snapshot, migrated.first, migrated.second)) &&
            sourceId != targetId) return@synchronized null
        val editor = sp.edit()
        var changed = false
        if (!snapshot.containsKey(targetKey) && snapshot.containsKey(sourceKey)) {
            editor.putString(targetKey, snapshot[sourceKey] as String)
            changed = true
        }
        if (preserveCurrent && !snapshot.containsKey("retiredOnlineConnectionV1")) {
            editor.putString("retiredOnlineConnectionV1",
                org.json.JSONObject().put("endpoint", endpoint).put("model", model).toString())
            changed = true
        }
        if (changed) check(editor.commit()) { "旧分段听译配置迁移失败，请重试" }
        migrated
    }

    /** 先完成可逆加密，再原子写入云端配置；失败时不保存半套连接信息。 */
    fun saveOnline(endpoint: String, model: String, key: String) = synchronized(sp) {
        require(!LiveTranslateModels.isRetired(model.trim())) {
            "请选择已接入的千问实时听译模型"
        }
        val platform = requireNotNull(PlatformCredentials.forUrl(endpoint)) { "在线听译地址无效" }
        val editor = sp.edit().putString("onlineEndpoint", endpoint.trim()).putString("onlineModel", model.trim())
        platformKeys.putBoundKey(editor, sp.all, platform.id,
            listeningCredentialSlot(ListeningMode.ONLINE, endpoint, model), key)
        check(editor.commit()) { "在线听译配置保存失败，请重试" }
    }

    /** 会话与连接测试从同一个偏好快照读取，避免地址与密钥跨保存混用。 */
    fun onlineConfig(): LiveTranslateConfig {
        val snapshot = sp.all
        val endpoint = snapshot["onlineEndpoint"] as? String ?: LiveTranslateConfig.DEFAULT_ENDPOINT
        val model = snapshot["onlineModel"] as? String ?: LiveTranslateConfig.DEFAULT_MODEL
        val key = boundKey(snapshot, endpoint, listeningCredentialSlot(ListeningMode.ONLINE, endpoint, model))
        return LiveTranslateConfig(
            endpoint = endpoint,
            model = model,
            apiKey = key,
            sourceLang = (snapshot["sourceLang"] as? String ?: "ja").takeUnless { it == "auto" },
            targetLang = Target.parse(snapshot["targetLang"] as? String)?.lang?.code ?: "zh",
        )
    }

    /** 已保存的配置才参与就绪判断，任意自定义语言不能冒充云端支持语种。 */
    val onlineReady: Boolean get() = onlineConfig().validationError() == null && targetLang.lang != null
    /** 在线听写单独保存连接字段，复用现有 Android Keystore 加密盒。 */
    val cloudAsrEndpoint: String get() = sp.getString("cloudAsrEndpoint", CloudAsrConfig.DEFAULT_ENDPOINT).orEmpty()
    val cloudAsrModel: String get() = sp.getString("cloudAsrModel", CloudAsrConfig.DEFAULT_MODEL).orEmpty()
    val cloudAsrKey: String get() {
        val snapshot = sp.all
        val endpoint = snapshot["cloudAsrEndpoint"] as? String ?: CloudAsrConfig.DEFAULT_ENDPOINT
        val model = snapshot["cloudAsrModel"] as? String ?: CloudAsrConfig.DEFAULT_MODEL
        return boundKey(snapshot, endpoint, listeningCredentialSlot(ListeningMode.CLOUD_ASR, endpoint, model))
    }

    /** 先加密再整套保存听写字段与当前地址所属平台的密钥。 */
    fun saveCloudAsr(endpoint: String, model: String, key: String) = synchronized(sp) {
        val platform = requireNotNull(PlatformCredentials.forUrl(endpoint)) { "在线听写地址无效" }
        val editor = sp.edit().putString("cloudAsrEndpoint", endpoint.trim()).putString("cloudAsrModel", model.trim())
        platformKeys.putBoundKey(editor, sp.all, platform.id,
            listeningCredentialSlot(ListeningMode.CLOUD_ASR, endpoint, model), key)
        check(editor.commit()) { "在线听写配置保存失败，请重试" }
    }

    /** 听写连接取同一偏好快照，场景背景随会话作为识别上下文使用。 */
    fun cloudAsrConfig(): CloudAsrConfig {
        val snapshot = sp.all
        val endpoint = snapshot["cloudAsrEndpoint"] as? String ?: CloudAsrConfig.DEFAULT_ENDPOINT
        val model = snapshot["cloudAsrModel"] as? String ?: CloudAsrConfig.DEFAULT_MODEL
        val key = boundKey(snapshot, endpoint, listeningCredentialSlot(ListeningMode.CLOUD_ASR, endpoint, model))
        return CloudAsrConfig(
            endpoint = endpoint,
            model = model,
            apiKey = key,
            sourceLanguage = snapshot["sourceLang"] as? String ?: "ja",
            context = scene.background.orEmpty().take(400),
        )
    }

    /** 仅判断已保存听写配置；文字翻译是否就绪由调用方分别检查。 */
    val cloudAsrReady: Boolean get() = cloudAsrConfig().validationErrors().isEmpty()

    // 会话、测试连接和生成术语均先读取偏好，再发送模型请求。
    init {
        migratePlatformKeys()
        migrateQwenHostKeys()
        migrateJevKeys()
        migrateRetiredOnlineConnection(onlineEndpoint, onlineModel, preserveCurrent = true)
        ByokTranslator.defaultUserAgent = "Yuyi/${BuildConfig.VERSION_NAME}"
        // 停用旧固定高度默认迁移；保留已存高度、预留选择及旧迁移标记，不反向推断用户意图。
        val range = sentenceRange()
        // 在旧区间补齐前区分新安装与旧配置；新版显式选择始终优先。
        if (!sp.contains("captionSentenceCount")) {
            val sentences = if (sp.contains("maxSentences") || sp.contains("keepSentences")) range.last else 3
            sp.edit().putInt("captionSentenceCount", sentences).apply()
        }
        if (!sp.contains("maxSentences") || sp.getInt("keepSentences", CaptionBoard.DEFAULT_SENTENCES) != range.first ||
            sp.getInt("maxSentences", range.last) != range.last) {
            setSentenceRange(range.first, range.last)
        }
    }

    /** Chinese caption size in sp */
    var textSp: Float
        get() = sp.getFloat("textSp", 18f).coerceIn(CaptionOverlayView.MIN_SP, CaptionOverlayView.MAX_SP)
        set(v) = sp.edit().putFloat("textSp", v).apply()

    /** Background opacity 0..1 */
    var bgAlpha: Float
        get() = sp.getFloat("bgAlpha", CaptionOverlayView.DEFAULT_ALPHA)
        set(v) = sp.edit().putFloat("bgAlpha", v).apply()

    /** Size and opacity in one edit, so a change listener never sees one new and one old value. */
    fun setStyle(sizeSp: Float, alpha: Float) = sp.edit().putFloat("textSp", sizeSp).putFloat("bgAlpha", alpha).apply()

    /** Home screen theme: "system", "light" or "dark" */
    var theme: String
        get() = sp.getString("theme", "system")!!
        set(v) = sp.edit().putString("theme", v).apply()

    /** Caption colour scheme id, see CaptionOverlayView.PALETTES */
    var palette: String
        get() = sp.getString("palette", CaptionOverlayView.PALETTES[0].id)!!
        set(v) = sp.edit().putString("palette", v).apply()

    /** 字幕宽度占观看区域的比例，零表示随内容自动伸缩。 */
    var widthFrac: Float
        get() = sp.getFloat("widthFrac", DEFAULT_WIDTH)
        set(v) = sp.edit().putFloat("widthFrac", v).apply()

    /** 字幕默认自动高度（零），已保存的自动或固定高度选择保持原值。 */
    var heightFrac: Float
        get() = sp.getFloat("heightFrac", 0f)
        set(v) = sp.edit().putFloat("heightFrac", v).apply()

    /** Show the recognised Japanese above the Chinese line */
    var showJa: Boolean
        get() = sp.getBoolean("showJa", false)
        set(v) = sp.edit().putBoolean("showJa", v).apply()

    /** Show the small timing line under the captions */
    var showStats: Boolean
        get() = sp.getBoolean("showStats", false)
        set(v) = sp.edit().putBoolean("showStats", v).apply()

    /** 默认预留实时字幕背景，避免临时字幕消失时黑底收缩；用户可手动关闭。 */
    var reserveDraftSpace: Boolean
        get() = sp.getBoolean("reserveDraftSpace", true)
        set(v) = sp.edit().putBoolean("reserveDraftSpace", v).apply()

    /** Overlay position (top-left, px); -1 = default */
    var overlayX: Int
        get() = sp.getInt("overlayX", -1)
        set(v) = sp.edit().putInt("overlayX", v).apply()
    var overlayY: Int
        get() = sp.getInt("overlayY", -1)
        set(v) = sp.edit().putInt("overlayY", v).apply()

    /** 原始基线兼容入口；单独写入无法确认所属区域，恢复时必须使用尺寸匹配入口。 */
    var overlayBaselineY: Int
        get() = sp.getInt("overlayBaselineY", -1)
        set(v) = sp.edit().putInt("overlayBaselineY", v)
            .putInt("overlayBaselineAreaWidth", -1).putInt("overlayBaselineAreaHeight", -1).apply()

    /** 基线所属可用区域宽度，单位像素；旧配置或单独保存基线时为负一。 */
    val overlayBaselineAreaWidth: Int
        get() = sp.getInt("overlayBaselineAreaWidth", -1)

    /** 基线所属可用区域高度，单位像素；必须与保存时的可用区域宽度配对。 */
    val overlayBaselineAreaHeight: Int
        get() = sp.getInt("overlayBaselineAreaHeight", -1)

    /** 定位完成后一次保存基线及扣除系统边距的可用区域，旋转重置后也使用此入口。 */
    fun saveOverlayBaseline(baselineY: Int, availableWidth: Int, availableHeight: Int) {
        require(baselineY >= 0) { "字幕基线不能为负数" }
        require(availableWidth > 0 && availableHeight > 0) { "字幕可用区域宽高必须为正数" }
        sp.edit().putInt("overlayBaselineY", baselineY)
            .putInt("overlayBaselineAreaWidth", availableWidth)
            .putInt("overlayBaselineAreaHeight", availableHeight).apply()
    }

    /** 同一快照中的宽高都匹配才恢复基线；缺少尺寸、尺寸改变或区域无效时返回空值。 */
    fun overlayBaselineForArea(availableWidth: Int, availableHeight: Int): Int? {
        if (availableWidth <= 0 || availableHeight <= 0) return null
        val values = sp.all
        if (values["overlayBaselineAreaWidth"] != availableWidth ||
            values["overlayBaselineAreaHeight"] != availableHeight) return null
        return (values["overlayBaselineY"] as? Int)?.takeIf { it >= 0 }
    }

    /** 最少保留句数，继续使用旧键；提高最少值时同步扩展最多值。 */
    var keepSentences: Int
        get() = sentenceRange().first
        set(v) = setSentenceRange(v, maxSentences)

    /** 历史区间上限仅用于兼容迁移，不参与新版草稿计数。 */
    var maxSentences: Int
        get() = sentenceRange().last
        set(v) = setSentenceRange(keepSentences, v)

    /** 从同一份偏好快照读取合法范围；旧版最多值默认四句，且不小于旧最少值。 */
    fun sentenceRange(): IntRange {
        val values = sp.all
        val min = ((values["keepSentences"] as? Int) ?: CaptionBoard.DEFAULT_SENTENCES).coerceIn(1, 10)
        val max = ((values["maxSentences"] as? Int) ?: 4).coerceIn(min, 10)
        return min..max
    }

    /** 一次写入两个端点，监听器不会读到半个新区间；端点冲突时优先保留最少值。 */
    fun setSentenceRange(minSentences: Int, maxSentences: Int) {
        val min = minSentences.coerceIn(1, 10)
        val max = maxSentences.coerceIn(min, 10)
        sp.edit().putInt("keepSentences", min).putInt("maxSentences", max).apply()
    }

    /** 新版显示策略默认为句数，旧组合限制不再同时参与显示。 */
    val captionLimitMode: CaptionLimitMode
        get() = runCatching { CaptionLimitMode.valueOf(sp.getString("captionLimitMode", null).orEmpty()) }.getOrDefault(CaptionLimitMode.SENTENCES)

    /** 新安装默认三句；旧区间上限在初始化时迁移，已保存值保持不变。 */
    val captionSentenceCount: Int
        get() = sp.getInt("captionSentenceCount", 3).coerceIn(1, 10)

    /** 同一编辑原子保存模式及两个独立记忆值，切换模式不会丢失另一种设置。 */
    fun setCaptionRetention(mode: CaptionLimitMode, sentences: Int, chars: Int) {
        sp.edit().putString("captionLimitMode", mode.name)
            .putInt("captionSentenceCount", sentences.coerceIn(1, 10))
            .putInt("keepChars", chars.coerceIn(10, 300)).apply()
    }

    /** 字数模式下的最多译文字数；旧值保留但不会与句数模式叠加。 */
    var keepChars: Int
        get() = sp.getInt("keepChars", CaptionBoard.DEFAULT_CHARS).coerceIn(10, 300)
        set(v) = sp.edit().putInt("keepChars", v.coerceIn(10, 300)).apply()

    /** 原文与译文合计的实际显示行数，0不限；只限制显示，不裁掉会话历史。 */
    var maxCaptionLines: Int
        get() = sp.getInt("maxCaptionLines", 0).coerceIn(0, 20)
        set(v) = sp.edit().putInt("maxCaptionLines", v.coerceIn(0, 20)).apply()

    /** true = only show finished sentences; false = live drafts that are corrected in place. Overlay and home share it. */
    var finalOnly: Boolean
        get() = sp.getBoolean("finalOnly", false)
        set(v) = sp.edit().putBoolean("finalOnly", v).apply()

    /** Spoken language (null = auto: Japanese or English) and caption language; read when a session starts. */
    var sourceLang: Lang?
        get() = sp.getString("sourceLang", "ja").let { if (it == "auto") null else Lang.of(it) ?: Lang.JA }
        set(v) = sp.edit().putString("sourceLang", v?.code ?: "auto")
            .putString("modeSource:${listeningMode.name}", v?.code ?: "auto").apply()
    /** a preset language, or one the user typed ("custom:<name>") */
    var targetLang: Target
        get() = Target.parse(sp.getString("targetLang", null)) ?: Target.ZH
        set(v) = sp.edit().putString("targetLang", v.stored)
            .putString("modeTarget:${listeningMode.name}", v.stored).apply()

    /** 返回指定模式已记录的源语种；空值表示首次进入，可沿用合法的当前选择。 */
    fun modeSource(mode: ListeningMode): String? = sp.getString("modeSource:${mode.name}", null)
        ?.takeIf { it == "auto" || Lang.of(it) != null }

    /** 返回指定模式已记录的目标语言，包括文字翻译的自定义语言。 */
    fun modeTarget(mode: ListeningMode): Target? = Target.parse(sp.getString("modeTarget:${mode.name}", null))

    /** 切换前记住当前模式语言，保留旧全局字段供服务兼容读取。 */
    fun saveModeLanguages() {
        sp.edit().putString("modeSource:${listeningMode.name}", sourceLang?.code ?: "auto")
            .putString("modeTarget:${listeningMode.name}", targetLang.stored).apply()
    }

    /** The built-in scenes (as edited) and the user's own, see [Scene.all]. */
    var scenes: List<Scene>
        get() = Scene.all(sp.getString("scenes", null))
        set(v) = sp.edit().putString("scenes", Scene.store(v)).apply()
    /** 默认使用B站直播；已有选择优先，按标识读取用户编辑后的背景与术语。 */
    var scene: Scene
        get() = sp.getString("scene", Scene.VTUBER.id).let { id ->
            scenes.let { all -> all.firstOrNull { it.id == id } ?: all.first { it.id == Scene.VTUBER.id } }
        }
        set(v) = sp.edit().putString("scene", v.id).apply()

    /** Use Jev (cloud) for sentence ends; needs the key of [jevProvider]. */
    var jevOn: Boolean
        get() = sp.getBoolean("jevOn", false)
        set(v) = sp.edit().putBoolean("jevOn", v).apply()
    /** Check every final translation with Jev; independent of [jevOn]. Off by default. */
    var jevCheck: Boolean
        get() = sp.getBoolean("jevCheck", false)
        set(v) = sp.edit().putBoolean("jevCheck", v).apply()
    /** Drop a repeat at the join of a kept draft and the rest (Pipeline.dedupeJoin). Off by default. */
    var dedupeJoin: Boolean
        get() = sp.getBoolean("dedupeJoin", false)
        set(v) = sp.edit().putBoolean("dedupeJoin", v).apply()
    /** Write a session log (Settings → 使用记录). Off by default: nothing is recorded until the user turns it on. */
    var keepLogs: Boolean
        get() = sp.getBoolean("keepLogs", false)
        set(v) = sp.edit().putBoolean("keepLogs", v).apply()
    /** Give the translator the last finished sentences as context (on by default). */
    var contextOn: Boolean
        get() = sp.getBoolean("contextOn", true)
        set(v) = sp.edit().putBoolean("contextOn", v).apply()
    var contextSentences: Int
        get() = sp.getInt("contextSentences", Pipeline.PREVIOUS_SENTENCES).coerceIn(1, Pipeline.MAX_PREVIOUS_SENTENCES)
        set(v) = sp.edit().putInt("contextSentences", v).apply()
    var jevProvider: JevProvider
        // with no choice yet, the free Zen endpoint
        get() = runCatching { JevProvider.valueOf(sp.getString("jevProvider", null) ?: "") }.getOrDefault(JevProvider.ZEN)
        set(v) = sp.edit().putString("jevProvider", v.name).apply()

    /** 自定义服务只存连接参数，沿用中央平台凭据。 */
    val jevCustom: JevCustom? get() = JevCustom.decode(sp.getString("jevCustom", null))

    /** 校验成功后整体保存，不让输入过程中的半个地址成为运行配置。 */
    fun setJevCustom(value: JevCustom) {
        require(value.endpoint().first == null && value.model.isNotBlank()) { "请填写有效地址与模型" }
        sp.edit().putString("jevCustom", value.encode()).apply()
    }

    /** 同一快照读取平台来源、模型与绑定凭据，避免切换连接时串用密钥。 */
    fun jevConnection(providerOverride: JevProvider? = null): JevConnection {
        val values = sp.all
        val provider = providerOverride ?: runCatching { JevProvider.valueOf(values["jevProvider"] as? String ?: "") }.getOrDefault(JevProvider.ZEN)
        val custom = if (provider == JevProvider.CUSTOM) JevCustom.decode(values["jevCustom"] as? String) else null
        val url = if (provider == JevProvider.CUSTOM) custom?.endpoint()?.second.orEmpty() else provider.url
        val model = if (provider == JevProvider.CUSTOM) custom?.model.orEmpty() else provider.model
        val platform = PlatformCredentials.forUrl(url)
        val key = when {
            provider == JevProvider.ZEN -> ""
            url.isEmpty() || model.isBlank() || platform == null -> null
            else -> {
                val bound = boundKeyOrNull(values, platform.id, jevCredentialSlot(provider))
                if (!bound.isNullOrEmpty()) bound
                else if (provider == JevProvider.CUSTOM &&
                    runCatching { platformKeys.selectedId(values, platform.id, jevCredentialSlot(provider)) == null }.getOrDefault(false)) ""
                else null
            }
        }
        return JevConnection(provider, url, model, key)
    }

    /** 官方与自定义 Jev 端点读取中央服务绑定；Zen 保留独立旧槽位。 */
    fun jevKey(p: JevProvider): String? {
        val platform = if (p.needsKey || p == JevProvider.CUSTOM) PlatformCredentials.forUrl(jevConnection(p).url) else null
        return if (platform != null) boundKeyOrNull(sp.all, platform.id, jevCredentialSlot(p))
            else sp.getString("jevKey:${p.name}", null)?.let { runCatching { KeyBox.open(it) }.getOrNull() }
    }

    /** 官方及自定义 Jev 原样保存中央绑定密钥，Zen 沿用独立加密存储。 */
    fun setJevKey(p: JevProvider, key: String?) {
        val platform = if (p.needsKey || p == JevProvider.CUSTOM) PlatformCredentials.forUrl(jevConnection(p).url) else null
        require(p != JevProvider.CUSTOM || platform != null) { "请先保存自定义辅助服务的连接" }
        if (platform != null) synchronized(sp) {
            val editor = sp.edit()
            platformKeys.putBoundKey(editor, sp.all, platform.id, jevCredentialSlot(p), key.orEmpty())
            check(editor.commit()) { "辅助服务密钥保存失败，请重试" }
        }
        else sp.edit().apply {
            if (key.isNullOrEmpty()) remove("jevKey:${p.name}") else putString("jevKey:${p.name}", KeyBox.seal(key))
        }.apply()
    }

    /** 首次补齐常用基础翻译接口；保留旧连接、平台密钥及当前选择，不发起网络。 */
    fun ensureDefaultTranslationServices() = synchronized(sp) {
        if (sp.getBoolean("translationServiceDefaultsV1", false)) return@synchronized
        val previous = byokProfile
        val defaults = listOf(
            Triple("DeepSeek", "https://api.deepseek.com/v1/chat/completions", "deepseek-flash"),
            Triple("千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
        )
        defaults.forEach { (name, url, model) ->
            val exists = byokProfiles.any { ByokTranslator.chatUrl(byokUrl(it)).second == ByokTranslator.chatUrl(url).second && byokModel(it) == model }
            if (!exists && byokProfiles.size < LlmProfile.MAX_PROFILES) {
                val empty = byokProfiles.firstOrNull { it.id == previous.id && byokUrl(it).isBlank() && byokModel(it).isBlank() }
                val profile = empty ?: addByokProfile(uniqueDefaultTranslationName(name), select = false)
                saveByok(url, model, null, profile = profile)
            }
        }
        check(sp.edit().putBoolean("translationServiceDefaultsV1", true).commit()) { "默认翻译服务保存失败" }
    }

    /** 自动内部名称只用于旧目录兼容，界面始终显示实际平台和模型。 */
    private fun uniqueDefaultTranslationName(base: String): String {
        val names = byokProfiles.map { it.name }.toSet()
        var result = base
        var number = 2
        while (result in names) { result = "$base $number"; number++ }
        return result
    }

    /** 翻译配置目录与当前配置；未保存目录时直接读取旧版默认配置。 */
    val byokProfiles: List<LlmProfile> get() = LlmProfile.all(sp.getString("byokProfiles", null))
    val byokProfile: LlmProfile get() = byokProfiles.let { profiles ->
        profiles.firstOrNull { it.id == sp.getString("byokActive", "") } ?: profiles.first()
    }
    /** 按目标配置读取地址，不改变当前运行配置。 */
    fun byokUrl(profile: LlmProfile): String = sp.getString("byokUrl${checkedByokProfile(profile).suffix}", "").orEmpty()
    /** 按目标配置读取模型，不改变当前运行配置。 */
    fun byokModel(profile: LlmProfile): String = sp.getString("byokModel${checkedByokProfile(profile).suffix}", "").orEmpty()
    /** 按目标地址来源读取中央密钥，不继承其他来源的凭据。 */
    fun byokKey(profile: LlmProfile): String? {
        val snapshot = sp.all
        val checked = checkedByokProfile(profile)
        val url = (snapshot["byokUrl${checked.suffix}"] as? String).orEmpty()
        return PlatformCredentials.forUrl(url)?.let {
            boundKeyOrNull(snapshot, it.id, translationCredentialSlot(checked.id))
        }
    }
    /** 按目标配置读取请求代理名称。 */
    fun byokUserAgent(profile: LlmProfile): String = sp.getString("byokUserAgent${checkedByokProfile(profile).suffix}", null).orEmpty()
    /** 按目标配置解密请求头，不切换活动翻译服务。 */
    fun byokHeaders(profile: LlmProfile): List<Pair<String, String>> =
        ByokService.parseHeaders(sp.getString("byokHeaders${checkedByokProfile(profile).suffix}", null)
            ?.let { runCatching { KeyBox.open(it) }.getOrNull() })
    /** 拒绝目录之外的标识，避免将编辑内容写入其他存储键。 */
    private fun checkedByokProfile(profile: LlmProfile): LlmProfile =
        byokProfiles.firstOrNull { it.id == profile.id } ?: error("翻译配置不存在")
    private val byokSuffix: String get() = byokProfile.suffix
    var byokUrl: String
        get() = sp.getString("byokUrl$byokSuffix", "").orEmpty()
        set(value) = sp.edit().putString("byokUrl$byokSuffix", value.trim()).apply()
    var byokModel: String
        get() = sp.getString("byokModel$byokSuffix", "").orEmpty()
        set(value) = sp.edit().putString("byokModel$byokSuffix", value.trim()).apply()
    var byokKey: String?
        get() = byokKey(byokProfile)
        set(value) {
            val platform = PlatformCredentials.forUrl(byokUrl) ?: error("请先填写有效的服务地址")
            synchronized(sp) {
                val editor = sp.edit()
                platformKeys.putBoundKey(editor, sp.all, platform.id,
                    translationCredentialSlot(byokProfile.id), value.orEmpty())
                check(editor.commit()) { "翻译密钥保存失败，请重试" }
            }
        }

    /** 一次保存当前配置全部字段；空密钥清空目标平台，null保留目标平台密钥。 */
    fun saveByok(url: String, model: String, key: String?, userAgent: String? = null,
                 headers: List<Pair<String, String>>? = null, profile: LlmProfile = byokProfile) = synchronized(sp) {
        val suffix = checkedByokProfile(profile).suffix
        val platform = PlatformCredentials.forUrl(url)
        require(key == null || platform != null) { "请先填写有效的服务地址" }
        val snapshot = sp.all
        check(sp.edit().apply {
            putString("byokUrl$suffix", url.trim()); putString("byokModel$suffix", model.trim())
            if (key != null) platformKeys.putBoundKey(this, snapshot, platform!!.id,
                translationCredentialSlot(profile.id), key)
            if (userAgent != null) { if (userAgent.isBlank()) remove("byokUserAgent$suffix") else putString("byokUserAgent$suffix", userAgent.trim()) }
            if (headers != null) { if (headers.isEmpty()) remove("byokHeaders$suffix") else putString("byokHeaders$suffix", KeyBox.seal(ByokService.storeHeaders(headers))) }
            if (byokProfile.id == profile.id) putLong("byokSaved", System.nanoTime())
        }.commit()) { "翻译配置保存失败，请重试" }
    }

    /** 已知官方文字平台必须有中央密钥，自定义来源仍可免密使用。 */
    private fun byokConfigurationReady(url: String, model: String, key: String): Boolean {
        if (ByokTranslator.chatUrl(url).second == null || model.isBlank()) return false
        val platformId = PlatformCredentials.forUrl(url)?.id
        val needsKey = TextServiceProviders.requiresKey(platformId)
        return !needsKey || key.isNotEmpty()
    }

    /** 从同一偏好快照判断配置是否完整，避免切换时混用另一平台的密钥。 */
    private fun byokProfileReady(profile: LlmProfile): Boolean {
        val snapshot = sp.all
        val url = (snapshot["byokUrl${profile.suffix}"] as? String).orEmpty()
        val model = (snapshot["byokModel${profile.suffix}"] as? String).orEmpty()
        val id = PlatformCredentials.forUrl(url)?.id
        val selected = id?.let { platformKeys.selected(snapshot, it, translationCredentialSlot(profile.id)) }
        return selected != null && byokConfigurationReady(url, model, selected.key)
    }

    /** 选择一个已有配置，已保存的有效配置从下一句起应用。 */
    fun useByokProfile(profile: LlmProfile) {
        val selected = byokProfiles.firstOrNull { it.id == profile.id } ?: error("翻译配置不存在")
        sp.edit().apply {
            putString("byokActive", selected.id)
            if (byokProfileReady(selected)) putLong("byokSaved", System.nanoTime())
        }.apply()
    }

    /** 新增独立空配置；旧入口默认选中，服务编辑可只创建目录项。 */
    fun addByokProfile(name: String, select: Boolean = true, userCreated: Boolean = false): LlmProfile {
        val profiles = byokProfiles
        require(profiles.size < LlmProfile.MAX_PROFILES) { "最多保存 ${LlmProfile.MAX_PROFILES} 套配置" }
        val (checked, error) = LlmProfile.name(name, profiles)
        require(checked != null) { error.orEmpty() }
        val profile = LlmProfile(LlmProfile.newId(profiles), checked)
        sp.edit().putString("byokProfiles", LlmProfile.store(profiles + profile)).apply {
            if (select) putString("byokActive", profile.id)
            if (userCreated) putBoolean("byokCustom:${profile.id}", true)
        }.apply()
        return profile
    }

    /** 用户主动添加的服务保留独立来源标记，即使连接恰好与默认模板相同。 */
    fun isCustomTranslation(profileId: String): Boolean = sp.getBoolean("byokCustom:$profileId", false)

    /** 重命名只改变目录，不移动凭据或触发运行中切换。 */
    fun renameByokProfile(profile: LlmProfile, name: String) {
        val profiles = byokProfiles
        require(profiles.any { it.id == profile.id }) { "翻译配置不存在" }
        val (checked, error) = LlmProfile.name(name, profiles, profile.id)
        require(checked != null) { error.orEmpty() }
        sp.edit().putString("byokProfiles", LlmProfile.store(profiles.map {
            if (it.id == profile.id) it.copy(name = checked) else it
        })).apply()
    }

    /** 由界面确认后删除指定配置；旧密钥密文留作恢复，其余配置至少保留一套。 */
    fun deleteByokProfile(profile: LlmProfile) {
        val profiles = byokProfiles
        require(profiles.size > 1) { "至少保留一套翻译配置" }
        require(profiles.any { it.id == profile.id }) { "翻译配置不存在" }
        val remaining = profiles.filterNot { it.id == profile.id }
        val active = byokProfile.id == profile.id
        sp.edit().apply {
            for (key in listOf("byokUrl", "byokModel", "byokUserAgent", "byokHeaders")) remove(key + profile.suffix)
            putString("byokProfiles", LlmProfile.store(remaining))
            if (active) {
                putString("byokActive", remaining.first().id)
                if (byokProfileReady(remaining.first())) putLong("byokSaved", System.nanoTime())
            }
        }.apply()
    }

    val byokUserAgent: String get() = sp.getString("byokUserAgent$byokSuffix", null).orEmpty()
    val byokHeaders: List<Pair<String, String>>
        get() = ByokService.parseHeaders(sp.getString("byokHeaders$byokSuffix", null)?.let { runCatching { KeyBox.open(it) }.getOrNull() })

    /** 会话从同一偏好快照解析配置及其所有凭据，避免跨配置混用地址与密钥。 */
    fun byokService(profile: LlmProfile = byokProfile): ByokService? {
        val all = sp.all
        val suffix = LlmProfile.all(all["byokProfiles"] as? String)
            .firstOrNull { it.id == profile.id }?.suffix ?: error("翻译配置不存在")
        val storedUrl = (all["byokUrl$suffix"] as? String).orEmpty()
        val url = ByokTranslator.chatUrl(storedUrl).second ?: return null
        val platform = PlatformCredentials.forUrl(storedUrl) ?: return null
        val selected = platformKeys.selected(all, platform.id, translationCredentialSlot(profile.id)) ?: return null
        val headers = (all["byokHeaders$suffix"] as? String)?.let { runCatching { KeyBox.open(it) }.getOrNull() }
        return ByokService(url, (all["byokModel$suffix"] as? String).orEmpty(),
            selected.key,
            (all["byokUserAgent$suffix"] as? String).orEmpty(), ByokService.parseHeaders(headers))
    }
    /** 按目标配置判断运行所需字段和中央密钥是否完整。 */
    fun byokReady(profile: LlmProfile): Boolean = byokProfileReady(checkedByokProfile(profile))
    /** 会话遇到尚未填好的新配置时保留正在使用的服务，完整保存后才切换。 */
    fun byokForSession(fallback: ByokService?): ByokService? =
        byokService()?.takeIf { byokConfigurationReady(it.url, it.model, it.key) } ?: fallback

    /**
     * The [ByokTranslator.THINKING_OFF] variant (by name) that answered without reasoning for this exact service URL and
     * model; null = not found yet. Kept per URL + model, so switching back and forth does not probe again.
     */
    fun byokVariant(url: String, model: String): String? = ByokTranslator.current(sp.getString("byokVariant:$url|$model", null))
    fun setByokVariant(url: String, model: String, name: String?) =
        sp.edit().apply { if (name == null) remove("byokVariant:$url|$model") else putString("byokVariant:$url|$model", name) }.apply()
    /** 官方平台还需已存密钥；自定义来源保持免密服务的就绪语义。 */
    val byokReady: Boolean get() {
        val snapshot = sp.all
        val profiles = LlmProfile.all(snapshot["byokProfiles"] as? String)
        val active = profiles.firstOrNull { it.id == snapshot["byokActive"] } ?: profiles.first()
        val suffix = active.suffix
        val url = (snapshot["byokUrl$suffix"] as? String).orEmpty()
        val model = (snapshot["byokModel$suffix"] as? String).orEmpty()
        val id = PlatformCredentials.forUrl(url)?.id
        val selected = id?.let { platformKeys.selected(snapshot, it, translationCredentialSlot(active.id)) }
        return selected != null && byokConfigurationReady(url, model, selected.key)
    }


    fun listen(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) = sp.registerOnSharedPreferenceChangeListener(l)
    fun unlisten(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) = sp.unregisterOnSharedPreferenceChangeListener(l)

    companion object {
        /** 新安装默认随内容自动收放，已有手动宽度继续读取用户保存值。 */
        const val DEFAULT_WIDTH = 0f
        /** 手动拖动角落缩放时的最小宽度比例，不限制设置中的自动宽度。 */
        const val MIN_WIDTH = 0.40f
    }
}

/** 使用不可导出的设备密钥执行 AES-GCM，持久化格式保持为 base64(IV + 密文)。 */
private object KeyBox {
    private const val ALIAS = "jpsub-jev"
    private val decrypted = DecryptionCache(decrypt = ::decrypt)

    /** 每次真正执行加解密时重新获取设备密钥，让别名异常后的重试有机会恢复。 */
    private fun key(): javax.crypto.SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        val g = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(android.security.keystore.KeyGenParameterSpec.Builder(ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }
    /** 每次使用真实 AES-GCM 和新 IV 加密，不向解密缓存预填未经解密的结果。 */
    fun seal(s: String): String {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key()) }
        return android.util.Base64.encodeToString(c.iv + c.doFinal(s.toByteArray()), android.util.Base64.NO_WRAP)
    }
    /** 仅按完整密文复用成功解密值，不缓存偏好键、默认指针或服务绑定。 */
    fun open(b64: String): String = decrypted.open(b64)

    /** 缓存未命中时执行原有认证解密，任何异常均由调用方处理。 */
    private fun decrypt(b64: String): String {
        val b = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, b, 0, 12))
        return String(c.doFinal(b, 12, b.size - 12))
    }
}
