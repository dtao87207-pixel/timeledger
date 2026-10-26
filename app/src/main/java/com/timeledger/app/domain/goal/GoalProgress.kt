package com.timeledger.app.domain.goal

import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.model.Goal
import com.timeledger.app.domain.model.GoalType

/**
 * 一个目标在某个周期内的完成情况 —— **每次都是现算出来的**，不落库、不缓存。
 *
 * 这是需求十三节「所有统计尽量从原始 TimeRecord 动态计算，避免修改记录后统计数据过期」
 * 在目标系统上的落地：本对象是计算结果，不是存储结构。
 * 用户回头改掉一条上周的记录，上周的完成度立刻跟着变，不需要任何失效逻辑。
 *
 * [achieved] 的单位与 [Goal.targetValue] 一致：TIME → 秒，COUNT → 个数，FREQUENCY → 次数。
 * 全程用 Double 是因为 COUNT 可能带小数（例如「跑了 2.5 公里」式的目标），
 * 而 TIME 用秒而不是小时，避免小时的小数在累加中丢精度。
 */
data class GoalProgress(
    val goal: Goal,
    /** 本次统计实际采用的时间窗口（已按目标有效期裁剪，可能小于整个周期）。 */
    val window: MillisRange,
    /** 已达成量，单位同 [Goal.targetValue]。 */
    val achieved: Double,
    /** 目标值，单位同 [Goal.targetValue]。 */
    val target: Double,
    /** 参与本次统计的记录条数，用于界面上解释「这数字是怎么来的」。 */
    val contributingRecordCount: Int,
) {

    /** 完成比例，可超过 1.0（超额完成）。 */
    val ratio: Double get() = if (target <= 0.0) 0.0 else achieved / target

    /** 是否已达标。 */
    val isAchieved: Boolean get() = achieved >= target

    /** 距离达标还差多少（已达标为 0.0）。 */
    val remaining: Double get() = (target - achieved).coerceAtLeast(0.0)

    /** 给进度条用的比例，封顶 1.0。 */
    val progressFraction: Double get() = ratio.coerceIn(0.0, 1.0)

    /** 该目标的计量单位，UI 展示用。 */
    val unitLabel: String
        get() = when (goal.type) {
            GoalType.TIME -> "秒"
            GoalType.COUNT -> "个"
            GoalType.FREQUENCY -> "次"
        }
}
