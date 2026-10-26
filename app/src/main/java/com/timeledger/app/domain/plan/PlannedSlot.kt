package com.timeledger.app.domain.plan

import com.timeledger.app.core.time.MillisRange

/**
 * 展开后的「计划时段」—— 计划条目在**某个具体逻辑日**上的落地结果。
 *
 * 与 [com.timeledger.app.domain.model.PlanBlock] 的区别：
 * - PlanBlock 是**挂钟时间模式**（「周一 09:00–10:40」），没有绝对时刻；
 * - PlannedSlot 是**绝对时间戳区间**，可以和 TimeRecord 直接比对。
 *
 * 分开的理由：课表是「每周一都这样」，而时间轴上的记录是「上周一确实做了什么」。
 * 把两者混成一个类型，就会出现「这条计划到底是哪个周一」这种说不清的状态。
 */
data class PlannedSlot(
    /** 来源计划条目 id，便于点回原文编辑。 */
    val planBlockId: Long,
    val title: String,
    /**
     * 该时段归属的逻辑日（由开始时刻决定，与 TimeRecord 同一套规则）。
     *
     * 为什么需要它：跨夜的课程（例如 23:30–01:00）在日历上横跨两天，
     * 但数据上必须只属于一格，否则统计与展示都会重复或错位。
     */
    val logicalDay: Long,
    /** 计划占用的绝对时间区间，左闭右开。 */
    val range: MillisRange,
    val categoryId: Long?,
    val activityId: Long?,
    val location: String?,
    val note: String?,
    /** 是否跨过午夜。来自计划条目本身的结构（结束分钟数不大于开始分钟数），不依赖时区。 */
    val crossesMidnight: Boolean,
) {

    val startMillis: Long get() = range.startMillis

    val endMillisExclusive: Long get() = range.endMillisExclusive

    val durationMillis: Long get() = range.durationMillis

    /** 计划时长（分钟）。UI 展示用，内部计算一律用 [durationMillis]。 */
    val durationMinutes: Int get() = (durationMillis / 60_000L).toInt()

    /**
     * 计划是否绑定了分类或活动。
     *
     * 这个判断在「计划 vs 实际」比对里是关键前提：没绑定就无法判断用户是否照做了，
     * 因此比对器会直接跳过「符合计划」的判定，而不是猜一个答案。
     * 定义在这里（而不是比对器里）是为了让绑定规则只有一处实现 ——
     * 目标系统、比对器、界面都问同一个问题。
     */
    val hasBinding: Boolean get() = activityId != null || categoryId != null
}
