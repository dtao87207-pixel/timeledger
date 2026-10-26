package com.timeledger.app.domain.model

/**
 * 目标类型（需求四节）。
 *
 * - [TIME]      时长型，例如「Java 每天 ≥ 3 小时」「Java 每周 ≥ 15 小时」。
 *               目标的 [Goal.targetValue] 以**秒**为单位存储，
 *               由绑定的 TimeRecord 时长动态累加得到，绝不缓存统计结果。
 * - [COUNT]     计数型，例如「LeetCode 每天 2 题」「完成 Java 3 个章节」。
 *               累加 TimeRecord.countValue。
 * - [FREQUENCY] 频次型，例如「健身每周 3 次」「跑步每周 3 次」。
 *               统计周期内匹配到的**记录条数**（一条记录代表一次发生）。
 *
 * 为什么不把三种类型拆成三张表：三种目标的绑定方式（Category/Activity）、
 * 周期、起止日期完全一致，只有「怎么算完成度」不同，
 * 因此用单表 + 类型枚举，完成度计算策略在目标模块中实现，便于后续新增类型。
 */
enum class GoalType {
    TIME,
    COUNT,
    FREQUENCY;

    /** 目标的计量单位，仅用于 UI 展示，不参与计算。 */
    val unitLabel: String
        get() = when (this) {
            TIME -> "小时"
            COUNT -> "个"
            FREQUENCY -> "次"
        }

    companion object {
        fun fromNameOrNull(value: String?): GoalType? =
            value?.let { name -> entries.firstOrNull { it.name == name } }
    }
}
