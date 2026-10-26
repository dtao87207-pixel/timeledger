package com.timeledger.app.domain.goal

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.core.time.TimeBoundary
import com.timeledger.app.data.local.entity.ActivityEntity
import com.timeledger.app.data.local.entity.CategoryEntity
import com.timeledger.app.data.repository.FakeActivityDao
import com.timeledger.app.data.repository.FakeCategoryDao
import com.timeledger.app.data.repository.FakeTimeRecordDao
import com.timeledger.app.data.repository.TimeRecordRepositoryImpl
import com.timeledger.app.domain.model.Goal
import com.timeledger.app.domain.model.GoalPeriod
import com.timeledger.app.domain.model.GoalType
import com.timeledger.app.domain.model.TimeRecordDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 目标进度服务测试 —— 与模块1的仓库实现串起来跑，验证「该查哪一段时间」这件事。
 *
 * 这是最容易出错、又最难在界面上发现的一环：周目标如果只查当天记录，
 * 数字会偏小，而界面上看不出任何异常，只会让人以为「我这周学得真少」。
 */
class GoalProgressServiceTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private val fixedNow = Instant.parse("2026-09-12T06:00:00Z").toEpochMilli()
    private val dayBoundary = DayBoundary(zoneId = shanghai, nowProvider = { fixedNow })

    /** 2026-09-12 是周六，因此本周周一是 09-07，周目标窗口横跨了整整一周。 */
    private val referenceDay: Long = LocalDate.of(2026, 9, 12).toEpochDay()
    private val weekStart: Long = TimeBoundary.weekStartEpochDay(referenceDay)

    private val hour = 3600_000L

    private lateinit var categoryDao: FakeCategoryDao
    private lateinit var activityDao: FakeActivityDao
    private lateinit var timeRecordDao: FakeTimeRecordDao
    private lateinit var timeRecordRepository: TimeRecordRepositoryImpl
    private lateinit var goalRepository: FakeGoalRepository
    private lateinit var service: GoalProgressService

    private var studyCategoryId: Long = 0L
    private var javaActivityId: Long = 0L

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
        goalRepository = FakeGoalRepository()
        service = GoalProgressService(
            goalRepository = goalRepository,
            timeRecordRepository = timeRecordRepository,
            calculator = GoalProgressCalculator(dayBoundary),
        )

        runBlocking {
            studyCategoryId = categoryDao.insert(CategoryEntity(name = "学习", sortOrder = 0))
            javaActivityId = activityDao.insert(
                ActivityEntity(categoryId = studyCategoryId, name = "Java")
            )
            // 本周一：Java 5 小时
            val monday = dayBoundary.dayRange(weekStart)
            timeRecordRepository.createManualRecord(
                TimeRecordDraft(
                    startMillis = monday.startMillis + hour,
                    endMillis = monday.startMillis + 6 * hour,
                    categoryId = studyCategoryId,
                    activityId = javaActivityId,
                )
            )
            // 参考日（周六）：Java 2 小时
            val saturday = dayBoundary.dayRange(referenceDay)
            timeRecordRepository.createManualRecord(
                TimeRecordDraft(
                    startMillis = saturday.startMillis + hour,
                    endMillis = saturday.startMillis + 3 * hour,
                    categoryId = studyCategoryId,
                    activityId = javaActivityId,
                )
            )
        }
    }

    private fun timeGoal(
        targetSeconds: Double,
        period: GoalPeriod,
        startEpochDay: Long? = null,
    ) = Goal(
        id = 1L,
        title = "Java",
        type = GoalType.TIME,
        period = period,
        targetValue = targetSeconds,
        categoryId = studyCategoryId,
        activityId = javaActivityId,
        startEpochDay = startEpochDay,
    )

    @Test
    fun `week goal counts records from the whole week not just the reference day`() = runTest {
        goalRepository.setGoals(listOf(timeGoal(15 * 3600.0, GoalPeriod.WEEK)))

        val progress = service.observeDayProgress(referenceDay).first()

        assertEquals(1, progress.size)
        // 周一 5 小时 + 周六 2 小时 = 7 小时
        assertEquals(7 * 3600.0, progress.single().achieved, 0.001)
        assertEquals(2, progress.single().contributingRecordCount)
    }

    @Test
    fun `day goal only counts the reference day`() = runTest {
        goalRepository.setGoals(listOf(timeGoal(3 * 3600.0, GoalPeriod.DAY)))

        val progress = service.observeDayProgress(referenceDay).first()

        assertEquals(2 * 3600.0, progress.single().achieved, 0.001)
    }

    @Test
    fun `week goal created mid-week clamps the window and ignores earlier days`() = runTest {
        // 周六才建的目标：窗口从周六 04:00 开始，周一那 5 小时不算在内。
        // V1 刻意不做「目标值折算」—— 只裁剪统计窗口，目标值仍然是 15 小时。
        goalRepository.setGoals(
            listOf(timeGoal(15 * 3600.0, GoalPeriod.WEEK, startEpochDay = referenceDay))
        )

        val progress = service.observeDayProgress(referenceDay).first()

        assertEquals(2 * 3600.0, progress.single().achieved, 0.001)
        assertEquals(1, progress.single().contributingRecordCount)
    }

    @Test
    fun `both day and week goals are served by the same union window`() = runTest {
        goalRepository.setGoals(
            listOf(
                timeGoal(3 * 3600.0, GoalPeriod.DAY).copy(id = 1L, title = "今日 Java"),
                timeGoal(15 * 3600.0, GoalPeriod.WEEK).copy(id = 2L, title = "本周 Java"),
            )
        )

        val progress = service.observeDayProgress(referenceDay).first()

        assertEquals(2, progress.size)
        val daily = progress.first { it.goal.period == GoalPeriod.DAY }
        val weekly = progress.first { it.goal.period == GoalPeriod.WEEK }
        // 同一条时间轴算出来的两个口径，日的一定不超过周的
        assertEquals(2 * 3600.0, daily.achieved, 0.001)
        assertEquals(7 * 3600.0, weekly.achieved, 0.001)
        assertTrue(daily.achieved <= weekly.achieved)
    }

    @Test
    fun `no applicable goal yields an empty list`() = runTest {
        goalRepository.setGoals(emptyList())
        assertTrue(service.observeDayProgress(referenceDay).first().isEmpty())
    }

    @Test
    fun `goal removed later stops being reported`() = runTest {
        goalRepository.setGoals(listOf(timeGoal(3 * 3600.0, GoalPeriod.DAY)))
        assertEquals(1, service.observeDayProgress(referenceDay).first().size)

        // 停用/删除后，Flow 必须重新发射并给出新的结果，不能留着旧进度
        goalRepository.setGoals(emptyList())
        assertTrue(service.observeDayProgress(referenceDay).first().isEmpty())
    }

    @Test
    fun `other days are unaffected by the reference day`() = runTest {
        goalRepository.setGoals(listOf(timeGoal(3 * 3600.0, GoalPeriod.DAY)))

        // 周日（参考日的下一天）没有任何记录
        val nextDay = referenceDay + 1
        val progress = service.observeDayProgress(nextDay).first()

        assertEquals(1, progress.size)
        assertEquals(0.0, progress.single().achieved, 0.001)
    }

    // ---------------- 一段时间内每天的进度（日历视图与目标趋势） ----------------

    @Test
    fun `range progress reports every day including days without records`() = runTest {
        goalRepository.setGoals(listOf(timeGoal(3 * 3600.0, GoalPeriod.DAY)))

        val range = service.observeRangeProgress(weekStart, referenceDay + 1).first()

        assertEquals(7, range.size)
        assertEquals(weekStart, range.first().epochDay)          // 周一
        assertEquals(referenceDay + 1, range.last().epochDay)    // 周日
        // 每天的条目都必须存在，否则日历上周三会凭空少一格
        assertEquals(
            (weekStart..referenceDay + 1).toList(),
            range.map { it.epochDay },
        )

        // 周一 5 小时 ≥ 3 小时 → 达标
        val monday = range.first { it.epochDay == weekStart }
        assertEquals(5 * 3600.0, monday.progress.single().achieved, 0.001)
        assertTrue(monday.progress.single().isAchieved)
        assertEquals(1, monday.achievedGoalCount)
        assertEquals(1.0, monday.achievedRatio!!, 0.0001)

        // 周六 2 小时 < 3 小时 → 未达标
        val saturday = range.first { it.epochDay == referenceDay }
        assertEquals(2 * 3600.0, saturday.progress.single().achieved, 0.001)
        assertFalse(saturday.progress.single().isAchieved)
        assertEquals(0, saturday.achievedGoalCount)

        // 周三没有任何记录
        val wednesday = range.first { it.epochDay == weekStart + 2 }
        assertEquals(0.0, wednesday.progress.single().achieved, 0.001)
        assertEquals(0.0, wednesday.achievedRatio!!, 0.0001)
    }

    @Test
    fun `range progress is empty when no goal applies to any day`() = runTest {
        goalRepository.setGoals(emptyList())
        assertTrue(service.observeRangeProgress(weekStart, referenceDay).first().isEmpty())
    }

    @Test
    fun `range progress rejects a reversed date range`() = runTest {
        goalRepository.setGoals(listOf(timeGoal(3 * 3600.0, GoalPeriod.DAY)))
        val error = runCatching {
            service.observeRangeProgress(referenceDay, weekStart)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
