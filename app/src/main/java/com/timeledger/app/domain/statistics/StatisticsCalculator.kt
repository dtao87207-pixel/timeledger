package com.timeledger.app.domain.statistics

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.core.time.TimeBoundary
import com.timeledger.app.domain.model.TimeRecord
import com.timeledger.app.domain.review.TimelineAggregator
import com.timeledger.app.domain.timeline.ResolvedTimeline
import com.timeledger.app.domain.timeline.TimelineResolver
import com.timeledger.app.domain.usage.UsageRecordKeys

/**
 * 统计计算器 —— 纯逻辑，把一段时间的记录压成 [RangeStatistics]。
 *
 * ## 三个刻意的决定
 *
 * ### 1. 周 / 月 / 年的边界都对齐 04:00
 * 与全局的逻辑日定义一致：一周是「周一 04:00 ~ 下周一 04:00」，
 * 一个月是「1 日 04:00 ~ 次月 1 日 04:00」。
 * 如果统计用自然日、记录用逻辑日，凌晨那几小时就会被算到相邻的区间里，
 * 出现「周统计加起来不等于月统计」这种对不上的结果。
 *
 * ### 2. 窗口由「日期列表」推导，而不是各算各的
 * [windowFor] 先算出区间内的日期列表，再用 [DayBoundary.daysRange] 得到窗口。
 * 这样「逐日明细拼起来」与「区间汇总」在构造上就是同一段时间，
 * 不会因为两处独立算边界而差一个小时。
 *
 * ### 3. 重叠消解整段只做一次
 * 先把整个区间的记录消解成一条时间轴，再按天裁剪。
 * 如果按天分别消解，一条跨天的记录在两天里的胜负可能不一致
 * （例如它在两天分别与不同的记录重叠），跨日统计就会自相矛盾。
 *
 * ## 不变式（测试逐条验证）
 * - 各天 `confirmedMillis` 之和 == 区间 `confirmedMillis`
 * - 各天窗口拼起来 == 区间窗口（因此各天 `unrecordedMillis` 之和 == 区间 `unrecordedMillis`）
 */
class StatisticsCalculator(
    private val dayBoundary: DayBoundary,
) {

    private val aggregator = TimelineAggregator()

    /** 该统计区间包含哪些逻辑日，按日期升序（周固定 7 天，月/年按日历天数）。 */
    fun epochDaysOf(period: StatisticsPeriod, epochDay: Long): List<Long> = when (period) {
        StatisticsPeriod.WEEK -> {
            val start = TimeBoundary.weekStartEpochDay(epochDay)
            TimeBoundary.epochDaysIn(start, start + 6)
        }

        StatisticsPeriod.MONTH -> {
            val firstDay = TimeBoundary.calendarDate(epochDay).withDayOfMonth(1)
            TimeBoundary.epochDaysIn(
                firstDay.toEpochDay(),
                firstDay.plusMonths(1).toEpochDay() - 1,
            )
        }

        StatisticsPeriod.YEAR -> {
            val firstDay = TimeBoundary.calendarDate(epochDay).withDayOfYear(1)
            TimeBoundary.epochDaysIn(
                firstDay.toEpochDay(),
                firstDay.plusYears(1).toEpochDay() - 1,
            )
        }
    }

    /** 该统计区间的时间窗口，与 [epochDaysOf] 严格对应。 */
    fun windowFor(period: StatisticsPeriod, epochDay: Long): MillisRange {
        val days = epochDaysOf(period, epochDay)
        return dayBoundary.daysRange(days.first(), days.last())
    }

    /**
     * 计算统计结果。
     *
     * [records] 必须已经覆盖整个区间 —— 取数由调用方（[StatisticsService]）负责，
     * 本类不碰数据库，因此可以在 JVM 上直接测。
     */
    fun compute(
        records: List<TimeRecord>,
        period: StatisticsPeriod,
        epochDay: Long,
    ): RangeStatistics {
        val days = epochDaysOf(period, epochDay)
        val window = dayBoundary.daysRange(days.first(), days.last())
        val timeline = TimelineResolver.resolve(records)

        return RangeStatistics(
            period = period,
            window = window,
            days = days.map { day ->
                DayStat(epochDay = day, breakdown = aggregator.aggregate(timeline, dayBoundary.dayRange(day)))
            },
            total = aggregator.aggregate(timeline, window),
            apps = appUsage(timeline, window),
        )
    }

    /**
     * 按**应用**聚合（需求十三节之外，用户直接提的需求：
     * 「还可以添加一个各个应用分别使用的时间」）。
     *
     * ## 怎么知道一条记录是哪个 App 的
     * 系统采集写下的记录，幂等键是 `usage|<哪一天>|<开始时刻>|<包名>`（见 `UsageRecordKeys`），
     * 所以包名可以从键里反解出来 —— **不需要给数据库加列**。
     * 手动记录没有这种键，自然不参与应用排行。
     *
     * 显示名优先用备注（系统采集时写入的是应用名），其次用包名 ——
     * 应用名解析失败时至少还能看到 `com.xxx.yyy`，而不是一条无名记录。
     *
     * ## 为什么要按窗口裁剪
     * 跨 4:00 的记录会同时出现在相邻两天的取数结果里，不裁剪就会在两周的统计里
     * 各算一遍。裁剪之后，「各天分摊之和 == 总时长」这条不变式对应用排行同样成立。
     */
    private fun appUsage(
        timeline: ResolvedTimeline,
        window: MillisRange,
    ): List<AppUsageStat> = timeline.segments
        .mapNotNull { segment ->
            val packageName = UsageRecordKeys.packageNameOf(segment.record.externalKey)
                ?: return@mapNotNull null
            val clipped = if (segment.isInstant) {
                segment.range.takeIf { it.startMillis in window }
            } else {
                segment.range.intersect(window)
            } ?: return@mapNotNull null
            Triple(packageName, segment.record.note?.takeIf { it.isNotBlank() }, clipped.durationMillis)
        }
        .groupBy { it.first }
        .map { (packageName, entries) ->
            AppUsageStat(
                packageName = packageName,
                label = entries.mapNotNull { it.second }.firstOrNull() ?: packageName,
                durationMillis = entries.sumOf { it.third },
                segmentCount = entries.size,
            )
        }
        .filter { it.durationMillis > 0L }
        .sortedWith(compareByDescending<AppUsageStat> { it.durationMillis }.thenBy { it.packageName })
}
