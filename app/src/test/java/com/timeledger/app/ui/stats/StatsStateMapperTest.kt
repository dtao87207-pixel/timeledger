package com.timeledger.app.ui.stats

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.domain.goal.GoalProgress
import com.timeledger.app.domain.model.Goal
import com.timeledger.app.domain.model.GoalPeriod
import com.timeledger.app.domain.model.GoalType
import com.timeledger.app.domain.model.RecordSource
import com.timeledger.app.domain.model.TimeRecord
import com.timeledger.app.domain.statistics.RangeStatistics
import com.timeledger.app.domain.statistics.StatisticsCalculator
import com.timeledger.app.domain.statistics.StatisticsPeriod
import com.timeledger.app.ui.day.CatalogLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 统计页状态映射测试。
 *
 * 日期基准：2026-09-12（周六），所在周为 09-07（周一）~ 09-13（周日）；
 * 2026 年 9 月 30 天；2026 年 365 天。
 */
class StatsStateMapperTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private val fixedNow = Instant.parse("2026-09-12T06:00:00Z").toEpochMilli()
    private val dayBoundary = DayBoundary(zoneId = shanghai, nowProvider = { fixedNow })

    private val sept1: Long = LocalDate.of(2026, 9, 1).toEpochDay()
    private val sept7: Long = LocalDate.of(2026, 9, 7).toEpochDay()
    private val sept12: Long = LocalDate.of(2026, 9, 12).toEpochDay()
    private val sept30: Long = LocalDate.of(2026, 9, 30).toEpochDay()

    private val hour = 3600_000L
    private val calculator = StatisticsCalculator(dayBoundary)
    private val mapper = StatsStateMapper()

    private val catalog = CatalogLookup(
        categoryNames = mapOf(1L to "学习", 2L to "娱乐"),
        categoryColors = mapOf(1L to 0xFF3F6BFF.toInt()),
        activityNames = mapOf(100L to "Java", 200L to "B站"),
        activityColors = mapOf(200L to 0xFFFF8A3D.toInt()),
    )

    private fun recordOn(
        day: Long,
        fromHoursFrom4am: Double,
        durationHours: Double,
        categoryId: Long? = 1L,
        activityId: Long? = 100L,
        source: RecordSource = RecordSource.MANUAL,
    ) = TimeRecord(
        startMillis = dayBoundary.dayRange(day).startMillis +
            (fromHoursFrom4am * hour).toLong(),
        endMillis = dayBoundary.dayRange(day).startMillis +
            ((fromHoursFrom4am + durationHours) * hour).toLong(),
        categoryId = categoryId,
        activityId = activityId,
        source = source,
    )

    private fun statsOf(period: StatisticsPeriod, records: List<TimeRecord>): RangeStatistics =
        calculator.compute(records, period, sept12)

    private fun map(
        period: StatisticsPeriod,
        records: List<TimeRecord>,
        goals: List<GoalProgress> = emptyList(),
        mapperToUse: StatsStateMapper = mapper,
    ): StatsUiState = mapperToUse.map(
        period = period,
        stats = statsOf(period, records),
        goalProgress = goals,
        catalog = catalog,
    )

    private fun goalProgress(id: Long, period: GoalPeriod) = GoalProgress(
        goal = Goal(
            id = id,
            title = "目标$id",
            type = GoalType.TIME,
            period = period,
            targetValue = 3 * 3600.0,
            categoryId = 1L,
        ),
        window = dayBoundary.dayRange(sept12),
        achieved = 3600.0,
        target = 3 * 3600.0,
        contributingRecordCount = 1,
    )

    // ---------------- 汇总 ----------------

    @Test
    fun `week state reports range totals and average per day`() {
        val state = map(
            StatisticsPeriod.WEEK,
            listOf(
                recordOn(sept7, 5.0, 2.0),
                recordOn(sept12, 5.0, 3.0),
            ),
        )

        assertEquals(StatisticsPeriod.WEEK, state.period)
        assertEquals("本周", state.periodLabel)
        // 周视图给出起止日期：光写「本周」用户不知道是哪七天
        assertEquals("9月7日–9月13日", state.rangeLabel)
        assertEquals("5h", state.totalLabel)
        // 5 小时 / 7 天 = 42 分钟（向下取整到分钟）
        assertEquals("42m / 天", state.averageLabel)
        assertEquals("2/7 天有记录", state.recordedDaysLabel)
        assertNull(state.pendingLabel)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `month state uses the month label`() {
        val state = map(StatisticsPeriod.MONTH, listOf(recordOn(sept12, 5.0, 2.0)))

        assertEquals("本月", state.periodLabel)
        assertEquals("2026年9月", state.rangeLabel)
        assertEquals("2h", state.totalLabel)
        assertEquals("4m / 天", state.averageLabel)
    }

    @Test
    fun `year state uses the year label`() {
        val state = map(StatisticsPeriod.YEAR, listOf(recordOn(sept12, 5.0, 2.0)))

        assertEquals("今年", state.periodLabel)
        assertEquals("2026年", state.rangeLabel)
        assertEquals("2h", state.totalLabel)
    }

    @Test
    fun `empty range is flagged empty`() {
        val state = map(StatisticsPeriod.WEEK, emptyList())

        assertTrue(state.isEmpty)
        assertEquals("0m", state.totalLabel)
        assertNull(state.trendPeakLabel)
        assertTrue(state.categoryRows.isEmpty())
        assertTrue(state.activityRows.isEmpty())
        assertFalse(state.hasTrend)
    }

    @Test
    fun `pending only range is not treated as empty`() {
        val state = map(
            StatisticsPeriod.WEEK,
            listOf(recordOn(sept12, 5.0, 1.0, source = RecordSource.SUGGESTED)),
        )

        // 有数据（只是还没确认），不该显示「还没有记录」的空状态
        assertFalse(state.isEmpty)
        assertEquals("0m", state.totalLabel)
        assertEquals("1h 待确认", state.pendingLabel)
        // 但待确认不进分类分布
        assertTrue(state.categoryRows.isEmpty())
    }

    // ---------------- 趋势 ----------------

    @Test
    fun `week trend has one bar per day labelled by weekday`() {
        val state = map(StatisticsPeriod.WEEK, listOf(recordOn(sept12, 5.0, 2.0)))

        assertEquals(7, state.trendBars.size)
        assertEquals(listOf("一", "二", "三", "四", "五", "六", "日"), state.trendBars.map { it.label })
        // 一周只有 7 根柱子，标签全显示
        assertTrue(state.trendBars.all { it.showLabel })
        assertTrue(state.hasTrend)
    }

    @Test
    fun `month trend has one bar per day and labels every fifth`() {
        val state = map(StatisticsPeriod.MONTH, listOf(recordOn(sept12, 5.0, 2.0)))

        assertEquals(30, state.trendBars.size)
        // 一个月 30 根柱子，标签全显示必然挤在一起：每 5 天标一次
        val labelled = state.trendBars.filter { it.showLabel }
        assertEquals(6, labelled.size)
        assertEquals(listOf("5", "10", "15", "20", "25", "30"), labelled.map { it.label })
    }

    @Test
    fun `trend fractions are relative to the peak day`() {
        val state = map(
            StatisticsPeriod.MONTH,
            listOf(
                recordOn(sept1, 5.0, 4.0),  // 最长的一天
                recordOn(sept12, 5.0, 2.0), // 一半
            ),
        )

        val barsByDay = state.trendBars.associateBy { it.epochDay }
        assertEquals(1f, barsByDay.getValue(sept1).fraction, 0.0001f)
        assertEquals(0.5f, barsByDay.getValue(sept12).fraction, 0.0001f)
        assertEquals(0f, barsByDay.getValue(sept30).fraction, 0.0001f)
        // 没记录的日子不写 0h，柱子留空
        assertNull(barsByDay.getValue(sept30).valueLabel)
        assertEquals("4h", state.trendPeakLabel)
    }

    @Test
    fun `year trend aggregates days into months`() {
        val state = map(
            StatisticsPeriod.YEAR,
            listOf(
                recordOn(LocalDate.of(2026, 1, 5).toEpochDay(), 5.0, 2.0),
                recordOn(LocalDate.of(2026, 1, 20).toEpochDay(), 5.0, 3.0),
                recordOn(LocalDate.of(2026, 3, 10).toEpochDay(), 5.0, 4.0),
            ),
        )

        // 年视图画 12 根柱子而不是 365 根
        assertEquals(12, state.trendBars.size)
        assertEquals("1月", state.trendBars.first().label)
        assertEquals("12月", state.trendBars.last().label)
        assertTrue(state.trendBars.all { it.showLabel })

        val january = state.trendBars[0]
        val february = state.trendBars[1]
        val march = state.trendBars[2]
        // 1 月两天合计 5 小时，是峰值
        assertEquals("5h", january.valueLabel)
        assertEquals(1f, january.fraction, 0.0001f)
        assertNull(february.valueLabel)
        assertEquals("4h", march.valueLabel)
        assertEquals(0.8f, march.fraction, 0.0001f)
        assertEquals("5h", state.trendPeakLabel)
    }

    // ---------------- 排行 ----------------

    @Test
    fun `category rows are ordered with shares of the confirmed total`() {
        val state = map(
            StatisticsPeriod.WEEK,
            listOf(
                recordOn(sept7, 5.0, 3.0),                                    // 学习 3h
                recordOn(sept12, 5.0, 1.0, categoryId = 2L, activityId = 200L), // 娱乐 1h
            ),
        )

        assertEquals(2, state.categoryRows.size)
        assertEquals("学习", state.categoryRows[0].name)
        assertEquals("3h", state.categoryRows[0].durationLabel)
        assertEquals(0.75f, state.categoryRows[0].fraction, 0.0001f)
        assertEquals(0xFF3F6BFF.toInt(), state.categoryRows[0].colorArgb)

        assertEquals("娱乐", state.categoryRows[1].name)
        assertEquals(0.25f, state.categoryRows[1].fraction, 0.0001f)
        // 没有配色的分类返回 null，界面用默认色
        assertNull(state.categoryRows[1].colorArgb)
        assertTrue(state.hasCategories)
    }

    @Test
    fun `activity rows fall back to their category colour`() {
        val state = map(
            StatisticsPeriod.WEEK,
            listOf(
                recordOn(sept7, 5.0, 1.0, categoryId = 1L, activityId = 100L),   // Java，无活动色
                recordOn(sept12, 5.0, 1.0, categoryId = 2L, activityId = 200L), // B站，有活动色
            ),
        )

        val java = state.activityRows.first { it.name == "Java" }
        val bilibili = state.activityRows.first { it.name == "B站" }
        // 活动没配颜色时退回所属分类的颜色，界面上仍能看出归属
        assertEquals(0xFF3F6BFF.toInt(), java.colorArgb)
        assertEquals(0xFFFF8A3D.toInt(), bilibili.colorArgb)
    }

    @Test
    fun `activity rows are limited and the remainder is reported`() {
        val limited = StatsStateMapper(maxActivities = 2)
        val state = map(
            StatisticsPeriod.WEEK,
            listOf(
                recordOn(sept7, 5.0, 1.0, activityId = 100L),
                recordOn(sept7, 8.0, 1.0, activityId = 200L),
                recordOn(sept7, 11.0, 1.0, activityId = 300L),
            ),
            mapperToUse = limited,
        )

        assertEquals(2, state.activityRows.size)
        // 被截掉的不是悄悄丢掉，而是明确告知数量
        assertEquals(1, state.hiddenActivityCount)
    }

    @Test
    fun `unknown activity id falls back to a readable name`() {
        val state = map(
            StatisticsPeriod.WEEK,
            listOf(recordOn(sept7, 5.0, 1.0, categoryId = 1L, activityId = 999L)),
        )

        assertEquals("未命名活动", state.activityRows.single().name)
    }

    @Test
    fun `unclassified time falls back to a readable name`() {
        val state = map(
            StatisticsPeriod.WEEK,
            listOf(recordOn(sept7, 5.0, 1.0, categoryId = null, activityId = null)),
        )

        assertEquals("未归类", state.categoryRows.single().name)
        assertTrue(state.activityRows.isEmpty())
    }

    // ---------------- 目标 ----------------

    @Test
    fun `goal rows are carried through and ordered by period`() {
        val state = map(
            StatisticsPeriod.WEEK,
            emptyList(),
            goals = listOf(
                goalProgress(id = 3L, period = GoalPeriod.MONTH),
                goalProgress(id = 1L, period = GoalPeriod.DAY),
                goalProgress(id = 2L, period = GoalPeriod.WEEK),
            ),
        )

        assertEquals(listOf("今日", "本周", "本月"), state.goalRows.map { it.periodLabel })
        assertEquals(listOf(1L, 2L, 3L), state.goalRows.map { it.goalId })
        assertTrue(state.hasGoals)
    }

    @Test
    fun `no goals yields an empty goal section`() {
        val state = map(StatisticsPeriod.WEEK, emptyList())
        assertFalse(state.hasGoals)
    }
}
