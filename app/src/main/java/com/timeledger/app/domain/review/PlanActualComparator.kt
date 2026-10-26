package com.timeledger.app.domain.review

import com.timeledger.app.domain.model.RecordSource
import com.timeledger.app.domain.plan.PlannedSlot
import com.timeledger.app.domain.timeline.ResolvedTimeline

/**
 * 计划 vs 实际的比对器（需求十节）—— 纯逻辑。
 *
 * ## 判定「符合计划」的规则
 * 计划绑定了活动就按**活动**比，只绑分类就按**分类**比（与目标系统的绑定口径一致）。
 * 两者都没绑（例如只写了「自习」）时，[PlanActualComparison.matchedMillis] 为 0，
 * 明细照常给出 —— 不知道计划该是什么，就不假装知道用户是否照做了。
 *
 * ## 为什么明细用「已消解的时间轴」而不是原始记录
 * 因为同一段时间可能既有用户手写的记录又有系统检测结果。
 * 用原始记录会出现「计划 2 小时，实际记了 3 小时」这种荒谬结果 ——
 * 那不是用户超时完成，而是同一段时间被算了两遍。
 *
 * ## 未确认的推测算不算
 * [PlanActualComparison.recordedMillis] 算（它确实是「这段时间有数据」），
 * 但 [PlanActualComparison.matchedMillis] 不算 —— 与目标系统口径保持一致：
 * 未确认的推测不换成绩。明细里保留 `source` 让界面自行弱化显示。
 */
class PlanActualComparator {

    fun compare(slot: PlannedSlot, timeline: ResolvedTimeline): PlanActualComparison {
        val segments = timeline.segments
            .mapNotNull { segment ->
                if (segment.isInstant) return@mapNotNull null
                val inSlot = segment.range.intersect(slot.range) ?: return@mapNotNull null
                if (inSlot.durationMillis <= 0L) return@mapNotNull null
                PlanActualSegment(
                    range = inSlot,
                    recordIndex = segment.recordIndex,
                    categoryId = segment.categoryId,
                    activityId = segment.activityId,
                    source = segment.source,
                )
            }

        val recordedMillis = segments.sumOf { it.durationMillis }
        val matchedMillis = if (!slot.hasBinding) {
            0L
        } else {
            segments
                .filter { it.source != RecordSource.SUGGESTED }
                .filter { matchesPlan(slot, it) }
                .sumOf { it.durationMillis }
        }

        return PlanActualComparison(
            slot = slot,
            recordedMillis = recordedMillis,
            matchedMillis = matchedMillis,
            segments = segments,
        )
    }

    fun compareAll(
        slots: List<PlannedSlot>,
        timeline: ResolvedTimeline,
    ): List<PlanActualComparison> = slots.map { compare(it, timeline) }

    /** 计划绑定活动就按活动比，否则按分类比（与 TimelineSegment 的匹配规则一致）。 */
    private fun matchesPlan(slot: PlannedSlot, segment: PlanActualSegment): Boolean =
        when {
            slot.activityId != null -> segment.activityId == slot.activityId
            slot.categoryId != null -> segment.categoryId == slot.categoryId
            else -> false
        }
}
