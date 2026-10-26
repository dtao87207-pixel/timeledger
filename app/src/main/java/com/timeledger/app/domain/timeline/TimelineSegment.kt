package com.timeledger.app.domain.timeline

import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.model.RecordSource
import com.timeledger.app.domain.model.TimeRecord

/**
 * 时间轴上的一段 —— 归属到**唯一一条**记录、且与其他段互不重叠。
 *
 * [recordIndex] 是它在输入列表中的下标。为什么用下标而不是 [TimeRecord.id]：
 * 解析结果需要能唯一指回原记录，而下标对「尚未落库、id 为 0」的记录同样有效，
 * 测试里构造的数据不需要先编造数据库主键。同时配合 `distinct()` 就能算出
 * 「有多少条不同的记录参与了统计」，不受数据类相等性的干扰。
 */
data class TimelineSegment(
    val range: MillisRange,
    val recordIndex: Int,
    val record: TimeRecord,
) {
    val durationMillis: Long get() = range.durationMillis

    val categoryId: Long? get() = record.categoryId

    val activityId: Long? get() = record.activityId

    val source: RecordSource get() = record.source

    /** 瞬时事件（零长度），只标记「这一刻发生过什么」，不占用时长。 */
    val isInstant: Boolean get() = range.isInstant
}

/**
 * 消解重叠之后的时间轴。
 *
 * 不变式（由 [TimelineResolver] 保证，测试逐条覆盖）：
 * 1. [segments] 按开始时间升序；
 * 2. 除瞬时事件外，任意两段**互不重叠**（`MillisRange.overlaps` 为 false）；
 * 3. 同一时刻最多只有一条记录「赢」—— 重叠时按来源优先级消解；
 * 4. 瞬时事件全部保留（它不占时间，不会与任何人冲突）。
 *
 * 因此「这一天有多长被记录覆盖」就是各段时长之和，**不需要再去重**。
 */
data class ResolvedTimeline(
    val segments: List<TimelineSegment>,
) {

    /** 被正时长段落覆盖的总毫秒数（不含瞬时事件，因为已互不重叠所以可直接相加）。 */
    val coveredDurationMillis: Long
        get() = segments.filterNot { it.isInstant }.sumOf { it.durationMillis }

    /**
     * 至少赢下一段的记录（去重）。
     *
     * 「赢下」的定义很关键：一条记录可能在时间轴上占**多段**（中间被更高优先级的记录切走），
     * 但它在 [segments] 里出现多次、在这里只算一条。
     * COUNT / FREQUENCY 类目标必须基于它来统计，否则一条记录被切成两段就会被算成两次。
     */
    val survivingRecords: List<TimeRecord>
        get() = segments.distinctBy { it.recordIndex }.map { it.record }

    /** 只保留与给定绑定匹配的段落。[activityId] 优先于 [categoryId]。 */
    fun segmentsMatching(categoryId: Long?, activityId: Long?): List<TimelineSegment> =
        segments.filter { matchesBinding(it.record, categoryId, activityId) }

    /**
     * 只保留与给定绑定匹配的「赢下的记录」，且**其开始时间落在 [window] 内**。
     *
     * 为什么这里用「开始时间在窗口内」而 TIME 用「区间裁剪」：
     * 计数与次数是**不可分割**的原子值 —— 一次健身跨过午夜不该被算成半次。
     * 所以计数类目标按记录归属的那一天整体计入，而时长类可以按分钟切开。
     */
    fun survivingRecordsMatching(
        categoryId: Long?,
        activityId: Long?,
        window: MillisRange,
    ): List<TimeRecord> = survivingRecords.filter {
        matchesBinding(it, categoryId, activityId) && it.startMillis in window
    }

    private fun matchesBinding(
        record: TimeRecord,
        categoryId: Long?,
        activityId: Long?,
    ): Boolean = when {
        activityId != null -> record.activityId == activityId
        categoryId != null -> record.categoryId == categoryId
        // 目标必须绑定分类或活动之一（Goal 的构造函数已保证），
        // 所以「两个都为空」是不可能出现的入参，返回 false 而不是抛异常。
        else -> false
    }

    companion object {
        val EMPTY = ResolvedTimeline(emptyList())
    }
}
