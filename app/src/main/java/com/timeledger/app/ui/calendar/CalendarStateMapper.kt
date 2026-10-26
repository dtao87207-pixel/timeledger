package com.timeledger.app.ui.calendar

import com.timeledger.app.domain.goal.DailyGoalProgress
import com.timeledger.app.domain.statistics.RangeStatistics
import com.timeledger.app.ui.day.DayViewUiState
import com.timeledger.app.ui.format.DisplayFormat
import java.time.LocalDate

/**
 * 月统计 + 每日目标进度 → 日历界面状态的**纯函数**映射。
 *
 * 与 [com.timeledger.app.ui.day.DayViewStateMapper] 同样的理由：本机无法目视验证界面，
 * 所以「网格怎么拼、每格显示什么、翻月后选中哪天」这些判断必须在测试里钉死。
 *
 * ## 关于硬编码的中文文案
 * 这个类里的文案（`本月记录`、`未分类` 之类）是直接写在代码里的，没放进 `strings.xml`。
 * 因为它是**纯 JVM 类**，放资源就得把 `Context` 传进来，测试也就从纯 JVM 测试
 * 退化成需要 Android 环境的测试 —— 而「能被测试覆盖」对本项目价值更高。
 * 本项目是中文单语 App，这个取舍是划算的。
 */
class CalendarStateMapper(
    /** 「今天」用回调取，方便测试固定；月视图里它只用来高亮当天。 */
    private val todayEpochDay: () -> Long,
) {

    fun map(
        anchorEpochDay: Long,
        selectedEpochDay: Long,
        monthStats: RangeStatistics,
        goalProgressByDay: Map<Long, DailyGoalProgress>,
        selectedDetail: DayViewUiState?,
    ): CalendarUiState {
        val firstDay = LocalDate.ofEpochDay(anchorEpochDay).withDayOfMonth(1)
        val daysInMonth = firstDay.lengthOfMonth()
        val firstEpochDay = firstDay.toEpochDay()
        val lastEpochDay = firstEpochDay + daysInMonth - 1

        // 周一为第一列，因此前面要补几个空格子 = 该月 1 号是周几 - 1
        val leadingBlanks = firstDay.dayOfWeek.value - 1
        val cellCount = ((leadingBlanks + daysInMonth + 6) / 7) * 7
        val gridStart = firstEpochDay - leadingBlanks

        val statsByDay = monthStats.days.associateBy { it.epochDay }
        val maxMillis = monthStats.days.maxOfOrNull { it.breakdown.confirmedMillis } ?: 0L
        val today = todayEpochDay()

        val cells = (0 until cellCount).map { index ->
            val epochDay = gridStart + index
            val inMonth = epochDay in firstEpochDay..lastEpochDay
            val confirmedMillis = if (inMonth) {
                statsByDay[epochDay]?.breakdown?.confirmedMillis ?: 0L
            } else {
                0L
            }
            val goals = goalProgressByDay[epochDay]

            CalendarCell(
                epochDay = epochDay,
                dayOfMonth = LocalDate.ofEpochDay(epochDay).dayOfMonth,
                inDisplayedMonth = inMonth,
                isSelected = epochDay == selectedEpochDay,
                isToday = epochDay == today,
                recordedLabel = if (inMonth) DisplayFormat.compactHoursLabel(confirmedMillis) else null,
                intensity = if (maxMillis > 0L) {
                    (confirmedMillis.toDouble() / maxMillis).toFloat()
                } else {
                    0f
                },
                // 没有适用目标的日子不显示 `0/0`：那看起来像「完成了 0 个目标」，
                // 而实际情况是「这天没有目标」，两者必须区分
                goalLabel = goals
                    ?.takeIf { it.activeGoalCount > 0 }
                    ?.let { "${it.achievedGoalCount}/${it.activeGoalCount}" },
            )
        }

        return CalendarUiState(
            monthLabel = DisplayFormat.monthLabel(anchorEpochDay),
            weekdayHeaders = WEEKDAY_HEADERS,
            weeks = cells.chunked(7),
            monthSummaryLabel = monthSummary(monthStats),
            selectedDayLabel = DisplayFormat.dateLabel(selectedEpochDay),
            selectedDetail = selectedDetail,
        )
    }

    private fun monthSummary(monthStats: RangeStatistics): String = buildString {
        append("本月记录 ")
        append(DisplayFormat.compactDuration(monthStats.totalConfirmedMillis))
        append("，")
        append(monthStats.recordedDayCount)
        append("/")
        append(monthStats.dayCount)
        append(" 天有记录")
    }

    private companion object {
        /** 周一到周日。与全局「一周从周一 04:00 开始」的定义一致。 */
        val WEEKDAY_HEADERS = listOf("一", "二", "三", "四", "五", "六", "日")
    }
}
