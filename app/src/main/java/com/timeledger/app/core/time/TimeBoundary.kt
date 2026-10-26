package com.timeledger.app.core.time

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「逻辑日」边界计算 —— 全项目唯一的「一天从几点开始」定义处。
 *
 * ## 一天从哪里翻篇：此刻仍是凌晨 4:00（模块 26 待改）
 * 用户拿手机内置的「健康使用设备 → 使用统计」一比，发现日数字对不上 ——
 * 内置那套按**自然日 0 点**分天，所以「今天」永远差一个「凌晨 0–4 点」的尾巴。
 * 他的选择是「一切以手机端内置的为主」，所以分界线要改成 0 点。
 *
 * **为什么这一改还没落地**：全项目有 35 条测试写的是 4 点语义
 * （`TimeBoundaryTest`、`PlanBlockExpanderTest`、`UsageAnalysisServiceTest`、
 * `ConfirmationStateMapperTest`……），它们不是错的，是**为旧口径写的**。
 * 改边界必须连同这些期望一起改，并且要单独一轮验证 —— 不能和会话重建
 * （真正让两边数字对不上的那个原因）混在一起改，否则出了偏差分不清是谁造成的。
 *
 * 代价先说清楚：改成 0 点之后，**睡一觉跨过午夜会被切成两天两段**
 * （23:00 睡到 07:00 → 昨天 23:00–24:00 属于昨天，今天 00:00–07:00 属于今天）。
 * 记录本身不会被切开（库里就一行），切开的是显示，由时间轴上的
 * 「延续自昨日 / 延续到次日」文案承担。
 *
 * 实现要点：
 * 1. 一律通过 [ZoneId] 下的 `ZonedDateTime` 换算，**不使用「加 86400000 毫秒」**。
 *    夏令时切换日的实际长度可能是 23 或 25 小时，加固定毫秒会算错边界。
 * 2. 本对象是纯函数工具，不依赖任何 Android API，可直接在 JVM 单元测试中验证。
 * 3. 逻辑日统一用 `LocalDate.toEpochDay()`（简称 epochDay）标识，便于存储与索引。
 *
 * 参数 [boundaryHour] 可注入：4 点与 0 点两套语义各有一批测试盯着。
 */
object TimeBoundary {

    /** 一天的分界线：凌晨 4 点（待模块 26 改成 0 点，见类注释）。 */
    const val DEFAULT_BOUNDARY_HOUR: Int = 4

    /** 逻辑日的起始瞬间（含）：当天 04:00:00.000。 */
    fun dayStartMillis(
        epochDay: Long,
        zone: ZoneId,
        boundaryHour: Int = DEFAULT_BOUNDARY_HOUR,
    ): Long = LocalDate.ofEpochDay(epochDay)
        .atTime(boundaryHour, 0)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()

    /** 逻辑日的结束瞬间（不含）：次日 04:00:00.000。 */
    fun dayEndMillis(
        epochDay: Long,
        zone: ZoneId,
        boundaryHour: Int = DEFAULT_BOUNDARY_HOUR,
    ): Long = dayStartMillis(epochDay + 1, zone, boundaryHour)

    /** 某个逻辑日的完整区间 `[当天04:00, 次日04:00)`。 */
    fun dayRange(
        epochDay: Long,
        zone: ZoneId,
        boundaryHour: Int = DEFAULT_BOUNDARY_HOUR,
    ): MillisRange = MillisRange(
        startMillis = dayStartMillis(epochDay, zone, boundaryHour),
        endMillisExclusive = dayEndMillis(epochDay, zone, boundaryHour),
    )

    /**
     * 给定的绝对时间戳落在哪个逻辑日。
     *
     * 本地时间 00:00（含）~ 03:59:59.999 属于**前一天**；04:00 之后属于当天。
     */
    fun logicalDayKey(
        millis: Long,
        zone: ZoneId,
        boundaryHour: Int = DEFAULT_BOUNDARY_HOUR,
    ): Long {
        val localDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val localHour = Instant.ofEpochMilli(millis).atZone(zone).hour
        return if (localHour < boundaryHour) localDate.minusDays(1).toEpochDay()
        else localDate.toEpochDay()
    }

    /** 当前时刻所属的逻辑日。 */
    fun todayKey(
        nowMillis: Long,
        zone: ZoneId,
        boundaryHour: Int = DEFAULT_BOUNDARY_HOUR,
    ): Long = logicalDayKey(nowMillis, zone, boundaryHour)

    /** 某逻辑日对应的日历日期（该逻辑日的起始日）。 */
    fun calendarDate(epochDay: Long): LocalDate = LocalDate.ofEpochDay(epochDay)

    /**
     * 逻辑日所在自然周的起始 epochDay。
     *
     * 以周一为一周第一天（与国内课程表一致）。
     */
    fun weekStartEpochDay(
        epochDay: Long,
        firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    ): Long {
        val dayOfWeek = LocalDate.ofEpochDay(epochDay).dayOfWeek
        // DayOfWeek.value: MONDAY=1 .. SUNDAY=7
        val offset = ((dayOfWeek.value - firstDayOfWeek.value) + 7) % 7
        return epochDay - offset
    }

    /** 某个逻辑日所在自然周的区间 `[周一04:00, 下周一04:00)`。 */
    fun weekRange(
        epochDay: Long,
        zone: ZoneId,
        firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
        boundaryHour: Int = DEFAULT_BOUNDARY_HOUR,
    ): MillisRange {
        val start = weekStartEpochDay(epochDay, firstDayOfWeek)
        return MillisRange(
            startMillis = dayStartMillis(start, zone, boundaryHour),
            endMillisExclusive = dayStartMillis(start + 7, zone, boundaryHour),
        )
    }

    /** 某个逻辑日所在自然月的区间 `[1号04:00, 次月1号04:00)`。 */
    fun monthRange(
        epochDay: Long,
        zone: ZoneId,
        boundaryHour: Int = DEFAULT_BOUNDARY_HOUR,
    ): MillisRange {
        val firstDay = LocalDate.ofEpochDay(epochDay).withDayOfMonth(1)
        val nextMonth = firstDay.plusMonths(1)
        return MillisRange(
            startMillis = dayStartMillis(firstDay.toEpochDay(), zone, boundaryHour),
            endMillisExclusive = dayStartMillis(nextMonth.toEpochDay(), zone, boundaryHour),
        )
    }

    /** 某个逻辑日所在自然年的区间 `[1月1日04:00, 次年1月1日04:00)`。 */
    fun yearRange(
        epochDay: Long,
        zone: ZoneId,
        boundaryHour: Int = DEFAULT_BOUNDARY_HOUR,
    ): MillisRange {
        val firstDay = LocalDate.ofEpochDay(epochDay).withDayOfYear(1)
        val nextYear = firstDay.plusYears(1)
        return MillisRange(
            startMillis = dayStartMillis(firstDay.toEpochDay(), zone, boundaryHour),
            endMillisExclusive = dayStartMillis(nextYear.toEpochDay(), zone, boundaryHour),
        )
    }

    /**
     * 从 [fromEpochDay]（含）到 [toEpochDay]（含）的连续逻辑日序列。
     * 用于日历月视图按天聚合。
     */
    fun epochDaysIn(fromEpochDay: Long, toEpochDay: Long): List<Long> {
        require(toEpochDay >= fromEpochDay) { "结束日不能早于开始日" }
        return (fromEpochDay..toEpochDay).toList()
    }
}
