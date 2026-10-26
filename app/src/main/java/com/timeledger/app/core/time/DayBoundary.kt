package com.timeledger.app.core.time

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

/**
 * 应用级时间上下文 —— 全项目**唯一**允许获取「现在几点」和「当前时区」的地方。
 *
 * 为什么要有这一层，而不是到处写 `System.currentTimeMillis()` 和 `ZoneId.systemDefault()`：
 * 1. **可测试**：单元测试可以注入固定的时间与写死 UTC 时区，
 *    否则「跨 4:00 边界」「跨夏令时」这些用例永远不可复现；
 * 2. **口径统一**：逻辑日边界、时区、当前时间三者绑定在一起，
 *    不会出现「查询用 4:00 边界、展示用 0:00 边界」这种统计错位；
 * 3. **可替换**：将来「补录昨天的记录」只要构造一个不同的实例，
 *    不需要改任何业务代码。
 *
 * 本类只依赖 `java.time`，不依赖 Android，可直接在 JVM 单元测试中使用。
 */
class DayBoundary(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val boundaryHour: Int = TimeBoundary.DEFAULT_BOUNDARY_HOUR,
    private val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {

    init {
        require(boundaryHour in 0..23) { "逻辑日边界小时必须在 0~23 之间，实际为 $boundaryHour" }
    }

    /** 当前时区。用于把「当天分钟数」这类挂钟时间换算成绝对时间戳。 */
    fun zone(): ZoneId = zoneId

    /** 逻辑日分界小时（默认 4 点）。 */
    fun boundaryHour(): Int = boundaryHour

    /** 当前时刻（毫秒时间戳）。 */
    fun nowMillis(): Long = nowProvider()

    /** 当前所属的逻辑日。凌晨 4 点前调用得到的是「昨天」。 */
    fun todayEpochDay(): Long = TimeBoundary.todayKey(nowMillis(), zoneId, boundaryHour)

    /** 给定时刻属于哪个逻辑日。 */
    fun dayKeyOf(millis: Long): Long = TimeBoundary.logicalDayKey(millis, zoneId, boundaryHour)

    /** 某个逻辑日的区间 `[当天04:00, 次日04:00)`。 */
    fun dayRange(epochDay: Long): MillisRange = TimeBoundary.dayRange(epochDay, zoneId, boundaryHour)

    /** 连续多个逻辑日的区间（用于日历月视图一次取全月数据）。 */
    fun daysRange(fromEpochDay: Long, toEpochDay: Long): MillisRange = MillisRange(
        startMillis = TimeBoundary.dayStartMillis(fromEpochDay, zoneId, boundaryHour),
        endMillisExclusive = TimeBoundary.dayEndMillis(toEpochDay, zoneId, boundaryHour),
    )

    /** 某个逻辑日所在周的区间 `[周一04:00, 下周一04:00)`。 */
    fun weekRange(epochDay: Long): MillisRange =
        TimeBoundary.weekRange(epochDay, zoneId, firstDayOfWeek, boundaryHour)

    /** 某个逻辑日所在月的区间。 */
    fun monthRange(epochDay: Long): MillisRange =
        TimeBoundary.monthRange(epochDay, zoneId, boundaryHour)

    /** 某个逻辑日所在年的区间。 */
    fun yearRange(epochDay: Long): MillisRange =
        TimeBoundary.yearRange(epochDay, zoneId, boundaryHour)

    /** 该逻辑日是星期几，1=周一 … 7=周日。 */
    fun dayOfWeek(epochDay: Long): Int = TimeBoundary.calendarDate(epochDay).dayOfWeek.value

    /** 该逻辑日所在自然月的第一天（日历月视图的起点）。 */
    fun monthFirstEpochDay(epochDay: Long): Long =
        TimeBoundary.calendarDate(epochDay).withDayOfMonth(1).toEpochDay()

    /** 该逻辑日所在自然月的最后一天。 */
    fun monthLastEpochDay(epochDay: Long): Long =
        TimeBoundary.calendarDate(epochDay).withDayOfMonth(1).plusMonths(1).toEpochDay() - 1

    /**
     * 月份翻页：返回目标月的**第一天**。
     *
     * 为什么先归一到 1 号再平移：直接对「1 月 31 日」加一个月会被日历规则截成 2 月 28 日，
     * 再往后翻就变成每月 28 号，锚点会一路漂移。归一之后翻页完全可预期。
     */
    fun shiftMonth(epochDay: Long, months: Long): Long =
        TimeBoundary.calendarDate(epochDay).withDayOfMonth(1).plusMonths(months).toEpochDay()

    /** 日数平移（周统计翻页用）。 */
    fun shiftDays(epochDay: Long, days: Long): Long = epochDay + days

    /** 年份平移（年统计翻页用）。 */
    fun shiftYear(epochDay: Long, years: Long): Long =
        TimeBoundary.calendarDate(epochDay).plusYears(years).toEpochDay()

    /**
     * 把「某个**日历日**的第 N 分钟」换算成绝对时间戳（**挂钟语义**）。
     *
     * 分钟数对应的是挂钟时间，不叠加 4 小时偏移：课表上的「09:00」就是挂钟 9 点，
     * 加偏移会让展示与录入对不上；至于这一时刻算哪一天，交给 [dayKeyOf] 判定。
     *
     * 实现上**必须用 [LocalTime]**，不能写成 `atStartOfDay().plusMinutes(n)`：
     * 后者加的是**绝对时长**，在夏令时切换日会算错 ——
     * 美东 2026-03-08 当天 02:00 直接跳到 03:00，于是
     * 「00:00 加 210 分钟」得到的是 **04:30**，而挂钟 03:30 才是我们要的答案。
     * 用 LocalTime 表达「挂钟 03:30」再由时区规则解析，才是正确语义。
     * （若该挂钟时刻正好落在夏令时跳过的区间里，Java 会按规则向后顺延，这也是期望行为。）
     *
     * [minuteOfDay] 允许取 1440，表示「当日 24:00」，等价于次日 00:00。
     */
    fun millisAtMinuteOfDay(epochDay: Long, minuteOfDay: Int): Long {
        require(minuteOfDay in 0..1440) { "分钟数必须在 0~1440 之间，实际为 $minuteOfDay" }

        if (minuteOfDay == 1440) {
            return TimeBoundary.calendarDate(epochDay + 1)
                .atTime(LocalTime.MIDNIGHT)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli()
        }

        val wallClock = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
        return TimeBoundary.calendarDate(epochDay)
            .atTime(wallClock)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
    }

    companion object {
        /** 生产环境用的默认实例：系统时区 + 4 点边界。 */
        fun system(): DayBoundary = DayBoundary(ZoneId.systemDefault())
    }
}
