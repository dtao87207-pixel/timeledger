package com.timeledger.app.domain.statistics

import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.review.DurationBreakdown

/**
 * 某一天的统计结果：逻辑日 + 该日的时长分布。
 *
 * 直接复用 [DurationBreakdown]，不另建一个「日统计」结构 ——
 * 单日该怎么算只有一处实现（[com.timeledger.app.domain.review.TimelineAggregator]），
 * 日统计、周统计、复盘用的是同一套口径。
 */
data class DayStat(
    val epochDay: Long,
    val breakdown: DurationBreakdown,
)

/** 趋势图上的一个数据点：某一天的时长。 */
data class DaySeriesPoint(
    val epochDay: Long,
    val durationMillis: Long,
)

/**
 * 某个 App 在一段时间里的使用时长（按应用排行用）。
 *
 * 只统计**系统采集**写下的记录：包名从记录的幂等键里反解出来（见
 * [com.timeledger.app.domain.usage.UsageRecordKeys]），因此不需要给数据库加列。
 * 手动记录不参与 —— 用户手写的那一笔本来就没说自己是哪个 App。
 */
data class AppUsageStat(
    val packageName: String,
    /** 显示名：系统采集时写入的应用名，拿不到就退回包名。 */
    val label: String,
    val durationMillis: Long,
    val segmentCount: Int,
)

/**
 * 一段时间的统计结果（周 / 月 / 年）。
 *
 * ## 为什么用「逐日明细 + 区间汇总」而不是若干条独立序列
 * 需求要的图表有总时长、分类趋势、目标趋势多种，如果每一张图配一个专用序列字段，
 * 模型会迅速膨胀，而且多一张图就要改一次模型。
 * 这里只提供两样东西：
 * - [days]：每一天的完整分布（趋势线、柱状图都从它派生）；
 * - [total]：整个区间的汇总（饼图/环形图的分类占比从 `total.byCategory` 拿）。
 *
 * 于是「总时长趋势」「Java 的趋势」「娱乐的占比」都能由调用方现取现算，
 * 不需要为新图表改模型。
 *
 * ## 逐日明细包含没有记录的日子
 * [days] 是窗口内**每一个**逻辑日，即使某天完全没记录（那时各字段为 0）。
 * 折线图必须要有连续的空点，否则「周二没记录」会被画成「周二和周三连成一条线」，
 * 看上去像是连续学习了两天。
 */
data class RangeStatistics(
    val period: StatisticsPeriod,
    /** 整个统计区间的窗口（周/月/年的 04:00 边界对齐区间）。 */
    val window: MillisRange,
    /** 窗口内每一天的统计，按日期升序。 */
    val days: List<DayStat>,
    /** 整个区间的汇总。分类/活动排行直接用 `total.byCategory` / `total.byActivity`。 */
    val total: DurationBreakdown,
    /** 按应用聚合的使用时长，已按时长降序。空表示这段时间没有系统采集的记录。 */
    val apps: List<AppUsageStat> = emptyList(),
) {

    val dayCount: Int get() = days.size

    val totalConfirmedMillis: Long get() = total.confirmedMillis

    val totalCoveredMillis: Long get() = total.coveredMillis

    val totalPendingMillis: Long get() = total.pendingMillis

    /**
     * 日均已确认时长。
     *
     * 分母是**区间总天数**，不是「有记录的天数」。用后者会得出荒谬的数字：
     * 一个月只记了一天 8 小时，就变成「日均 8 小时」。
     * 日均的意义是「平均每天投入多少」，没记录的日子也应该按 0 计入平均。
     */
    val averageConfirmedPerDayMillis: Long
        get() = if (dayCount == 0) 0L else total.confirmedMillis / dayCount

    val averageCoveredPerDayMillis: Long
        get() = if (dayCount == 0) 0L else total.coveredMillis / dayCount

    /** 区间内有记录的天数。 */
    val recordedDayCount: Int get() = days.count { it.breakdown.coveredMillis > 0L }

    /**
     * 记录覆盖率：有记录的天数 / 总天数。
     *
     * 它衡量的是「这个 App 替用户还原了多少天」，而不是用户效率 ——
     * 覆盖率低就说明系统感知还没帮上忙，这正是需求十七节要验证的问题之一。
     */
    val recordedDayRatio: Double
        get() = if (dayCount == 0) 0.0 else recordedDayCount.toDouble() / dayCount

    /** 完全没有任何记录的天数。 */
    val emptyDayCount: Int get() = dayCount - recordedDayCount

    /** 未分类的已确认时长（系统采到了但还没推断出归属）。 */
    val uncategorizedMillis: Long get() = total.durationOfCategory(null)

    /** 每日「已确认总时长」序列，折线图直接用。 */
    fun confirmedSeries(): List<DaySeriesPoint> =
        days.map { DaySeriesPoint(it.epochDay, it.breakdown.confirmedMillis) }

    /** 每日「有记录总时长」序列（含待确认）。 */
    fun coveredSeries(): List<DaySeriesPoint> =
        days.map { DaySeriesPoint(it.epochDay, it.breakdown.coveredMillis) }

    /** 某个分类每日的已确认时长序列（分类趋势图）。[categoryId] 传 null 表示「未分类」。 */
    fun categorySeries(categoryId: Long?): List<DaySeriesPoint> =
        days.map { DaySeriesPoint(it.epochDay, it.breakdown.durationOfCategory(categoryId)) }

    /** 某个活动每日的已确认时长序列。 */
    fun activitySeries(activityId: Long): List<DaySeriesPoint> =
        days.map { DaySeriesPoint(it.epochDay, it.breakdown.durationOfActivity(activityId)) }

    /** 时长最长的前 [limit] 个分类（饼图/环形图直接用）。 */
    fun topCategories(limit: Int = 6): List<com.timeledger.app.domain.review.DurationBucket> =
        total.byCategory.take(limit)

    /** 时长最长的前 [limit] 个活动。 */
    fun topActivities(limit: Int = 6): List<com.timeledger.app.domain.review.DurationBucket> =
        total.byActivity.take(limit)

    /** 时长最长的前 [limit] 个应用。 */
    fun topApps(limit: Int = 6): List<AppUsageStat> = apps.take(limit)
}
