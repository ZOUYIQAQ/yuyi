package io.github.korenkrita.simulsub

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** 以隔离数据库与可控时钟检查长期明细账本。 */
class UsageCostRepositoryDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 后台写入延迟不改变原始回报所属时段，后续小时查询仍按实际观测时间统计。 */
    @Test fun delayedPersistenceRetainsObservationTime() {
        val observed = at(17, 10)
        val (repo, _) = repository({ at(17, 12) })
        repo.record(null, CostCategory.ASR, "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
            "qwen-audio-3.1-asr-flash-streaming", MeteredUsage(inputTokens = 100, outputTokens = 10),
            observedAtMs = observed)
        assertEquals(1, repo.totalsBetween(observed, at(17, 11)).requests)
        assertEquals(0, repo.totalsBetween(at(17, 12), at(17, 13)).requests)
    }

    /** 为测试创建隔离账本，不触及应用真实数据。 */
    private fun repository(clock: () -> Long, name: String = "usage-cost-test-${UUID.randomUUID()}.db"):
        Pair<UsageCostRepository, String> {
        val constructor = UsageCostRepository::class.java.getDeclaredConstructor(
            Context::class.java, kotlin.jvm.functions.Function0::class.java, String::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(context, clock, name) to name
    }

    /** 按设备当前时区生成时间戳以跨小时和跨日验证左闭右开范围。 */
    private fun at(day: Int, hour: Int): Long = LocalDateTime.of(2026, 10, day, hour, 0)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** 累计事件重复回调不计费，跨小时增长保存两行且可独立查询。 */
    @Test fun cumulativeSnapshotsKeepHourlyDeltasWithoutDuplicates() {
        var now = at(17, 10)
        val (repo, _) = repository({ now })
        repo.setRate("origin:https://cost.example", "custom", ManualCostRate(input = BigDecimal("1000000")))
        val id = repo.beginSession("同传")
        val endpoint = "https://cost.example/v1?token=不保存的内容"
        repo.record(id, CostCategory.ASR, endpoint, "custom", MeteredUsage(inputTokens = 2), "流一", true)
        repo.record(id, CostCategory.ASR, endpoint, "custom", MeteredUsage(inputTokens = 2), "流一", true)
        now = at(17, 11)
        repo.record(id, CostCategory.ASR, endpoint, "custom", MeteredUsage(inputTokens = 5), "流一", true)
        assertEquals(0, repo.totalsBetween(at(17, 10), at(17, 11)).amountYuan.compareTo(BigDecimal("2")))
        assertEquals(0, repo.totalsBetween(at(17, 11), at(17, 12)).amountYuan.compareTo(BigDecimal("3")))
        assertEquals(1, repo.totalsBetween(at(17, 10), at(17, 12)).requests)
        assertEquals(2, rowCount(repo))
        assertEquals("origin:https://cost.example", firstPlatformId(repo))
    }

    /** ASR 秒数作为元数据跨小时追加差值，Token 费率仍只计算 Token。 */
    @Test fun audioSecondsRemainInHourlyLedgerWithoutExtraCharge() {
        var now = at(17, 10)
        val (repo, _) = repository({ now })
        repo.setRate("origin:https://cost.example", "asr",
            ManualCostRate(input = BigDecimal("1000000"), output = BigDecimal("0")))
        repo.record(null, CostCategory.ASR, "https://cost.example/v1", "asr",
            MeteredUsage(inputTokens = 2, outputTokens = 0, audioSeconds = 2.0), "音频流", true)
        now = at(17, 11)
        repo.record(null, CostCategory.ASR, "https://cost.example/v1", "asr",
            MeteredUsage(inputTokens = 5, outputTokens = 0, audioSeconds = 5.0), "音频流", true)
        now = at(17, 12)
        repo.record(null, CostCategory.ASR, "https://cost.example/v1", "asr",
            MeteredUsage(inputTokens = 5, outputTokens = 0, audioSeconds = 8.0), "音频流", true)
        assertEquals(0, repo.totalsBetween(at(17, 10), at(17, 11)).amountYuan.compareTo(BigDecimal("2")))
        assertEquals(0, repo.totalsBetween(at(17, 11), at(17, 12)).amountYuan.compareTo(BigDecimal("3")))
        assertEquals(0, repo.totalsBetween(at(17, 12), at(17, 13)).amountYuan.compareTo(BigDecimal.ZERO))
        assertEquals(1, repo.totalsBetween(at(17, 10), at(17, 11)).requests)
        assertEquals(1, repo.totalsBetween(at(17, 11), at(17, 12)).requests)
        assertEquals(1, repo.totalsBetween(at(17, 12), at(17, 13)).requests)
        assertEquals(1, repo.totalsBetween(at(17, 10), at(17, 13)).requests)
        assertEquals(listOf(2.0, 3.0, 3.0), audioSecondsRows(repo))
    }

    /** 长小数手填费率按来源命中并随明细落盘，重建后金额与费率均无精度损失。 */
    @Test fun longManualRateAndOriginSurviveRestart() {
        val now = at(17, 10)
        val databaseName = "usage-cost-test-${UUID.randomUUID()}.db"
        val (first, _) = repository({ now }, databaseName)
        val rateText = "0.123456789012345678901234567890"
        first.setRate("origin:https://cost.example", "精度模型",
            ManualCostRate(input = BigDecimal(rateText)))
        first.record(null, CostCategory.TEST, "https://cost.example/v1?query=只用于请求", "精度模型",
            MeteredUsage(inputTokens = 1), "长费率事件")
        val (reopened, _) = repository({ now }, databaseName)
        val expected = BigDecimal(rateText).divide(BigDecimal("1000000"))
        assertEquals(0, reopened.totalsBetween(now, now + 1).amountYuan.compareTo(expected))
        assertEquals("origin:https://cost.example", firstPlatformId(reopened))
        assertEquals(rateText, firstRateInput(reopened))
    }

    /** 跨日累计回调进入实际观测当天；重建账本仍能查询两日明细。 */
    @Test fun restartRestoresCrossDayDetailsAndSession() {
        var now = at(17, 23)
        val databaseName = "usage-cost-test-${UUID.randomUUID()}.db"
        val (first, _) = repository({ now }, databaseName)
        first.setRate("origin:https://cost.example", "custom", ManualCostRate(input = BigDecimal("1000000")))
        val id = first.beginSession("前夜同传")
        first.record(id, CostCategory.TRANSLATION, "https://cost.example/v1", "custom",
            MeteredUsage(inputTokens = 1), "跨日流", true)
        now = at(18, 0)
        first.endSession(id)
        first.record(id, CostCategory.TRANSLATION, "https://cost.example/v1", "custom",
            MeteredUsage(inputTokens = 4), "跨日流", true)
        val (reopened, _) = repository({ now }, databaseName)
        assertEquals(0, reopened.totalsBetween(at(17, 23), at(18, 0)).amountYuan.compareTo(BigDecimal.ONE))
        assertEquals(0, reopened.state.value.today.amountYuan.compareTo(BigDecimal("3")))
        assertEquals(0, reopened.state.value.lastSession!!.amountYuan.compareTo(BigDecimal("4")))
        assertEquals("前夜同传", reopened.state.value.lastSession!!.label)
        assertNull(reopened.state.value.currentSession)
        assertEquals(2, rowCount(reopened))
    }

    /** 无用量、未知模型和普通重复事件保留未知状态且不重复计算请求。 */
    @Test fun unknownAndOrdinaryDuplicatesStayHonest() {
        val (repo, _) = repository({ at(17, 10) })
        repo.record(null, CostCategory.JEV, "https://openrouter.ai/api/v1?secret=不保存", "未知模型",
            MeteredUsage(), "未知事件")
        repo.record(null, CostCategory.JEV, "https://openrouter.ai/api/v1?secret=不保存", "未知模型",
            MeteredUsage(), "未知事件")
        val total = repo.totalsBetween(at(17, 10), at(17, 11))
        assertEquals(1, total.requests)
        assertEquals(1, total.unpricedRequests)
        assertEquals(listOf("未知模型"), total.unknownModels)
        assertEquals(1, rowCount(repo))
    }

    /** 缓存字段稍后到达时补记未命中的输入费，不重复计算此前已知输出。 */
    @Test fun lateCacheUsageCompletesPreviouslyUnknownPrice() {
        var now = at(17, 10)
        val (repo, _) = repository({ now })
        repo.setRate("origin:https://cost.example", "custom",
            ManualCostRate(BigDecimal("2"), BigDecimal("8"), BigDecimal("0.04")))
        repo.record(null, CostCategory.TRANSLATION, "https://cost.example/v1", "custom",
            MeteredUsage(inputTokens = 100, outputTokens = 10), "缓存流", true)
        assertEquals(1, repo.state.value.today.unpricedRequests)
        assertEquals(0, repo.state.value.today.amountYuan.compareTo(BigDecimal("0.00008")))
        now = at(17, 11)
        repo.record(null, CostCategory.TRANSLATION, "https://cost.example/v1", "custom",
            MeteredUsage(inputTokens = 100, outputTokens = 10, cachedInputTokens = 20), "缓存流", true)
        assertEquals(0, repo.state.value.today.unpricedRequests)
        assertEquals(0, repo.state.value.today.amountYuan.compareTo(BigDecimal("0.0002408")))
        assertEquals(2, rowCount(repo))
    }

    /** 进程重建时孤儿会话以最后持久化活动截止，后续时间不继续累计。 */
    @Test fun restartClosesOrphanAtLastPersistedActivity() {
        var now = at(17, 10)
        val databaseName = "usage-cost-test-${UUID.randomUUID()}.db"
        val (first, _) = repository({ now }, databaseName)
        val session = first.beginSession("意外中断")
        now += 60_000
        first.record(session, CostCategory.TEST, "https://unknown.example/v1", "未知模型",
            MeteredUsage(inputTokens = 1), "中断事件")
        now += 3_600_000
        val (reopened, _) = repository({ now }, databaseName)
        assertNull(reopened.state.value.currentSession)
        assertEquals(60L, reopened.state.value.lastSession!!.durationSeconds)
        now += 3_600_000
        reopened.refreshToday()
        assertEquals(60L, reopened.state.value.lastSession!!.durationSeconds)
    }

    /** 读取测试账本明细行数，确认增量确实追加到 SQLite。 */
    private fun rowCount(repo: UsageCostRepository): Int {
        val field = UsageCostRepository::class.java.getDeclaredField("database")
        field.isAccessible = true
        val helper = field.get(repo) as android.database.sqlite.SQLiteOpenHelper
        return helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM usage_events", emptyArray()).use { c ->
            c.moveToFirst(); c.getInt(0)
        }
    }

    /** 查看首条明细的平台字段，确保查询参数和路径没有进入账本。 */
    private fun firstPlatformId(repo: UsageCostRepository): String {
        val field = UsageCostRepository::class.java.getDeclaredField("database")
        field.isAccessible = true
        val helper = field.get(repo) as android.database.sqlite.SQLiteOpenHelper
        return helper.readableDatabase.rawQuery("SELECT platform_id FROM usage_events ORDER BY id LIMIT 1",
            emptyArray()).use { c -> c.moveToFirst(); c.getString(0) }
    }

    /** 从原始增量明细核对秒数是否按观测时间保留。 */
    private fun audioSecondsRows(repo: UsageCostRepository): List<Double> {
        val field = UsageCostRepository::class.java.getDeclaredField("database")
        field.isAccessible = true
        val helper = field.get(repo) as android.database.sqlite.SQLiteOpenHelper
        return helper.readableDatabase.rawQuery("SELECT audio_seconds FROM usage_events ORDER BY id",
            emptyArray()).use { c ->
            buildList { while (c.moveToNext()) add(c.getDouble(0)) }
        }
    }

    /** 读取第一条明细的原始费率字符串以验证持久化精度。 */
    private fun firstRateInput(repo: UsageCostRepository): String {
        val field = UsageCostRepository::class.java.getDeclaredField("database")
        field.isAccessible = true
        val helper = field.get(repo) as android.database.sqlite.SQLiteOpenHelper
        return helper.readableDatabase.rawQuery("SELECT rate_input FROM usage_events ORDER BY id LIMIT 1",
            emptyArray()).use { c -> c.moveToFirst(); c.getString(0) }
    }
}
