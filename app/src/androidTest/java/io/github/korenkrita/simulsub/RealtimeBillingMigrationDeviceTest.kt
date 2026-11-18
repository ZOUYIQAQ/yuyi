package io.github.korenkrita.simulsub

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.platform.app.InstrumentationRegistry
import java.math.BigDecimal
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** 以真实旧结构与留存的3.5用量序列验证修复，测试数据库和用户账本完全隔离。 */
class RealtimeBillingMigrationDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val endpoint = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime"
    private val model = "qwen3.5-livetranslate-flash-realtime"
    private val input = listOf(84L, 183L, 267L, 342L, 426L, 510L, 609L)
    private val audio = listOf(21L, 49L, 70L, 84L, 105L, 126L, 154L)
    private val output = listOf(15L, 41L, 56L, 66L, 83L, 98L, 124L)
    private val now = 1_792_282_000_000L

    /** 新连接每次只记录增长；重发、乱序、另一连接相同响应号均不能串账。 */
    @Test fun cumulativeConnectionCostsAreLinearAndIndependent() {
        val repo = repository("linear-${UUID.randomUUID()}.db")
        val session = repo.beginSession("独立计费")
        for (index in audio.indices) repo.record(session, CostCategory.DIRECT, endpoint, model,
            observation(index), "connection-a:usage", cumulative = true)
        val expected = BigDecimal("0.01856")
        assertEquals(0, repo.state.value.currentSession!!.amountYuan.compareTo(expected))
        assertEquals(1, repo.state.value.currentSession!!.requests)
        repo.record(session, CostCategory.DIRECT, endpoint, model, observation(0), "connection-a:usage", true)
        repo.record(session, CostCategory.DIRECT, endpoint, model, observation(6), "connection-a:usage", true)
        assertEquals(0, repo.state.value.currentSession!!.amountYuan.compareTo(expected))
        repo.record(session, CostCategory.DIRECT, endpoint, model, observation(6), "connection-b:usage", true)
        assertEquals(0, repo.state.value.currentSession!!.amountYuan.compareTo(expected * BigDecimal("2")))
        assertEquals(2, repo.state.value.currentSession!!.requests)
    }

    /** v1累计快照求和错误原地修复，原始七行和费率不变，第二次打开也不重复校正。 */
    @Test fun versionOneUpgradeCorrectsTotalsAndKeepsOriginalRows() {
        val name = "legacy-${UUID.randomUUID()}.db"
        seedVersionOne(name)
        val repo = repository(name)
        assertEquals(0, repo.totalsBetween(0, now + 10).amountYuan.compareTo(BigDecimal("0.01856")))
        assertEquals(1, repo.totalsBetween(0, now + 10).requests)
        val helper = helper(repo)
        helper.readableDatabase.rawQuery("SELECT SUM(audio_tokens),COUNT(*) FROM usage_events", null).use {
            assertTrue(it.moveToFirst()); assertEquals(609L, it.getLong(0)); assertEquals(7, it.getInt(1))
        }
        helper.readableDatabase.rawQuery("SELECT SUM(audio_tokens),COUNT(*) FROM usage_event_corrections", null).use {
            assertTrue(it.moveToFirst()); assertEquals(154L, it.getLong(0)); assertEquals(7, it.getInt(1))
        }
        // 最后一次快照的增长量属于最后一个观测时间，不把历史费用挪到升级当天。
        assertEquals(0, repo.totalsBetween(now + 6, now + 7).amountYuan.compareTo(BigDecimal("0.00372")))
        helper.close()
        val reopened = repository(name)
        assertEquals(0, reopened.totalsBetween(0, now + 10).amountYuan.compareTo(BigDecimal("0.01856")))
    }

    /** 无法确认连接身份的旧记录不猜测、不按响应数打折。 */
    @Test fun ambiguousOldEventIdsRemainUnchanged() {
        val name = "legacy-unknown-${UUID.randomUUID()}.db"
        seedVersionOne(name, knownPrefix = false)
        val repo = repository(name)
        assertEquals(7, repo.totalsBetween(0, now + 10).requests)
        helper(repo).readableDatabase.rawQuery("SELECT COUNT(*) FROM usage_event_corrections", null).use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
    }

    /** 缺失用量仍为未计价；后续完整累计快照可以补齐，而不是永远保留一个假的零。 */
    @Test fun unknownSnapshotCanBeCompletedWithoutDuplicateCharge() {
        val repo = repository("missing-${UUID.randomUUID()}.db")
        repo.record(null, CostCategory.DIRECT, endpoint, model, MeteredUsage(), "scope:usage", true)
        assertEquals(1, repo.state.value.today.unpricedRequests)
        repo.record(null, CostCategory.DIRECT, endpoint, model,
            MeteredUsage(inputTokens = 154, outputTokens = 124, audioInputTokens = 154), "scope:usage", true)
        assertEquals(0, repo.state.value.today.unpricedRequests)
        assertEquals(0, repo.state.value.today.amountYuan.compareTo(BigDecimal("0.01856")))
    }

    /** 完整快照之后的新未知观测必须显示未计价，后续完整累计快照才能解除警告。 */
    @Test fun aLaterUnknownObservationIsNotHiddenByOldCompleteValues() {
        val repo = repository("later-missing-${UUID.randomUUID()}.db")
        val known = MeteredUsage(inputTokens = 21, outputTokens = 15, audioInputTokens = 21)
        repo.record(null, CostCategory.DIRECT, endpoint, model, known, "scope:usage", true)
        assertEquals(0, repo.state.value.today.unpricedRequests)
        val before = repo.state.value.today.amountYuan
        repo.record(null, CostCategory.DIRECT, endpoint, model, MeteredUsage(), "scope:usage", true)
        assertEquals(1, repo.state.value.today.unpricedRequests)
        assertEquals(before, repo.state.value.today.amountYuan)
        repo.record(null, CostCategory.DIRECT, endpoint, model, known, "scope:usage", true)
        assertEquals(0, repo.state.value.today.unpricedRequests)
        assertEquals(before, repo.state.value.today.amountYuan)
    }

    /** 后续有单价不能清除早先无单价区段的警告；按时间查询只报告本范围的缺口。 */
    @Test fun laterRatesCannotPriceAnEarlierHistoricalGap() {
        val name = "legacy-rate-gap-${UUID.randomUUID()}.db"
        seedVersionOne(name)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE usage_events SET input_tokens=audio_tokens")
            db.execSQL("UPDATE streams SET input_tokens=audio_tokens")
            db.execSQL("UPDATE usage_events SET rate_output=NULL,rate_audio=NULL,amount_yuan=NULL WHERE at_ms=?", arrayOf(now))
        }
        val repo = repository(name)
        val all = repo.totalsBetween(now, now + 10)
        assertEquals(1, all.unpricedRequests)
        assertEquals(0, all.amountYuan.compareTo(BigDecimal("0.01622")))
        val later = repo.totalsBetween(now + 1, now + 10)
        assertEquals(0, later.unpricedRequests)
        assertEquals(0, later.amountYuan.compareTo(all.amountYuan))
    }

    /** 旧版最后一条响应缺失用量时不能因前一快照完整而误报整段已计价。 */
    @Test fun migrationRetainsAnUnknownFinalObservation() {
        val name = "legacy-tail-gap-${UUID.randomUUID()}.db"
        seedVersionOne(name)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE usage_events SET input_tokens=audio_tokens")
            db.execSQL("UPDATE streams SET input_tokens=audio_tokens")
            db.execSQL("UPDATE usage_events SET input_tokens=NULL,output_tokens=NULL,audio_tokens=NULL,amount_yuan=NULL WHERE at_ms=?", arrayOf(now + 6))
        }
        val repo = repository(name)
        assertEquals(1, repo.totalsBetween(now, now + 10).unpricedRequests)
        assertEquals(0, repo.totalsBetween(now, now + 10).amountYuan.compareTo(BigDecimal("0.01484")))
    }

    /** 原观测既无数量也无费率时不制造永久费用缺口，完整快照可以覆盖此前未知区间。 */
    @Test fun anEmptyHistoricalObservationDoesNotCreateAPermanentRateGap() {
        val name = "legacy-empty-first-${UUID.randomUUID()}.db"
        seedVersionOne(name)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE usage_events SET input_tokens=audio_tokens")
            db.execSQL("UPDATE streams SET input_tokens=audio_tokens")
            db.execSQL("UPDATE usage_events SET input_tokens=NULL,output_tokens=NULL,audio_tokens=NULL,rate_output=NULL,rate_audio=NULL,amount_yuan=NULL WHERE at_ms=?", arrayOf(now))
        }
        val repo = repository(name)
        assertEquals(0, repo.totalsBetween(now, now + 10).unpricedRequests)
        assertEquals(0, repo.totalsBetween(now, now + 10).amountYuan.compareTo(BigDecimal("0.01856")))
    }

    /** 从修复前源码保存的v1结构创建真实旧数据库，不依赖新版建表器模拟旧版。 */
    private fun seedVersionOne(name: String, knownPrefix: Boolean = true) {
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val sql = InstrumentationRegistry.getInstrumentation().context.assets.open("usage-ledger-v1.sql")
                .bufferedReader().use { it.readText() }.lineSequence().filterNot { it.startsWith("--") }.joinToString("\n")
            sql.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { db.execSQL(it) }
            val prefix = if (knownPrefix) "12af6c91-b401-4ac5-8b63-b80b8ea6e376" else "unknown"
            for (index in audio.indices) {
                val values = ContentValues().apply {
                    put("event_id", "$prefix:resp_$index"); put("category", "DIRECT"); put("platform_id", "qwen")
                    put("model", model); put("input_tokens", input[index]); put("output_tokens", output[index])
                    put("audio_tokens", audio[index])
                }
                db.insertOrThrow("streams", null, ContentValues(values).apply { put("cumulative", 0) })
                db.insertOrThrow("usage_events", null, ContentValues(values).apply {
                    put("at_ms", now + index); put("rate_output", "100"); put("rate_audio", "40")
                    put("amount_yuan", BigDecimal.valueOf(audio[index]).multiply(BigDecimal("40"))
                        .add(BigDecimal.valueOf(output[index]).multiply(BigDecimal("100")))
                        .divide(BigDecimal("1000000")).toPlainString())
                    put("unpriced", 1)
                })
            }
            db.version = 1
        }
    }

    /** 仅返回留存序列中的数字，不需要真实录音、字幕或凭据。 */
    private fun observation(index: Int) = MeteredUsage(inputTokens = input[index], outputTokens = output[index],
        audioInputTokens = audio[index])

    /** 通过既有隔离入口创建账本，并以固定时钟验证迁移不改变观测时段。 */
    private fun repository(name: String): UsageCostRepository = UsageCostRepository::class.java.getDeclaredConstructor(
        Context::class.java, kotlin.jvm.functions.Function0::class.java, String::class.java).apply { isAccessible = true }
        .newInstance(context, { now + 10 }, name)

    /** 仅读写测试专属数据库以核实原始记录确实保留。 */
    private fun helper(repo: UsageCostRepository): SQLiteOpenHelper = UsageCostRepository::class.java
        .getDeclaredField("database").apply { isAccessible = true }.get(repo) as SQLiteOpenHelper
}
