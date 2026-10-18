package io.github.korenkrita.simulsub

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** 独立于字幕日志的长期用量账本；数据库事务和实例锁共同保证幂等。 */
class UsageCostRepository private constructor(context: Context, private val nowMs: () -> Long, databaseName: String) {
    private val database = LedgerDb(context.applicationContext, databaseName)
    private val lock = Any()
    private val mutableState = MutableStateFlow(emptySummary())
    val state: StateFlow<UsageCostSummary> = mutableState

    init {
        synchronized(lock) {
            database.writableDatabase.execSQL(
                "UPDATE sessions SET ended_ms=last_activity_ms WHERE ended_ms IS NULL")
            publish()
        }
    }

    companion object {
        @Volatile private var instance: UsageCostRepository? = null

        /** 返回应用唯一账本实例。 */
        fun get(context: Context): UsageCostRepository = instance ?: synchronized(this) {
            instance ?: UsageCostRepository(context.applicationContext, System::currentTimeMillis,
                "usage_cost_ledger.db").also { instance = it }
        }

        /** 构造空状态，仅作为数据库加载前的短暂状态。 */
        private fun emptySummary(): UsageCostSummary = UsageCostSummary(UsageCostTotal(label = "今天"), null, null)
    }

    /** 开始并持久化一次同传会话，返回稳定会话 ID。 */
    fun beginSession(label: String): String = synchronized(lock) {
        val id = UUID.randomUUID().toString()
        val at = nowMs()
        database.writableDatabase.insertOrThrow("sessions", null, ContentValues().apply {
            put("id", id); put("label", label.take(80)); put("started_ms", at)
            put("last_activity_ms", at)
        })
        publish()
        id
    }

    /** 标记会话结束；迟到的网络回调仍按原会话 ID 保存。 */
    fun endSession(id: String) = synchronized(lock) {
        database.writableDatabase.execSQL("UPDATE sessions SET ended_ms=? WHERE id=? AND ended_ms IS NULL", arrayOf(nowMs(), id))
        publish()
    }

    /** 写入一次请求观测；累计事件每次只追加增长部分，重复快照不追加。 */
    fun record(
        sessionId: String?, category: CostCategory, endpoint: String, model: String,
        usage: MeteredUsage, eventId: String = UUID.randomUUID().toString(), cumulative: Boolean = false,
        observedAtMs: Long? = null,
    ) = synchronized(lock) {
        require(eventId.isNotBlank()) { "事件 ID 不能为空" }
        require(usage.inputTokens?.let { it >= 0 } != false)
        require(usage.outputTokens?.let { it >= 0 } != false)
        require(usage.cachedInputTokens?.let { it >= 0 } != false)
        require(usage.audioInputTokens?.let { it >= 0 } != false)
        require(usage.audioSeconds?.let { it.isFinite() && it >= 0 } != false)
        val db = database.writableDatabase
        val platformId = platformFor(endpoint)
        val at = observedAtMs ?: nowMs()
        db.beginTransaction()
        try {
            val previous = db.rawQuery("SELECT * FROM streams WHERE event_id=?", arrayOf(eventId)).use { cursor ->
                if (cursor.moveToFirst()) snapshot(cursor) else null
            }
            if (previous != null) {
                require(previous.sessionId == sessionId && previous.category == category.name &&
                    previous.platformId == platformId && previous.model == model && previous.cumulative == cumulative) {
                    "同一事件 ID 不得用于其他请求"
                }
            }
            if (previous == null || cumulative) {
                val merged = if (previous == null) usage else maxUsage(previous.usage, usage)
                val delta = if (previous == null) usage else deltaUsage(previous.usage, merged)
                if (previous == null || merged != previous.usage) {
                    val rate = if (previous != null) eventRate(db, eventId)
                        else manualRate(db, platformId, model)
                            ?: CostAccounting.officialRate(platformId, endpoint, model, at)
                    val estimate = CostAccounting.estimate(merged, rate)
                    val previousAmount = previous?.let { CostAccounting.estimate(it.usage, rate).amountYuan }
                    val incrementalAmount = estimate.amountYuan?.subtract(previousAmount ?: BigDecimal.ZERO)
                    db.insertOrThrow("usage_events", null, ContentValues().apply {
                        put("event_id", eventId); put("at_ms", at); put("session_id", sessionId)
                        put("category", category.name); put("platform_id", platformId); put("model", model)
                        put("input_tokens", delta.inputTokens); put("output_tokens", delta.outputTokens)
                        put("cached_tokens", delta.cachedInputTokens); put("audio_tokens", delta.audioInputTokens)
                        put("audio_seconds", delta.audioSeconds)
                        put("rate_input", rate?.input?.toPlainString()); put("rate_output", rate?.output?.toPlainString())
                        put("rate_cache", rate?.cache?.toPlainString()); put("rate_audio", rate?.audio?.toPlainString())
                        put("rate_second", rate?.perSecond?.toPlainString())
                        put("amount_yuan", incrementalAmount?.toPlainString())
                        put("unpriced", if (estimate.unpriced) 1 else 0)
                    })
                    db.insertWithOnConflict("streams", null, ContentValues().apply {
                        put("event_id", eventId); put("session_id", sessionId); put("category", category.name)
                        put("platform_id", platformId); put("model", model); put("cumulative", if (cumulative) 1 else 0)
                        put("input_tokens", merged.inputTokens); put("output_tokens", merged.outputTokens)
                        put("cached_tokens", merged.cachedInputTokens); put("audio_tokens", merged.audioInputTokens)
                        put("audio_seconds", merged.audioSeconds)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            if (sessionId != null) {
                db.execSQL("UPDATE sessions SET last_activity_ms=MAX(last_activity_ms,?) WHERE id=?",
                    arrayOf(at, sessionId))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        publish()
    }

    /** 手填平台和模型的价格，后续观测采用新价格，历史快照不变。 */
    fun setRate(platformId: String, model: String, rate: ManualCostRate) = synchronized(lock) {
        require(platformId.isNotBlank() && model.isNotBlank())
        listOf(rate.input, rate.output, rate.cache, rate.audio, rate.perSecond).forEach {
            require(it == null || it >= BigDecimal.ZERO) { "费率不能为负数" }
        }
        database.writableDatabase.insertWithOnConflict("manual_rates", null, ContentValues().apply {
            put("platform_id", platformId); put("model", model)
            put("rate_input", rate.input?.toPlainString()); put("rate_output", rate.output?.toPlainString())
            put("rate_cache", rate.cache?.toPlainString()); put("rate_audio", rate.audio?.toPlainString())
            put("rate_second", rate.perSecond?.toPlainString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        publish()
    }

    /** 查询左闭右开时间范围内的原始增量，支持按小时或任意时段汇总。 */
    fun totalsBetween(fromMs: Long, toMs: Long): UsageCostTotal = synchronized(lock) {
        require(fromMs <= toMs)
        total(fromMs, toMs, null, "")
    }

    /** 按设备当前日界重新计算今天和会话摘要。 */
    fun refreshToday() = synchronized(lock) { publish() }

    /** 从完整来源提取规范化平台 ID，绝不保存 URL 路径、查询或凭据。 */
    private fun platformFor(endpoint: String): String {
        val uri = runCatching { URI(endpoint) }.getOrNull() ?: return "unknown"
        if (uri.rawUserInfo != null) return "unknown"
        val origin = runCatching { URI(uri.scheme, null, uri.host, uri.port, "/", null, null).toString() }.getOrNull()
            ?: return "unknown"
        return PlatformCredentials.forUrl(origin)?.id ?: "unknown"
    }

    /** 读取一次手填费率；返回 null 表示继续匹配内置费率。 */
    private fun manualRate(db: SQLiteDatabase, platformId: String, model: String): ManualCostRate? =
        db.rawQuery("SELECT * FROM manual_rates WHERE platform_id=? AND model=?", arrayOf(platformId, model)).use { c ->
            if (!c.moveToFirst()) null else ManualCostRate(
                decimal(c, "rate_input"), decimal(c, "rate_output"), decimal(c, "rate_cache"),
                decimal(c, "rate_audio"), decimal(c, "rate_second"))
        }

    /** 沿用首次观测保存的费率，避免中途改价重算同一个请求。 */
    private fun eventRate(db: SQLiteDatabase, eventId: String): ManualCostRate? =
        db.rawQuery("SELECT rate_input,rate_output,rate_cache,rate_audio,rate_second FROM usage_events WHERE event_id=? ORDER BY id LIMIT 1",
            arrayOf(eventId)).use { c ->
            if (!c.moveToFirst()) null else ManualCostRate(
                decimal(c, "rate_input"), decimal(c, "rate_output"), decimal(c, "rate_cache"),
                decimal(c, "rate_audio"), decimal(c, "rate_second"))
                    .takeIf { listOf(it.input, it.output, it.cache, it.audio, it.perSecond).any { value -> value != null } }
        }

    /** 读取上一次累计快照。 */
    private fun snapshot(c: Cursor): Stream = Stream(
        nullableString(c, "session_id"), c.getString(c.getColumnIndexOrThrow("category")),
        c.getString(c.getColumnIndexOrThrow("platform_id")), c.getString(c.getColumnIndexOrThrow("model")),
        c.getInt(c.getColumnIndexOrThrow("cumulative")) != 0, usage(c))

    /** 读取一行可空数字用量。 */
    private fun usage(c: Cursor): MeteredUsage = MeteredUsage(
        nullableLong(c, "input_tokens"), nullableLong(c, "output_tokens"),
        nullableLong(c, "cached_tokens"), nullableLong(c, "audio_tokens"), nullableDouble(c, "audio_seconds"))

    /** 把缺失字段保留为 null，并对累计字段逐项取最大。 */
    private fun maxUsage(old: MeteredUsage, new: MeteredUsage): MeteredUsage = MeteredUsage(
        maxLong(old.inputTokens, new.inputTokens), maxLong(old.outputTokens, new.outputTokens),
        maxLong(old.cachedInputTokens, new.cachedInputTokens), maxLong(old.audioInputTokens, new.audioInputTokens),
        maxDouble(old.audioSeconds, new.audioSeconds))

    /** 合并单个可空小数计数。 */
    private fun maxDouble(a: Double?, b: Double?): Double? = when {
        a == null -> b; b == null -> a; else -> maxOf(a, b)
    }

    /** 合并单个可空整数计数。 */
    private fun maxLong(a: Long?, b: Long?): Long? = when {
        a == null -> b; b == null -> a; else -> maxOf(a, b)
    }

    /** 将累计快照转换为此次观测的增量。 */
    private fun deltaUsage(old: MeteredUsage, current: MeteredUsage): MeteredUsage = MeteredUsage(
        deltaLong(old.inputTokens, current.inputTokens), deltaLong(old.outputTokens, current.outputTokens),
        deltaLong(old.cachedInputTokens, current.cachedInputTokens), deltaLong(old.audioInputTokens, current.audioInputTokens),
        current.audioSeconds?.let { it - (old.audioSeconds ?: 0.0) })

    /** 对可空累计值求差，首次出现的字段按零基线计算。 */
    private fun deltaLong(old: Long?, current: Long?): Long? = current?.minus(old ?: 0L)

    /** 用原始观测表生成费用、未计价计数、分类与去重请求数。 */
    private fun total(fromMs: Long?, toMs: Long?, sessionId: String?, label: String): UsageCostTotal {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (fromMs != null) { clauses += "at_ms>=?"; args += fromMs.toString() }
        if (toMs != null) { clauses += "at_ms<?"; args += toMs.toString() }
        if (sessionId != null) { clauses += "session_id=?"; args += sessionId }
        val where = if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")
        var amount = BigDecimal.ZERO
        val categories = mutableMapOf<CostCategory, BigDecimal>()
        val models = linkedSetOf<String>()
        val requestIds = hashSetOf<String>()
        val latestUnpriced = linkedMapOf<String, String?>()
        database.readableDatabase.rawQuery("SELECT * FROM usage_events$where ORDER BY at_ms,id", args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val eventId = c.getString(c.getColumnIndexOrThrow("event_id"))
                requestIds += eventId
                val category = CostCategory.valueOf(c.getString(c.getColumnIndexOrThrow("category")))
                val rowAmount = decimal(c, "amount_yuan")
                if (rowAmount != null) {
                    amount = amount.add(rowAmount)
                    categories[category] = (categories[category] ?: BigDecimal.ZERO).add(rowAmount)
                }
                latestUnpriced[eventId] = if (c.getInt(c.getColumnIndexOrThrow("unpriced")) != 0)
                    c.getString(c.getColumnIndexOrThrow("model")) else null
            }
        }
        latestUnpriced.values.filterNotNull().forEach { models += it }
        val duration = duration(fromMs, toMs, sessionId)
        return UsageCostTotal(amount, latestUnpriced.values.count { it != null }, requestIds.size,
            duration, label, categories, models.toList())
    }

    /** 以会话与时间窗口交集计算秒数，不借字幕日志推测时长。 */
    private fun duration(fromMs: Long?, toMs: Long?, sessionId: String?): Long {
        val where = if (sessionId == null) "" else " WHERE id=?"
        val args = if (sessionId == null) emptyArray() else arrayOf(sessionId)
        var milliseconds = 0L
        database.readableDatabase.rawQuery("SELECT started_ms, ended_ms FROM sessions$where", args).use { c ->
            while (c.moveToNext()) {
                val start = maxOf(c.getLong(0), fromMs ?: Long.MIN_VALUE)
                val end = minOf(if (c.isNull(1)) nowMs() else c.getLong(1), toMs ?: Long.MAX_VALUE)
                if (end > start) milliseconds += end - start
            }
        }
        return milliseconds / 1000
    }

    /** 从账本重算当前快照；结束会话后的迟到回调仍能更新上次会话。 */
    private fun publish() {
        val now = nowMs()
        val todayStart = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val last = latestSession(true)
        val current = latestSession(false)
        mutableState.value = UsageCostSummary(
            total(todayStart, now + 1, null, "今天"),
            last?.let { total(null, null, it.first, it.second) },
            current?.let { total(null, null, it.first, it.second) })
    }

    /** 取最近结束或正在进行的会话。 */
    private fun latestSession(ended: Boolean): Pair<String, String>? {
        val condition = if (ended) "ended_ms IS NOT NULL" else "ended_ms IS NULL"
        val order = if (ended) "ended_ms DESC, started_ms DESC" else "started_ms DESC"
        return database.readableDatabase.rawQuery(
            "SELECT id,label FROM sessions WHERE $condition ORDER BY $order LIMIT 1", emptyArray()).use { c ->
            if (c.moveToFirst()) c.getString(0) to c.getString(1) else null
        }
    }

    /** 读取可空整数列。 */
    private fun nullableLong(c: Cursor, column: String): Long? = c.getColumnIndexOrThrow(column).let { i ->
        if (c.isNull(i)) null else c.getLong(i)
    }

    /** 读取可空小数列。 */
    private fun nullableDouble(c: Cursor, column: String): Double? = c.getColumnIndexOrThrow(column).let { i ->
        if (c.isNull(i)) null else c.getDouble(i)
    }

    /** 读取可空字符串列。 */
    private fun nullableString(c: Cursor, column: String): String? = c.getColumnIndexOrThrow(column).let { i ->
        if (c.isNull(i)) null else c.getString(i)
    }

    /** 以十进制原文恢复金额及费率精度。 */
    private fun decimal(c: Cursor, column: String): BigDecimal? = nullableString(c, column)?.let(::BigDecimal)

    /** 累计流上次快照的固定身份和原始数值。 */
    private data class Stream(val sessionId: String?, val category: String, val platformId: String,
        val model: String, val cumulative: Boolean, val usage: MeteredUsage)

    /** 账本与索引仅保存来源、模型、数字、事件和会话元数据。 */
    private class LedgerDb(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 1) {
        /** 初始化长期明细、累计状态、会话与手填费率。 */
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,label TEXT NOT NULL,started_ms INTEGER NOT NULL,last_activity_ms INTEGER NOT NULL,ended_ms INTEGER)")
            db.execSQL("CREATE TABLE streams(event_id TEXT PRIMARY KEY,session_id TEXT,category TEXT NOT NULL,platform_id TEXT NOT NULL,model TEXT NOT NULL,cumulative INTEGER NOT NULL,input_tokens INTEGER,output_tokens INTEGER,cached_tokens INTEGER,audio_tokens INTEGER,audio_seconds REAL)")
            db.execSQL("CREATE TABLE usage_events(id INTEGER PRIMARY KEY AUTOINCREMENT,event_id TEXT NOT NULL,at_ms INTEGER NOT NULL,session_id TEXT,category TEXT NOT NULL,platform_id TEXT NOT NULL,model TEXT NOT NULL,input_tokens INTEGER,output_tokens INTEGER,cached_tokens INTEGER,audio_tokens INTEGER,audio_seconds REAL,rate_input TEXT,rate_output TEXT,rate_cache TEXT,rate_audio TEXT,rate_second TEXT,amount_yuan TEXT,unpriced INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX usage_events_at_ms ON usage_events(at_ms)")
            db.execSQL("CREATE INDEX usage_events_session_at ON usage_events(session_id,at_ms)")
            db.execSQL("CREATE TABLE manual_rates(platform_id TEXT NOT NULL,model TEXT NOT NULL,rate_input TEXT,rate_output TEXT,rate_cache TEXT,rate_audio TEXT,rate_second TEXT,PRIMARY KEY(platform_id,model))")
        }

        /** 已有 v1 账本打开时原地补齐事件查询索引，不改表或清理历史。 */
        override fun onOpen(db: SQLiteDatabase) {
            super.onOpen(db)
            if (!db.isReadOnly) {
                db.execSQL("CREATE INDEX IF NOT EXISTS usage_events_event_id_id ON usage_events(event_id,id)")
            }
        }

        /** 当前数据库版本暂无迁移；未来升级必须保留全部历史记录。 */
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("用量账本迁移尚未实现：$oldVersion → $newVersion")
        }
    }
}
