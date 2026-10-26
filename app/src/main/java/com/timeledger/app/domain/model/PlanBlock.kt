package com.timeledger.app.domain.model

/**
 * 计划 / 课程表条目（需求五节）。
 *
 * 两种形态，用 [dateEpochDay] 区分：
 * - [dateEpochDay] == null：**每周重复**，按 [dayOfWeek] 匹配（V1 主要形态，即课程表）。
 * - [dateEpochDay] != null：**单次日程**，只在指定那一天生效。
 *
 * 用「当天分钟数」而不是时间戳来表达，是刻意的选择（需求十六节第 9 条的精神）：
 * 每周重复的课表是**挂钟时间模式**，不是绝对时刻。如果存时间戳，
 * 每学期/每次时区变化都要批量重算。展开成具体时段时再由
 * `PlanBlockExpander` 结合 ZoneId 与逻辑日边界换算。
 *
 * 跨夜与边界规则（后续实现计划模块时必须遵守）：
 * 1. [endMinuteOfDay] <= [startMinuteOfDay] 表示**跨夜**，例如 23:30–01:00 存为 1410 → 60。
 * 2. 分钟数直接对应**日历时间**，不做偏移。因此「周一 01:00」的课在逻辑上属于周日那一格，
 *    归属逻辑日由 `TimeBoundary.logicalDayKey` 统一判定，本模型不引入特例。
 * 3. [endMinuteOfDay] 允许等于 1440，表示到当日 24:00 结束。
 */
data class PlanBlock(
    val id: Long = 0L,
    /** 标题，例如「高等数学」。 */
    val title: String,
    /** 单次日程的日期；为空表示按 [dayOfWeek] 每周重复。 */
    val dateEpochDay: Long? = null,
    /** 每周重复时的星期几，1=周一 … 7=周日（与 `java.time.DayOfWeek.value` 一致）。 */
    val dayOfWeek: Int? = null,
    /** 开始分钟数，0~1439。 */
    val startMinuteOfDay: Int,
    /** 结束分钟数，1~1440；<= [startMinuteOfDay] 表示跨夜。 */
    val endMinuteOfDay: Int,
    val categoryId: Long? = null,
    val activityId: Long? = null,
    /** 地点，例如「文一 203」。 */
    val location: String? = null,
    val note: String? = null,
    /** 生效起止逻辑日（epochDay，含）。用于学期起止，V1 允许为空表示不限。 */
    val validFromEpochDay: Long? = null,
    val validToEpochDay: Long? = null,
    /**
     * 上哪些周（模块 28）：`1-3周,5-17周` / `单周` / `双周`；null 或空 = **每周**。
     *
     * 为什么需要它：真实课表里几乎每门课都带周次（`1-3周,5-17周`、`第4周`），
     * 而计划块原本只有「每周重复 + 生效区间」。硬导成「每周都上」，
     * App 会在没课的那些周里说「计划有课但没记录」——**等于让 App 对用户说谎**。
     *
     * 解析交给 `WeekSpec`（纯函数、可测）；这里只原样保存课表给的文本，
     * 这样界面上也能照着原文显示出来让用户核对。
     */
    val weeksSpec: String? = null,
    val isActive: Boolean = true,
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
) {

    /** 是否为每周重复条目。 */
    val isWeeklyRecurring: Boolean get() = dateEpochDay == null

    /** 是否跨夜（结束分钟数不大于开始分钟数）。 */
    val crossesMidnight: Boolean get() = endMinuteOfDay <= startMinuteOfDay

    /** 计划时长（分钟）。跨夜条目会自动补上跨过 24:00 的部分。 */
    val durationMinutes: Int
        get() = if (crossesMidnight) endMinuteOfDay + 1440 - startMinuteOfDay
        else endMinuteOfDay - startMinuteOfDay

    init {
        require(dateEpochDay != null || dayOfWeek != null) {
            "每周重复的计划必须指定 dayOfWeek"
        }
        if (dayOfWeek != null) {
            require(dayOfWeek in 1..7) { "dayOfWeek 必须在 1~7 之间，实际为 $dayOfWeek" }
        }
        require(startMinuteOfDay in 0..1439) {
            "startMinuteOfDay 必须在 0~1439 之间，实际为 $startMinuteOfDay"
        }
        require(endMinuteOfDay in 1..1440) {
            "endMinuteOfDay 必须在 1~1440 之间，实际为 $endMinuteOfDay"
        }
        require(validFromEpochDay == null || validToEpochDay == null || validToEpochDay >= validFromEpochDay) {
            "计划生效结束日不能早于开始日"
        }
    }
}
