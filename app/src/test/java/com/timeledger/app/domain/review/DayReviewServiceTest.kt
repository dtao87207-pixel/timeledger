package com.timeledger.app.domain.review

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.data.local.entity.ActivityEntity
import com.timeledger.app.data.local.entity.CategoryEntity
import com.timeledger.app.data.repository.FakeActivityDao
import com.timeledger.app.data.repository.FakeCategoryDao
import com.timeledger.app.data.repository.FakeTimeRecordDao
import com.timeledger.app.data.repository.TimeRecordRepositoryImpl
import com.timeledger.app.domain.goal.FakeGoalRepository
import com.timeledger.app.domain.goal.GoalProgressCalculator
import com.timeledger.app.domain.goal.GoalProgressService
import com.timeledger.app.domain.model.Goal
import com.timeledger.app.domain.model.GoalPeriod
import com.timeledger.app.domain.model.GoalType
import com.timeledger.app.domain.model.PlanBlock
import com.timeledger.app.domain.model.RecordSource
import com.timeledger.app.domain.model.TimeRecordDraft
import com.timeledger.app.domain.model.UsageEvent
import com.timeledger.app.domain.plan.PlanBlockExpander
import com.timeledger.app.domain.usage.FakeUsageEventHost
import com.timeledger.app.domain.usage.UsageEventTypes
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
 * 每日复盘服务测试 —— 与真实的仓库实现（配合内存 DAO）串起来跑。
 *
 * 这是模块 4 的收口测试：验证「记录 + 计划 + 目标」三路数据能被正确合成一份复盘，
 * 而且**每次读都是现算的**（改了记录或计划，复盘必须跟着变）。
 */
class DayReviewServiceTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private val fixedNow = Instant.parse("2026-09-12T06:00:00Z").toEpochMilli()
    private val dayBoundary = DayBoundary(zoneId = shanghai, nowProvider = { fixedNow })

    /** 2026-09-12 是周六。 */
    private val saturday: Long = LocalDate.of(2026, 9, 12).toEpochDay()

    private val minute = 60_000L
    private val hour = 3600_000L
    private val saturdayWindow = dayBoundary.dayRange(saturday)

    /** 周六 04:00 之后的第 N 小时。 */
    private fun at(hoursFrom4am: Double): Long =
        saturdayWindow.startMillis + (hoursFrom4am * hour).toLong()

    private lateinit var categoryDao: FakeCategoryDao
    private lateinit var activityDao: FakeActivityDao
    private lateinit var timeRecordDao: FakeTimeRecordDao
    private lateinit var timeRecordRepository: TimeRecordRepositoryImpl
    private lateinit var planRepository: FakePlanBlockRepository
    private lateinit var goalRepository: FakeGoalRepository
    private lateinit var service: DayReviewService

    /** 原始事件：只用来给空白块写解释（模块 24）。 */
    private val usageEventHost = FakeUsageEventHost()

    private var studyCategoryId: Long = 0L
    private var funCategoryId: Long = 0L
    private var javaActivityId: Long = 0L
    private var bilibiliActivityId: Long = 0L

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
        planRepository = FakePlanBlockRepository()
        goalRepository = FakeGoalRepository()

        val expander = PlanBlockExpander(dayBoundary)
        val goalProgressService = GoalProgressService(
            goalRepository = goalRepository,
            timeRecordRepository = timeRecordRepository,
            calculator = GoalProgressCalculator(dayBoundary),
        )
        service = DayReviewService(
            timeRecordRepository = timeRecordRepository,
            planBlockRepository = planRepository,
            goalProgressService = goalProgressService,
            planBlockExpander = expander,
            dayBoundary = dayBoundary,
            usageEventRepository = usageEventHost,
        )

        runBlocking {
            studyCategoryId = categoryDao.insert(CategoryEntity(name = "学习", sortOrder = 0))
            funCategoryId = categoryDao.insert(CategoryEntity(name = "娱乐", sortOrder = 1))
            javaActivityId = activityDao.insert(
                ActivityEntity(categoryId = studyCategoryId, name = "Java")
            )
            bilibiliActivityId = activityDao.insert(
                ActivityEntity(categoryId = funCategoryId, name = "B站")
            )
        }
    }

    private fun javaPlan() = PlanBlock(
        id = 1L,
        title = "Java",
        dateEpochDay = null,
        dayOfWeek = 6, // 周六
        startMinuteOfDay = 9 * 60,       // 09:00
        endMinuteOfDay = 10 * 60 + 40,   // 10:40
        categoryId = studyCategoryId,
        activityId = javaActivityId,
    )

    private suspend fun addManualRecord(
        startHoursFrom4am: Double,
        endHoursFrom4am: Double,
        categoryId: Long = studyCategoryId,
        activityId: Long = javaActivityId,
    ) {
        timeRecordRepository.createManualRecord(
            TimeRecordDraft(
                startMillis = at(startHoursFrom4am),
                endMillis = at(endHoursFrom4am),
                categoryId = categoryId,
                activityId = activityId,
            )
        )
    }

    private fun javaDayGoal(targetHours: Double) = Goal(
        id = 1L,
        title = "Java",
        type = GoalType.TIME,
        period = GoalPeriod.DAY,
        targetValue = targetHours * 3600.0,
        categoryId = studyCategoryId,
        activityId = javaActivityId,
    )

    // ---------------- 三路数据合成 ----------------

    @Test
    fun `day review combines durations plan comparison and goals`() = runTest {
        planRepository.setPlans(listOf(javaPlan()))
        goalRepository.setGoals(listOf(javaDayGoal(3.0)))
        // 09:00–11:00 学 Java，比计划时段（09:00–10:40）长
        addManualRecord(startHoursFrom4am = 5.0, endHoursFrom4am = 7.0)

        val review = service.observeDayReview(saturday).first()

        // 窗口
        assertEquals(saturdayWindow, review.window)
        assertEquals(saturday, review.epochDay)

        // 时长分布
        assertEquals(2 * hour, review.durations.coveredMillis)
        assertEquals(2 * hour, review.durations.confirmedMillis)
        assertEquals(0L, review.durations.pendingMillis)
        assertEquals(
            listOf(DurationBucket(studyCategoryId, null, 2 * hour)),
            review.durations.byCategory,
        )
        assertEquals(
            listOf(DurationBucket(studyCategoryId, javaActivityId, 2 * hour)),
            review.durations.byActivity,
        )

        // 计划 vs 实际：记录被裁剪到计划时段内
        assertEquals(1, review.planComparisons.size)
        val comparison = review.planComparisons.single()
        assertEquals(100 * minute, comparison.plannedMillis)
        assertEquals(100 * minute, comparison.recordedMillis)
        assertEquals(100 * minute, comparison.matchedMillis)
        assertEquals(0L, comparison.unrecordedMillis)
        assertEquals(1.0, comparison.adherenceRatio, 0.0001)
        assertEquals(1.0, review.planAdherenceRatio, 0.0001)

        // 目标：2 小时 / 3 小时
        assertEquals(1, review.activeGoalCount)
        assertEquals(7200.0, review.goalProgress.single().achieved, 0.001)
        assertFalse(review.goalProgress.single().isAchieved)
        assertEquals(0, review.achievedGoalCount)

        assertEquals(0, review.pendingRecordCount)
        assertTrue(review.hasAnyData)
    }

    @Test
    fun `deviation inside the planned slot is visible in the review`() = runTest {
        planRepository.setPlans(listOf(javaPlan()))
        // 计划时段内先学 Java 40 分钟，再去看 B站 60 分钟
        addManualRecord(startHoursFrom4am = 5.0, endHoursFrom4am = 5.0 + 40.0 / 60.0)
        addManualRecord(
            startHoursFrom4am = 5.0 + 40.0 / 60.0,
            endHoursFrom4am = 6.0 + 40.0 / 60.0,
            categoryId = funCategoryId,
            activityId = bilibiliActivityId,
        )

        val review = service.observeDayReview(saturday).first()
        val comparison = review.planComparisons.single()

        assertEquals(100 * minute, comparison.plannedMillis)
        assertEquals(100 * minute, comparison.recordedMillis)
        assertEquals(40 * minute, comparison.matchedMillis)
        assertEquals(60 * minute, comparison.deviatedMillis)
        assertEquals(0L, comparison.unrecordedMillis)
        assertEquals(0.4, comparison.adherenceRatio, 0.0001)
    }

    @Test
    fun `unconfirmed suggestions are reported as pending and left out of category totals`() = runTest {
        addManualRecord(startHoursFrom4am = 5.0, endHoursFrom4am = 7.0)
        timeRecordRepository.saveSystemRecord(
            TimeRecordDraft(
                startMillis = at(10.0),
                endMillis = at(11.0),
                categoryId = funCategoryId,
                activityId = bilibiliActivityId,
                confidence = 0.4,
                externalKey = "evt-1",
            ),
            RecordSource.SUGGESTED,
        )

        val review = service.observeDayReview(saturday).first()

        assertEquals(3 * hour, review.durations.coveredMillis)
        assertEquals(2 * hour, review.durations.confirmedMillis)
        assertEquals(1 * hour, review.durations.pendingMillis)
        // 待确认的 1 小时不出现在娱乐分类里
        assertEquals(listOf(DurationBucket(studyCategoryId, null, 2 * hour)), review.durations.byCategory)
        assertEquals(1, review.pendingRecordCount)
    }

    // ---------------- 空数据与无计划 ----------------

    @Test
    fun `day without plan yields empty comparisons`() = runTest {
        // 计划只在周五，周六没有课
        planRepository.setPlans(listOf(javaPlan().copy(dayOfWeek = 5)))
        addManualRecord(startHoursFrom4am = 5.0, endHoursFrom4am = 6.0)

        val review = service.observeDayReview(saturday).first()

        assertTrue(review.planComparisons.isEmpty())
        assertEquals(0L, review.plannedMillis)
        // 没有计划时达成率是 0 而不是 100%，避免「什么都没安排=完美达成」的错觉
        assertEquals(0.0, review.planAdherenceRatio, 0.0001)
    }

    @Test
    fun `day without any record reports the elapsed part of the day as unrecorded`() = runTest {
        val review = service.observeDayReview(saturday).first()

        // 今天是 09-12，此刻是 14:00 —— 逻辑日从 04:00 起算，所以「已经过去」的是 10 小时。
        // 剩下的 14 小时**还没发生**，算成「没记录」会让下午三点的首页写着「未记录 13h」。
        assertEquals(0L, review.durations.coveredMillis)
        assertEquals(10 * hour, review.durations.unrecordedMillis)
        assertEquals(listOf(dayBoundary.dayRange(saturday).copy(endMillisExclusive = at(10.0))), review.durations.unrecordedSegments)
        assertFalse(review.hasAnyData)
        assertTrue(review.planComparisons.isEmpty())
        assertTrue(review.goalProgress.isEmpty())
    }

    @Test
    fun `a past day reports the whole 24 hours`() = runTest {
        val yesterday = saturday - 1
        val review = service.observeDayReview(yesterday).first()

        assertEquals(24 * hour, review.durations.unrecordedMillis)
        assertEquals(listOf(dayBoundary.dayRange(yesterday)), review.durations.unrecordedSegments)
    }

    @Test
    fun `a future day has nothing to review yet`() = runTest {
        // 日历上翻到明天：还没发生的时间不该被当成「24 小时没记录」
        val review = service.observeDayReview(saturday + 1).first()

        assertEquals(0L, review.durations.coveredMillis)
        assertEquals(0L, review.durations.unrecordedMillis)
        assertTrue(review.durations.unrecordedSegments.isEmpty())
    }

    @Test
    fun `goal that does not apply today is not reported`() = runTest {
        goalRepository.setGoals(
            listOf(javaDayGoal(3.0).copy(endEpochDay = saturday - 1))
        )
        addManualRecord(startHoursFrom4am = 5.0, endHoursFrom4am = 6.0)

        val review = service.observeDayReview(saturday).first()

        assertTrue(review.goalProgress.isEmpty())
        assertEquals(0, review.activeGoalCount)
    }

    // ---------------- 现算，不缓存 ----------------

    @Test
    fun `review is recomputed after a record is added`() = runTest {
        addManualRecord(startHoursFrom4am = 5.0, endHoursFrom4am = 6.0)
        val first = service.observeDayReview(saturday).first()
        assertEquals(1 * hour, first.durations.coveredMillis)

        // 再补一条记录：复盘必须立刻反映出来，不能留着上一次算好的数字
        addManualRecord(
            startHoursFrom4am = 14.0,
            endHoursFrom4am = 15.0,
            categoryId = funCategoryId,
            activityId = bilibiliActivityId,
        )
        val second = service.observeDayReview(saturday).first()

        assertEquals(2 * hour, second.durations.coveredMillis)
        assertEquals(2, second.durations.byCategory.size)
        assertEquals(1 * hour, second.durations.durationOfCategory(funCategoryId))
    }

    @Test
    fun `review reflects a plan edited afterwards`() = runTest {
        addManualRecord(startHoursFrom4am = 5.0, endHoursFrom4am = 6.0)
        val before = service.observeDayReview(saturday).first()
        assertTrue(before.planComparisons.isEmpty())

        planRepository.setPlans(listOf(javaPlan()))
        val after = service.observeDayReview(saturday).first()

        assertEquals(1, after.planComparisons.size)
        // 09:00–10:40 的计划时段与 09:00–10:00 的记录重叠 60 分钟
        assertEquals(100 * minute, after.planComparisons.single().plannedMillis)
        assertEquals(60 * minute, after.planComparisons.single().matchedMillis)
        assertEquals(40 * minute, after.planComparisons.single().unrecordedMillis)
    }

    @Test
    fun `records of another day do not leak into this day`() = runTest {
        addManualRecord(startHoursFrom4am = 5.0, endHoursFrom4am = 6.0)
        val yesterday = saturday - 1

        val review = service.observeDayReview(yesterday).first()

        assertEquals(0L, review.durations.coveredMillis)
        assertEquals(24 * hour, review.durations.unrecordedMillis)
    }

    // ---------------- 空白块的解释（模块 24） ----------------

    @Test
    fun `blank stretches are explained from the raw events`() = runTest {
        // 空白 = 09:00–12:00（记录之间那一段）。里面有两类事实：
        // 用户零碎打开过三次 App，以及两段合计 40 分钟的熄屏（都不满 5 分钟）。
        addManualRecord(startHoursFrom4am = 1.0, endHoursFrom4am = 5.0)   // 05:00–09:00
        addManualRecord(startHoursFrom4am = 8.0, endHoursFrom4am = 9.0)   // 12:00–13:00

        usageEventHost.appendEvents(
            listOf(
                event(UsageEventTypes.ACTIVITY_RESUMED, 5.5, "com.tencent.mm"),
                event(UsageEventTypes.ACTIVITY_RESUMED, 6.0, "com.taobao.taobao"),
                event(UsageEventTypes.ACTIVITY_RESUMED, 6.5, "com.tencent.mm"),
                event(UsageEventTypes.SCREEN_NON_INTERACTIVE, 7.0),
                event(UsageEventTypes.SCREEN_INTERACTIVE, 7.25),
                event(UsageEventTypes.SCREEN_NON_INTERACTIVE, 7.5),
                event(UsageEventTypes.SCREEN_INTERACTIVE, 7.75),
            )
        )

        val review = service.observeDayReview(saturday).first()
        val activity = review.gapActivities.single { it.range.startMillis == at(5.0) }

        assertTrue(activity.hasEvents)
        assertEquals(3, activity.appOpenCount)
        assertEquals(30 * minute, activity.idleMillis)
    }

    /** 逻辑日 04:00 之后的第 N 小时。 */
    private fun event(type: Int, hoursFrom4am: Double, packageName: String? = null): UsageEvent {
        val timestamp = at(hoursFrom4am)
        return UsageEvent(
            packageName = packageName,
            eventType = type,
            timestampMillis = timestamp,
            eventKey = UsageEvent.buildEventKey(type, timestamp, packageName),
        )
    }
}
