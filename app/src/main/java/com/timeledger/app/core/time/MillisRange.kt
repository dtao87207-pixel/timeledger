package com.timeledger.app.core.time

/**
 * 毫秒时间戳的**左闭右开**区间 `[startMillis, endMillisExclusive)`。
 *
 * 为什么不用 LongRange：LongRange 是闭区间，无法表达「不含结束点」，
 * 而时间轴上的所有区间语义都是左闭右开（避免相邻区间在端点处重复计算）。
 * 所有跨日、重叠判断都基于这个类型，保证全项目只有一种区间语义。
 */
data class MillisRange(
    val startMillis: Long,
    val endMillisExclusive: Long,
) : Comparable<MillisRange> {

    init {
        require(endMillisExclusive >= startMillis) {
            "区间结束不能早于开始: [$startMillis, $endMillisExclusive)"
        }
    }

    /** 区间长度（毫秒）。零长度区间表示「瞬时事件」（例如只记了次数的记录）。 */
    val durationMillis: Long get() = endMillisExclusive - startMillis

    /** 是否为瞬时事件（start == end）。 */
    val isInstant: Boolean get() = startMillis == endMillisExclusive

    operator fun contains(millis: Long): Boolean =
        millis >= startMillis && millis < endMillisExclusive

    /**
     * 与另一区间是否有**严格几何交集**。
     *
     * 实现直接复用 [intersect]，保证「有重叠」与「交集非空」永远一致 ——
     * 这两个判断如果各写一套公式，迟早会出现一个说有、一个说没有的情况。
     *
     * 因此**零长度区间（瞬时事件）不与任何区间重叠**，包括它落在对方内部的情况：
     * 数学上 `[T, T)` 是空集，空集与任何集合的交集都是空。
     *
     * 注意本方法回答的是「两段区间是否相交」，**不是**「某个时间点是否落在那段区间里」。
     * 后者是另一回事：查询某个逻辑日有哪些记录时，落在区间内的瞬时事件必须被算进来，
     * 那套判定写在 `TimeRecordDao` 的区间查询 SQL 里（并且专门为零长度记录补了特例）。
     * 两者不要混用。
     */
    fun overlaps(other: MillisRange): Boolean = intersect(other) != null

    /** 与另一区间的交集；无交集时返回 null（零长度交集也视为 null）。 */
    fun intersect(other: MillisRange): MillisRange? {
        val start = maxOf(startMillis, other.startMillis)
        val end = minOf(endMillisExclusive, other.endMillisExclusive)
        return if (end > start) MillisRange(start, end) else null
    }

    override fun compareTo(other: MillisRange): Int =
        compareValuesBy(this, other, { it.startMillis }, { it.endMillisExclusive })

    companion object {
        /** 空区间，用于 fold 的初始值。 */
        val EMPTY = MillisRange(0L, 0L)
    }
}
