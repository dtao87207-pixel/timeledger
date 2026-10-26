package com.timeledger.app.domain.review

import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.model.RecordSource
import com.timeledger.app.domain.plan.PlannedSlot

/**
 * 「计划时段内实际发生的一段」—— 已经裁剪到该计划时段之内。
 *
 * 保留 [source] 是为了让界面能区分「系统检测到的事实」与「用户确认过的记录」，
 * 以及把 `SUGGESTED` 的待确认部分弱化显示。
 */
data class PlanActualSegment(
    val range: MillisRange,
    val recordIndex: Int,
    val categoryId: Long?,
    val activityId: Long?,
    val source: RecordSource,
) {
    val durationMillis: Long get() = range.durationMillis
}

/**
 * 一个计划时段与实际情况的对比（需求十节：这是产品核心）。
 *
 * ## 为什么不是一个「完成度百分比」了事
 * 需求里的例子说明得很清楚：计划 19:00–21:00 学 Java，系统检测到
 * Android Studio 43 分钟、Chrome 35 分钟、B站 20 分钟。
 * 这三段全部落在计划时段内，但只有前两段算「按计划」。
 * 所以本模型同时给出：
 * - [recordedMillis]：这段时间里总共记了多少（不管是否符合计划）；
 * - [matchedMillis]：其中与计划内容一致的部分；
 * - [segments]：具体是什么，交给界面原样展示（「B站 20 分钟」这种信息不能丢）。
 *
 * 光给一个百分比，用户没法据此调整安排；给出明细才能回答「我到底把时间花哪了」。
 */
data class PlanActualComparison(
    val slot: PlannedSlot,
    /** 计划时段内**有记录覆盖**的时长。 */
    val recordedMillis: Long,
    /** 其中与计划绑定的分类/活动一致的时长。 */
    val matchedMillis: Long,
    /** 计划时段内的实际明细，按时间升序、已裁剪到该时段内。 */
    val segments: List<PlanActualSegment>,
) {

    val plannedMillis: Long get() = slot.durationMillis

    /** 计划时段内没有任何记录的时间。 */
    val unrecordedMillis: Long get() = (plannedMillis - recordedMillis).coerceAtLeast(0L)

    /** 其中未确认（`SUGGESTED`）的部分。 */
    val pendingMillis: Long
        get() = segments.filter { it.source == RecordSource.SUGGESTED }
            .sumOf { it.durationMillis }

    /** 计划是否绑定了分类或活动。没绑定时无法判断「是否符合计划」。 */
    val hasBinding: Boolean get() = slot.hasBinding

    /**
     * 计划时段内「记了别的事」的时长。
     *
     * 计划没有绑定分类/活动时一律返回 0：那种情况下根本无法判断是否符合计划，
     * 给出一个数字只会误导用户以为自己在偏离计划。
     */
    val deviatedMillis: Long
        get() = if (!hasBinding) {
            0L
        } else {
            (recordedMillis - matchedMillis - pendingMillis).coerceAtLeast(0L)
        }

    /** 按计划执行的比例：一致的时间 / 计划时长。 */
    val adherenceRatio: Double
        get() = if (plannedMillis <= 0L) 0.0 else matchedMillis.toDouble() / plannedMillis
}
