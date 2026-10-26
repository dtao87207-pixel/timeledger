package com.timeledger.app.domain.review

import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.model.RecordSource
import com.timeledger.app.domain.timeline.ResolvedTimeline
import com.timeledger.app.domain.timeline.TimelineSegment

/**
 * 时间轴聚合器 —— 把一条时间轴压成「各类时间有多长」。
 *
 * 纯逻辑，不碰数据库：输入是 [ResolvedTimeline]（已经消解过重叠）+ 统计窗口，
 * 输出是 [DurationBreakdown]。
 *
 * ## 三个容易算错的地方，这里逐一处理
 *
 * 1. **必须先消解重叠再聚合**。手动记录与系统记录允许重叠，
 *    直接对原始记录求和会把同一段时间算两遍（见 TimelineResolver 的说明）。
 *    本类接收的是消解结果，因此天然不会重复。
 * 2. **必须裁剪到窗口**。一条 03:00–06:00 的记录横跨 4:00 分界线，
 *    属于今天的只有 04:00–06:00 那两小时；不裁剪就会让某一天超过 24 小时。
 * 3. **未确认的推测单独计算**。`SUGGESTED` 是系统猜的、还没经用户确认，
 *    分类分布里不包含它，而是单独作为 [DurationBreakdown.pendingMillis] 汇报 ——
 *    这样界面上既能显示「学习 5h20」，也能显示「另有 40 分钟待确认」，两者不混淆。
 *
 * 以上第 1、2 条与目标系统的口径完全一致：同一套规则不能有两个实现。
 */
class TimelineAggregator {

    fun aggregate(timeline: ResolvedTimeline, window: MillisRange): DurationBreakdown {
        // 只保留「有长度、且落在窗口内」的部分；瞬时事件不占时间，不参与时长统计。
        val clipped = timeline.segments
            .mapNotNull { segment ->
                if (segment.isInstant) return@mapNotNull null
                val inWindow = segment.range.intersect(window) ?: return@mapNotNull null
                if (inWindow.durationMillis <= 0L) return@mapNotNull null
                ClippedSegment(segment, inWindow)
            }

        val confirmed = clipped.filter { it.segment.source != RecordSource.SUGGESTED }
        val pendingMillis = clipped
            .filter { it.segment.source == RecordSource.SUGGESTED }
            .sumOf { it.inWindow.durationMillis }

        val coveredMillis = clipped.sumOf { it.inWindow.durationMillis }

        return DurationBreakdown(
            window = window,
            coveredMillis = coveredMillis,
            // 用减法而不是重新求和：这样「已确认 + 待确认 = 有记录」是**构造上成立**的，
            // 不会因为两处求和口径不一致而出现对不上的数字。
            confirmedMillis = coveredMillis - pendingMillis,
            pendingMillis = pendingMillis,
            unrecordedMillis = (window.durationMillis - coveredMillis).coerceAtLeast(0L),
            byCategory = aggregateByCategory(confirmed),
            byActivity = aggregateByActivity(confirmed),
            unrecordedSegments = uncoveredSegments(clipped.map { it.inWindow }, window),
        )
    }

    private fun aggregateByCategory(confirmed: List<ClippedSegment>): List<DurationBucket> =
        confirmed
            .groupBy { it.segment.categoryId }
            .map { (categoryId, group) ->
                DurationBucket(
                    categoryId = categoryId,
                    activityId = null,
                    durationMillis = group.sumOf { it.inWindow.durationMillis },
                )
            }
            .sortedWith(BUCKET_ORDER)

    private fun aggregateByActivity(confirmed: List<ClippedSegment>): List<DurationBucket> =
        confirmed
            .filter { it.segment.activityId != null }
            .groupBy { it.segment.activityId }
            .map { (activityId, group) ->
                DurationBucket(
                    // 带上所属分类，界面按分类分组显示时不用再回表查归属
                    categoryId = group.first().segment.categoryId,
                    activityId = activityId,
                    durationMillis = group.sumOf { it.inWindow.durationMillis },
                )
            }
            .sortedWith(BUCKET_ORDER)

    /**
     * 找出窗口内没有任何记录覆盖的时间段。
     *
     * 输入必须已按时间升序且互不重叠（[ResolvedTimeline] 保证），
     * 因此一次线性扫描就能得到全部缺口，不需要两两比较。
     */
    private fun uncoveredSegments(
        clippedInOrder: List<MillisRange>,
        window: MillisRange,
    ): List<MillisRange> {
        val gaps = mutableListOf<MillisRange>()
        var cursor = window.startMillis
        for (range in clippedInOrder) {
            if (range.startMillis > cursor) {
                gaps += MillisRange(cursor, range.startMillis)
            }
            if (range.endMillisExclusive > cursor) {
                cursor = range.endMillisExclusive
            }
        }
        if (cursor < window.endMillisExclusive) {
            gaps += MillisRange(cursor, window.endMillisExclusive)
        }
        return gaps
    }

    /** 一个段落 + 它裁剪到窗口之后剩下的部分。 */
    private data class ClippedSegment(
        val segment: TimelineSegment,
        val inWindow: MillisRange,
    )

    private companion object {
        /**
         * 时长降序；并列时按 id 升序。
         *
         * 并列也要有确定的顺序，否则同样的数据两次渲染出不同排列，
         * 用户会以为数字变了。null（未分类/未指定活动）排在最后。
         */
        val BUCKET_ORDER: Comparator<DurationBucket> =
            compareByDescending<DurationBucket> { it.durationMillis }
                .thenBy { it.activityId ?: Long.MAX_VALUE }
                .thenBy { it.categoryId ?: Long.MAX_VALUE }
    }
}
