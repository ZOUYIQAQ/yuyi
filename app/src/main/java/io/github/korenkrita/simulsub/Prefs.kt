package io.github.korenkrita.simulsub

import android.content.Context

/** Subtitle look, shared by the settings screen (live preview) and the overlay. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("jpsub", Context.MODE_PRIVATE)
    // 会话、测试连接和生成术语均先读取偏好，再发送模型请求。
    init {
        ByokTranslator.defaultUserAgent = "Yuyi/${BuildConfig.VERSION_NAME}"
        val range = sentenceRange()
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

    /** Overlay width as a fraction of the screen width */
    var widthFrac: Float
        get() = sp.getFloat("widthFrac", DEFAULT_WIDTH)
        set(v) = sp.edit().putFloat("widthFrac", v).apply()

    /** Caption area height as a fraction of the usable screen height; 0 = fit the text (default) */
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

    /** Overlay position (top-left, px); -1 = default */
    var overlayX: Int
        get() = sp.getInt("overlayX", -1)
        set(v) = sp.edit().putInt("overlayX", v).apply()
    var overlayY: Int
        get() = sp.getInt("overlayY", -1)
        set(v) = sp.edit().putInt("overlayY", v).apply()

    /** 最少保留句数，继续使用旧键；提高最少值时同步扩展最多值。 */
    var keepSentences: Int
        get() = sentenceRange().first
        set(v) = setSentenceRange(v, maxSentences)

    /** 显示最多句数包含草稿，范围为最少句数到十句。 */
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

    /** 保留旧区间的上限作为新句数值，不再使用区间下限。 */
    val captionSentenceCount: Int
        get() = sp.getInt("captionSentenceCount", sentenceRange().last).coerceIn(1, 10)

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
        set(v) = sp.edit().putString("sourceLang", v?.code ?: "auto").apply()
    /** a preset language, or one the user typed ("custom:<name>") */
    var targetLang: Target
        get() = Target.parse(sp.getString("targetLang", null)) ?: Target.ZH
        set(v) = sp.edit().putString("targetLang", v.stored).apply()

    /** The built-in scenes (as edited) and the user's own, see [Scene.all]. */
    var scenes: List<Scene>
        get() = Scene.all(sp.getString("scenes", null))
        set(v) = sp.edit().putString("scenes", Scene.store(v)).apply()
    /** What the subtitles are about (translator background + term list), one of [scenes]; read when a session starts. */
    var scene: Scene
        get() = sp.getString("scene", null).let { id -> scenes.let { all -> all.firstOrNull { it.id == id } ?: all.first() } }
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

    /** API key per provider, encrypted with an Android Keystore key (the stored value is useless off this device). */
    fun jevKey(p: JevProvider): String? = sp.getString("jevKey:${p.name}", null)?.let { runCatching { KeyBox.open(it) }.getOrNull() }
    fun setJevKey(p: JevProvider, key: String?) =
        sp.edit().apply { if (key.isNullOrBlank()) remove("jevKey:${p.name}") else putString("jevKey:${p.name}", KeyBox.seal(key.trim())) }.apply()

    /** BYOK: the user's OpenAI-compatible service. The key is sealed like the Jev keys. */
    var byokUrl: String
        get() = sp.getString("byokUrl", "") ?: ""
        set(v) = sp.edit().putString("byokUrl", v.trim()).apply()
    var byokModel: String
        get() = sp.getString("byokModel", "") ?: ""
        set(v) = sp.edit().putString("byokModel", v.trim()).apply()
    var byokKey: String?
        get() = sp.getString("byokKey", null)?.let { runCatching { KeyBox.open(it) }.getOrNull() }
        set(v) = sp.edit().apply { if (v.isNullOrBlank()) remove("byokKey") else putString("byokKey", KeyBox.seal(v.trim())) }.apply()
    /**
     * "保存": all BYOK fields in one edit (null key = keep the saved one), plus the byokSaved marker a running session
     * switches on. One edit, so a reader never sees the new address with the old key.
     */
    fun saveByok(url: String, model: String, key: String?, userAgent: String? = null,
                 headers: List<Pair<String, String>>? = null) = sp.edit().apply {
        putString("byokUrl", url.trim()); putString("byokModel", model.trim())
        if (key != null) { if (key.isBlank()) remove("byokKey") else putString("byokKey", KeyBox.seal(key.trim())) }
        if (userAgent != null) { if (userAgent.isBlank()) remove("byokUserAgent") else putString("byokUserAgent", userAgent.trim()) }
        // sealed like the key: a header may carry a second key
        if (headers != null) { if (headers.isEmpty()) remove("byokHeaders") else putString("byokHeaders", KeyBox.seal(ByokService.storeHeaders(headers))) }
        putLong("byokSaved", System.nanoTime())
    }.apply()
    val byokUserAgent: String get() = sp.getString("byokUserAgent", null).orEmpty()
    val byokHeaders: List<Pair<String, String>>
        get() = ByokService.parseHeaders(sp.getString("byokHeaders", null)?.let { runCatching { KeyBox.open(it) }.getOrNull() })
    /**
     * The saved service read together (one copy of the preferences), for a session building its translator; null when
     * the address is not valid. The key may be "" (a service without one).
     */
    fun byokService(): ByokService? {
        val all = sp.all
        val url = ByokTranslator.chatUrl((all["byokUrl"] as? String).orEmpty()).second ?: return null
        fun open(k: String) = (all[k] as? String)?.let { runCatching { KeyBox.open(it) }.getOrNull() }
        return ByokService(url, (all["byokModel"] as? String).orEmpty(), open("byokKey").orEmpty(),
            (all["byokUserAgent"] as? String).orEmpty(), ByokService.parseHeaders(open("byokHeaders")))
    }
    /**
     * The [ByokTranslator.THINKING_OFF] variant (by name) that answered without reasoning for this exact service URL and
     * model; null = not found yet. Kept per URL + model, so switching back and forth does not probe again.
     */
    fun byokVariant(url: String, model: String): String? = sp.getString("byokVariant:$url|$model", null)
    fun setByokVariant(url: String, model: String, name: String?) =
        sp.edit().apply { if (name == null) remove("byokVariant:$url|$model") else putString("byokVariant:$url|$model", name) }.apply()
    /** URL and model are there and valid: BYOK can start (the key may be left empty, for a free service). */
    val byokReady: Boolean get() = ByokTranslator.chatUrl(byokUrl).second != null && byokModel.isNotEmpty()


    fun listen(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) = sp.registerOnSharedPreferenceChangeListener(l)
    fun unlisten(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) = sp.unregisterOnSharedPreferenceChangeListener(l)

    companion object {
        const val DEFAULT_WIDTH = 0.90f
        /** Width slider / resize range as a fraction of the screen width. */
        const val MIN_WIDTH = 0.40f
    }
}

/** AES-GCM with a non-exportable Android Keystore key; output = base64(iv ‖ ciphertext). */
private object KeyBox {
    private const val ALIAS = "jpsub-jev"
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
    fun seal(s: String): String {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key()) }
        return android.util.Base64.encodeToString(c.iv + c.doFinal(s.toByteArray()), android.util.Base64.NO_WRAP)
    }
    fun open(b64: String): String {
        val b = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, b, 0, 12))
        return String(c.doFinal(b, 12, b.size - 12))
    }
}
