package com.timeledger.app.domain.usage

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.data.local.entity.ActivityEntity
import com.timeledger.app.data.local.entity.CategoryEntity
import com.timeledger.app.data.repository.FakeActivityDao
import com.timeledger.app.data.repository.FakeCategoryDao
import com.timeledger.app.data.repository.FakeTimeRecordDao
import com.timeledger.app.data.repository.CategoryRepositoryImpl
import com.timeledger.app.data.repository.TimeRecordRepositoryImpl
import com.timeledger.app.domain.model.AppMapping
import com.timeledger.app.domain.model.RecordSource
import com.timeledger.app.domain.model.TimeRecord
import com.timeledger.app.domain.model.TimeRecordDraft
import com.timeledger.app.domain.model.UsageEvent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 系统感知编排测试（需求六、九节）。
 *
 * ## 为什么这里用**真实**的时间记录仓库
 * 「系统采集不得覆盖用户的手动记录」是这款产品的硬规则，而它是由仓库层保证的。
 * 用替身去测，等于自己把这条规则又实现一遍，然后验证自己的实现 ——
 * 所以这里用真仓库 + DAO 替身，让数据真的按规则走一遍。
 *
 * ## 时间固定
 * 时区上海、`now` 固定为 2026-09-12 22:00（+08:00），这样才能断言
 * 「还在用的会话记到现在，而不是记到明天凌晨 4 点」这种边界 ——
 * 用「现在」做基准的代码，测试里必须有一个不回头的「现在」。
 */
class UsageAnalysisServiceTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    /** 固定为 9/12 22:00（+08:00）：下面的会话都发生在当天下午，全部早于「现在」。 */
    private val fixedNow: Long = Instant.parse("2026-09-12T14:00:00Z").toEpochMilli()
    private val sept12: Long = LocalDate.of(2026, 9, 12).toEpochDay()

    private val dayBoundary = DayBoundary(zoneId = shanghai, nowProvider = { fixedNow })

    private lateinit var categoryDao: FakeCategoryDao
    private lateinit var activityDao: FakeActivityDao
    private lateinit var timeRecordDao: FakeTimeRecordDao
    private lateinit var timeRecordRepository: TimeRecordRepositoryImpl
    private lateinit var usageEventHost: FakeUsageEventHost
    private lateinit var appMappingHost: FakeAppMappingHost
    private lateinit var service: UsageAnalysisService

    private var studyCategoryId: Long = 0L
    private var javaActivityId: Long = 0L
    private var screenOffCategoryId: Long = 0L

    /** 事件里用的两个包名。 */
    private val knownApp = "com.example.study"
    private val unknownApp = "com.example.unknown"

    private fun ts(iso: String): Long = Instant.parse(iso).toEpochMilli()

    /** 9/12 的 04:00 起点。 */
    private val dayStart: Long = ts("2026-09-11T20:00:00Z")

    private val hour = 3_600_000L

    private fun at(minutesFromDayStart: Long): Long = dayStart + minutesFromDayStart * 60_000L

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
        service = UsageAnalysisService(
            usageEventRepository = usageEventHost,
            appMappingRepository = appMappingHost,
            timeRecordRepository = timeRecordRepository,
            dayBoundary = dayBoundary,
            categoryRepository = CategoryRepositoryImpl(categoryDao, dayBoundary),
        )

        runBlocking {
            studyCategoryId = categoryDao.insert(CategoryEntity(name = "学习", sortOrder = 0))
            javaActivityId = activityDao.insert(
                ActivityEntity(categoryId = studyCategoryId, name = "Java")
            )
            // 内置分类「熄屏」正常由种子数据写入，测试里手动补上
            screenOffCategoryId = categoryDao.insert(CategoryEntity(name = "熄屏", sortOrder = 90))
            // 用户已经把这个 App 归类为「学习 / Java」
            appMappingHost.put(
                AppMapping(
                    packageName = knownApp,
                    appLabel = "学习通",
                    categoryId = studyCategoryId,
                    activityId = javaActivityId,
                    confidence = 1.0,
                    isUserConfirmed = true,
                )
            )
        }
    }

    private suspend fun usage(
        pkg: String,
        fromMinute: Long,
        toMinute: Long,
    ) {
        usageEventHost.appendEvents(
            listOf(
                UsageEvent(
                    packageName = pkg,
                    eventType = UsageEventTypes.ACTIVITY_RESUMED,
                    timestampMillis = at(fromMinute),
                    eventKey = UsageEvent.buildEventKey(
                        UsageEventTypes.ACTIVITY_RESUMED, at(fromMinute), pkg,
                    ),
                ),
                UsageEvent(
                    packageName = pkg,
                    eventType = UsageEventTypes.ACTIVITY_PAUSED,
                    timestampMillis = at(toMinute),
                    eventKey = UsageEvent.buildEventKey(
                        UsageEventTypes.ACTIVITY_PAUSED, at(toMinute), pkg,
                    ),
                ),
            )
        )
    }

    private suspend fun records(epochDay: Long = sept12): List<TimeRecord> =
        timeRecordRepository.getRecordsIn(dayBoundary.dayRange(epochDay))

    // ---------------- 主路径 ----------------

    @Test
    fun `a confirmed app becomes a system record without confirmation`() = runTest {
        usage(knownApp, fromMinute = 600, toMinute = 660) // 14:00–15:00

        val result = service.analyzeDay(sept12)

        assertEquals(1, result.sessionCount)
        assertEquals(1, result.writtenCount)
        assertEquals(1, result.systemCount)
        assertEquals(0, result.suggestedCount)

        val record = records().single()
        assertEquals(RecordSource.SYSTEM, record.source)
        assertEquals(studyCategoryId, record.categoryId)
        assertEquals(javaActivityId, record.activityId)
        assertEquals(at(600), record.startMillis)
        assertEquals(at(660), record.endMillis)
        assertTrue("系统来源的记录不需要确认", !record.needsConfirmation)
    }

    @Test
    fun `an unknown app is recorded without asking, and reported for mapping`() = runTest {
        usage(unknownApp, fromMinute = 600, toMinute = 630)

        val result = service.analyzeDay(sept12)

        // 模块 20 起，未归类的记录直接落库、不进待确认 ——
        // 「你用了这个 App 30 分钟」是事实，问 47 次同样的问题只会让人烦
        assertEquals(0, result.suggestedCount)
        assertEquals(1, result.systemCount)
        assertEquals(listOf(unknownApp), result.unresolvedPackages)

        val record = records().single()
        assertEquals(RecordSource.SYSTEM, record.source)
        assertNull(record.categoryId)
        assertTrue("不该再逐条问用户", !record.needsConfirmation)
        // 未归类时 App 名进备注，时间轴才有的可显示
        assertEquals(unknownApp, record.note)
        // 登记成未确定归属的映射，用户才能在映射界面里给它归类
        assertNotNull(appMappingHost.getMapping(unknownApp))
    }

    @Test
    fun `unresolved apps are reported by time spent descending`() = runTest {
        usage("com.small", fromMinute = 600, toMinute = 610) // 10 分钟
        usage("com.big", fromMinute = 620, toMinute = 700) // 80 分钟

        val result = service.analyzeDay(sept12)

        assertEquals(listOf("com.big", "com.small"), result.unresolvedPackages)
    }

    @Test
    fun `several sessions produce several records`() = runTest {
        usage(knownApp, fromMinute = 600, toMinute = 660)
        usage(unknownApp, fromMinute = 660, toMinute = 690)

        val result = service.analyzeDay(sept12)

        assertEquals(2, result.sessionCount)
        assertEquals(2, result.writtenCount)
        assertEquals(2, result.systemCount)
        assertEquals(0, result.suggestedCount)
        assertEquals(2, records().size)
    }

    // ---------------- 手动记录优先 ----------------

    @Test
    fun `a manual record is never overwritten and blocked sessions are reported`() = runTest {
        // 用户先自己记了 14:00–14:30 的「Java」
        timeRecordRepository.createManualRecord(
            TimeRecordDraft(
                startMillis = at(600),
                endMillis = at(630),
                categoryId = studyCategoryId,
                activityId = javaActivityId,
            )
        )
        // 系统在同一时段采到一段使用记录
        usage(unknownApp, fromMinute = 600, toMinute = 630)

        val result = service.analyzeDay(sept12)

        assertEquals(0, result.writtenCount)
        assertEquals(1, result.blockedByManualCount)

        val record = records().single()
        assertEquals("手动记录必须原样保留", RecordSource.MANUAL, record.source)
        assertEquals(studyCategoryId, record.categoryId)
    }

    @Test
    fun `re-analysis does not touch manual or confirmed records`() = runTest {
        usage(knownApp, fromMinute = 600, toMinute = 660)
        service.analyzeDay(sept12)

        // 用户确认了这一条（MERGED），并把时间改短了一点
        val systemRecord = records().single()
        timeRecordRepository.updateRecord(
            systemRecord.id,
            TimeRecordDraft(
                startMillis = at(600),
                endMillis = at(640),
                categoryId = studyCategoryId,
                activityId = javaActivityId,
            ),
        )

        service.analyzeDay(sept12)

        val confirmed = records().single()
        assertEquals(RecordSource.MERGED, confirmed.source)
        assertEquals("用户改过的时间不能被系统分析覆盖", at(640), confirmed.endMillis)
    }

    // ---------------- 重新分析 ----------------

    @Test
    fun `re-analysis replaces system records instead of duplicating them`() = runTest {
        usage(knownApp, fromMinute = 600, toMinute = 660)
        service.analyzeDay(sept12)
        service.analyzeDay(sept12)
        service.analyzeDay(sept12)

        assertEquals(1, records().size)
    }

    @Test
    fun `changing the mapping takes effect on the next analysis`() = runTest {
        // 这是「先清空再写入」而不是「靠幂等键增量写入」的真正原因：
        // 增量写入会命中幂等键直接跳过，用户归类之后时间轴仍然显示未归类，
        // 他会以为这个功能坏了。
        usage(unknownApp, fromMinute = 600, toMinute = 630)
        service.analyzeDay(sept12)
        assertNull(records().single().categoryId)

        // 用户把它归类到「学习 / Java」
        appMappingHost.confirmMapping(unknownApp, studyCategoryId, javaActivityId)
        val result = service.analyzeDay(sept12)

        val record = records().single()
        assertEquals(studyCategoryId, record.categoryId)
        assertEquals(javaActivityId, record.activityId)
        assertEquals(RecordSource.SYSTEM, record.source)
        assertEquals(1, result.systemCount)
    }

    @Test
    fun `a confirmed session survives re-analysis without being duplicated`() = runTest {
        usage(unknownApp, fromMinute = 600, toMinute = 630)
        service.analyzeDay(sept12)

        // 用户在确认页上把这段改成「学习 / Java」
        val suggested = records().single()
        timeRecordRepository.updateRecord(
            suggested.id,
            TimeRecordDraft(
                startMillis = suggested.startMillis,
                endMillis = suggested.endMillis,
                categoryId = studyCategoryId,
                activityId = javaActivityId,
            ),
        )

        service.analyzeDay(sept12)

        // 幂等键还在那条记录上，所以重新分析不会再多写一条重复的
        val all = records()
        assertEquals(1, all.size)
        assertEquals(RecordSource.MERGED, all.single().source)
    }

    // ---------------- 边界 ----------------

    @Test
    fun `an app still in use is recorded up to now not to the end of the day`() = runTest {
        // 只有 RESUMED，没有 PAUSED：用户此刻还在用。
        // 如果会话直接算到窗口结束（明天 04:00），这一条会变成 14 小时的记录。
        usageEventHost.appendEvents(
            listOf(
                UsageEvent(
                    packageName = knownApp,
                    eventType = UsageEventTypes.ACTIVITY_RESUMED,
                    timestampMillis = at(600),
                    eventKey = UsageEvent.buildEventKey(
                        UsageEventTypes.ACTIVITY_RESUMED, at(600), knownApp,
                    ),
                )
            )
        )

        val result = service.analyzeDay(sept12)

        assertEquals(fixedNow, result.analyzedRange.endMillisExclusive)
        val record = records().single()
        assertEquals(fixedNow, record.endMillis)
    }

    @Test
    fun `no events means nothing is written`() = runTest {
        val result = service.analyzeDay(sept12)

        assertEquals(0, result.sessionCount)
        assertEquals(0, result.writtenCount)
        assertTrue(!result.hasAnyEvent)
        assertTrue(records().isEmpty())
    }

    @Test
    fun `a session that crosses 4am is only recorded for this day's part`() = runTest {
        // 前一天 03:50 打开、04:10 关掉：本窗口内只应留下 04:00–04:10
        usageEventHost.appendEvents(
            listOf(
                UsageEvent(
                    packageName = knownApp,
                    eventType = UsageEventTypes.ACTIVITY_RESUMED,
                    timestampMillis = dayStart - 10 * 60_000L,
                    eventKey = UsageEvent.buildEventKey(
                        UsageEventTypes.ACTIVITY_RESUMED, dayStart - 10 * 60_000L, knownApp,
                    ),
                ),
                UsageEvent(
                    packageName = knownApp,
                    eventType = UsageEventTypes.ACTIVITY_PAUSED,
                    timestampMillis = at(10),
                    eventKey = UsageEvent.buildEventKey(
                        UsageEventTypes.ACTIVITY_PAUSED, at(10), knownApp,
                    ),
                ),
            )
        )

        service.analyzeDay(sept12)

        val record = records().single()
        assertEquals(dayStart, record.startMillis)
        assertEquals(at(10), record.endMillis)
    }

    @Test
    fun `analysing a future day writes nothing`() = runTest {
        usage(knownApp, fromMinute = 600, toMinute = 660)

        val result = service.analyzeDay(sept12 + 1)

        assertEquals(0, result.sessionCount)
        assertTrue(records().isEmpty())
    }

    @Test
    fun `a day older than the analyzable window is left completely alone`() = runTest {
        // 这条守卫是为了让「原始事件保留策略」安全：超过保留期的事件会被清理，
        // 而重新分析的第一步是「清空这一天的系统记录再重建」——
        // 两者撞在一起就是「清空了、已经没有原始事件可重建」，那一天的记录会凭空消失。
        val oldDay = sept12 - 40
        val oldWindow = dayBoundary.dayRange(oldDay)
        timeRecordRepository.saveSystemRecord(
            TimeRecordDraft(
                startMillis = oldWindow.startMillis + 10 * hour,
                endMillis = oldWindow.startMillis + 11 * hour,
                categoryId = studyCategoryId,
                activityId = javaActivityId,
                externalKey = "usage|$oldDay|old",
            ),
            RecordSource.SYSTEM,
        )
        assertEquals(1, records(oldDay).size)

        val result = service.analyzeDay(oldDay)

        assertEquals(0, result.sessionCount)
        assertEquals("旧日子的记录不能被清空", 1, records(oldDay).size)
    }

    @Test
    fun `yesterday is still analysable`() = runTest {
        // 边界要留够：凌晨的使用属于昨天，昨天必须照常分析
        // at(-600) = 9/12 04:00 往前 10 小时 = 9/11 18:00，落在昨天的窗口里
        usage(knownApp, fromMinute = -600, toMinute = -540)

        val result = service.analyzeDay(sept12 - 1)

        assertEquals(1, result.sessionCount)
        assertEquals(1, records(sept12 - 1).size)
    }

    @Test
    fun `stale system records are cleared when the raw events are gone`() = runTest {
        usage(knownApp, fromMinute = 600, toMinute = 660)
        service.analyzeDay(sept12)
        assertEquals(1, records().size)

        // 原始事件被保留策略清理掉了（系统 UsageStats 的历史本来就不可靠）
        usageEventHost.pruneOlderThan(dayBoundary.dayRange(sept12).endMillisExclusive)

        val result = service.analyzeDay(sept12)

        assertEquals(0, result.sessionCount)
        assertTrue("重新分析必须清掉已经不成立的系统记录", records().isEmpty())
    }

    @Test
    fun `short app switches are not recorded`() = runTest {
        // 30 秒的切走不产生记录（也不并进相邻会话）
        usage(knownApp, fromMinute = 600, toMinute = 660)
        usage(unknownApp, fromMinute = 660, toMinute = 660)

        val result = service.analyzeDay(sept12)

        assertEquals(1, result.sessionCount)
        assertEquals(1, records().size)
    }

    @Test
    fun `the analysed range starts at the logical day boundary`() = runTest {
        val result = service.analyzeDay(sept12)

        assertEquals(dayStart, result.analyzedRange.startMillis)
    }

    // ---------------- 熄屏（模块 23） ----------------

    /** 摆一段熄屏：从 [fromMinute] 熄到 [toMinute]。 */
    private suspend fun screenOff(fromMinute: Long, toMinute: Long) {
        usageEventHost.appendEvents(
            listOf(
                UsageEvent(
                    packageName = null,
                    eventType = UsageEventTypes.SCREEN_NON_INTERACTIVE,
                    timestampMillis = at(fromMinute),
                    eventKey = UsageEvent.buildEventKey(
                        UsageEventTypes.SCREEN_NON_INTERACTIVE, at(fromMinute), null,
                    ),
                ),
                UsageEvent(
                    packageName = null,
                    eventType = UsageEventTypes.SCREEN_INTERACTIVE,
                    timestampMillis = at(toMinute),
                    eventKey = UsageEvent.buildEventKey(
                        UsageEventTypes.SCREEN_INTERACTIVE, at(toMinute), null,
                    ),
                ),
            )
        )
    }

    @Test
    fun `a long screen-off stretch becomes a record`() = runTest {
        // 手机没在用的那段时间以前是空白（未知），现在是一条记录（已知）
        usage(knownApp, fromMinute = 600, toMinute = 660)
        screenOff(fromMinute = 700, toMinute = 820) // 两小时

        service.analyzeDay(sept12)

        val screenOffRecord = records().single { it.categoryId == screenOffCategoryId }
        assertEquals(at(700), screenOffRecord.startMillis)
        assertEquals(at(820), screenOffRecord.endMillis)
        assertEquals(RecordSource.SYSTEM, screenOffRecord.source)
        assertTrue("熄屏是事实，不需要确认", !screenOffRecord.needsConfirmation)
    }

    @Test
    fun `a screen-off shorter than a minute is not worth a record`() = runTest {
        // 掏口袋、看时间的几秒钟熄屏写成记录只会变成碎片
        usage(knownApp, fromMinute = 600, toMinute = 660)
        screenOff(fromMinute = 700, toMinute = 700) // 立刻又亮了

        service.analyzeDay(sept12)

        assertTrue(records().none { it.categoryId == screenOffCategoryId })
    }

    @Test
    fun `a few minutes of screen-off is worth a record now`() = runTest {
        // 门槛从 5 分钟降到 1 分钟的理由（真机数据）：09-12「黑屏 ≥30 秒」共 10h14m，
        // 而「黑屏 ≥5 分钟」只有 6h55m —— 中间三个多小时因为被亮屏打断而整段消失了，
        // 在时间轴上就是用户问的「间隔中的时间也没有进行分类，而是直接断开」。
        usage(knownApp, fromMinute = 600, toMinute = 660)
        screenOff(fromMinute = 700, toMinute = 703) // 三分钟

        service.analyzeDay(sept12)

        val screenOffRecord = records().single { it.categoryId == screenOffCategoryId }
        assertEquals(at(700), screenOffRecord.startMillis)
        assertEquals(at(703), screenOffRecord.endMillis)
    }

    @Test
    fun `screen-off records do not appear in the app ranking`() = runTest {
        // 幂等键用 screen-off 前缀而不是 usage —— 否则应用排行里会冒出一个
        // 名叫「screen-off」的应用
        usage(knownApp, fromMinute = 600, toMinute = 660)
        screenOff(fromMinute = 700, toMinute = 820)

        service.analyzeDay(sept12)

        val screenOffRecord = records().single { it.categoryId == screenOffCategoryId }
        assertTrue(UsageRecordKeys.isScreenOffKey(screenOffRecord.externalKey))
        assertNull(UsageRecordKeys.packageNameOf(screenOffRecord.externalKey))
        assertTrue(!UsageRecordKeys.isUsageKey(screenOffRecord.externalKey))
    }

    @Test
    fun `a screen-off stretch is clipped to the day window`() = runTest {
        // 从 03:00 熄到 05:00（跨 4:00 分界线）：本日只留 04:00–05:00 那一段，
        // 与其它记录完全同一套口径
        usageEventHost.appendEvents(
            listOf(
                UsageEvent(
                    packageName = null,
                    eventType = UsageEventTypes.SCREEN_NON_INTERACTIVE,
                    timestampMillis = dayStart - hour,
                    eventKey = UsageEvent.buildEventKey(
                        UsageEventTypes.SCREEN_NON_INTERACTIVE, dayStart - hour, null,
                    ),
                ),
                UsageEvent(
                    packageName = null,
                    eventType = UsageEventTypes.SCREEN_INTERACTIVE,
                    timestampMillis = dayStart + hour,
                    eventKey = UsageEvent.buildEventKey(
                        UsageEventTypes.SCREEN_INTERACTIVE, dayStart + hour, null,
                    ),
                ),
            )
        )

        service.analyzeDay(sept12)

        val screenOffRecord = records().single { it.categoryId == screenOffCategoryId }
        assertEquals(dayStart, screenOffRecord.startMillis)
        assertEquals(dayStart + hour, screenOffRecord.endMillis)
    }

    @Test
    fun `a manual record still wins over a screen-off stretch`() = runTest {
        // 用户已经手记了「14:00–16:00」：系统不该拿熄屏去盖掉它 ——
        // 手动记录优先是产品硬规则，熄屏也不例外
        timeRecordRepository.createManualRecord(
            TimeRecordDraft(
                startMillis = at(700),
                endMillis = at(820),
                categoryId = studyCategoryId,
                activityId = javaActivityId,
            )
        )
        screenOff(fromMinute = 700, toMinute = 820)

        val result = service.analyzeDay(sept12)

        assertEquals(1, result.blockedByManualCount)
        val manual = records().single()
        assertEquals(RecordSource.MANUAL, manual.source)
        assertEquals(studyCategoryId, manual.categoryId)
    }

    @Test
    fun `re-analysis does not duplicate screen-off records`() = runTest {
        usage(knownApp, fromMinute = 600, toMinute = 660)
        screenOff(fromMinute = 700, toMinute = 820)

        service.analyzeDay(sept12)
        service.analyzeDay(sept12)

        assertEquals(1, records().count { it.categoryId == screenOffCategoryId })
    }

    @Test
    fun `screen-off is skipped when the built-in category is gone`() = runTest {
        // 用户把「熄屏」删掉或归档了：功能降级成「不写熄屏记录」，
        // 而不是写一条指向不存在分类的记录（那会被仓库直接拒绝）
        runBlocking { categoryDao.archive(screenOffCategoryId, dayBoundary.nowMillis()) }
        usage(knownApp, fromMinute = 600, toMinute = 660)
        screenOff(fromMinute = 700, toMinute = 820)

        service.analyzeDay(sept12)

        assertTrue(records().none { it.categoryId == screenOffCategoryId })
        // 其它记录照常
        assertEquals(1, records().size)
    }

    @Test
    fun `the screen-off threshold must exceed the merge window`() {
        // 不变式：否则同一段时间会被算两次（既并进上一个会话，又写成熄屏记录）
        val failure = runCatching {
            UsageAnalysisService(
                usageEventRepository = usageEventHost,
                appMappingRepository = appMappingHost,
                timeRecordRepository = timeRecordRepository,
                dayBoundary = dayBoundary,
                categoryRepository = CategoryRepositoryImpl(categoryDao, dayBoundary),
                screenOffMinMillis = 10_000L, // 小于合并窗口 30 秒
            )
        }.exceptionOrNull()

        assertTrue("阈值小于合并窗口时必须直接拒绝构造", failure is IllegalArgumentException)
    }

    @Test
    fun `written records never straddle the day boundary`() = runTest {
        usage(knownApp, fromMinute = 600, toMinute = 660)
        usage(unknownApp, fromMinute = 660, toMinute = 700)

        service.analyzeDay(sept12)

        val window = dayBoundary.dayRange(sept12)
        records().forEach { record ->
            assertTrue(
                "系统记录必须落在本逻辑日窗口内：${record.startMillis}..${record.endMillis}",
                record.startMillis >= window.startMillis && record.endMillis <= window.endMillisExclusive,
            )
        }
    }
}
