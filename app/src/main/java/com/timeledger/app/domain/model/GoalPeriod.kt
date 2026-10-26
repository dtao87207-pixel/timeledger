package com.timeledger.app.domain.model

/**
 * 目标周期（需求四节）。
 *
 * 周期与「逻辑日」的关系：
 * - [DAY]   对齐 [com.timeledger.app.core.time.TimeBoundary] 的逻辑日（04:00 ~ 次日 03:59），
 *           而不是自然日 00:00 ~ 23:59。
 * - [WEEK]  以周一 04:00 为起点，下周一 04:00 为终点。
 * - [MONTH] 以当月 1 日 04:00 为起点，次月 1 日 04:00 为终点。
 *
 * 这样「今天」在凌晨 1 点时仍属于昨天的目标周期，与产品「凌晨 4 点才翻篇」的设定一致。
 */
enum class GoalPeriod {
    DAY,
    WEEK,
    MONTH;

    companion object {
        fun fromNameOrNull(value: String?): GoalPeriod? =
            value?.let { name -> entries.firstOrNull { it.name == name } }
    }
}
