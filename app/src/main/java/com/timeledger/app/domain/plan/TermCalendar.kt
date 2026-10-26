package com.timeledger.app.domain.plan

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 「某一天是第几周」（模块 28）。
 *
 * ## 为什么需要它
 * [WeekSpec] 只会回答「第 N 周上不上这门课」——它不知道今天是第几周。
 * 而「第几周」需要一个**学期起点**：用户给的是 **2026-09-14（周一）= 第 1 周**。
 *
 * ## 口径（写清楚，免得以后各算各的）
 * - 第 1 周从学期起点那天算起，之后每 7 天进一周（**周一对齐**，与全项目
 *   `firstDayOfWeek = MONDAY` 一致）；
 * - 起点不是周一时，**回退到那一周的周一**：否则「第 2 周」会从周三开始，
 *   同一门课在不同星期几落在不同周次，统计就乱了；
 * - 起点之前的日子返回 **0 或负数**——那不是「第 0 周没课」，而是「学期还没开始」，
 *   调用方据此判断即可（[WeekSpec.matches] 对非正数返回 false）。
 *
 * 纯函数、只依赖 `java.time`：没有 Android、没有数据库，测试能写死日期。
 */
object TermCalendar {

    /** 第 [date] 天属于第几周；学期未开始时 ≤ 0。 */
    fun weekNumberOn(date: LocalDate, termStart: LocalDate): Int {
        val firstMonday = mondayOf(termStart)
        val days = ChronoUnit.DAYS.between(firstMonday, date)
        // 向下取整的整除：负数天数（学期之前）要落到 0 及以下，而不是 0
        return Math.floorDiv(days, 7L).toInt() + 1
    }

    /** 学期起点所在那一周的周一。 */
    fun mondayOf(termStart: LocalDate): LocalDate =
        termStart.minusDays((termStart.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())

    /** 第 [weekNumber] 周的第一天（周一）——课表界面上要能显示「第 5 周 10/12 起」。 */
    fun weekStart(termStart: LocalDate, weekNumber: Int): LocalDate =
        mondayOf(termStart).plusWeeks((weekNumber - 1).toLong())
}
