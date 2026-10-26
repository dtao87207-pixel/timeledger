package com.timeledger.app.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 时间上下文测试 —— 主要盯住「分钟数 → 绝对时刻」的挂钟语义。
 *
 * 这块单独测的理由：它是**唯一**把课表的「09:00」变成绝对时间戳的地方，
 * 一旦语义错了（例如按绝对时长相加），错误会藏在夏令时那一天里，
 * 平时完全看不出来，只有用户发现某天的课表整体偏了一小时。
 */
class DayBoundaryTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private val newYork: ZoneId = ZoneId.of("America/New_York")

    private val fixedNow = Instant.parse("2026-09-12T06:00:00Z").toEpochMilli()
    private val day = LocalDate.of(2026, 9, 12).toEpochDay()

    private val boundary = DayBoundary(zoneId = shanghai, nowProvider = { fixedNow })

    @Test
    fun `minute of day maps to wall clock time`() {
        // 09:00 == 9*60 = 540
        val expected = Instant.parse("2026-09-12T01:00:00Z").toEpochMilli() // 09:00 +08:00
        assertEquals(expected, boundary.millisAtMinuteOfDay(day, 540))
    }

    @Test
    fun `midnight maps to the start of the calendar day`() {
        val expected = Instant.parse("2026-09-11T16:00:00Z").toEpochMilli() // 00:00 +08:00
        assertEquals(expected, boundary.millisAtMinuteOfDay(day, 0))
    }

    @Test
    fun `minute 1440 means the next calendar day midnight`() {
        val endOfDay = boundary.millisAtMinuteOfDay(day, 1440)
        val nextMidnight = boundary.millisAtMinuteOfDay(day + 1, 0)
        assertEquals(nextMidnight, endOfDay)
        val expected = Instant.parse("2026-09-12T16:00:00Z").toEpochMilli() // 次日 00:00 +08:00
        assertEquals(expected, endOfDay)
    }

    @Test
    fun `minutes are wall clock not absolute duration across a dst jump`() {
        // 美东 2026-03-08：02:00 直接跳到 03:00，这一天的 03:30 与 01:30 之间只隔 1 小时。
        // 如果用「00:00 + N 分钟」的绝对时长算法，03:30（210 分钟）会算成 04:30。
        val dstDay = LocalDate.of(2026, 3, 8).toEpochDay()
        val nyBoundary = DayBoundary(zoneId = newYork, nowProvider = { fixedNow })

        val halfPastOne = nyBoundary.millisAtMinuteOfDay(dstDay, 90)   // 01:30 EST
        val halfPastThree = nyBoundary.millisAtMinuteOfDay(dstDay, 210) // 03:30 EDT

        assertEquals(Instant.parse("2026-03-08T06:30:00Z").toEpochMilli(), halfPastOne)
        assertEquals(Instant.parse("2026-03-08T07:30:00Z").toEpochMilli(), halfPastThree)
        // 挂钟上隔了 2 小时，实际只过去 1 小时
        assertEquals(60 * 60 * 1000L, halfPastThree - halfPastOne)
    }

    @Test
    fun `wall clock time in the skipped hour is shifted forward`() {
        // 美东 2026-03-08 的 02:30 这个挂钟时刻并不存在，
        // 按 Java 的时区规则会向后顺延到跳变之后，而不是抛异常。
        val dstDay = LocalDate.of(2026, 3, 8).toEpochDay()
        val nyBoundary = DayBoundary(zoneId = newYork, nowProvider = { fixedNow })

        val result = nyBoundary.millisAtMinuteOfDay(dstDay, 150) // 02:30
        assertEquals(Instant.parse("2026-03-08T07:30:00Z").toEpochMilli(), result) // 03:30 EDT
    }

    @Test
    fun `minute of day rejects values outside the supported range`() {
        val tooBig = runCatching { boundary.millisAtMinuteOfDay(day, 1441) }.exceptionOrNull()
        val negative = runCatching { boundary.millisAtMinuteOfDay(day, -1) }.exceptionOrNull()
        assertTrue(tooBig is IllegalArgumentException)
        assertTrue(negative is IllegalArgumentException)
    }

    @Test
    fun `day key follows the four am boundary`() {
        // 04:00 当刻属于当天，03:59 属于前一天
        assertEquals(day, boundary.dayKeyOf(boundary.millisAtMinuteOfDay(day, 240)))
        assertEquals(day - 1, boundary.dayKeyOf(boundary.millisAtMinuteOfDay(day, 239)))
    }

    @Test
    fun `today epoch day uses the injected instant`() {
        assertEquals(day, boundary.todayEpochDay())
        assertEquals(fixedNow, boundary.nowMillis())
    }

    @Test
    fun `day of week matches java convention`() {
        // 2026-09-12 是周六 → 6
        assertEquals(6, boundary.dayOfWeek(day))
    }

    @Test
    fun `month first and last epoch day handle month lengths`() {
        // 2026-09-12 → 9 月 1 日 ~ 9 月 30 日
        assertEquals(LocalDate.of(2026, 9, 1).toEpochDay(), boundary.monthFirstEpochDay(day))
        assertEquals(LocalDate.of(2026, 9, 30).toEpochDay(), boundary.monthLastEpochDay(day))

        // 平年 2 月
        val feb2026 = LocalDate.of(2026, 2, 10).toEpochDay()
        assertEquals(LocalDate.of(2026, 2, 1).toEpochDay(), boundary.monthFirstEpochDay(feb2026))
        assertEquals(LocalDate.of(2026, 2, 28).toEpochDay(), boundary.monthLastEpochDay(feb2026))

        // 闰年 2 月
        val feb2028 = LocalDate.of(2028, 2, 10).toEpochDay()
        assertEquals(LocalDate.of(2028, 2, 29).toEpochDay(), boundary.monthLastEpochDay(feb2028))
    }

    @Test
    fun `shift month normalises to the first day to avoid drift`() {
        // 直接给「1 月 31 日」加一个月会被日历规则截成 2 月 28 日，
        // 再往后翻就变成每月 28 号，锚点一路漂移。归一化到 1 号之后翻页完全可预期。
        val jan31 = LocalDate.of(2026, 1, 31).toEpochDay()
        val feb = boundary.shiftMonth(jan31, 1)
        assertEquals(LocalDate.of(2026, 2, 1).toEpochDay(), feb)
        assertEquals(LocalDate.of(2026, 3, 1).toEpochDay(), boundary.shiftMonth(feb, 1))

        // 跨年
        assertEquals(
            LocalDate.of(2027, 1, 1).toEpochDay(),
            boundary.shiftMonth(LocalDate.of(2026, 12, 15).toEpochDay(), 1),
        )
        assertEquals(
            LocalDate.of(2025, 12, 1).toEpochDay(),
            boundary.shiftMonth(LocalDate.of(2026, 1, 15).toEpochDay(), -1),
        )
    }
}
