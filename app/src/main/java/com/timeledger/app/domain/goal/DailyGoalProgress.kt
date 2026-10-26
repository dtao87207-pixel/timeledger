package com.timeledger.app.domain.goal

/**
 * 某一天的目标进度快照。
 *
 * 用于日历视图（需求二节：点击某一天查看完整时间轴、查看每日目标完成情况）
 * 与统计里的「目标趋势」（需求十三节：月视图有目标趋势）。
 *
 * 为什么是「一天的进度列表」而不是「一个目标的每日序列」：
 * 同一天可能同时有日目标、周目标、月目标，它们各自的窗口不同；
 * 按天组织更贴近读取方式（一次读一天），要做趋势时调用方再按目标分组即可。
 */
data class DailyGoalProgress(
    val epochDay: Long,
    /** 当天生效的目标进度，顺序与目标列表一致。 */
    val progress: List<GoalProgress>,
) {

    val activeGoalCount: Int get() = progress.size

    val achievedGoalCount: Int get() = progress.count { it.isAchieved }

    /** 有适用目标的那些目标里，已达标所占比例；没有适用目标时为 null（而不是 0）。 */
    val achievedRatio: Double?
        get() = if (progress.isEmpty()) null
        else achievedGoalCount.toDouble() / progress.size
}
