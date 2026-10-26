package com.timeledger.app.ui.reminder

import com.timeledger.app.core.time.DayBoundary

/** 一次提醒的触发安排。 */
data class ReminderTrigger(
    val kind: ReminderKind,
    /** 触发的绝对时刻。 */
    val triggerMillis: Long,
    /**
     * 这次提醒针对哪一天。
     *
     * 复盘提醒的触发时刻在**次日** 04:00，但它针对的是刚刚结束的那一天 ——
     * 这个区别不写清楚，复盘通知里就会显示成「明天」的日期。
     */
    val targetEpochDay: Long,
)

/**
 * 提醒时刻的计算 —— 纯函数，可完整测试。
 *
 * 与逻辑日边界共用同一套时间规则（`DayBoundary`）：
 * 复盘提醒就是「逻辑日结束的那一刻」，也就是默认的凌晨 4:00。
 * 这样「一天从 4 点开始」这条产品设定只有一处实现。
 */
object ReminderSchedule {

    fun nextTrigger(
        kind: ReminderKind,
        nowMillis: Long,
        dayBoundary: DayBoundary,
    ): ReminderTrigger {
        val currentDay = dayBoundary.dayKeyOf(nowMillis)
        // 今天的那个时刻已经过了就顺延到明天；最多看两天一定能找到
        for (offset in 0L..1L) {
            val day = currentDay + offset
            val triggerMillis = triggerMillisFor(kind, day, dayBoundary)
            if (triggerMillis > nowMillis) {
                return ReminderTrigger(kind, triggerMillis, targetEpochDay = day)
            }
        }
        val day = currentDay + 2L
        return ReminderTrigger(kind, triggerMillisFor(kind, day, dayBoundary), targetEpochDay = day)
    }

    /** 三个节点的下一次触发，按时间升序。 */
    fun nextTriggers(nowMillis: Long, dayBoundary: DayBoundary): List<ReminderTrigger> =
        ReminderKind.entries
            .map { kind -> nextTrigger(kind, nowMillis, dayBoundary) }
            .sortedBy { it.triggerMillis }

    /**
     * 某个**触发时刻**应该汇报哪一天。
     *
     * - 中午与晚上：触发时刻所在的逻辑日；
     * - 复盘：**刚刚结束的那一天**（触发时刻是次日 04:00，本身已经属于新的一天）。
     *
     * 闹钟可能被系统推迟（用的是非精确闹钟），所以这里按「实际触发的那一刻」算，
     * 而不是按预定时刻 —— 被推迟到早上 8 点才响的复盘，依然应该复盘昨天。
     */
    fun targetDayFor(kind: ReminderKind, nowMillis: Long, dayBoundary: DayBoundary): Long {
        val currentDay = dayBoundary.dayKeyOf(nowMillis)
        return when (kind) {
            ReminderKind.REVIEW -> currentDay - 1
            ReminderKind.NOON, ReminderKind.EVENING -> currentDay
        }
    }

    /**
     * 某个逻辑日上该节点的触发时刻。
     *
     * - 中午 / 晚上：该逻辑日的挂钟时刻（12:00 / 21:00）—— 逻辑日 04:00 开始，
     *   所以这两个时刻都落在这一天之内；
     * - 复盘：这一天的结束瞬间，也就是次日 04:00。
     */
    private fun triggerMillisFor(
        kind: ReminderKind,
        logicalDay: Long,
        dayBoundary: DayBoundary,
    ): Long = when (kind) {
        ReminderKind.REVIEW -> dayBoundary.dayRange(logicalDay).endMillisExclusive
        else -> dayBoundary.millisAtMinuteOfDay(logicalDay, kind.triggerHour * 60)
    }
}
