package com.timeledger.app.ui.calendar

import com.timeledger.app.ui.day.DayViewUiState

/**
 * 月视图里的一个格子。
 *
 * [inDisplayedMonth] 为 false 的是补齐网格用的相邻月日期：它们不出数据，
 * 但仍然要占位置，否则月初/月末那一行的对齐会错乱。
 */
data class CalendarCell(
    val epochDay: Long,
    val dayOfMonth: Int,
    val inDisplayedMonth: Boolean,
    val isSelected: Boolean,
    val isToday: Boolean,
    /** 该日已确认时长的小时数文案；null 表示没有记录（界面显示留白而不是 0h）。 */
    val recordedLabel: String?,
    /** 相对当月最长那天的比例，用来画格子底部的强度条。 */
    val intensity: Float,
    /** 目标完成情况，例如 `2/3`；该日没有适用目标时为 null。 */
    val goalLabel: String?,
)

/**
 * 日历页状态（需求二节）。
 *
 * 网格用「若干行、每行 7 格」表示，而不是一维列表加 7：
 * 这样界面排版不需要自己做除法和取余，也就不会出现某一行少一格这类只有肉眼才能发现的错位。
 */
data class CalendarUiState(
    val monthLabel: String,
    /** 周一 … 周日。与全局「一周从周一 04:00 开始」的定义一致。 */
    val weekdayHeaders: List<String>,
    val weeks: List<List<CalendarCell>>,
    /** 本月汇总的一句话：`本月记录 38h，6/30 天有记录`。 */
    val monthSummaryLabel: String,
    val selectedDayLabel: String,
    /** 选中那一天的详情；还没算出来时为 null。 */
    val selectedDetail: DayViewUiState?,
) {
    val cellCount: Int get() = weeks.size * 7
}
