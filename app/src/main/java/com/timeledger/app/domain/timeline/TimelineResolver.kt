package com.timeledger.app.domain.timeline

import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.model.TimeRecord

/**
 * 重叠消解器 —— 把一堆可能互相重叠的时间记录，整理成一条**互不重叠**的时间轴。
 *
 * ## 为什么必须有它
 * 需求三节明确允许手动记录与系统记录覆盖同一时段，并给出了优先级
 * `MANUAL > MERGED > SYSTEM > SUGGESTED`。
 * 于是「今天学习了几小时」这种问题就**不能**把原始记录时长直接相加 ——
 * 用户手写的「14:00–15:00 Java」和系统测到的「14:30–15:30 Android Studio」
 * 是同一件事的两个证据，直接相加会凭空多出半小时。
 *
 * ## 算法
 * 1. 把所有正时长记录的起止时刻收集成边界点，切成互不重叠的**基本区间**；
 * 2. 每个基本区间由「覆盖它的记录中优先级最高的那条」胜出；
 * 3. 相邻且属于同一条记录的段合并，避免同一件事在时间轴上碎成好几块；
 * 4. 零长度记录（瞬时事件）不占时间、不与任何区间重叠，因此**全部保留**。
 *
 * 胜出顺序（完全确定，同样的输入永远得到同样的输出）：
 * 来源优先级 → 置信度 → 开始时间早的优先 → 输入下标小的优先。
 * 最后两级只是为了保证**确定性**：优先级与置信度都相同时，
 * 如果胜负随输入顺序摇摆，同一份数据两次渲染出不同时间轴，用户会以为记录丢了。
 *
 * ## 边界约定
 * 只负责「怎么消解」，不负责「取哪些记录」：调用方必须传入**已经过滤好**的记录
 * （已排除软删除、已按需要的区间取数）。这样本类保持纯函数，能直接在 JVM 上测。
 */
object TimelineResolver {

    /** 胜出顺序：优先级高的排最前，因此取 min。 */
    private val winnerOrder =
        compareByDescending<IndexedValue<TimeRecord>> { it.value.source.priority }
            .thenByDescending { it.value.confidence }
            .thenBy { it.value.startMillis }
            .thenBy { it.index }

    fun resolve(records: List<TimeRecord>): ResolvedTimeline {
        if (records.isEmpty()) return ResolvedTimeline.EMPTY

        val indexed = records.withIndex().toList()
        val spans = indexed.filter { it.value.endMillis > it.value.startMillis }
        val instants = indexed.filter { it.value.endMillis == it.value.startMillis }

        val segments = mutableListOf<TimelineSegment>()

        if (spans.isNotEmpty()) {
            // 所有边界点：基本区间就是相邻两个边界点之间那一段。
            val boundaries = sortedSetOf<Long>()
            spans.forEach { span ->
                boundaries += span.value.startMillis
                boundaries += span.value.endMillis
            }
            val points = boundaries.toList()

            for (i in 0 until points.size - 1) {
                val from = points[i]
                val to = points[i + 1]
                val slice = MillisRange(from, to)
                val winner = spans
                    .filter { it.value.range.overlaps(slice) }
                    .minWithOrNull(winnerOrder)
                    ?: continue
                segments += TimelineSegment(slice, winner.index, winner.value)
            }
        }

        val merged = mergeAdjacent(segments)

        // 瞬时事件不参与竞争，直接并入结果。
        val withInstants = merged + instants.map {
            TimelineSegment(
                range = MillisRange(it.value.startMillis, it.value.endMillis),
                recordIndex = it.index,
                record = it.value,
            )
        }

        return ResolvedTimeline(
            withInstants.sortedWith(
                compareBy({ it.range.startMillis }, { it.range.endMillisExclusive }, { it.recordIndex })
            )
        )
    }

    /**
     * 合并首尾相接且属于同一条记录的段。
     *
     * 不做这件事的话，一条被低优先级记录「穿过端点」的记录会被切成两段 ——
     * 时长统计不受影响（相加一样），但时间轴与复盘界面会显示成两件不相干的事。
     */
    private fun mergeAdjacent(segments: List<TimelineSegment>): List<TimelineSegment> {
        if (segments.size < 2) return segments

        val result = mutableListOf<TimelineSegment>()
        for (segment in segments) {
            val last = result.lastOrNull()
            if (last != null &&
                last.recordIndex == segment.recordIndex &&
                last.range.endMillisExclusive == segment.range.startMillis
            ) {
                result[result.lastIndex] = last.copy(
                    range = MillisRange(last.range.startMillis, segment.range.endMillisExclusive)
                )
            } else {
                result += segment
            }
        }
        return result
    }
}
