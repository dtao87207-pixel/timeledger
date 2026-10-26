package com.timeledger.app.ui.calendar

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.domain.goal.DailyGoalProgress
import com.timeledger.app.domain.goal.GoalProgress
import com.timeledger.app.domain.model.Goal
import com.timeledger.app.domain.model.GoalPeriod
import com.timeledger.app.domain.model.GoalType
import com.timeledger.app.domain.model.TimeRecord
import com.timeledger.app.domain.statistics.RangeStatistics
import com.timeledger.app.ui.day.DayTimeline
import com.timeledger.app.domain.statistics.StatisticsCalculator
import com.timeledger.app.domain.statistics.StatisticsPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 日历状态映射测试。
 *
 * 日期基准：2026 年 9 月 —— **9 月 1 日是周二**，共 30 天；
 * 因此网格应为「周一起始、前面补 1 格」= 35 格（5 行）。
 * 2026-09-12 是周六。
 */
class CalendarStateMapperTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private val fixedNow = Instant.parse("2026-09-12T06:00:00Z").toEpochMilli()
    private val dayBoundary = DayBoundary(zoneId = shanghai, nowProvider = { fixedNow })

    private val sept12: Long = LocalDate.of(2026, 9, 12).toEpochDay()
    private val sept1: Long = LocalDate.of(2026, 9, 1).toEpochDay()
    private val sept30: Long = LocalDate.of(2026, 9, 30).toEpochDay()
    private val aug31: Long = LocalDate.of(2026, 8, 31).toEpochDay()
    private val oct1: Long = LocalDate.of(2026, 10, 1).toEpochDay()

    private val hour = 3600_000L
    private val calculator = StatisticsCalculator(dayBoundary)
    private val mapper = CalendarStateMapper(todayEpochDay = { sept12 })

    private fun recordOn(day: Long, fromHoursFrom4am: Double, durationHours: Double) = TimeRecord(
        startMillis = dayBoundary.dayRange(day).startMillis + (fromHoursFrom4am * hour).toLong(),
        endMillis = dayBoundary.dayRange(day).startMillis + ((fromHoursFrom4am + durationHours) * hour).toLong(),
        categoryId = 1L,
        activityId = 100L,
        source = com.timeledger.app.domain.model.RecordSource.MANUAL,
    )

    private fun monthStats(records: List<TimeRecord>): RangeStatistics =
        calculator.compute(records, StatisticsPeriod.MONTH, sept12)

    private fun dailyGoals(day: Long, achieved: Int, active: Int) = DailyGoalProgress(
        epochDay = day,
        progress = (1..active).map { index ->
            GoalProgress(
                goal = Goal(
                    id = index.toLong(),
                    title = "目标$index",
                    type = GoalType.TIME,
                    period = GoalPeriod.DAY,
                    targetValue = 3600.0,
                    categoryId = 1L,
                ),
                window = dayBoundary.dayRange(day),
                achieved = if (index <= achieved) 3600.0 else 0.0,
                target = 3600.0,
                contributingRecordCount = 1,
            )
        },
    )

    // ---------------- 网格结构 ----------------

    @Test
    fun `grid starts on monday and covers whole weeks`() {
        val state = mapper.map(sept12, sept12, monthStats(emptyList()), emptyMap(), null)

        // 9 月 1 日是周二 → 前面补 1 格；30 + 1 = 31 → 向上取整到 35 格（5 行）
        assertEquals(5, state.weeks.size)
        assertTrue(state.weeks.all { it.size == 7 })
        assertEquals(35, state.cellCount)

        // 第一格是 8 月 31 日（周一），它只是补齐网格用的
        val first = state.weeks.first().first()
        assertEquals(aug31, first.epochDay)
        assertEquals(31, first.dayOfMonth)
        assertFalse(first.inDisplayedMonth)

        // 第二格才是 9 月 1 日
        val second = state.weeks.first()[1]
        assertEquals(sept1, second.epochDay)
        assertTrue(second.inDisplayedMonth)
    }

    @Test
    fun `month boundaries are marked correctly`() {
        val state = mapper.map(sept12, sept12, monthStats(emptyList()), emptyMap(), null)
        val cells = state.weeks.flatten()

        assertTrue(cells.first { it.epochDay == sept1 }.inDisplayedMonth)
        assertTrue(cells.first { it.epochDay == sept30 }.inDisplayedMonth)
        assertFalse(cells.first { it.epochDay == oct1 }.inDisplayedMonth)
        assertNull(cells.first { it.epochDay == oct1 }.recordedLabel)
        assertEquals(0f, cells.first { it.epochDay == oct1 }.intensity, 0.0001f)
    }

    @Test
    fun `weekday headers start on monday`() {
        val state = mapper.map(sept12, sept12, monthStats(emptyList()), emptyMap(), null)
        assertEquals(listOf("一", "二", "三", "四", "五", "六", "日"), state.weekdayHeaders)
    }

    @Test
    fun `month label uses the chinese format`() {
        val state = mapper.map(sept12, sept12, monthStats(emptyList()), emptyMap(), null)
        assertEquals("2026年9月", state.monthLabel)
    }

    // ---------------- 每格内容 ----------------

    @Test
    fun `today and selected day are marked`() {
        val state = mapper.map(sept12, sept1, monthStats(emptyList()), emptyMap(), null)
        val cells = state.weeks.flatten()

        // todayEpochDay 固定为 9/12
        assertTrue(cells.first { it.epochDay == sept12 }.isToday)
        assertFalse(cells.first { it.epochDay == sept1 }.isToday)
        // selected 是 9/1
        assertTrue(cells.first { it.epochDay == sept1 }.isSelected)
        assertFalse(cells.first { it.epochDay == sept12 }.isSelected)
    }

    @Test
    fun `recorded hours are floored to whole hours`() {
        val state = mapper.map(
            sept12,
            sept12,
            monthStats(
                listOf(
                    recordOn(sept12, 5.0, 5.0 + 59.0 / 60.0), // 5 小时 59 分
                    recordOn(sept1, 5.0, 0.5),                 // 半小时
                )
            ),
            emptyMap(),
            null,
        )
        val cells = state.weeks.flatten()

        // 向下取整：宁可少报也不虚报
        assertEquals("5h", cells.first { it.epochDay == sept12 }.recordedLabel)
        assertEquals("<1h", cells.first { it.epochDay == sept1 }.recordedLabel)
    }

    @Test
    fun `days without records have no label at all`() {
        val state = mapper.map(sept12, sept12, monthStats(emptyList()), emptyMap(), null)
        val cell = state.weeks.flatten().first { it.epochDay == sept12 }

        // null 而不是 "0h"：界面据此留白，而不是给每一天都糊上一个 0
        assertNull(cell.recordedLabel)
    }

    @Test
    fun `intensity is relative to the busiest day of the month`() {
        val state = mapper.map(
            sept12,
            sept12,
            monthStats(
                listOf(
                    recordOn(sept1, 5.0, 4.0),  // 最长的一天
                    recordOn(sept12, 5.0, 2.0), // 一半
                )
            ),
            emptyMap(),
            null,
        )
        val cells = state.weeks.flatten()

        assertEquals(1f, cells.first { it.epochDay == sept1 }.intensity, 0.0001f)
        assertEquals(0.5f, cells.first { it.epochDay == sept12 }.intensity, 0.0001f)
        assertEquals(0f, cells.first { it.epochDay == sept30 }.intensity, 0.0001f)
    }

    // ---------------- 目标完成情况 ----------------

    @Test
    fun `goal label shows achieved over active count`() {
        val state = mapper.map(
            sept12,
            sept12,
            monthStats(emptyList()),
            mapOf(sept12 to dailyGoals(sept12, achieved = 2, active = 3)),
            null,
        )
        val cell = state.weeks.flatten().first { it.epochDay == sept12 }

        assertEquals("2/3", cell.goalLabel)
    }

    @Test
    fun `goal label is absent when no goal applies that day`() {
        val state = mapper.map(
            sept12,
            sept12,
            monthStats(emptyList()),
            // 有目标但当天一个都不适用（activeGoalCount = 0）
            mapOf(sept1 to dailyGoals(sept1, achieved = 0, active = 0)),
            null,
        )
        val cells = state.weeks.flatten()

        // 不显示 0/0：那看起来像「完成了 0 个目标」，实际是「这天没有目标」
        assertNull(cells.first { it.epochDay == sept1 }.goalLabel)
        assertNull(cells.first { it.epochDay == sept12 }.goalLabel)
    }

    // ---------------- 汇总与详情 ----------------

    @Test
    fun `month summary reports total and recorded days`() {
        val state = mapper.map(
            sept12,
            sept12,
            monthStats(
                listOf(
                    recordOn(sept1, 5.0, 2.0),
                    recordOn(sept12, 5.0, 3.0),
                )
            ),
            emptyMap(),
            null,
        )

        assertEquals("本月记录 5h，2/30 天有记录", state.monthSummaryLabel)
    }

    @Test
    fun `selected day label and detail are passed through`() {
        val detail = com.timeledger.app.ui.day.DayViewUiState(
            dateLabel = "9月1日 周二",
            recordedLabel = "2h",
            pendingLabel = null,
            unrecordedLabel = "22h",
            goals = emptyList(),
            currentMoment = null,
            dayShares = emptyList(),
            appShares = emptyList(),
            elapsedLabel = "24h",
            elapsedNote = "一整天",
            timeline = DayTimeline.EMPTY,
            gaps = emptyList(),
            planNote = null,
            isEmptyDay = false,
            quickAddOptions = emptyList(),
        )

        val state = mapper.map(sept12, sept1, monthStats(emptyList()), emptyMap(), detail)

        assertEquals("9月1日 周二", state.selectedDayLabel)
        assertEquals("2h", state.selectedDetail?.recordedLabel)
    }

    @Test
    fun `detail is null while it is still loading`() {
        val state = mapper.map(sept12, sept12, monthStats(emptyList()), emptyMap(), null)
        assertNull(state.selectedDetail)
    }
}
