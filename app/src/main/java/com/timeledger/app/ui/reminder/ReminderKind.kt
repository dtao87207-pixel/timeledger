package com.timeledger.app.ui.reminder

import com.timeledger.app.core.time.TimeBoundary

/**
 * 提醒节点（需求十二节）。
 *
 * 三个节点的**性质并不相同**，这一点必须写清楚，否则实现时很容易做成「每天定点响三次」：
 *
 * - [NOON]：确认上午的记录。有内容才提醒，没内容就不打扰。
 * - [EVENING]：看看今天还有什么目标没完成、有什么没确认。全都做完了就不打扰。
 * - [REVIEW]：凌晨 4 点进入每日复盘。**这一条只做静默通知** ——
 *   凌晨响铃会把人吵醒，既违背「低打扰」，也基本等于让用户卸载这个 App。
 *
 * 需求原文也说了「4:00 的通知不是强制结算，而是进入每日复盘」，
 * 所以它是「这一天整理好了」的告知，不是催办。
 */
enum class ReminderKind(
    val triggerHour: Int,
    val label: String,
) {
    NOON(12, "中午"),

    EVENING(21, "晚上"),

    /** 与逻辑日边界同一个时刻：这一条提醒的是「刚刚结束的那一天」。 */
    REVIEW(TimeBoundary.DEFAULT_BOUNDARY_HOUR, "每日复盘"),
    ;

    companion object {
        fun fromNameOrNull(value: String?): ReminderKind? =
            value?.let { name -> entries.firstOrNull { it.name == name } }
    }
}
