package com.timeledger.app.ui.reminder

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.goal.GoalProgress
import com.timeledger.app.domain.model.Goal
import com.timeledger.app.domain.model.GoalPeriod
import com.timeledger.app.domain.model.GoalType
import com.timeledger.app.domain.model.RecordSource
import com.timeledger.app.domain.model.TimeRecord
import com.timeledger.app.domain.review.DayReview
import com.timeledger.app.domain.review.TimelineAggregator
import com.timeledger.app.domain.timeline.TimelineResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 提醒规划测试。
 *
 * 重点不是「怎么提醒」，而是「**什么时候不提醒**」：
 * 需求十二节要求低打扰，而一个到点就响的 App 会被用户直接关掉通知 ——
 * 通知一关，事后确认这条闭环就断了。所以每个跳过条件都有单独的测试。
 */
class ReminderPlannerTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private val fixedNow = Instant.parse("2026-09-12T06:00:00Z").toEpochMilli()
    private val dayBoundary = DayBoundary(zoneId = shanghai, nowProvider = { fixedNow })

    private val sept12: Long = LocalDate.of(2026, 9, 12).toEpochDay()
    private val window = dayBoundary.dayRange(sept12)
    private val hour = 3600_000L

    private val noonMillis = dayBoundary.millisAtMinuteOfDay(sept12, 12 * 60)

    private val planner = ReminderPlanner()

    private fun at(hoursFrom4am: Double): Long = window.startMillis + (hoursFrom4am * hour).toLong()

    private fun timeGoal(
        title: String = "Java",
        targetSeconds: Double = 3 * 3600.0,
        achievedSeconds: Double = 0.0,
    ) = goalProgress(title, GoalType.TIME, targetSeconds, achievedSeconds)

    private fun countGoal(
        title: String = "LeetCode",
        target: Double = 2.0,
        achieved: Double = 1.0,
    ) = goalProgress(title, GoalType.COUNT, target, achieved)

    private fun goalProgress(
        title: String,
        type: GoalType,
        target: Double,
        achieved: Double,
    ) = GoalProgress(
        goal = Goal(
            id = 1L,
            title = title,
            type = type,
            period = GoalPeriod.DAY,
            targetValue = target,
            categoryId = 1L,
        ),
        window = window,
        achieved = achieved,
        target = target,
        contributingRecordCount = 1,
    )

    private fun context(
        kind: ReminderKind,
        confirmedMillis: Long = 0L,
        pendingRecordCount: Int = 0,
        pendingMillis: Long = 0L,
        gaps: List<MillisRange> = emptyList(),
        unrecordedMillis: Long = 0L,
        goals: List<GoalProgress> = emptyList(),
        hasAnyData: Boolean = true,
    ) = ReminderContext(
        kind = kind,
        epochDay = sept12,
        dateLabel = "9月12日 周六",
        confirmedMillis = confirmedMillis,
        pendingRecordCount = pendingRecordCount,
        pendingMillis = pendingMillis,
        gaps = gaps,
        unrecordedMillis = unrecordedMillis,
        goals = goals,
        hasAnyData = hasAnyData,
        noonMillis = noonMillis,
    )

    // ---------------- 中午 ----------------

    @Test
    fun `noon skips when the morning is fully recorded`() {
        // 上午没有任何缺口，也没有待确认的记录
        val plan = planner.plan(
            context(
                kind = ReminderKind.NOON,
                gaps = listOf(MillisRange(at(14.0), at(16.0))), // 缺口在下午
            )
        )

        assertFalse(plan.shouldNotify)
        assertEquals("", plan.body)
    }

    @Test
    fun `noon notifies about pending records`() {
        val plan = planner.plan(
            context(kind = ReminderKind.NOON, pendingRecordCount = 2, pendingMillis = hour)
        )

        assertTrue(plan.shouldNotify)
        assertTrue(plan.title.isNotEmpty())
        assertTrue(plan.body.contains("2 条记录待确认"))
        // 中午这条是正常提醒，不静默
        assertFalse(plan.silent)
    }

    @Test
    fun `noon counts only the morning part of a gap that crosses noon`() {
        // 11:00–14:00 的缺口：属于上午的只有 11:00–12:00 那一小时
        val plan = planner.plan(
            context(kind = ReminderKind.NOON, gaps = listOf(MillisRange(at(7.0), at(10.0))))
        )

        assertTrue(plan.shouldNotify)
        assertTrue("实际正文：${plan.body}", plan.body.contains("上午有 1h 没记录"))
    }

    @Test
    fun `noon ignores gaps that start after noon`() {
        val plan = planner.plan(
            context(kind = ReminderKind.NOON, gaps = listOf(MillisRange(at(10.0), at(12.0))))
        )
        assertFalse(plan.shouldNotify)
    }

    @Test
    fun `noon skips when there is nothing at all`() {
        val plan = planner.plan(context(kind = ReminderKind.NOON, hasAnyData = false))
        assertFalse(plan.shouldNotify)
    }

    // ---------------- 晚上 ----------------

    @Test
    fun `evening skips when everything is done`() {
        // 目标都达标、也没有待确认 —— 这时候提醒只会变成骚扰
        val plan = planner.plan(
            context(
                kind = ReminderKind.EVENING,
                goals = listOf(
                    timeGoal(achievedSeconds = 3 * 3600.0),
                    countGoal(achieved = 2.0),
                ),
            )
        )

        assertFalse(plan.shouldNotify)
    }

    @Test
    fun `evening lists the unfinished goals`() {
        val plan = planner.plan(
            context(
                kind = ReminderKind.EVENING,
                goals = listOf(
                    timeGoal(targetSeconds = 3 * 3600.0, achievedSeconds = 3 * 3600.0 - 2400.0),
                    countGoal(target = 2.0, achieved = 1.0),
                ),
            )
        )

        assertTrue(plan.shouldNotify)
        // 已达标的目标不出现在正文里
        assertTrue("实际正文：${plan.body}", plan.body.contains("Java 还差 40m"))
        assertTrue("实际正文：${plan.body}", plan.body.contains("LeetCode 还差 1个"))
    }

    @Test
    fun `evening collapses more than two goals`() {
        val plan = planner.plan(
            context(
                kind = ReminderKind.EVENING,
                goals = listOf(
                    timeGoal(title = "Java"),
                    countGoal(title = "LeetCode"),
                    timeGoal(title = "英语"),
                ),
            )
        )

        // 通知栏放不下太多，列一长串本身就是在施压
        assertTrue("实际正文：${plan.body}", plan.body.contains("Java 还差 3h"))
        assertTrue("实际正文：${plan.body}", plan.body.contains("另有 1 个目标"))
        assertFalse("实际正文：${plan.body}", plan.body.contains("英语"))
    }

    @Test
    fun `evening still notifies about pending records when goals are done`() {
        val plan = planner.plan(
            context(
                kind = ReminderKind.EVENING,
                pendingRecordCount = 3,
                pendingMillis = 2 * hour,
                goals = listOf(timeGoal(achievedSeconds = 3 * 3600.0)),
            )
        )

        assertTrue(plan.shouldNotify)
        assertTrue(plan.body.contains("3 条待确认"))
    }

    @Test
    fun `evening skips when there are no goals and nothing pending`() {
        val plan = planner.plan(context(kind = ReminderKind.EVENING))
        assertFalse(plan.shouldNotify)
    }

    // ---------------- 凌晨复盘 ----------------

    @Test
    fun `review summarises the finished day`() {
        val plan = planner.plan(
            context(
                kind = ReminderKind.REVIEW,
                confirmedMillis = (8 * 60 + 20) * 60_000L,
                pendingRecordCount = 1,
                pendingMillis = 30 * 60_000L,
                unrecordedMillis = 3 * hour,
            )
        )

        assertTrue(plan.shouldNotify)
        assertEquals("9月12日 周六 整理好了", plan.title)
        assertTrue("实际正文：${plan.body}", plan.body.contains("记录 8h20m"))
        assertTrue("实际正文：${plan.body}", plan.body.contains("1 条待确认"))
        assertTrue("实际正文：${plan.body}", plan.body.contains("未记录 3h"))
    }

    @Test
    fun `review is always silent`() {
        // 凌晨 4 点响铃会把人吵醒，既违背低打扰也几乎必然导致卸载
        val plan = planner.plan(
            context(kind = ReminderKind.REVIEW, confirmedMillis = hour)
        )

        assertTrue(plan.shouldNotify)
        assertTrue(plan.silent)
    }

    @Test
    fun `review skips a day with no data at all`() {
        val plan = planner.plan(
            context(kind = ReminderKind.REVIEW, unrecordedMillis = 24 * hour, hasAnyData = false)
        )
        assertFalse(plan.shouldNotify)
    }

    @Test
    fun `only review is silent`() {
        val noon = planner.plan(
            context(kind = ReminderKind.NOON, pendingRecordCount = 1, pendingMillis = hour)
        )
        val evening = planner.plan(context(kind = ReminderKind.EVENING, pendingRecordCount = 1, pendingMillis = hour))

        assertFalse(noon.silent)
        assertFalse(evening.silent)
    }

    // ---------------- 从复盘数据构造 ----------------

    @Test
    fun `context is built from a day review`() {
        // 平台层只需要准备好「当天记录 + 目标进度」，业务规则不落在它那一侧
        val records = listOf(
            TimeRecord(
                startMillis = at(0.0),
                endMillis = at(2.0),
                categoryId = 1L,
                activityId = null,
                source = RecordSource.MANUAL,
            ),
            TimeRecord(
                startMillis = at(10.0),
                endMillis = at(11.0),
                categoryId = 2L,
                activityId = null,
                source = RecordSource.SUGGESTED,
                confidence = 0.4,
            ),
        )
        val timeline = TimelineResolver.resolve(records)
        val review = DayReview(
            epochDay = sept12,
            window = window,
            timeline = timeline,
            durations = TimelineAggregator().aggregate(timeline, window),
            planComparisons = emptyList(),
            goalProgress = emptyList(),
            pendingRecordCount = records.count { it.needsConfirmation },
        )

        val context = ReminderContext.of(
            kind = ReminderKind.NOON,
            review = review,
            goals = emptyList(),
            noonMillis = noonMillis,
        )

        assertEquals(ReminderKind.NOON, context.kind)
        assertEquals(sept12, context.epochDay)
        assertEquals("9月12日 周六", context.dateLabel)
        assertEquals(2 * hour, context.confirmedMillis)
        assertEquals(1, context.pendingRecordCount)
        assertEquals(hour, context.pendingMillis)
        // 06:00–14:00 与 15:00–次日 04:00 两段没记录：覆盖 3 小时，未记录 21 小时
        assertEquals(2, context.gaps.size)
        assertEquals(21 * hour, context.unrecordedMillis)
        assertTrue(context.hasAnyData)
    }
}
