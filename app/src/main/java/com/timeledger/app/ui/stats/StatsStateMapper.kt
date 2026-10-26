package com.timeledger.app.ui.stats

import com.timeledger.app.domain.goal.GoalProgress
import com.timeledger.app.domain.statistics.RangeStatistics
import com.timeledger.app.domain.statistics.StatisticsPeriod
import com.timeledger.app.ui.day.CatalogLookup
import com.timeledger.app.ui.day.toGoalRows
import com.timeledger.app.ui.format.DisplayFormat
import java.time.LocalDate

/**
 * 区间统计 → 统计页界面状态的**纯函数**映射。
 *
 * 与其他界面映射器同样的理由：本机没有模拟器、没有设备、也没有 Studio Preview，
 * 界面长什么样无法目视确认。所以「算什么、怎么排序、柱子的相对高度按谁算」
 * 这些判断都集中到这里，用单元测试钉死。
 *
 * ## 趋势图的粒度随周期变化
 * 周视图画 7 根柱子（按天）、月视图画 28~31 根（按天）、**年视图画 12 根（按月）**。
 * 年视图如果按天画就是 365 根，既看不清也放不下 —— 粒度选择本身就是「不要为了图表而图表」
 * （需求十三节）的一部分：图要让人一眼看出模式，而不是把所有数据点都塞进去。
 *
 * ## 关于硬编码的中文文案
 * 与 `DayViewStateMapper` 一致：硬编码而不放 `strings.xml`，
 * 是为了让这个类保持纯 JVM 类、测试不需要 Android 环境。详见 README 的设计决策。
 */
class StatsStateMapper(
    /** 活动排行的展示上限，超出的数量会以「另有 N 个活动」说明，而不是悄悄丢掉。 */
    private val maxActivities: Int = 8,
    /** 应用排行的展示上限。与活动共用同一个上限值，避免两处各定一套。 */
    private val maxApps: Int = 8,
) {

    fun map(
        period: StatisticsPeriod,
        stats: RangeStatistics,
        goalProgress: List<GoalProgress>,
        catalog: CatalogLookup,
    ): StatsUiState {
        val total = stats.totalConfirmedMillis
        val trend = buildTrend(period, stats)
        val allActivityRows = activityRows(stats, catalog, total)
        val allAppRows = appRows(stats, total)

        val insights = buildInsights(stats, goalProgress, catalog)
        
        return StatsUiState(
            period = period,
            periodLabel = periodLabel(period),
            rangeLabel = rangeLabel(period, stats),
            totalLabel = DisplayFormat.compactDuration(total),
            averageLabel = "${DisplayFormat.compactDuration(stats.averageConfirmedPerDayMillis)} / 天",
            recordedDaysLabel = "${stats.recordedDayCount}/${stats.dayCount} 天有记录",
            categoryRows = categoryRows(stats, catalog, total),
            activityRows = allActivityRows.take(maxActivities),
            hiddenActivityCount = (allActivityRows.size - maxActivities).coerceAtLeast(0),
            appRows = allAppRows.take(maxActivities),
            hiddenAppCount = (allAppRows.size - maxActivities).coerceAtLeast(0),
            trendBars = trend.bars,
            trendPeakLabel = trend.peakLabel,
            goalRows = goalProgress.toGoalRows(),
            pendingLabel = pendingLabel(stats),
            insights = insights,
            // 完全没有记录（连待确认都没有）才算空；只有待确认时仍要显示，
            // 否则用户会以为「没记过东西」，而实际上系统采到了但还没确认
            isEmpty = total == 0L && stats.totalPendingMillis == 0L,
        )
    }

    /**
     * 按应用排行（用户直接提的需求：「还可以添加一个各个应用分别使用的时间」）。
     *
     * 比例的分母与分类排行一致（已确认总时长），所以「应用占比」和「分类占比」
     * 是同一把尺子 —— 否则用户会看到两个页面各说各话。
     */
    private fun appRows(stats: RangeStatistics, total: Long): List<RankRow> =
        stats.topApps(limit = maxApps).map { app ->
            RankRow(
                name = app.label,
                durationLabel = DisplayFormat.compactDuration(app.durationMillis),
                fraction = if (total <= 0L) 0f else (app.durationMillis.toDouble() / total).toFloat(),
                // 应用没有自己的颜色（分类才有），让界面用主题色画条形
                colorArgb = null,
            )
        }

    private fun periodLabel(period: StatisticsPeriod): String = when (period) {
        StatisticsPeriod.WEEK -> "本周"
        StatisticsPeriod.MONTH -> "本月"
        StatisticsPeriod.YEAR -> "今年"
    }

    private fun rangeLabel(period: StatisticsPeriod, stats: RangeStatistics): String {
        val first = stats.days.firstOrNull()?.epochDay ?: return ""
        val last = stats.days.lastOrNull()?.epochDay ?: return ""
        return when (period) {
            // 周视图给出起止日期：光写「本周」用户不知道是哪七天
            StatisticsPeriod.WEEK ->
                "${DisplayFormat.shortDateLabel(first)}–${DisplayFormat.shortDateLabel(last)}"
            StatisticsPeriod.MONTH -> DisplayFormat.monthLabel(first)
            StatisticsPeriod.YEAR -> DisplayFormat.yearLabel(first)
        }
    }

    private fun categoryRows(
        stats: RangeStatistics,
        catalog: CatalogLookup,
        total: Long,
    ): List<RankRow> {
        if (total <= 0L) return emptyList()
        return stats.total.byCategory.map { bucket ->
            RankRow(
                name = catalog.categoryName(bucket.categoryId) ?: UNCLASSIFIED,
                durationLabel = DisplayFormat.compactDuration(bucket.durationMillis),
                // 分母用「已确认总时长」：分类之间可比，而且各分类占比之和正好是 100%
                fraction = fractionOf(bucket.durationMillis, total),
                colorArgb = catalog.categoryColor(bucket.categoryId),
            )
        }
    }

    private fun activityRows(
        stats: RangeStatistics,
        catalog: CatalogLookup,
        total: Long,
    ): List<RankRow> {
        if (total <= 0L) return emptyList()
        return stats.total.byActivity.map { bucket ->
            RankRow(
                name = catalog.activityName(bucket.activityId) ?: UNNAMED_ACTIVITY,
                durationLabel = DisplayFormat.compactDuration(bucket.durationMillis),
                fraction = fractionOf(bucket.durationMillis, total),
                // 活动没配颜色就退回它所属分类的颜色，界面上仍能看出归属
                colorArgb = catalog.activityColor(bucket.activityId)
                    ?: catalog.categoryColor(bucket.categoryId),
            )
        }
    }

    private fun pendingLabel(stats: RangeStatistics): String? =
        if (stats.totalPendingMillis <= 0L) {
            null
        } else {
            "${DisplayFormat.compactDuration(stats.totalPendingMillis)} 待确认"
        }

    /**
     * 构建统计结论列表，提供关键信息的快速概览。
     */
    private fun buildInsights(
        stats: RangeStatistics, 
        goalProgress: List<GoalProgress>,
        catalog: CatalogLookup
    ): List<String> {
        val insights = mutableListOf<String>()
        
        // 添加总时长信息
        if (stats.totalConfirmedMillis > 0) {
            insights.add("已记录 ${DisplayFormat.compactDuration(stats.totalConfirmedMillis)}")
        }
        
        // 添加待确认信息
        if (stats.totalPendingMillis > 0) {
            insights.add("${DisplayFormat.compactDuration(stats.totalPendingMillis)} 待确认")
        }
        
        // 添加目标完成情况（如果有未完成的目标）
        val unfinishedGoals = goalProgress.filter { !it.isAchieved }
        if (unfinishedGoals.isNotEmpty()) {
            unfinishedGoals.take(2).forEach { progress -> // 最多显示2个未完成目标
                val remaining = progress.target - progress.achieved
                val remainingLabel = DisplayFormat.goalAchievedLabel(remaining, progress.goal.type)
                insights.add("${progress.goal.title} 还差 $remainingLabel")
            }
        }
        
        // 如果有分类信息，可以添加主要活动信息
        if (stats.total.byCategory.isNotEmpty()) {
            val topCategory = stats.total.byCategory.maxByOrNull { it.durationMillis }
            if (topCategory != null && topCategory.durationMillis > 0) {
                val categoryName = catalog.categoryName(topCategory.categoryId) ?: "未归类"
                insights.add("主要时间花在 $categoryName (${DisplayFormat.compactDuration(topCategory.durationMillis)})")
            }
        }
        
        return insights
    }

    // ---------------- 趋势 ----------------

    private data class Trend(val bars: List<TrendBar>, val peakLabel: String?)

    private fun buildTrend(period: StatisticsPeriod, stats: RangeStatistics): Trend =
        when (period) {
            StatisticsPeriod.WEEK -> dailyTrend(
                stats = stats,
                labelOf = { epochDay -> weekdayShort(epochDay) },
            )
            StatisticsPeriod.MONTH -> dailyTrend(
                stats = stats,
                labelOf = { epochDay -> LocalDate.ofEpochDay(epochDay).dayOfMonth.toString() },
                // 一个月有 28~31 根柱子，标签全显示必然挤在一起：
                // 每 5 天标一次，既能定位又不重叠
                showLabel = { epochDay -> LocalDate.ofEpochDay(epochDay).dayOfMonth % 5 == 0 },
            )

            StatisticsPeriod.YEAR -> monthlyTrend(stats)
        }

    private fun dailyTrend(
        stats: RangeStatistics,
        labelOf: (Long) -> String,
        showLabel: (Long) -> Boolean = { true },
    ): Trend {
        val peak = stats.days.maxOfOrNull { it.breakdown.confirmedMillis } ?: 0L
        return Trend(
            bars = stats.days.map { day ->
                TrendBar(
                    epochDay = day.epochDay,
                    label = labelOf(day.epochDay),
                    fraction = fractionOf(day.breakdown.confirmedMillis, peak),
                    valueLabel = DisplayFormat.compactHoursLabel(day.breakdown.confirmedMillis),
                    showLabel = showLabel(day.epochDay),
                )
            },
            peakLabel = DisplayFormat.compactHoursLabel(peak),
        )
    }

    private fun monthlyTrend(stats: RangeStatistics): Trend {
        val months = monthlyTotals(stats)
        val peak = months.maxOfOrNull { it.millis } ?: 0L
        return Trend(
            bars = months.map { month ->
                TrendBar(
                    epochDay = month.firstDayEpochDay,
                    label = "${month.month}月",
                    fraction = fractionOf(month.millis, peak),
                    valueLabel = DisplayFormat.compactHoursLabel(month.millis),
                    // 一年只有 12 根柱子，标签放得下
                    showLabel = true,
                )
            },
            peakLabel = DisplayFormat.compactHoursLabel(peak),
        )
    }

    private data class MonthTotal(
        val month: Int,
        val firstDayEpochDay: Long,
        val millis: Long,
    )

    /** 把逐日明细按月汇总 —— 只有年视图用得到。 */
    private fun monthlyTotals(stats: RangeStatistics): List<MonthTotal> =
        stats.days
            .groupBy { LocalDate.ofEpochDay(it.epochDay).monthValue }
            .entries
            .sortedBy { it.key }
            .map { (month, days) ->
                MonthTotal(
                    month = month,
                    firstDayEpochDay = days.first().epochDay,
                    millis = days.sumOf { it.breakdown.confirmedMillis },
                )
            }

    private fun weekdayShort(epochDay: Long): String =
        DisplayFormat.weekdayLabel(LocalDate.ofEpochDay(epochDay)).removePrefix("周")

    private fun fractionOf(value: Long, peak: Long): Float =
        if (peak <= 0L) 0f else (value.toDouble() / peak).toFloat()

    private companion object {
        const val UNCLASSIFIED = "未归类"
        const val UNNAMED_ACTIVITY = "未命名活动"
    }
}
