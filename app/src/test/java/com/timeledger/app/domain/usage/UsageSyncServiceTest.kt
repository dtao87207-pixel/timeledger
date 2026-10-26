package com.timeledger.app.domain.usage

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.data.local.entity.ActivityEntity
import com.timeledger.app.data.local.entity.CategoryEntity
import com.timeledger.app.data.repository.FakeActivityDao
import com.timeledger.app.data.repository.FakeCategoryDao
import com.timeledger.app.data.repository.CategoryRepositoryImpl
import com.timeledger.app.data.repository.FakeTimeRecordDao
import com.timeledger.app.data.repository.TimeRecordRepositoryImpl
import com.timeledger.app.domain.model.AppMapping
import com.timeledger.app.domain.model.RecordSource
import com.timeledger.app.domain.model.TimeRecord
import com.timeledger.app.domain.model.TimeRecordDraft
import com.timeledger.app.domain.model.UsageEvent
import com.timeledger.app.domain.repository.UsageEventRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 采集编排测试（需求六节）。
 *
 * 平台读取用替身（真机上就是 `UsageStatsManager`），其余全用真实现：
 * 事件落库、映射登记、分析写入、手动记录保护 —— 这些都是产品规则，
 * 用替身测等于把它们重新实现一遍。
 *
 * 这样安排的结果是：**真机上需要验证的东西只剩「`queryEvents` 回来的事件对不对」**。
 */
class UsageSyncServiceTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    /** 9/12 22:00（+08:00）。 */
    private val fixedNow: Long = Instant.parse("2026-09-12T14:00:00Z").toEpochMilli()
    private val sept12: Long = LocalDate.of(2026, 9, 12).toEpochDay()

    private val dayBoundary = DayBoundary(zoneId = shanghai, nowProvider = { fixedNow })

    private lateinit var categoryDao: FakeCategoryDao
    private lateinit var activityDao: FakeActivityDao
    private lateinit var timeRecordDao: FakeTimeRecordDao
    private lateinit var timeRecordRepository: TimeRecordRepositoryImpl
    private lateinit var usageEventHost: FakeUsageEventHost
    private lateinit var appMappingHost: FakeAppMappingHost
    private lateinit var reader: RecordingUsageEventReader

    private val knownApp = "com.example.study"
    private val unknownApp = "com.example.unknown"

    private fun ts(iso: String): Long = Instant.parse(iso).toEpochMilli()

    /** 逻辑日 9/12 的起点：9/12 04:00。 */
    private val dayStart: Long = ts("2026-09-11T20:00:00Z")

    private fun at(minutesFromDayStart: Long): Long = dayStart + minutesFromDayStart * 60_000L

    /** 假平台读取器：返回预先摆好的事件，并记下被问过的区间。 */
    private class RecordingUsageEventReader : UsageEventReader {

        var granted = true
        var events: List<UsageEvent> = emptyList()
        val readRanges = mutableListOf<MillisRange>()

        override suspend fun isGranted(): Boolean = granted

        override suspend fun read(range: MillisRange): List<UsageEvent> {
            readRanges += range
            return events.filter {
                it.timestampMillis >= range.startMillis && it.timestampMillis < range.endMillisExclusive
            }
        }
    }

    private fun event(type: Int, atMillis: Long, pkg: String?): UsageEvent = UsageEvent(
        packageName = pkg,
        eventType = type,
        timestampMillis = atMillis,
        eventKey = UsageEvent.buildEventKey(type, atMillis, pkg),
    )

    /** 摆一段「某个 App 从 fromMinute 用到 toMinute」的原始事件。 */
    private fun usage(pkg: String, fromMinute: Long, toMinute: Long) {
        reader.events = reader.events + listOf(
            event(UsageEventTypes.ACTIVITY_RESUMED, at(fromMinute), pkg),
            event(UsageEventTypes.ACTIVITY_PAUSED, at(toMinute), pkg),
        )
    }

    private fun service(
        retentionDays: Long = UsageSyncService.DEFAULT_RETENTION_DAYS,
        labelResolver: suspend (String) -> String? = { null },
    ) = UsageSyncService(
        reader = reader,
        usageEventRepository = usageEventHost,
        appMappingRepository = appMappingHost,
        usageAnalysisService = UsageAnalysisService(
            usageEventRepository = usageEventHost,
            appMappingRepository = appMappingHost,
            timeRecordRepository = timeRecordRepository,
            dayBoundary = dayBoundary,
            categoryRepository = CategoryRepositoryImpl(categoryDao, dayBoundary),
        ),
        dayBoundary = dayBoundary,
        appLabelResolver = labelResolver,
        retentionDays = retentionDays,
    )

    @Before
    fun setUp() {
        categoryDao = FakeCategoryDao()
        activityDao = FakeActivityDao()
        timeRecordDao = FakeTimeRecordDao()
        timeRecordRepository = TimeRecordRepositoryImpl(
            timeRecordDao = timeRecordDao,
            categoryDao = categoryDao,
            activityDao = activityDao,
            dayBoundary = dayBoundary,
        )
        usageEventHost = FakeUsageEventHost()
        appMappingHost = FakeAppMappingHost()
        reader = RecordingUsageEventReader()

        runBlocking {
            val categoryId = categoryDao.insert(CategoryEntity(name = "学习", sortOrder = 0))
            val activityId = activityDao.insert(ActivityEntity(categoryId = categoryId, name = "Java"))
            appMappingHost.put(
                AppMapping(
                    packageName = knownApp,
                    appLabel = "学习通",
                    categoryId = categoryId,
                    activityId = activityId,
                    confidence = 1.0,
                    isUserConfirmed = true,
                )
            )
        }
    }

    private suspend fun records(epochDay: Long = sept12): List<TimeRecord> =
        timeRecordRepository.getRecordsIn(dayBoundary.dayRange(epochDay))

    /** 只分析今天，测试里更可控。 */
    private suspend fun UsageSyncService.syncToday() = sync(epochDay = sept12, includePreviousDay = false)

    // ---------------- 授权 ----------------

    @Test
    fun `without access nothing is read or written`() = runTest {
        reader.granted = false
        usage(knownApp, fromMinute = 600, toMinute = 660)

        val result = service().syncToday()

        assertTrue(!result.granted)
        assertEquals(0, result.readEventCount)
        assertTrue(reader.readRanges.isEmpty())
        assertTrue(usageEventHost.count() == 0)
        assertTrue(records().isEmpty())
    }

    // ---------------- 主路径 ----------------

    @Test
    fun `events are stored and analysed into records`() = runTest {
        usage(knownApp, fromMinute = 600, toMinute = 660)

        val result = service().syncToday()

        assertTrue(result.granted)
        assertEquals(2, result.newEventCount)
        assertEquals(2, result.readEventCount)
        assertEquals(1, result.sessionCount)
        assertEquals(1, result.writtenCount)
        assertEquals(1, result.systemCount)

        val record = records().single()
        assertEquals(RecordSource.SYSTEM, record.source)
        assertEquals(at(600), record.startMillis)
    }

    @Test
    fun `the read range starts before the day so overnight sessions are caught`() = runTest {
        service().syncToday()

        val range = reader.readRanges.single()
        assertEquals(dayStart, range.startMillis)
        assertEquals("结束时刻必须收在现在", fixedNow, range.endMillisExclusive)
    }

    @Test
    fun `collecting twice does not duplicate raw events or records`() = runTest {
        usage(knownApp, fromMinute = 600, toMinute = 660)
        val sync = service()

        sync.syncToday()
        val second = sync.syncToday()

        assertEquals(2, second.readEventCount)
        assertEquals("重复读取不应该写入重复的原始事件", 0, second.newEventCount)
        assertEquals(2, usageEventHost.count())
        assertEquals(1, records().size)
    }

    // ---------------- 昨天也要分析 ----------------

    @Test
    fun `the previous day is analysed too so late night usage is not lost`() = runTest {
        // 9/12 02:00 的使用属于逻辑日 9/11（04:00~次日 04:00），
        // 只分析今天的话，用户翻到昨天会看到一天空白
        reader.events = listOf(
            event(UsageEventTypes.ACTIVITY_RESUMED, ts("2026-09-11T18:00:00Z"), knownApp), // 9/12 02:00
            event(UsageEventTypes.ACTIVITY_PAUSED, ts("2026-09-11T18:30:00Z"), knownApp),
        )

        val result = service().sync(epochDay = sept12, includePreviousDay = true)

        assertEquals(2, result.analyses.size)
        assertEquals(listOf(sept12 - 1, sept12), result.analyses.map { it.epochDay })
        // 记录落在昨天，而不是今天
        assertEquals(1, records(sept12 - 1).size)
        assertTrue(records(sept12).isEmpty())
    }

    // ---------------- 应用名登记 ----------------

    @Test
    fun `unknown packages are registered so the user can map them later`() = runTest {
        usage(unknownApp, fromMinute = 600, toMinute = 660)

        val result = service { pkg -> if (pkg == unknownApp) "某未知应用" else null }.syncToday()

        val mapping = appMappingHost.getMapping(unknownApp)
        assertEquals("某未知应用", mapping?.appLabel)
        assertEquals(listOf(unknownApp), result.unresolvedPackages)
    }

    @Test
    fun `the app name reaches the record note so the timeline can say something`() = runTest {
        usage(unknownApp, fromMinute = 600, toMinute = 660)

        service { "某未知应用" }.syncToday()

        assertEquals("某未知应用", records().single().note)
    }

    @Test
    fun `an uninstallable package falls back to its package name`() = runTest {
        usage(unknownApp, fromMinute = 600, toMinute = 660)

        // 解析不到应用名（已卸载、或是系统组件）时不能少一条记录，只是名字难看一点
        service { null }.syncToday()

        assertEquals(unknownApp, records().single().note)
    }

    @Test
    fun `collection never overwrites a user confirmed binding`() = runTest {
        usage(knownApp, fromMinute = 600, toMinute = 660)
        val confirmed = appMappingHost.getMapping(knownApp)!!

        // 采集时会把「包名 → 应用名」登记一遍。对用户确认过的映射，
        // 允许刷新展示名（那只是显示），但**归属与置信度一个字都不能动** ——
        // 否则用户归类好的 App 会被系统悄悄改回去。
        service { "系统读到的名字" }.syncToday()

        val mapping = appMappingHost.getMapping(knownApp)!!
        assertEquals(confirmed.categoryId, mapping.categoryId)
        assertEquals(confirmed.activityId, mapping.activityId)
        assertEquals("确认过的置信度不能被改低", confirmed.confidence, mapping.confidence, 0.0)
        assertTrue(mapping.isUserConfirmed)
        assertEquals(RecordSource.SYSTEM, records().single().source)
    }

    // ---------------- 与手动记录共存 ----------------

    @Test
    fun `manual records still win over collected usage`() = runTest {
        // 用户自己记了 14:00–14:30，系统在同一时段采到了使用记录
        timeRecordRepository.createManualRecord(
            TimeRecordDraft(
                startMillis = at(600),
                endMillis = at(630),
                categoryId = appMappingHost.getMapping(knownApp)!!.categoryId,
            )
        )
        usage(unknownApp, fromMinute = 600, toMinute = 630)

        val result = service().syncToday()

        assertEquals(0, result.writtenCount)
        assertEquals(1, result.blockedByManualCount)
        assertEquals(RecordSource.MANUAL, records().single().source)
    }

    // ---------------- 结果汇总 ----------------

    @Test
    fun `status numbers add up across the analysed days`() = runTest {
        usage(knownApp, fromMinute = 600, toMinute = 660)
        usage(unknownApp, fromMinute = 660, toMinute = 690)

        val result = service().syncToday()

        assertEquals(2, result.writtenCount)
        assertEquals(2, result.systemCount)
        assertEquals(0, result.suggestedCount)
        assertEquals(listOf(unknownApp), result.unresolvedPackages)
    }

    @Test
    fun `an empty system produces an empty but granted result`() = runTest {
        val result = service().syncToday()

        assertTrue(result.granted)
        assertEquals(0, result.readEventCount)
        assertEquals(0, result.writtenCount)
        assertEquals(0, result.sessionCount)
        assertTrue(records().isEmpty())
    }

    @Test
    fun `denied result describes a missing permission`() {
        val denied = UsageSyncResult.denied()

        assertTrue(!denied.granted)
        assertEquals(0, denied.writtenCount)
        assertTrue(denied.unresolvedPackages.isEmpty())
    }

    @Test
    fun `access state is reported without reading anything`() = runTest {
        reader.granted = false
        assertTrue(!service().isAccessGranted())

        reader.granted = true
        assertTrue(service().isAccessGranted())
        assertTrue("只查权限不该读事件", reader.readRanges.isEmpty())
    }

    @Test
    fun `events outside the read window are not stored`() = runTest {
        // 三天前的数据不该被这次采集带进来（分析窗口最多到前一天）
        reader.events = listOf(
            event(UsageEventTypes.ACTIVITY_RESUMED, ts("2026-09-09T06:00:00Z"), knownApp),
            event(UsageEventTypes.ACTIVITY_PAUSED, ts("2026-09-09T07:00:00Z"), knownApp),
        )

        val result = service().syncToday()

        assertEquals(0, result.readEventCount)
        assertEquals(0, usageEventHost.count())
        assertTrue(records().isEmpty())
    }

    // ---------------- 原始事件的保留 ----------------

    @Test
    fun `old raw events are pruned while recent ones are kept`() = runTest {
        // usage_events 是唯一会持续增长的表，必须有保留策略。
        // 先塞两条十天前的旧事件进来，再采集今天的数据。
        usageEventHost.appendEvents(
            listOf(
                event(UsageEventTypes.ACTIVITY_RESUMED, ts("2026-09-02T06:00:00Z"), knownApp),
                event(UsageEventTypes.ACTIVITY_PAUSED, ts("2026-09-02T07:00:00Z"), knownApp),
            )
        )
        usage(knownApp, fromMinute = 600, toMinute = 660)

        val result = service(retentionDays = 3).syncToday()

        assertEquals(2, result.prunedEventCount)
        assertEquals("今天的原始事件必须留着", 2, usageEventHost.count())
        assertEquals(1, records().size)
    }

    @Test
    fun `pruning happens after analysis so today's records still get written`() = runTest {
        // 顺序不能反：先清后分析会把当天要用的数据清掉，那一天的记录就再也生成不出来
        usageEventHost.appendEvents(
            listOf(event(UsageEventTypes.ACTIVITY_RESUMED, ts("2026-09-01T06:00:00Z"), knownApp))
        )
        usage(unknownApp, fromMinute = 600, toMinute = 690)

        val result = service(retentionDays = 1).syncToday()

        assertEquals(1, result.sessionCount)
        assertEquals(1, records().size)
        assertTrue(result.prunedEventCount > 0)
    }

    @Test
    fun `recent events survive the default retention window`() = runTest {
        usage(knownApp, fromMinute = 600, toMinute = 660)

        val result = service().syncToday()

        assertEquals(0, result.prunedEventCount)
        assertEquals(2, usageEventHost.count())
    }

    @Test
    fun `the retention window is much wider than what is analysed`() {
        // 保留期远大于实际会分析的范围（今天 + 昨天），边界上不做危险的事
        assertTrue(UsageSyncService.DEFAULT_RETENTION_DAYS > 30)
        assertTrue(UsageSyncService.DEFAULT_RETENTION_DAYS > UsageAnalysisService.DEFAULT_ANALYZABLE_DAYS)
    }
}
