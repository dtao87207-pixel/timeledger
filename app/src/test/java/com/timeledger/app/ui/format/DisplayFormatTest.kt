package com.timeledger.app.ui.format

import com.timeledger.app.domain.model.GoalPeriod
import com.timeledger.app.domain.model.GoalType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 界面文案格式化测试。
 *
 * 这个模块唯一「能看」的东西就是文字，所以边界必须逐个钉死：
 * 不足一分钟、恰好整点、整数小时、跨零点结束。
 * 本项目没有能目视验证界面的环境，这些测试就是唯一的把关。
 */
class DisplayFormatTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")

    // ---------------- 时长 ----------------

    @Test
    fun `compact duration covers every magnitude`() {
        assertEquals("0m", DisplayFormat.compactDuration(0L))
        assertEquals("0m", DisplayFormat.compactDuration(-5L))
        // 有值但不足一分钟：写 0m 会让人以为没记录
        assertEquals("<1m", DisplayFormat.compactDuration(30_000L))
        assertEquals("1m", DisplayFormat.compactDuration(60_000L))
        assertEquals("30m", DisplayFormat.compactDuration(30 * 60_000L))
        assertEquals("1h", DisplayFormat.compactDuration(60 * 60_000L))
        assertEquals("2h20m", DisplayFormat.compactDuration(140 * 60_000L))
        assertEquals("12h", DisplayFormat.compactDuration(12 * 60 * 60_000L))
        assertEquals("23h59m", DisplayFormat.compactDuration((23 * 60 + 59) * 60_000L))
    }

    @Test
    fun `hours label keeps one decimal only when needed`() {
        // 目标的达成量在领域层是秒
        assertEquals("0h", DisplayFormat.hoursLabel(0.0))
        assertEquals("1h", DisplayFormat.hoursLabel(3600.0))
        assertEquals("3h", DisplayFormat.hoursLabel(3 * 3600.0))
        assertEquals("12.5h", DisplayFormat.hoursLabel(12.5 * 3600.0))
        assertEquals("1.5h", DisplayFormat.hoursLabel(5400.0))
        // 2 小时 20 分 → 2.3h（保留一位小数）
        assertEquals("2.3h", DisplayFormat.hoursLabel(140 * 60.0))
    }

    @Test
    fun `number label drops the trailing zero`() {
        assertEquals("2", DisplayFormat.numberLabel(2.0))
        assertEquals("0.5", DisplayFormat.numberLabel(0.5))
        assertEquals("0", DisplayFormat.numberLabel(0.0))
    }

    // ---------------- 目标 ----------------

    @Test
    fun `goal target and achieved labels follow the goal type`() {
        assertEquals("3h", DisplayFormat.goalTargetLabel(3 * 3600.0, GoalType.TIME))
        assertEquals("2个", DisplayFormat.goalTargetLabel(2.0, GoalType.COUNT))
        assertEquals("3次", DisplayFormat.goalTargetLabel(3.0, GoalType.FREQUENCY))

        // TIME 的达成量是秒，要转成 2h20m 这样的时长
        assertEquals("2h20m", DisplayFormat.goalAchievedLabel(140 * 60.0, GoalType.TIME))
        assertEquals("1个", DisplayFormat.goalAchievedLabel(1.0, GoalType.COUNT))
        assertEquals("2次", DisplayFormat.goalAchievedLabel(2.0, GoalType.FREQUENCY))
    }

    @Test
    fun `period labels`() {
        assertEquals("今日", DisplayFormat.periodLabel(GoalPeriod.DAY))
        assertEquals("本周", DisplayFormat.periodLabel(GoalPeriod.WEEK))
        assertEquals("本月", DisplayFormat.periodLabel(GoalPeriod.MONTH))
    }

    // ---------------- 时间与日期 ----------------

    @Test
    fun `clock label pads to two digits`() {
        // 2026-09-12 14:05 +08:00 == 06:05 UTC
        assertEquals("14:05", DisplayFormat.clockLabel(Instant.parse("2026-09-12T06:05:00Z").toEpochMilli(), shanghai))
        // 午夜
        assertEquals("00:00", DisplayFormat.clockLabel(Instant.parse("2026-09-11T16:00:00Z").toEpochMilli(), shanghai))
        assertEquals("04:00", DisplayFormat.clockLabel(Instant.parse("2026-09-11T20:00:00Z").toEpochMilli(), shanghai))
    }

    @Test
    fun `range label joins two clocks`() {
        val start = Instant.parse("2026-09-11T20:00:00Z").toEpochMilli() // 04:00
        val end = Instant.parse("2026-09-11T22:30:00Z").toEpochMilli()   // 06:30
        assertEquals("04:00–06:30", DisplayFormat.rangeLabel(start, end, shanghai))
    }

    @Test
    fun `date label follows the chinese format regardless of locale`() {
        // 2026-09-12 是周六
        assertEquals("9月12日 周六", DisplayFormat.dateLabel(LocalDate.of(2026, 9, 12).toEpochDay()))
        assertEquals("1月1日 周四", DisplayFormat.dateLabel(LocalDate.of(2026, 1, 1).toEpochDay()))
        assertEquals("12月31日 周四", DisplayFormat.dateLabel(LocalDate.of(2026, 12, 31).toEpochDay()))
        assertEquals("9月12日", DisplayFormat.shortDateLabel(LocalDate.of(2026, 9, 12).toEpochDay()))
    }

    @Test
    fun `percent label rounds to whole numbers`() {
        assertEquals("67%", DisplayFormat.percentLabel(2.0 / 3.0))
        assertEquals("0%", DisplayFormat.percentLabel(0.0))
        assertEquals("100%", DisplayFormat.percentLabel(1.0))
        // 超额完成也要如实显示，而不是封顶到 100%
        assertEquals("150%", DisplayFormat.percentLabel(1.5))
    }

    @Test
    fun `month label`() {
        assertEquals("2026年9月", DisplayFormat.monthLabel(LocalDate.of(2026, 9, 12).toEpochDay()))
        assertEquals("2026年12月", DisplayFormat.monthLabel(LocalDate.of(2026, 12, 31).toEpochDay()))
    }

    @Test
    fun `calendar cell hours are floored and absent when empty`() {
        // 没有记录返回 null：界面据此留白，而不是给每一天都糊上一个 0h
        assertNull(DisplayFormat.compactHoursLabel(0L))
        assertNull(DisplayFormat.compactHoursLabel(-1L))
        assertEquals("<1h", DisplayFormat.compactHoursLabel(30 * 60_000L))
        assertEquals("1h", DisplayFormat.compactHoursLabel(60 * 60_000L))
        // 1 小时 59 分向下取整成 1h，不虚报成 2h
        assertEquals("1h", DisplayFormat.compactHoursLabel(119 * 60_000L))
        assertEquals("23h", DisplayFormat.compactHoursLabel(23 * 60 * 60_000L))
    }

    @Test
    fun `relative time picks the unit people would use`() {
        val now = 1_700_000_000_000L
        val minute = 60_000L
        val hour = 60 * minute

        assertEquals("刚刚", DisplayFormat.relativeTimeLabel(now - 30_000L, now))
        assertEquals("23 分钟前", DisplayFormat.relativeTimeLabel(now - 23 * minute, now))
        // 差一点点到一小时也说「59 分钟前」，不说「0 小时前」
        assertEquals("59 分钟前", DisplayFormat.relativeTimeLabel(now - 59 * minute, now))
        assertEquals("1 小时前", DisplayFormat.relativeTimeLabel(now - 61 * minute, now))
        assertEquals("23 小时前", DisplayFormat.relativeTimeLabel(now - 23 * hour, now))
        assertEquals("1 天前", DisplayFormat.relativeTimeLabel(now - 25 * hour, now))
        assertEquals("3 天前", DisplayFormat.relativeTimeLabel(now - 3 * 24 * hour, now))
    }

    @Test
    fun `a future moment does not become a negative number of days`() {
        // 时钟回拨或数据异常时，宁可说「刚刚」也不要显示「-1 天前」
        val now = 1_700_000_000_000L
        assertEquals("刚刚", DisplayFormat.relativeTimeLabel(now + 3_600_000L, now))
    }
}
