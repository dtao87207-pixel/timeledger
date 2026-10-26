package com.timeledger.app.domain.statistics

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.core.time.TimeBoundary
import com.timeledger.app.domain.model.RecordSource
import com.timeledger.app.domain.model.TimeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 统计数据计算测试。
 *
 * 日期基准：2026-09-12 是**周六**，所在周为 09-07（周一）~ 09-13（周日）；
 * 2026 年 9 月有 30 天；2026 年不是闰年，共 365 天。
 */
class StatisticsCalculatorTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private val fixedNow = Instant.parse("2026-09-12T06:00:00Z").toEpochMilli()
    private val dayBoundary = DayBoundary(zoneId = shanghai, nowProvider = { fixedNow })
    private val calculator = StatisticsCalculator(dayBoundary)

    private val hour = 3600_000L
    private val sept7: Long = LocalDate.of(2026, 9, 7).toEpochDay()   // 周一
    private val sept11: Long = LocalDate.of(2026, 9, 11).toEpochDay() // 周五
    private val sept12: Long = LocalDate.of(2026, 9, 12).toEpochDay() // 周六（参考日）
    private val sept13: Long = LocalDate.of(2026, 9, 13).toEpochDay() // 周日

    /** 距该逻辑日 04:00 的第 N 小时；负值表示当天 04:00 之前（属于前一个逻辑日）。 */
    private fun at(day: Long, hoursFrom4am: Double): Long =
        dayBoundary.dayRange(day).startMillis + (hoursFrom4am * hour).toLong()

    private fun record(
        startMillis: Long,
        durationHours: Double,
        categoryId: Long? = 1L,
        activityId: Long? = null,
        source: RecordSource = RecordSource.MANUAL,
        id: Long = 0L,
    ) = TimeRecord(
        id = id,
        startMillis = startMillis,
        endMillis = startMillis + (durationHours * hour).toLong(),
        categoryId = categoryId,
        activityId = activityId,
        source = source,
    )

    private fun recordOn(
        day: Long,
        fromHoursFrom4am: Double,
        durationHours: Double,
        categoryId: Long? = 1L,
        source: RecordSource = RecordSource.MANUAL,
    ) = record(at(day, fromHoursFrom4am), durationHours, categoryId, source = source)

    // ---------------- 区间边界 ----------------

    @Test
    fun `week period is monday to sunday`() {
        val days = calculator.epochDaysOf(StatisticsPeriod.WEEK, sept12)

        assertEquals(7, days.size)
        assertEquals(sept7, days.first())
        assertEquals(sept13, days.last())
        // 与 TimeBoundary 的周定义一致（周一 04:00 ~ 下周一 04:00）
        assertEquals(TimeBoundary.weekRange(sept12, shanghai), calculator.windowFor(StatisticsPeriod.WEEK, sept12))
    }

    @Test
    fun `month period covers the whole calendar month`() {
        val days = calculator.epochDaysOf(StatisticsPeriod.MONTH, sept12)

        assertEquals(30, days.size) // 2026 年 9 月
        assertEquals(LocalDate.of(2026, 9, 1).toEpochDay(), days.first())
        assertEquals(LocalDate.of(2026, 9, 30).toEpochDay(), days.last())
        assertEquals(30 * 24 * hour, calculator.windowFor(StatisticsPeriod.MONTH, sept12).durationMillis)
    }

    @Test
    fun `year period covers the whole calendar year`() {
        val days = calculator.epochDaysOf(StatisticsPeriod.YEAR, sept12)

        assertEquals(365, days.size) // 2026 不是闰年
        assertEquals(LocalDate.of(2026, 1, 1).toEpochDay(), days.first())
        assertEquals(LocalDate.of(2026, 12, 31).toEpochDay(), days.last())
    }

    @Test
    fun `any day inside the period yields the same window`() {
        // 落在同一周里的任意一天，得到的都是同一周
        val fromMonday = calculator.windowFor(StatisticsPeriod.WEEK, sept7)
        val fromSaturday = calculator.windowFor(StatisticsPeriod.WEEK, sept12)
        val fromSunday = calculator.windowFor(StatisticsPeriod.WEEK, sept13)
        assertEquals(fromMonday, fromSaturday)
        assertEquals(fromMonday, fromSunday)
    }

    // ---------------- 逐日与汇总一致 ----------------

    @Test
    fun `daily windows tile the range window exactly`() {
        for (period in StatisticsPeriod.entries) {
            val stats = calculator.compute(emptyList(), period, sept12)
            val window = stats.window

            assertEquals(window.startMillis, stats.days.first().let { dayBoundary.dayRange(it.epochDay).startMillis })
            assertEquals(window.endMillisExclusive, stats.days.last().let { dayBoundary.dayRange(it.epochDay).endMillisExclusive })

            // 相邻两天的窗口首尾相接，没有缝也没有重叠
            stats.days.zipWithNext().forEach { (a, b) ->
                assertEquals(
                    dayBoundary.dayRange(a.epochDay).endMillisExclusive,
                    dayBoundary.dayRange(b.epochDay).startMillis,
                )
            }
        }
    }

    @Test
    fun `per day totals sum to the range total`() {
        val records = listOf(
            recordOn(sept7, 5.0, 2.0),
            recordOn(sept12, 5.0, 3.0),
            recordOn(sept13, 14.0, 1.0, categoryId = 2L),
            recordOn(sept12, 10.0, 1.5, categoryId = 2L, source = RecordSource.SUGGESTED),
        )

        val stats = calculator.compute(records, StatisticsPeriod.WEEK, sept12)

        assertEquals(
            stats.total.confirmedMillis,
            stats.days.sumOf { it.breakdown.confirmedMillis },
        )
        assertEquals(
            stats.total.coveredMillis,
            stats.days.sumOf { it.breakdown.coveredMillis },
        )
        assertEquals(
            stats.total.unrecordedMillis,
            stats.days.sumOf { it.breakdown.unrecordedMillis },
        )
        assertEquals(
            stats.total.pendingMillis,
            stats.days.sumOf { it.breakdown.pendingMillis },
        )
    }

    @Test
    fun `a record crossing the day boundary is split across two days`() {
        // 周六 03:00–05:00：属于周五那一格的 1 小时 + 属于周六那一格的 1 小时
        val crossing = record(at(sept12, -1.0), durationHours = 2.0)

        val stats = calculator.compute(listOf(crossing), StatisticsPeriod.WEEK, sept12)

        val friday = stats.days.first { it.epochDay == sept11 }.breakdown
        val saturday = stats.days.first { it.epochDay == sept12 }.breakdown

        assertEquals(1 * hour, friday.confirmedMillis)
        assertEquals(1 * hour, saturday.confirmedMillis)
        // 两天加起来正好是记录总时长，不重不漏
        assertEquals(2 * hour, stats.totalConfirmedMillis)
    }

    @Test
    fun `records outside the range are clipped away`() {
        // 上上周的一条记录不该出现在本周统计里
        val lastWeek = recordOn(sept7 - 7, 5.0, 4.0)
        val thisWeek = recordOn(sept12, 5.0, 2.0)

        val stats = calculator.compute(listOf(lastWeek, thisWeek), StatisticsPeriod.WEEK, sept12)

        assertEquals(2 * hour, stats.totalConfirmedMillis)
        assertEquals(2 * hour, stats.days.sumOf { it.breakdown.confirmedMillis })
    }

    // ---------------- 日均与覆盖率 ----------------

    @Test
    fun `average per day uses every day in the range as denominator`() {
        // 一周只记了一天 7 小时：日均是 1 小时而不是 7 小时
        val stats = calculator.compute(
            listOf(recordOn(sept12, 5.0, 7.0)),
            StatisticsPeriod.WEEK,
            sept12,
        )

        assertEquals(7 * hour, stats.totalConfirmedMillis)
        assertEquals(7, stats.dayCount)
        assertEquals(1 * hour, stats.averageConfirmedPerDayMillis)
    }

    @Test
    fun `recorded day count and coverage ratio`() {
        val stats = calculator.compute(
            listOf(
                recordOn(sept7, 5.0, 1.0),
                recordOn(sept12, 5.0, 2.0),
            ),
            StatisticsPeriod.WEEK,
            sept12,
        )

        assertEquals(2, stats.recordedDayCount)
        assertEquals(5, stats.emptyDayCount)
        assertEquals(2.0 / 7.0, stats.recordedDayRatio, 0.0001)
    }

    @Test
    fun `empty range reports zero averages and full unrecorded time`() {
        val stats = calculator.compute(emptyList(), StatisticsPeriod.WEEK, sept12)

        assertEquals(0L, stats.totalConfirmedMillis)
        assertEquals(0L, stats.averageConfirmedPerDayMillis)
        assertEquals(0, stats.recordedDayCount)
        assertEquals(0.0, stats.recordedDayRatio, 0.0001)
        assertEquals(0L, stats.uncategorizedMillis)
        // 一周完全没有记录 → 未记录时长等于整周
        assertEquals(stats.window.durationMillis, stats.total.unrecordedMillis)
    }

    // ---------------- 序列（供趋势图） ----------------

    @Test
    fun `series are continuous and include days without records`() {
        val stats = calculator.compute(
            listOf(recordOn(sept12, 5.0, 2.0)),
            StatisticsPeriod.WEEK,
            sept12,
        )

        val series = stats.confirmedSeries()

        assertEquals(7, series.size)
        // 首尾就是周一与周日，中间每天都有一点，没有跳号
        assertEquals(sept7, series.first().epochDay)
        assertEquals(sept13, series.last().epochDay)
        // 没有记录的日子是 0，而不是被跳过 —— 折线图需要连续的空点
        assertEquals(0L, series.first { it.epochDay == sept7 }.durationMillis)
        assertEquals(2 * hour, series.first { it.epochDay == sept12 }.durationMillis)
        assertEquals(0L, series.first { it.epochDay == sept13 }.durationMillis)
    }

    @Test
    fun `category series extracts one category per day`() {
        val stats = calculator.compute(
            listOf(
                recordOn(sept7, 5.0, 2.0, categoryId = 1L),
                recordOn(sept12, 5.0, 3.0, categoryId = 1L),
                recordOn(sept12, 10.0, 1.0, categoryId = 2L),
            ),
            StatisticsPeriod.WEEK,
            sept12,
        )

        val study = stats.categorySeries(1L)
        val funSeries = stats.categorySeries(2L)

        assertEquals(7, study.size)
        assertEquals(2 * hour, study.first { it.epochDay == sept7 }.durationMillis)
        assertEquals(3 * hour, study.first { it.epochDay == sept12 }.durationMillis)
        assertEquals(0L, study.first { it.epochDay == sept13 }.durationMillis)
        assertEquals(1 * hour, funSeries.first { it.epochDay == sept12 }.durationMillis)
    }

    @Test
    fun `top categories come from the range total in descending order`() {
        val stats = calculator.compute(
            listOf(
                recordOn(sept7, 5.0, 1.0, categoryId = 1L),
                recordOn(sept12, 5.0, 3.0, categoryId = 2L),
                recordOn(sept13, 5.0, 2.0, categoryId = 3L),
            ),
            StatisticsPeriod.WEEK,
            sept12,
        )

        val top = stats.topCategories(limit = 2)

        assertEquals(2, top.size)
        assertEquals(2L, top[0].categoryId) // 3 小时
        assertEquals(3L, top[1].categoryId) // 2 小时
        assertEquals(3, stats.total.byCategory.size)
    }

    // ---------------- 待确认与未分类 ----------------

    @Test
    fun `pending time is excluded from confirmed but keeps its day recorded`() {
        val stats = calculator.compute(
            listOf(recordOn(sept12, 5.0, 1.0, source = RecordSource.SUGGESTED)),
            StatisticsPeriod.WEEK,
            sept12,
        )

        val saturday = stats.days.first { it.epochDay == sept12 }.breakdown
        assertEquals(0L, saturday.confirmedMillis)
        assertEquals(1 * hour, saturday.pendingMillis)
        assertEquals(1 * hour, saturday.coveredMillis)
        // 有记录（只是待确认），所以这天算「有记录的天」
        assertEquals(1, stats.recordedDayCount)
    }

    @Test
    fun `uncategorized confirmed time is reported separately`() {
        val stats = calculator.compute(
            listOf(
                recordOn(sept12, 5.0, 1.0, categoryId = null),
                recordOn(sept12, 10.0, 2.0, categoryId = 1L),
            ),
            StatisticsPeriod.WEEK,
            sept12,
        )

        assertEquals(1 * hour, stats.uncategorizedMillis)
        assertEquals(3 * hour, stats.totalConfirmedMillis)
        // 未分类单独成桶，所以各分类之和仍然等于已确认总时长
        assertEquals(stats.totalConfirmedMillis, stats.total.byCategory.sumOf { it.durationMillis })
    }

    // ---------------- 月 / 年 ----------------

    @Test
    fun `month statistics aggregate across weeks`() {
        val records = listOf(
            recordOn(LocalDate.of(2026, 9, 2).toEpochDay(), 5.0, 2.0, categoryId = 1L),
            recordOn(LocalDate.of(2026, 9, 18).toEpochDay(), 5.0, 3.0, categoryId = 1L),
            recordOn(LocalDate.of(2026, 9, 30).toEpochDay(), 5.0, 1.0, categoryId = 2L),
        )

        val stats = calculator.compute(records, StatisticsPeriod.MONTH, sept12)

        assertEquals(30, stats.dayCount)
        assertEquals(6 * hour, stats.totalConfirmedMillis)
        // 分类汇总跨周累加：学习 2h（9/2）+ 3h（9/18）= 5h
        assertEquals(5 * hour, stats.total.durationOfCategory(1L))
        assertEquals(1 * hour, stats.total.durationOfCategory(2L))
        assertEquals(6 * hour, stats.days.sumOf { it.breakdown.confirmedMillis })
    }

    @Test
    fun `year statistics provide basic totals`() {
        val records = listOf(
            recordOn(LocalDate.of(2026, 1, 5).toEpochDay(), 5.0, 2.0),
            recordOn(LocalDate.of(2026, 12, 25).toEpochDay(), 5.0, 4.0),
        )

        val stats = calculator.compute(records, StatisticsPeriod.YEAR, sept12)

        assertEquals(365, stats.dayCount)
        assertEquals(6 * hour, stats.totalConfirmedMillis)
        assertEquals(2, stats.recordedDayCount)
    }
}
