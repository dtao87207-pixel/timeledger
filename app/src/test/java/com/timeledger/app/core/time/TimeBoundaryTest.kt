package com.timeledger.app.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 逻辑日边界测试。
 *
 * 期望值刻意用 **UTC 瞬时字符串**（`Instant.parse`）写死，而不是用
 * `LocalDate.atTime(...)` 再算一遍：
 * 后者和实现用的是同一套 API，等于自己证明自己。
 * 用 UTC 字面量表达「北京时间 2026-09-12 04:00」，才是独立的第二来源。
 */
class TimeBoundaryTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private val sept12 = LocalDate.of(2026, 9, 12).toEpochDay()
    private val sept11 = LocalDate.of(2026, 9, 11).toEpochDay()
    private val sept13 = LocalDate.of(2026, 9, 13).toEpochDay()

    // ---- 分界点本身 ----

    @Test
    fun `day start is local 04_00`() {
        // 2026-09-12 04:00 +08:00 == 2026-09-11 20:00 UTC
        val expected = Instant.parse("2026-09-11T20:00:00Z").toEpochMilli()
        assertEquals(expected, TimeBoundary.dayStartMillis(sept12, shanghai))
    }

    @Test
    fun `day end is next day 04_00`() {
        // 2026-09-13 04:00 +08:00 == 2026-09-12 20:00 UTC
        val expected = Instant.parse("2026-09-12T20:00:00Z").toEpochMilli()
        assertEquals(expected, TimeBoundary.dayEndMillis(sept12, shanghai))
    }

    @Test
    fun `day range is half open from 04_00 to next 04_00`() {
        val range = TimeBoundary.dayRange(sept12, shanghai)
        assertEquals(Instant.parse("2026-09-11T20:00:00Z").toEpochMilli(), range.startMillis)
        assertEquals(Instant.parse("2026-09-12T20:00:00Z").toEpochMilli(), range.endMillisExclusive)
        // 上海没有夏令时，逻辑日固定 24 小时
        assertEquals(24L * 60 * 60 * 1000, range.durationMillis)
    }

    // ---- 归属规则：凌晨 4 点前算前一天 ----

    @Test
    fun `03_59_59_999 belongs to previous day`() {
        val millis = Instant.parse("2026-09-11T19:59:59.999Z").toEpochMilli()
        assertEquals(sept11, TimeBoundary.logicalDayKey(millis, shanghai))
    }

    @Test
    fun `04_00_00_000 belongs to the day itself`() {
        val millis = Instant.parse("2026-09-11T20:00:00Z").toEpochMilli()
        assertEquals(sept12, TimeBoundary.logicalDayKey(millis, shanghai))
    }

    @Test
    fun `01_00 after midnight belongs to previous day`() {
        // 需求原文的核心例子：9月13日 01:00 应当算 9月12日 这一天
        // 2026-09-13 01:00 +08:00 == 2026-09-12 17:00 UTC
        val millis = Instant.parse("2026-09-12T17:00:00Z").toEpochMilli()
        assertEquals(sept12, TimeBoundary.logicalDayKey(millis, shanghai))
    }

    @Test
    fun `23_59 belongs to the same calendar day`() {
        // 2026-09-12 23:59 +08:00 == 2026-09-12 15:59 UTC
        val millis = Instant.parse("2026-09-12T15:59:00Z").toEpochMilli()
        assertEquals(sept12, TimeBoundary.logicalDayKey(millis, shanghai))
    }

    @Test
    fun `custom boundary hour of zero equals calendar day`() {
        val millis = Instant.parse("2026-09-12T17:00:00Z").toEpochMilli() // 9/13 01:00 +08:00
        assertEquals(sept13, TimeBoundary.logicalDayKey(millis, shanghai, boundaryHour = 0))
    }

    @Test
    fun `boundary hour of twelve moves the split to noon`() {
        // 9/12 11:00 +08:00 == 9/12 03:00 UTC，落在 12 点边界之前，应算 9/11
        val before = Instant.parse("2026-09-12T03:00:00Z").toEpochMilli()
        assertEquals(sept11, TimeBoundary.logicalDayKey(before, shanghai, boundaryHour = 12))
        // 9/12 13:00 +08:00 == 9/12 05:00 UTC，落在之后，应算 9/12
        val after = Instant.parse("2026-09-12T05:00:00Z").toEpochMilli()
        assertEquals(sept12, TimeBoundary.logicalDayKey(after, shanghai, boundaryHour = 12))
    }

    // ---- 夏令时：验证「不靠加 86400000 毫秒」这个决定 ----

    @Test
    fun `day length is 23 hours on spring forward`() {
        // 美东 2026-03-08 02:00 跳到 03:00，因此 3月7日 这一格只有 23 小时
        val newYork = ZoneId.of("America/New_York")
        val march7 = LocalDate.of(2026, 3, 7).toEpochDay()
        val range = TimeBoundary.dayRange(march7, newYork)
        assertEquals(23L * 60 * 60 * 1000, range.durationMillis)
    }

    @Test
    fun `day length is 25 hours on fall back`() {
        // 美东 2026-11-01 02:00 回拨到 01:00，因此 10月31日 这一格有 25 小时
        val newYork = ZoneId.of("America/New_York")
        val oct31 = LocalDate.of(2026, 10, 31).toEpochDay()
        val range = TimeBoundary.dayRange(oct31, newYork)
        assertEquals(25L * 60 * 60 * 1000, range.durationMillis)
    }

    @Test
    fun `logical day still tracks the 04_00 boundary across a dst switch`() {
        val newYork = ZoneId.of("America/New_York")
        val march7 = LocalDate.of(2026, 3, 7).toEpochDay()
        // 2026-03-08 03:30 美东时间（此时已是 EDT，UTC-4）== 07:30 UTC
        val millis = Instant.parse("2026-03-08T07:30:00Z").toEpochMilli()
        assertEquals(march7, TimeBoundary.logicalDayKey(millis, newYork))
    }

    // ---- 周 / 月 / 年 ----

    @Test
    fun `week start of unix epoch is the monday before it`() {
        // 1970-01-01 是周四，因此那一周的周一是 1969-12-29，epochDay = -3
        assertEquals(-3L, TimeBoundary.weekStartEpochDay(0L))
    }

    @Test
    fun `week start is always a monday not later than the given day`() {
        for (offset in 0L..13L) {
            val day = sept12 + offset
            val start = TimeBoundary.weekStartEpochDay(day)
            val date = LocalDate.ofEpochDay(start)
            assertEquals(java.time.DayOfWeek.MONDAY, date.dayOfWeek)
            assertTrue("week start must not be after the day", start <= day)
            assertTrue("week start must be within 7 days", day - start < 7)
        }
    }

    @Test
    fun `week range spans exactly seven logical days`() {
        val range = TimeBoundary.weekRange(sept12, shanghai)
        val start = TimeBoundary.weekStartEpochDay(sept12)
        assertEquals(TimeBoundary.dayStartMillis(start, shanghai), range.startMillis)
        assertEquals(TimeBoundary.dayStartMillis(start + 7, shanghai), range.endMillisExclusive)
        assertEquals(7L * 24 * 60 * 60 * 1000, range.durationMillis)
    }

    @Test
    fun `month range covers the whole calendar month`() {
        val range = TimeBoundary.monthRange(sept12, shanghai)
        // 9月1日 04:00 +08:00 == 8月31日 20:00 UTC
        assertEquals(Instant.parse("2026-08-31T20:00:00Z").toEpochMilli(), range.startMillis)
        // 10月1日 04:00 +08:00 == 9月30日 20:00 UTC
        assertEquals(Instant.parse("2026-09-30T20:00:00Z").toEpochMilli(), range.endMillisExclusive)
    }

    @Test
    fun `year range covers the whole calendar year`() {
        val range = TimeBoundary.yearRange(sept12, shanghai)
        // 2026年1月1日 04:00 +08:00 == 2025年12月31日 20:00 UTC
        assertEquals(Instant.parse("2025-12-31T20:00:00Z").toEpochMilli(), range.startMillis)
        // 2027年1月1日 04:00 +08:00 == 2026年12月31日 20:00 UTC
        assertEquals(Instant.parse("2026-12-31T20:00:00Z").toEpochMilli(), range.endMillisExclusive)
    }

    @Test
    fun `epoch days in returns an inclusive ascending list`() {
        val days = TimeBoundary.epochDaysIn(sept12, sept13)
        assertEquals(listOf(sept12, sept13), days)
        assertEquals(listOf(sept12), TimeBoundary.epochDaysIn(sept12, sept12))
        // 结束日早于开始日是调用方写错了，直接抛异常比返回空列表更容易暴露问题
        val error = runCatching { TimeBoundary.epochDaysIn(sept12, sept12 - 1) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `calendar date round trips with epoch day`() {
        assertEquals(LocalDate.of(2026, 9, 12), TimeBoundary.calendarDate(sept12))
        assertFalse(TimeBoundary.calendarDate(sept12).isLeapYear)
    }

    @Test
    fun `today key uses the injected instant`() {
        val millis = Instant.parse("2026-09-12T17:00:00Z").toEpochMilli()
        assertEquals(sept12, TimeBoundary.todayKey(millis, shanghai))
    }

    @Test
    fun `millis range of a zero length day start equals its end`() {
        val start = TimeBoundary.dayStartMillis(sept12, shanghai)
        val range = MillisRange(start, start)
        assertTrue(range.isInstant)
        assertNull(range.intersect(MillisRange(start, start)))
    }
}
