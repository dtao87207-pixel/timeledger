package com.timeledger.app.ui.format

import com.timeledger.app.domain.model.GoalPeriod
import com.timeledger.app.domain.model.GoalType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * 界面文案格式化 —— 纯函数，不依赖任何 Android API。
 *
 * 放在单独一处、并且**脱离 Compose**，是为了让「2h20m 到底怎么算出来的」可以被单元测试覆盖。
 * 如果把格式化写死在 Composable 里，这类边界（不足一分钟、恰好整点、跨零点结束）
 * 就只能靠肉眼看界面来验证 —— 而本项目根本没有能看界面的环境。
 *
 * 单位约定（与领域模型一致）：
 * - 时长在领域层一律是**毫秒**，这里负责转成「2h20m」；
 * - 目标值在领域层是**秒**（TIME）/ 个数（COUNT）/ 次数（FREQUENCY）。
 */
object DisplayFormat {

    private const val MINUTE_MILLIS = 60_000L
    private const val SECONDS_PER_HOUR = 3600.0

    /**
     * 紧凑时长：`2h20m` / `30m` / `<1m` / `0m`。
     *
     * 为什么不用「2小时20分钟」：首页一行里要同时放标题、时长与目标，
     * 中文长单位会把布局挤爆，而 `2h20m` 在中文语境里同样一眼能懂。
     */
    fun compactDuration(millis: Long): String {
        if (millis <= 0L) return "0m"
        val totalMinutes = millis / MINUTE_MILLIS
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0L && minutes > 0L -> "${hours}h${minutes}m"
            hours > 0L -> "${hours}h"
            totalMinutes > 0L -> "${minutes}m"
            // 有值但不足一分钟：写 0m 会让人以为没记录，写 <1m 更诚实
            else -> "<1m"
        }
    }

    /** 小时数（保留一位小数，整数不带小数点）：`3h` / `12.5h`。 */
    fun hoursLabel(seconds: Double): String {
        val rounded = (seconds / SECONDS_PER_HOUR * 10).roundToInt() / 10.0
        val asLong = rounded.toLong()
        return if (rounded == asLong.toDouble()) "${asLong}h" else "${rounded}h"
    }

    /**
     * 日历格子里的小时数：只到小时，不足一小时显示 `<1h`；没有记录返回 null。
     *
     * 为什么向下取整：格子里放不下分钟，而向上取整会把「记了 1 小时 5 分」显示成 2h，
     * 那是虚报。宁可少报一点也不虚报。
     */
    fun compactHoursLabel(millis: Long): String? {
        if (millis <= 0L) return null
        val hours = millis / MINUTE_MILLIS / 60
        return if (hours <= 0L) "<1h" else "${hours}h"
    }

    /** 数字（整数不带小数点）：`2` / `0.5`。 */
    fun numberLabel(value: Double): String {
        val asLong = value.toLong()
        return if (value == asLong.toDouble()) asLong.toString() else value.toString()
    }

    /** 目标值文案：TIME → `3h`，COUNT → `2个`，FREQUENCY → `3次`。 */
    fun goalTargetLabel(targetValue: Double, type: GoalType): String = when (type) {
        GoalType.TIME -> hoursLabel(targetValue)
        GoalType.COUNT -> numberLabel(targetValue) + "个"
        GoalType.FREQUENCY -> numberLabel(targetValue) + "次"
    }

    /** 已达成量文案，单位与 [goalTargetLabel] 一致。 */
    fun goalAchievedLabel(achieved: Double, type: GoalType): String = when (type) {
        // TIME 的达成量是秒，转成毫秒再走时长格式化
        GoalType.TIME -> compactDuration((achieved * 1000.0).toLong())
        GoalType.COUNT -> numberLabel(achieved) + "个"
        GoalType.FREQUENCY -> numberLabel(achieved) + "次"
    }

    /** 目标周期文案。 */
    fun periodLabel(period: GoalPeriod): String = when (period) {
        GoalPeriod.DAY -> "今日"
        GoalPeriod.WEEK -> "本周"
        GoalPeriod.MONTH -> "本月"
    }

    /** 时钟：`14:05`。手写补零而不用 String.format，避免受系统 Locale 影响。 */
    fun clockLabel(millis: Long, zone: ZoneId): String {
        val time = Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()
        return "${twoDigits(time.hour)}:${twoDigits(time.minute)}"
    }

    /** 时间区间：`19:00–21:00`。 */
    fun rangeLabel(startMillis: Long, endMillisExclusive: Long, zone: ZoneId): String =
        "${clockLabel(startMillis, zone)}–${clockLabel(endMillisExclusive, zone)}"

    /** 日期：`9月12日 周六`。 */
    fun dateLabel(epochDay: Long): String {
        val date = LocalDate.ofEpochDay(epochDay)
        return "${date.monthValue}月${date.dayOfMonth}日 ${weekdayLabel(date)}"
    }

    /** 月份：`2026年9月`（日历页标题）。 */
    fun monthLabel(epochDay: Long): String {
        val date = LocalDate.ofEpochDay(epochDay)
        return "${date.year}年${date.monthValue}月"
    }

    /** 年份：`2026年`（年统计标题）。 */
    fun yearLabel(epochDay: Long): String = "${LocalDate.ofEpochDay(epochDay).year}年"

    /** 短日期：`9月12日`。 */
    fun shortDateLabel(epochDay: Long): String {
        val date = LocalDate.ofEpochDay(epochDay)
        return "${date.monthValue}月${date.dayOfMonth}日"
    }

    /**
     * 相对时间：`刚刚` / `23 分钟前` / `5 小时前` / `3 天前`。
     *
     * 用在「最近用过」这类地方：用户要知道的是「多久以前」，
     * 而不是一个具体时刻 —— 「8月31日 14:20」还得自己在脑子里做减法。
     *
     * 未来时刻（时钟回拨或数据异常）按「刚刚」处理，不显示负数天。
     */
    fun relativeTimeLabel(millis: Long, nowMillis: Long): String {
        val elapsed = nowMillis - millis
        if (elapsed < 60_000L) return "刚刚"

        val minutes = elapsed / 60_000L
        if (minutes < 60L) return "$minutes 分钟前"

        val hours = elapsed / 3_600_000L
        if (hours < 24L) return "$hours 小时前"

        return "${elapsed / 86_400_000L} 天前"
    }

    /** 星期：`周一`…`周日`。手动映射而不用 Locale，保证与系统语言无关、结果稳定。 */
    fun weekdayLabel(date: LocalDate): String = when (date.dayOfWeek) {
        DayOfWeek.MONDAY -> "周一"
        DayOfWeek.TUESDAY -> "周二"
        DayOfWeek.WEDNESDAY -> "周三"
        DayOfWeek.THURSDAY -> "周四"
        DayOfWeek.FRIDAY -> "周五"
        DayOfWeek.SATURDAY -> "周六"
        DayOfWeek.SUNDAY -> "周日"
    }

    /** 百分比：`67%`。 */
    fun percentLabel(ratio: Double): String = "${(ratio * 100).roundToInt()}%"

    private fun twoDigits(value: Int): String = if (value < 10) "0$value" else value.toString()
}
