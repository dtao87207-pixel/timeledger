package com.timeledger.app.ui.reminder

import com.timeledger.app.core.time.DayBoundary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 提醒时刻计算测试。
 *
 * 最要紧的一条是**复盘提醒针对的是哪一天**：
 * 它的触发时刻落在次日 04:00，但复盘的对象是**刚刚结束的那一天**。
 * 这一点写错，复盘通知里就会显示成明天的日期。
 *
 * 日期基准：2026-09-12（周六），逻辑日 = 9月12日 04:00 ~ 9月13日 04:00。
 */
class ReminderScheduleTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private val fixedNow = Instant.parse("2026-09-12T06:00:00Z").toEpochMilli()
    private val dayBoundary = DayBoundary(zoneId = shanghai, nowProvider = { fixedNow })

    private val sept12: Long = LocalDate.of(2026, 9, 12).toEpochDay()
    private val sept13: Long = LocalDate.of(2026, 9, 13).toEpochDay()
    private val sept14: Long = LocalDate.of(2026, 9, 14).toEpochDay()

    private fun at(day: Long, hour: Int, minute: Int = 0): Long =
        dayBoundary.millisAtMinuteOfDay(day, hour * 60 + minute)

    private fun next(kind: ReminderKind, nowMillis: Long) =
        ReminderSchedule.nextTrigger(kind, nowMillis, dayBoundary)

    // ---------------- 中午 ----------------

    @Test
    fun `noon trigger is today noon when it is still ahead`() {
        val trigger = next(ReminderKind.NOON, at(sept12, 9))

        assertEquals(at(sept12, 12), trigger.triggerMillis)
        assertEquals(sept12, trigger.targetEpochDay)
    }

    @Test
    fun `noon trigger rolls to tomorrow once noon has passed`() {
        val trigger = next(ReminderKind.NOON, at(sept12, 13))

        assertEquals(at(sept13, 12), trigger.triggerMillis)
        assertEquals(sept13, trigger.targetEpochDay)
    }

    @Test
    fun `trigger exactly at the moment rolls to the next day`() {
        // 12:00 整点触发时，这一条已经到点了，下一次是明天
        val trigger = next(ReminderKind.NOON, at(sept12, 12))
        assertEquals(at(sept13, 12), trigger.triggerMillis)
    }

    // ---------------- 晚上 ----------------

    @Test
    fun `evening trigger rolls to tomorrow once evening has passed`() {
        val trigger = next(ReminderKind.EVENING, at(sept12, 22))

        assertEquals(at(sept13, 21), trigger.triggerMillis)
        assertEquals(sept13, trigger.targetEpochDay)
    }

    // ---------------- 凌晨复盘 ----------------

    @Test
    fun `review trigger is the end of the current logical day`() {
        val trigger = next(ReminderKind.REVIEW, at(sept12, 14))

        // 9月12日 这一格的结束瞬间 = 9月13日 04:00
        assertEquals(at(sept13, 4), trigger.triggerMillis)
        // 但它复盘的是 9月12日，而不是 9月13日
        assertEquals(sept12, trigger.targetEpochDay)
    }

    @Test
    fun `review trigger before dawn still targets the previous day`() {
        // 9月13日 02:00 属于 9月12日 那一格，下一次边界就在两小时后
        val trigger = next(ReminderKind.REVIEW, at(sept13, 2))

        assertEquals(at(sept13, 4), trigger.triggerMillis)
        assertEquals(sept12, trigger.targetEpochDay)
    }

    @Test
    fun `review trigger after the boundary targets the following day`() {
        val trigger = next(ReminderKind.REVIEW, at(sept13, 5))

        assertEquals(at(sept14, 4), trigger.triggerMillis)
        assertEquals(sept13, trigger.targetEpochDay)
    }

    // ---------------- 三个节点的整体顺序 ----------------

    @Test
    fun `next triggers are sorted by time`() {
        val morning = ReminderSchedule.nextTriggers(at(sept12, 9), dayBoundary)
        assertEquals(
            listOf(ReminderKind.NOON, ReminderKind.EVENING, ReminderKind.REVIEW),
            morning.map { it.kind },
        )

        // 晚上 22 点之后，最先到的是凌晨的复盘，其次是明天的中午与晚上
        val night = ReminderSchedule.nextTriggers(at(sept12, 22), dayBoundary)
        assertEquals(
            listOf(ReminderKind.REVIEW, ReminderKind.NOON, ReminderKind.EVENING),
            night.map { it.kind },
        )
        assertEquals(at(sept13, 4), night[0].triggerMillis)
        assertEquals(at(sept13, 12), night[1].triggerMillis)
        assertEquals(at(sept13, 21), night[2].triggerMillis)
    }

    @Test
    fun `every trigger is in the future`() {
        val now = at(sept12, 15)
        ReminderSchedule.nextTriggers(now, dayBoundary).forEach { trigger ->
            assertEquals(true, trigger.triggerMillis > now)
        }
    }

    @Test
    fun `reminder kinds expose their trigger hour`() {
        assertEquals(12, ReminderKind.NOON.triggerHour)
        assertEquals(21, ReminderKind.EVENING.triggerHour)
        // 复盘与逻辑日边界是同一个时刻
        assertEquals(4, ReminderKind.REVIEW.triggerHour)
    }

    // ---------------- 触发时该汇报哪一天 ----------------

    @Test
    fun `target day follows the trigger instant`() {
        assertEquals(
            sept12,
            ReminderSchedule.targetDayFor(ReminderKind.NOON, at(sept12, 12), dayBoundary),
        )
        assertEquals(
            sept12,
            ReminderSchedule.targetDayFor(ReminderKind.EVENING, at(sept12, 21), dayBoundary),
        )
    }

    @Test
    fun `review targets the day that just ended`() {
        // 触发时刻是次日 04:00，那一刻本身已经属于新的一天
        assertEquals(
            sept12,
            ReminderSchedule.targetDayFor(ReminderKind.REVIEW, at(sept13, 4), dayBoundary),
        )
    }

    @Test
    fun `a delayed review alarm still reviews yesterday`() {
        // 用的是非精确闹钟，系统可能推迟它：推到上午 8 点、甚至傍晚才响，
        // 复盘的对象依然应该是昨天 —— 所以按「实际触发的那一刻」算，而不是按预定时刻
        assertEquals(
            sept12,
            ReminderSchedule.targetDayFor(ReminderKind.REVIEW, at(sept13, 8), dayBoundary),
        )
        assertEquals(
            sept12,
            ReminderSchedule.targetDayFor(ReminderKind.REVIEW, at(sept13, 20), dayBoundary),
        )
    }

    @Test
    fun `scheduled target matches the target computed at the trigger instant`() {
        // 两处算法必须一致：安排时说「复盘 9月12日」，触发时也要算出 9月12日。
        // 不一致的话，通知里会写错日期，而且只在真机上才会被发现。
        val now = at(sept12, 14)
        ReminderKind.entries.forEach { kind ->
            val trigger = ReminderSchedule.nextTrigger(kind, now, dayBoundary)
            assertEquals(
                "节点 $kind 的预定向与触发时算出的一天应当一致",
                trigger.targetEpochDay,
                ReminderSchedule.targetDayFor(kind, trigger.triggerMillis, dayBoundary),
            )
        }
    }
}
