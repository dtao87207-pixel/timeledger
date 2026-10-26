package com.timeledger.app.domain.goal

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.core.time.TimeBoundary
import com.timeledger.app.domain.model.Goal
import com.timeledger.app.domain.model.GoalPeriod
import com.timeledger.app.domain.model.GoalType
import com.timeledger.app.domain.model.RecordSource
import com.timeledger.app.domain.model.TimeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 目标完成度计算测试。
 *
 * 时间基准写死：上海时区，逻辑日为 2026-09-12，窗口内的偏移量用「距当天 04:00 的第 N 分钟」
 * 表示，读断言时不用心算毫秒。
 */
class GoalProgressCalculatorTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private val fixedNow = Instant.parse("2026-09-12T06:00:00Z").toEpochMilli()
    private val dayBoundary = DayBoundary(zoneId = shanghai, nowProvider = { fixedNow })
    private val calculator = GoalProgressCalculator(dayBoundary)

    private val referenceDay: Long = LocalDate.of(2026, 9, 12).toEpochDay()
    private val dayWindow = dayBoundary.dayRange(referenceDay)
    private val minute = 60_000L

    /** 距当天 04:00 的第 [minutes] 分钟；负数表示当天 04:00 之前（属于前一个逻辑日）。 */
    private fun at(minutes: Int): Long = dayWindow.startMillis + minutes * minute

    private val studyCategory = 1L
    private val funCategory = 2L
    private val javaActivity = 100L
    private val bilibiliActivity = 200L

    private fun record(
        startMinute: Int,
        durationMinutes: Int,
        source: RecordSource = RecordSource.MANUAL,
        categoryId: Long? = studyCategory,
        activityId: Long? = javaActivity,
        countValue: Double? = null,
        id: Long = 0L,
    ) = TimeRecord(
        id = id,
        startMillis = at(startMinute),
        endMillis = at(startMinute + durationMinutes),
        categoryId = categoryId,
        activityId = activityId,
        source = source,
        countValue = countValue,
    )

    private fun timeGoal(
        targetSeconds: Double,
        categoryId: Long? = studyCategory,
        activityId: Long? = javaActivity,
        period: GoalPeriod = GoalPeriod.DAY,
        startEpochDay: Long? = null,
        endEpochDay: Long? = null,
    ) = Goal(
        id = 1L,
        title = "测试目标",
        type = GoalType.TIME,
        period = period,
        targetValue = targetSeconds,
        categoryId = categoryId,
        activityId = activityId,
        startEpochDay = startEpochDay,
        endEpochDay = endEpochDay,
    )

    private fun countGoal(targetCount: Double, countValue: Double) = Goal(
        id = 2L,
        title = "做题",
        type = GoalType.COUNT,
        period = GoalPeriod.DAY,
        targetValue = targetCount,
        categoryId = studyCategory,
        activityId = javaActivity,
    )

    private fun frequencyGoal(
        targetTimes: Double,
        activityId: Long? = null,
        period: GoalPeriod = GoalPeriod.WEEK,
    ) = Goal(
        id = 3L,
        title = "健身",
        type = GoalType.FREQUENCY,
        period = period,
        targetValue = targetTimes,
        categoryId = studyCategory,
        activityId = activityId,
    )

    // ---------------- TIME ----------------

    @Test
    fun `time goal sums matching durations in seconds`() {
        val progress = calculator.calculate(
            goal = timeGoal(targetSeconds = 3 * 3600.0),
            records = listOf(record(0, 90), record(120, 30)),
            epochDay = referenceDay,
        )

        assertNotNull(progress)
        // 90 + 30 = 120 分钟 = 7200 秒
        assertEquals(7200.0, progress!!.achieved, 0.001)
        assertEquals(3 * 3600.0, progress.target, 0.001)
        assertEquals(2, progress.contributingRecordCount)
        assertFalse(progress.isAchieved)
        assertEquals(3 * 3600.0 - 7200.0, progress.remaining, 0.001)
    }

    @Test
    fun `time goal excludes unconfirmed suggestions`() {
        // 系统猜出来的候选行为不能帮用户达标（需求三节：推测必须经用户确认）
        val progress = calculator.calculate(
            goal = timeGoal(targetSeconds = 3600.0),
            records = listOf(
                record(0, 30, source = RecordSource.SYSTEM),
                record(30, 30, source = RecordSource.SUGGESTED),
                record(60, 30, source = RecordSource.MERGED),
                record(90, 30, source = RecordSource.MANUAL),
            ),
            epochDay = referenceDay,
        )!!

        // 只有 SYSTEM / MERGED / MANUAL 的三段算数：90 分钟
        assertEquals(90 * 60.0, progress.achieved, 0.001)
        assertEquals(3, progress.contributingRecordCount)
    }

    @Test
    fun `time goal does not double count overlapping manual and system records`() {
        // 这是最容易出错的地方：用户手写 0–60，系统测到 30–90，
        // 直接相加会得到 120 分钟，实际覆盖只有 90 分钟。
        val progress = calculator.calculate(
            goal = timeGoal(targetSeconds = 2 * 3600.0),
            records = listOf(
                record(0, 60, source = RecordSource.MANUAL),
                record(30, 60, source = RecordSource.SYSTEM),
            ),
            epochDay = referenceDay,
        )!!

        assertEquals(90 * 60.0, progress.achieved, 0.001)
    }

    @Test
    fun `time goal clips a record that crosses the day boundary`() {
        // 03:00–06:00 的记录：它属于前一个逻辑日，但档在 04:00 之后的那 2 小时
        // 要算进今天的时长 —— 否则跨日记录会凭空消失。
        val progress = calculator.calculate(
            goal = timeGoal(targetSeconds = 4 * 3600.0),
            records = listOf(record(startMinute = -60, durationMinutes = 180)),
            epochDay = referenceDay,
        )!!

        assertEquals(2 * 3600.0, progress.achieved, 0.001)
    }

    @Test
    fun `time goal ignores records of other categories and activities`() {
        val progress = calculator.calculate(
            goal = timeGoal(targetSeconds = 3600.0),
            records = listOf(
                record(0, 30, categoryId = studyCategory, activityId = javaActivity),
                // 同分类但别的活动：绑活动的目标不算
                record(30, 30, categoryId = studyCategory, activityId = 999L),
                // 别的分类
                record(60, 30, categoryId = funCategory, activityId = bilibiliActivity),
            ),
            epochDay = referenceDay,
        )!!

        assertEquals(30 * 60.0, progress.achieved, 0.001)
        assertEquals(1, progress.contributingRecordCount)
    }

    @Test
    fun `category bound time goal counts every activity under it`() {
        val progress = calculator.calculate(
            goal = timeGoal(targetSeconds = 3600.0, activityId = null),
            records = listOf(
                record(0, 30, categoryId = studyCategory, activityId = javaActivity),
                record(30, 45, categoryId = studyCategory, activityId = 999L),
                record(75, 30, categoryId = funCategory, activityId = bilibiliActivity),
            ),
            epochDay = referenceDay,
        )!!

        assertEquals(75 * 60.0, progress.achieved, 0.001)
    }

    @Test
    fun `instant records contribute no time to a time goal`() {
        val progress = calculator.calculate(
            goal = timeGoal(targetSeconds = 3600.0),
            records = listOf(record(10, 0, countValue = 2.0), record(30, 20)),
            epochDay = referenceDay,
        )!!

        assertEquals(20 * 60.0, progress.achieved, 0.001)
        // 零长度记录不贡献时长，因此不计入「参与统计的记录数」
        assertEquals(1, progress.contributingRecordCount)
    }

    // ---------------- COUNT ----------------

    @Test
    fun `count goal sums count values`() {
        val progress = calculator.calculate(
            goal = countGoal(targetCount = 2.0, countValue = 0.0),
            records = listOf(
                record(10, 0, countValue = 1.0),
                record(60, 30, countValue = 0.5),
            ),
            epochDay = referenceDay,
        )!!

        assertEquals(1.5, progress.achieved, 0.001)
        assertEquals(2.0, progress.target, 0.001)
    }

    @Test
    fun `count goal counts a partly covered record fully and excludes suggestions`() {
        val progress = calculator.calculate(
            goal = countGoal(targetCount = 2.0, countValue = 0.0),
            records = listOf(
                // 用户记的「LeetCode 2 题」，14:00–15:00
                record(0, 60, source = RecordSource.MANUAL, countValue = 2.0, id = 1L),
                // 系统测到 14:30–15:30，抢走后半段 ——
                // 但计数是不可分割的原子值，这一条仍然算 2 题，不能按比例缩成 1 题
                record(30, 60, source = RecordSource.SYSTEM, id = 2L),
                // 未确认的推测不参与统计
                record(120, 0, source = RecordSource.SUGGESTED, countValue = 5.0, id = 3L),
            ),
            epochDay = referenceDay,
        )!!

        assertEquals(2.0, progress.achieved, 0.001)
        assertTrue(progress.isAchieved)
        // 手动那条 + 系统那条都在统计范围内（系统那条没有 countValue，按 0 计）
        assertEquals(2, progress.contributingRecordCount)
    }

    @Test
    fun `count goal counts each surviving record once even if it spans two segments`() {
        // outer（SYSTEM，5 题）被 inner（MANUAL，1 题）从中间切开 → outer 占两段。
        // 如果按「段落」统计，5 题会被算两次。
        val progress = calculator.calculate(
            goal = countGoal(targetCount = 10.0, countValue = 0.0),
            records = listOf(
                record(0, 120, source = RecordSource.SYSTEM, countValue = 5.0, id = 10L),
                record(30, 30, source = RecordSource.MANUAL, countValue = 1.0, id = 11L),
            ),
            epochDay = referenceDay,
        )!!

        assertEquals(6.0, progress.achieved, 0.001)
    }

    // ---------------- FREQUENCY ----------------

    @Test
    fun `frequency goal counts records not segments`() {
        val progress = calculator.calculate(
            goal = frequencyGoal(targetTimes = 3.0),
            records = listOf(
                // 一条被切成两段的记录
                record(0, 120, source = RecordSource.SYSTEM, id = 10L),
                record(30, 30, source = RecordSource.MANUAL, id = 11L),
                // 另一次独立发生
                record(200, 30, source = RecordSource.MANUAL, id = 12L),
            ),
            epochDay = referenceDay,
        )!!

        assertEquals(3.0, progress.achieved, 0.001)
        assertTrue(progress.isAchieved)
        assertEquals(0.0, progress.remaining, 0.001)
    }

    @Test
    fun `frequency goal ignores records owned by another period`() {
        // 记录开始于当天 04:00 之前 → 归属前一个逻辑日，不算进今天。
        // 这里必须用 DAY 周期：换成 WEEK 的话，凌晨 3:30 仍然落在本周窗口内，就该算数。
        val progress = calculator.calculate(
            goal = frequencyGoal(targetTimes = 1.0, period = GoalPeriod.DAY),
            records = listOf(record(startMinute = -30, durationMinutes = 60)),
            epochDay = referenceDay,
        )!!

        assertEquals(0.0, progress.achieved, 0.001)
    }

    @Test
    fun `frequency goal counts a record starting just before the day boundary for the week period`() {
        // 同一个记录，换成周目标就算数 —— 说明差异来自「窗口」，而不是记录本身
        val progress = calculator.calculate(
            goal = frequencyGoal(targetTimes = 1.0, period = GoalPeriod.WEEK),
            records = listOf(record(startMinute = -30, durationMinutes = 60)),
            epochDay = referenceDay,
        )!!

        assertEquals(1.0, progress.achieved, 0.001)
    }

    // ---------------- 周期窗口 ----------------

    @Test
    fun `day goal window is the logical day`() {
        val window = calculator.windowFor(timeGoal(3600.0), referenceDay)!!
        assertEquals(dayWindow, window)
        assertEquals(24 * 60 * minute, window.durationMillis)
    }

    @Test
    fun `week goal window covers seven days starting monday`() {
        val window = calculator.windowFor(
            timeGoal(3600.0, period = GoalPeriod.WEEK),
            referenceDay,
        )!!
        assertEquals(7 * 24 * 60 * minute, window.durationMillis)
        // 窗口必须包含参考日
        assertTrue(dayWindow.startMillis in window)
        // 起点就是该周周一的 04:00
        val weekStart = TimeBoundary.weekStartEpochDay(referenceDay)
        assertEquals(TimeBoundary.dayStartMillis(weekStart, shanghai), window.startMillis)
        assertEquals(
            LocalDate.ofEpochDay(weekStart).dayOfWeek.value,
            1, // 周一
        )
    }

    @Test
    fun `month goal window covers the whole calendar month`() {
        val window = calculator.windowFor(
            timeGoal(3600.0, period = GoalPeriod.MONTH),
            referenceDay,
        )!!
        // 2026 年 9 月是 30 天
        assertEquals(30 * 24 * 60 * minute, window.durationMillis)
    }

    @Test
    fun `window is clamped by the goal validity period`() {
        // 周三（这里用参考日）才建的目标，窗口从当天 04:00 开始，而不是从本周一
        val window = calculator.windowFor(
            timeGoal(3600.0, period = GoalPeriod.WEEK, startEpochDay = referenceDay),
            referenceDay,
        )!!
        assertEquals(dayWindow.startMillis, window.startMillis)
        // 结束时间不受影响
        assertEquals(
            dayBoundary.dayRange(TimeBoundary.weekStartEpochDay(referenceDay) + 7).startMillis,
            window.endMillisExclusive,
        )
    }

    @Test
    fun `goal outside its validity period reports null`() {
        val expired = timeGoal(3600.0, endEpochDay = referenceDay - 5)
        assertNull(calculator.windowFor(expired, referenceDay))
        assertNull(calculator.calculate(expired, emptyList(), referenceDay))

        val notStarted = timeGoal(3600.0, startEpochDay = referenceDay + 5)
        assertNull(calculator.windowFor(notStarted, referenceDay))
    }

    @Test
    fun `window union spans every applicable goal window`() {
        val goals = listOf(
            timeGoal(3600.0, period = GoalPeriod.DAY),
            timeGoal(15 * 3600.0, period = GoalPeriod.WEEK),
            timeGoal(60 * 3600.0, period = GoalPeriod.MONTH),
        )

        val union = calculator.windowUnion(goals, referenceDay)!!

        // 并集必须覆盖最宽的月窗口
        assertEquals(calculator.windowFor(goals[2], referenceDay)!!, union)
    }

    @Test
    fun `window union is null when no goal applies`() {
        val goals = listOf(timeGoal(3600.0, endEpochDay = referenceDay - 1))
        assertNull(calculator.windowUnion(goals, referenceDay))
    }

    // ---------------- 汇总与一致性 ----------------

    @Test
    fun `calculateAll skips inapplicable goals and shares one resolved timeline`() {
        val goals = listOf(
            timeGoal(3600.0, activityId = javaActivity),
            timeGoal(3600.0, activityId = bilibiliActivity, categoryId = funCategory),
            timeGoal(3600.0, startEpochDay = referenceDay + 10), // 尚未生效
        )
        val records = listOf(
            record(0, 30, categoryId = studyCategory, activityId = javaActivity),
            record(30, 45, categoryId = funCategory, activityId = bilibiliActivity),
        )

        val progressList = calculator.calculateAll(goals, records, referenceDay)

        assertEquals(2, progressList.size)
        assertEquals(30 * 60.0, progressList[0].achieved, 0.001)
        assertEquals(45 * 60.0, progressList[1].achieved, 0.001)
    }

    @Test
    fun `category goal total equals the sum of its activity goals when they do not overlap`() {
        val records = listOf(
            record(0, 30, categoryId = studyCategory, activityId = javaActivity),
            record(30, 45, categoryId = studyCategory, activityId = 999L),
        )
        val categoryGoal = timeGoal(3600.0, activityId = null)
        val javaGoal = timeGoal(3600.0, activityId = javaActivity)
        val otherGoal = timeGoal(3600.0, activityId = 999L)

        val all = calculator.calculateAll(listOf(categoryGoal, javaGoal, otherGoal), records, referenceDay)

        assertEquals(3, all.size)
        assertEquals(
            all[0].achieved,
            all[1].achieved + all[2].achieved,
            0.001,
        )
    }

    @Test
    fun `progress ratio and fraction behave at and beyond the target`() {
        val goal = timeGoal(targetSeconds = 3600.0)
        val progress = calculator.calculate(goal, listOf(record(0, 90)), referenceDay)!!

        assertEquals(1.5, progress.ratio, 0.001)
        assertTrue(progress.isAchieved)
        assertEquals(0.0, progress.remaining, 0.001)
        // 进度条比例封顶 1.0，但 ratio 保留真实值供 UI 显示「150%」
        assertEquals(1.0, progress.progressFraction, 0.001)
        assertEquals("秒", progress.unitLabel)
    }

    @Test
    fun `empty record list yields zero achievement`() {
        val progress = calculator.calculate(timeGoal(3600.0), emptyList(), referenceDay)!!
        assertEquals(0.0, progress.achieved, 0.001)
        assertEquals(0, progress.contributingRecordCount)
        assertFalse(progress.isAchieved)
    }

    @Test
    fun `calculateAll on empty goals returns empty list`() {
        assertTrue(calculator.calculateAll(emptyList(), emptyList(), referenceDay).isEmpty())
    }
}
