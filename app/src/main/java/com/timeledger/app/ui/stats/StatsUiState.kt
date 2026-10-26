package com.timeledger.app.ui.stats

import com.timeledger.app.domain.statistics.StatisticsPeriod
import com.timeledger.app.ui.day.GoalRow

/** 排行里的一行（分类或活动）。条形长度是占「已确认总时长」的比例。 */
data class RankRow(
    val name: String,
    val durationLabel: String,
    val fraction: Float,
    val colorArgb: Int?,
)

/**
 * 趋势图上的一根柱子。
 *
 * [label] 的含义随周期变化：周视图是星期几、月视图是几号、年视图是几月 ——
 * 因为「趋势」在不同周期下要看的粒度不同，年视图画 365 根柱子既看不清也放不下。
 */
data class TrendBar(
    /** 该柱子代表的时间（周/月为那一天，年为该月第一天）。 */
    val epochDay: Long,
    val label: String,
    val fraction: Float,
    /** 该柱的时长文案；没有记录时为 null（柱子留空，不写 0h）。 */
    val valueLabel: String?,
    /**
     * 是否在柱子下方显示 [label]。
     *
     * 为什么由映射器决定而不是界面决定：月视图有 31 根柱子，每根只有十来个 dp 宽，
     * 标签全显示必然互相挤压或截断。这个「隔几根显示一个标签」的判断属于
     * 「显示什么」，而本机无法目视验证界面 —— 放在映射器里才能被测试覆盖。
     */
    val showLabel: Boolean,
)

/**
 * 统计页状态（需求十三节）。
 *
 * 和界面其他部分一样，这是个**纯数据**结构，由 [StatsStateMapper] 从领域模型算出来，
 * 因此「显示什么」可以在 JVM 上测 —— 本机没有模拟器与设备，这是唯一的验证手段。
 */
data class StatsUiState(
    /** 当前选中的周期，界面据此高亮选择器。 */
    val period: StatisticsPeriod,
    /** `本周` / `本月` / `今年`。 */
    val periodLabel: String,
    /** `9月7日–9月13日` / `2026年9月` / `2026年`。 */
    val rangeLabel: String,
    /** 区间总时长。 */
    val totalLabel: String,
    /** 日均时长：`5.4h / 天`。 */
    val averageLabel: String,
    /** `6/7 天有记录`。 */
    val recordedDaysLabel: String,
    val categoryRows: List<RankRow>,
    val activityRows: List<RankRow>,
    /** 因超出展示上限而未列出的活动数量；没有时为 0。 */
    val hiddenActivityCount: Int,
    /**
     * 按应用排行：`抖音极速版 1h55m`。
     *
     * 用户直接提的需求：「还可以添加一个各个应用分别使用的时间」。
     * 分类回答「我在什么上花时间」，应用回答「具体在用什么」—— 两个都要。
     */
    val appRows: List<RankRow>,
    /** 因超出展示上限而未列出的应用数量。 */
    val hiddenAppCount: Int,
    val trendBars: List<TrendBar>,
    /** 趋势图峰值文案，用于让柱子的相对高度有意义；没有数据时为 null。 */
    val trendPeakLabel: String?,
    /** 目标完成率（各目标按自己的周期计算）。 */
    val goalRows: List<GoalRow>,
    val pendingLabel: String?,
    /** 统计结论列表，用于快速了解关键信息。 */
    val insights: List<String>,
    /** 这段时间完全没有记录。 */
    val isEmpty: Boolean,
) {

    val hasCategories: Boolean get() = categoryRows.isNotEmpty()

    val hasActivities: Boolean get() = activityRows.isNotEmpty()

    val hasApps: Boolean get() = appRows.isNotEmpty()

    val hasGoals: Boolean get() = goalRows.isNotEmpty()

    val hasTrend: Boolean get() = trendBars.any { it.valueLabel != null }
}
