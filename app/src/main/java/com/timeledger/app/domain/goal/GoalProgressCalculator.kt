package com.timeledger.app.domain.goal

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.core.time.TimeBoundary
import com.timeledger.app.domain.model.Goal
import com.timeledger.app.domain.model.GoalPeriod
import com.timeledger.app.domain.model.GoalType
import com.timeledger.app.domain.model.RecordSource
import com.timeledger.app.domain.model.TimeRecord
import com.timeledger.app.domain.timeline.ResolvedTimeline
import com.timeledger.app.domain.timeline.TimelineResolver

/**
 * 目标完成度计算器 —— 纯逻辑，不碰数据库。
 *
 * ## 三种目标怎么算（需求四节）
 * - [GoalType.TIME]：把消解重叠后的时间轴**裁剪到统计窗口**，累加匹配区段的时长。
 * - [GoalType.COUNT]：累加匹配记录的 `countValue`。
 * - [GoalType.FREQUENCY]：统计匹配记录的**条数**（一条记录 = 发生一次）。
 *
 * ## 三个必须写清楚的判定规则
 *
 * ### 1. 先消解重叠，再统计
 * 所有口径都建立在 [TimelineResolver] 的输出上，而不是原始记录列表上。
 * 原因见 TimeRecord 的说明：手动记录与系统记录允许重叠，
 * 直接相加会把同一段时间算两遍（手写的「14:00–15:00 Java」+ 系统测到的
 * 「14:30–15:30 Android Studio」= 1.5 小时，实际上只有 1 小时）。
 *
 * ### 2. 排除 SUGGESTED：未确认的推测不换成绩
 * SUGGESTED 是「系统猜的、还没经用户确认」的候选行为（需求三节 C）。
 * 让它计入完成度，等于系统一猜就帮用户达标 —— 那目标就失去意义了。
 * 因此三种类型统一排除 SUGGESTED，其余来源（含系统检测到的真实使用事实）都算数，
 * 这正是「系统数据能否明显减少用户手动记录」要验证的地方（需求十七节问题 2）。
 *
 * ### 3. 时长为 TIME 的计量，计数与频次按「记录归属」整体计入
 * - TIME 可以按分钟切开，所以跨 4:00 的记录按天窗口裁剪（见 TimeRecord 的说明）；
 * - COUNT / FREQUENCY 是不可分割的原子值，一次健身跨过午夜不该算成「半次」，
 *   因此按记录的 `startMillis` 归属到某一个周期里整体计入。
 *
 * ## 周期窗口与有效期
 * [GoalPeriod.DAY] / [WEEK] / [MONTH] 分别对应当天的 04:00~次日 04:00、
 * 周一 04:00~下周一 04:00、当月 1 日 04:00~次月 1 日 04:00。
 * 目标自身的生效区间（[Goal.startEpochDay] / [Goal.endEpochDay]）会**裁剪窗口**，
 * 但**不做目标值折算**：周三新建的「每周 15 小时」，那一周仍按 15 小时考核。
 * 折算成「本周只剩 5 天所以只要 10 小时」听着聪明，但产品上没有任何依据，
 * 属于「为了高级而增加复杂度」，V1 明确不做。
 */
class GoalProgressCalculator(
    private val dayBoundary: DayBoundary,
) {

    /**
     * 目标在 [epochDay] 这一天的统计窗口；返回 null 表示目标在当天**不适用**
     * （未生效、已过期，或窗口被有效期裁没了）。
     */
    fun windowFor(goal: Goal, epochDay: Long): MillisRange? {
        if (!goal.isEffectiveOn(epochDay)) return null

        val periodWindow = when (goal.period) {
            GoalPeriod.DAY -> dayBoundary.dayRange(epochDay)
            GoalPeriod.WEEK -> dayBoundary.weekRange(epochDay)
            GoalPeriod.MONTH -> dayBoundary.monthRange(epochDay)
        }

        val validityStart = goal.startEpochDay
            ?.let { dayBoundary.dayRange(it).startMillis }
            ?: periodWindow.startMillis
        val validityEnd = goal.endEpochDay
            ?.let { dayBoundary.dayRange(it).endMillisExclusive }
            ?: periodWindow.endMillisExclusive

        val start = maxOf(periodWindow.startMillis, validityStart)
        val end = minOf(periodWindow.endMillisExclusive, validityEnd)
        // 目标在当天生效（上面已判断），而当天一定落在周期窗口内，
        // 所以这里裁不出空窗口；仍保留判断以免将来改了 isEffectiveOn 的语义后静默出错。
        if (end <= start) return null
        return MillisRange(start, end)
    }

    /**
     * 一次查询就能取全所有目标所需记录的时间范围。
     *
     * 为什么需要它：周目标的窗口比「今天」宽得多，只查当天会漏掉周一的数据。
     * 取所有窗口的并集，一次查询搞定，避免每个目标各查一次数据库。
     */
    /**
     * 一段日期范围内，所有目标所需记录的时间范围（各窗口的并集）。
     *
     * 用于日历月视图与「目标趋势」：需要一次性取出「这段时间里所有目标可能用到的记录」，
     * 而不是每天各查一次（一个月就是 31 次查询）。
     *
     * 注意并集可能**超出**请求的日期范围：范围内的某一天若有周目标，
     * 它的窗口会延伸到那一周的首尾。这是正确的 —— 周目标的完成度本来就由整周决定。
     */
    fun windowUnion(
        goals: List<Goal>,
        fromEpochDay: Long,
        toEpochDay: Long,
    ): MillisRange? {
        require(toEpochDay >= fromEpochDay) { "结束日期不能早于开始日期" }
        val days = TimeBoundary.epochDaysIn(fromEpochDay, toEpochDay)
        val windows = days.flatMap { day -> goals.mapNotNull { windowFor(it, day) } }
        if (windows.isEmpty()) return null
        return MillisRange(
            startMillis = windows.minOf { it.startMillis },
            endMillisExclusive = windows.maxOf { it.endMillisExclusive },
        )
    }

    fun windowUnion(goals: List<Goal>, epochDay: Long): MillisRange? {
        val windows = goals.mapNotNull { windowFor(it, epochDay) }
        if (windows.isEmpty()) return null
        return MillisRange(
            startMillis = windows.minOf { it.startMillis },
            endMillisExclusive = windows.maxOf { it.endMillisExclusive },
        )
    }

    /** 计算单个目标。返回 null 表示该目标在这一天不适用。 */
    fun calculate(
        goal: Goal,
        records: List<TimeRecord>,
        epochDay: Long,
    ): GoalProgress? {
        val window = windowFor(goal, epochDay) ?: return null
        val timeline = TimelineResolver.resolve(records)
        return calculateWithin(goal, timeline, window)
    }

    /** 批量计算，自动跳过不适用的目标。 */
    fun calculateAll(
        goals: List<Goal>,
        records: List<TimeRecord>,
        epochDay: Long,
    ): List<GoalProgress> = calculateAll(goals, TimelineResolver.resolve(records), epochDay)

    /**
     * 批量计算，复用调用方已经消解好的时间轴。
     *
     * 「一个月里每一天算一次」这种场景必须用这个重载：时间轴只消解一次，
     * 既避免 31 天各消解一遍，也避免出现「同一天在两处算出不同结果」的可能。
     * 重叠消解只做一次、所有目标共用同一条时间轴，
     * 这样「学习类合计」与「Java 活动」两个目标的数字天然不会互相矛盾。
     */
    fun calculateAll(
        goals: List<Goal>,
        timeline: ResolvedTimeline,
        epochDay: Long,
    ): List<GoalProgress> {
        if (goals.isEmpty()) return emptyList()
        return goals.mapNotNull { goal ->
            windowFor(goal, epochDay)?.let { calculateWithin(goal, timeline, window = it) }
        }
    }

    /**
     * 计算目标进度，支持手动覆盖
     */
    fun calculateWithOverride(
        goal: Goal,
        records: List<TimeRecord>,
        epochDay: Long,
        manualOverride: Double? = null,
    ): GoalProgress? {
        val window = windowFor(goal, epochDay) ?: return null
        
        return if (manualOverride != null) {
            // 如果有手动覆盖值，则使用覆盖值
            val actualAchieved = when (goal.type) {
                GoalType.TIME -> manualOverride.coerceAtMost(goal.targetValue) // 时间类目标不超过目标值
                else -> manualOverride // 计数和频率类目标可以超过目标值
            }
            
            GoalProgress(
                goal = goal,
                window = window,
                achieved = actualAchieved,
                target = goal.targetValue,
                contributingRecordCount = 0, // 手动覆盖时没有具体的贡献记录
            )
        } else {
            // 否则使用自动计算
            val timeline = TimelineResolver.resolve(records)
            calculateWithin(goal, timeline, window)
        }
    }

    private fun calculateWithin(
        goal: Goal,
        timeline: ResolvedTimeline,
        window: MillisRange,
    ): GoalProgress {
        // 排除未确认的推测，见类注释第 2 条。
        val eligible = ResolvedTimeline(
            timeline.segments.filter { it.source != RecordSource.SUGGESTED }
        )

        return when (goal.type) {
            GoalType.TIME -> timeProgress(goal, eligible, window)
            GoalType.COUNT -> countProgress(goal, eligible, window)
            GoalType.FREQUENCY -> frequencyProgress(goal, eligible, window)
        }
    }

    private fun timeProgress(
        goal: Goal,
        timeline: ResolvedTimeline,
        window: MillisRange,
    ): GoalProgress {
        val matching = timeline.segmentsMatching(goal.categoryId, goal.activityId)
            .mapNotNull { segment -> segment.range.intersect(window)?.let { segment to it } }
            .filter { (_, clipped) -> clipped.durationMillis > 0L }

        val millis = matching.sumOf { (_, clipped) -> clipped.durationMillis }
        return GoalProgress(
            goal = goal,
            window = window,
            // 秒：与 Goal.targetValue 的单位保持一致
            achieved = millis / 1000.0,
            target = goal.targetValue,
            contributingRecordCount = matching.map { (segment, _) -> segment.recordIndex }.distinct().size,
        )
    }

    private fun countProgress(
        goal: Goal,
        timeline: ResolvedTimeline,
        window: MillisRange,
    ): GoalProgress {
        val records = timeline.survivingRecordsMatching(goal.categoryId, goal.activityId, window)
        return GoalProgress(
            goal = goal,
            window = window,
            achieved = records.sumOf { it.countValue ?: 0.0 },
            target = goal.targetValue,
            contributingRecordCount = records.size,
        )
    }

    private fun frequencyProgress(
        goal: Goal,
        timeline: ResolvedTimeline,
        window: MillisRange,
    ): GoalProgress {
        // 注意用的是「赢下的记录」而不是「段落」：
        // 一条记录如果在时间轴上被切成两段（中间被更高优先级记录穿过），
        // 它仍然只算发生了一次。
        val records = timeline.survivingRecordsMatching(goal.categoryId, goal.activityId, window)
        return GoalProgress(
            goal = goal,
            window = window,
            achieved = records.size.toDouble(),
            target = goal.targetValue,
            contributingRecordCount = records.size,
        )
    }
}
