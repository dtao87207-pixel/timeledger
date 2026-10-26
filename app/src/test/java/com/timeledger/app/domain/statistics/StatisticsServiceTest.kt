package com.timeledger.app.domain.statistics

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.data.local.entity.ActivityEntity
import com.timeledger.app.data.local.entity.CategoryEntity
import com.timeledger.app.data.repository.FakeActivityDao
import com.timeledger.app.data.repository.FakeCategoryDao
import com.timeledger.app.data.repository.FakeTimeRecordDao
import com.timeledger.app.data.repository.TimeRecordRepositoryImpl
import com.timeledger.app.domain.model.TimeRecordDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 统计服务测试 —— 验证「该查哪一段时间」这件事。
 *
 * 与目标服务同样的风险：周统计如果只查当天，数字会偏小，而界面上看不出异常。
 * 这里用真实仓库（配合内存 DAO）串起来跑，确认取数窗口覆盖了整周 / 整月。
 */
class StatisticsServiceTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private val fixedNow = Instant.parse("2026-09-12T06:00:00Z").toEpochMilli()
    private val dayBoundary = DayBoundary(zoneId = shanghai, nowProvider = { fixedNow })

    private val hour = 3600_000L
    private val sept6: Long = LocalDate.of(2026, 9, 6).toEpochDay()   // 上周日
    private val sept7: Long = LocalDate.of(2026, 9, 7).toEpochDay()   // 本周一
    private val sept12: Long = LocalDate.of(2026, 9, 12).toEpochDay() // 本周六（参考日）
    private val sept25: Long = LocalDate.of(2026, 9, 25).toEpochDay()

    private lateinit var categoryDao: FakeCategoryDao
    private lateinit var activityDao: FakeActivityDao
    private lateinit var timeRecordDao: FakeTimeRecordDao
    private lateinit var repository: TimeRecordRepositoryImpl
    private lateinit var service: StatisticsService

    private var studyCategoryId: Long = 0L
    private var funCategoryId: Long = 0L
    private var javaActivityId: Long = 0L

    @Before
    fun setUp() {
        categoryDao = FakeCategoryDao()
        activityDao = FakeActivityDao()
        timeRecordDao = FakeTimeRecordDao()
        repository = TimeRecordRepositoryImpl(
            timeRecordDao = timeRecordDao,
            categoryDao = categoryDao,
            activityDao = activityDao,
            dayBoundary = dayBoundary,
        )
        service = StatisticsService(
            timeRecordRepository = repository,
            calculator = StatisticsCalculator(dayBoundary),
        )

        runBlocking {
            studyCategoryId = categoryDao.insert(CategoryEntity(name = "学习", sortOrder = 0))
            funCategoryId = categoryDao.insert(CategoryEntity(name = "娱乐", sortOrder = 1))
            javaActivityId = activityDao.insert(
                ActivityEntity(categoryId = studyCategoryId, name = "Java")
            )
        }
    }

    /**
     * 注意参数用「默认值」而不是 `categoryId ?: studyCategoryId`：
     * 后者无法表达「显式传 null」，会导致只想记分类、不记活动的记录
     * 被悄悄补上一个活动，进而因「活动与分类不一致」被仓库拒绝。
     */
    private suspend fun addRecord(
        day: Long,
        hoursFrom4am: Double,
        durationHours: Double,
        categoryId: Long? = studyCategoryId,
        activityId: Long? = javaActivityId,
    ) {
        val dayStart = dayBoundary.dayRange(day).startMillis
        repository.createManualRecord(
            TimeRecordDraft(
                startMillis = dayStart + (hoursFrom4am * hour).toLong(),
                endMillis = dayStart + ((hoursFrom4am + durationHours) * hour).toLong(),
                categoryId = categoryId,
                activityId = activityId,
            )
        )
    }

    @Test
    fun `week statistics cover the whole week not just the reference day`() = runTest {
        addRecord(sept7, 5.0, 2.0)   // 周一 2 小时
        addRecord(sept12, 5.0, 3.0)  // 周六 3 小时

        val stats = service.observeStatistics(StatisticsPeriod.WEEK, sept12).first()

        assertEquals(7, stats.dayCount)
        assertEquals(5 * hour, stats.totalConfirmedMillis)
        assertEquals(2 * hour, stats.days.first { it.epochDay == sept7 }.breakdown.confirmedMillis)
        assertEquals(3 * hour, stats.days.first { it.epochDay == sept12 }.breakdown.confirmedMillis)
    }

    @Test
    fun `month statistics cover every week of the month`() = runTest {
        addRecord(LocalDate.of(2026, 9, 2).toEpochDay(), 5.0, 2.0)
        addRecord(sept25, 5.0, 1.5)

        val stats = service.observeStatistics(StatisticsPeriod.MONTH, sept12).first()

        assertEquals(30, stats.dayCount)
        // 注意用 toLong()：assertEquals(Double, Long) 会走对象比较，永远不相等
        assertEquals((3.5 * hour).toLong(), stats.totalConfirmedMillis)
        assertEquals(2, stats.recordedDayCount)
    }

    @Test
    fun `records from adjacent periods are excluded`() = runTest {
        addRecord(sept6, 5.0, 4.0)   // 上周日，属于上一周
        addRecord(sept7, 5.0, 1.0)   // 本周一

        val stats = service.observeStatistics(StatisticsPeriod.WEEK, sept12).first()

        assertEquals(1 * hour, stats.totalConfirmedMillis)
        assertEquals(1, stats.recordedDayCount)
    }

    @Test
    fun `a record crossing the period edge is counted only for the overlapping part`() = runTest {
        // 周一 03:00–05:00：跨过周一 04:00 这条周分界线，
        // 上一周只该拿到 03:00–04:00 那 1 小时，本周拿到 04:00–05:00 那 1 小时
        val mondayStart = dayBoundary.dayRange(sept7).startMillis
        repository.createManualRecord(
            TimeRecordDraft(
                startMillis = mondayStart - 1 * hour,
                endMillis = mondayStart + 1 * hour,
                categoryId = studyCategoryId,
                activityId = javaActivityId,
            )
        )

        val thisWeek = service.observeStatistics(StatisticsPeriod.WEEK, sept12).first()
        val lastWeek = service.observeStatistics(StatisticsPeriod.WEEK, sept7 - 1).first()

        assertEquals(1 * hour, thisWeek.totalConfirmedMillis)
        assertEquals(1 * hour, lastWeek.totalConfirmedMillis)
        // 两周加起来正好是记录总时长，不重不漏
        assertEquals(2 * hour, thisWeek.totalConfirmedMillis + lastWeek.totalConfirmedMillis)
    }

    @Test
    fun `statistics are recomputed after a record changes`() = runTest {
        addRecord(sept12, 5.0, 1.0)
        val before = service.observeStatistics(StatisticsPeriod.WEEK, sept12).first()
        assertEquals(1 * hour, before.totalConfirmedMillis)

        addRecord(sept12, 10.0, 2.0, categoryId = funCategoryId, activityId = null)
        val after = service.observeStatistics(StatisticsPeriod.WEEK, sept12).first()

        assertEquals(3 * hour, after.totalConfirmedMillis)
        assertEquals(1 * hour, after.total.durationOfCategory(studyCategoryId))
        assertEquals(2 * hour, after.total.durationOfCategory(funCategoryId))
        assertEquals(1, after.recordedDayCount)
    }

    @Test
    fun `empty statistics report zero and full unrecorded window`() = runTest {
        val stats = service.observeStatistics(StatisticsPeriod.WEEK, sept12).first()

        assertEquals(0L, stats.totalConfirmedMillis)
        assertEquals(0L, stats.averageConfirmedPerDayMillis)
        assertEquals(stats.window.durationMillis, stats.total.unrecordedMillis)
        assertEquals(0, stats.recordedDayCount)
    }
}
