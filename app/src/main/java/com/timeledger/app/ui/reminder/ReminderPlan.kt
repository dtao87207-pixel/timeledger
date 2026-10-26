package com.timeledger.app.ui.reminder

import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.goal.GoalProgress
import com.timeledger.app.domain.review.DayReview
import com.timeledger.app.ui.format.DisplayFormat

/**
 * 决定「要不要打扰、说什么」所需要的全部输入。
 *
 * 做成一个显式的数据结构、而不是让规划器直接去查数据库，有两个好处：
 * 1. 规划器是纯函数，可以完整测试 —— 而提醒的规则（什么时候**不**打扰）
 *    恰恰是最需要测试的部分；
 * 2. 平台层（通知接收器）只需要准备好这份数据，不需要了解任何业务规则。
 */
data class ReminderContext(
    val kind: ReminderKind,
    /** 这次提醒针对哪一天（复盘提醒针对的是刚刚结束的那一天）。 */
    val epochDay: Long,
    /** `9月12日 周六`。 */
    val dateLabel: String,
    /** 当天已确认的时长。 */
    val confirmedMillis: Long,
    val pendingRecordCount: Int,
    val pendingMillis: Long,
    /** 当天完全没有记录的时间段。 */
    val gaps: List<MillisRange>,
    val unrecordedMillis: Long,
    /** 当天生效中的目标进度。 */
    val goals: List<GoalProgress>,
    /** 当天有没有任何记录或计划；完全没有数据时不该打扰用户。 */
    val hasAnyData: Boolean,
    /** 当天中午的时刻，用来判断哪些缺口属于「上午」。 */
    val noonMillis: Long,
) {

    companion object {
        /**
         * 从复盘数据构造。
         *
         * [noonMillis] 由调用方用 `DayBoundary.millisAtMinuteOfDay(epochDay, 12*60)` 算出 ——
         * 不在这里用「日边界 + 8 小时」硬算，是因为夏令时切换日两者会差一小时。
         */
        fun of(
            kind: ReminderKind,
            review: DayReview,
            goals: List<GoalProgress>,
            noonMillis: Long,
        ): ReminderContext = ReminderContext(
            kind = kind,
            epochDay = review.epochDay,
            dateLabel = DisplayFormat.dateLabel(review.epochDay),
            confirmedMillis = review.durations.confirmedMillis,
            pendingRecordCount = review.pendingRecordCount,
            pendingMillis = review.durations.pendingMillis,
            gaps = review.durations.unrecordedSegments,
            unrecordedMillis = review.durations.unrecordedMillis,
            goals = goals,
            hasAnyData = review.hasAnyData,
            noonMillis = noonMillis,
        )
    }
}

/**
 * 一次提醒的计划。
 *
 * [shouldNotify] 为 false 时 [title] 与 [body] 为空 —— 不打扰就是不打扰，
 * 不做「静默推送一条空通知」这种自欺欺人的事。
 */
data class ReminderPlan(
    val kind: ReminderKind,
    val epochDay: Long,
    val shouldNotify: Boolean,
    val title: String,
    val body: String,
    /**
     * 静默通知：不响铃、不弹横幅，只躺在通知栏里。
     *
     * 只有凌晨复盘那条是静默的。
     */
    val silent: Boolean,
) {

    companion object {
        /** 决定不提醒。 */
        fun skip(kind: ReminderKind, epochDay: Long): ReminderPlan = ReminderPlan(
            kind = kind,
            epochDay = epochDay,
            shouldNotify = false,
            title = "",
            body = "",
            silent = false,
        )
    }
}
