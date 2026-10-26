package com.timeledger.app.domain.review

import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.goal.GoalProgress
import com.timeledger.app.domain.timeline.ResolvedTimeline
import com.timeledger.app.domain.usage.GapActivity

/**
 * 每日复盘（需求十一节）—— 某一天的全部复盘数据，一次性现算出来。
 *
 * 它是**只读模型**：不落库、不缓存，每次上游数据变化都重新算。
 * 用户改掉一条上午的记录，复盘里的总时长、分类分布、计划达成立刻跟着变，
 * 不需要任何失效逻辑（需求十三节：避免修改记录后统计数据过期）。
 *
 * 对应需求十一节的展示清单：
 * | 需求条目 | 本模型中的字段 |
 * |---|---|
 * | 今日总时间 | [durations]`.coveredMillis` / `.confirmedMillis` |
 * | 各分类时间 | [durations]`.byCategory` |
 * | 具体 Activity 时间 | [durations]`.byActivity` |
 * | 今日目标完成率 | [goalProgress] |
 * | 计划 vs 实际 | [planComparisons] |
 * | 未确认时间 | [durations]`.pendingMillis`、[pendingRecordCount] |
 * | 未记录时间 | [durations]`.unrecordedSegments` |
 *
 * 「手机 App 使用情况」一节暂缺：那需要系统感知模块先跑起来。
 * 这里刻意**不放占位字段** —— 空字段会让调用方以为它只是暂时没数据，
 * 而实际上功能还没实现；等到系统感知模块落地时再加字段，语义更诚实。
 */
data class DayReview(
    val epochDay: Long,
    /** 这一天的逻辑日窗口（04:00 ~ 次日 04:00）。 */
    val window: MillisRange,
    /**
     * 当天消解重叠之后的**完整时间轴**。
     *
     * 复盘里的时长分布与计划比对都是由它派生的；这里一并暴露出来的理由：
     * 首页要按时间顺序列出「今天都干了什么」，那需要**逐段**的时间轴，
     * 而不是聚合后的数字。同时这也避免界面层自己再消解一次重叠
     * —— 那会产生第二套口径，还可能与复盘里的数字对不上。
     */
    val timeline: ResolvedTimeline,
    val durations: DurationBreakdown,
    /** 每个计划时段与实际的对比，按计划开始时间升序。 */
    val planComparisons: List<PlanActualComparison>,
    /** 当天所有生效目标的完成情况（含日目标与周/月目标）。 */
    val goalProgress: List<GoalProgress>,
    /** 当天处于「待用户确认」状态的记录条数。 */
    val pendingRecordCount: Int,
    /**
     * 每一段「没有记录的时间」里，原始事件还能说明什么（模块 24）。
     *
     * 默认空列表：不是所有调用方都需要这份解释（统计、目标都不需要），
     * 而它的计算要读原始事件、还要问平台层哪些包名不算「用户打开的应用」——
     * 让不需要的调用方白付这份成本没有道理。
     */
    val gapActivities: List<GapActivity> = emptyList(),
    /** 是否有任何生效中的计划块（与今天是否适用无关）。用于区分「没有课表」和「课表今天没课」。 */
    val hasAnyActivePlan: Boolean = false,
) {

    /**
     * 计划总时长。
     *
     * 注意：排课冲突时（两门课同一时段）会各算各的，因此这个值可能超过当天窗口。
     * 这是如实反映课表，而不是把冲突偷偷抹平。
     */
    val plannedMillis: Long get() = planComparisons.sumOf { it.plannedMillis }

    /** 计划时段内实际有记录的时长。 */
    val plannedRecordedMillis: Long get() = planComparisons.sumOf { it.recordedMillis }

    /** 计划时段内「照计划做了」的时长。 */
    val plannedMatchedMillis: Long get() = planComparisons.sumOf { it.matchedMillis }

    /** 整体计划达成比例（照计划做的时长 / 计划总时长）。 */
    val planAdherenceRatio: Double
        get() = if (plannedMillis <= 0L) 0.0 else plannedMatchedMillis.toDouble() / plannedMillis

    /** 当天是否有任何可复盘的数据（记录或计划）。 */
    val hasAnyData: Boolean
        get() = durations.coveredMillis > 0L || planComparisons.isNotEmpty()

    /** 有效的（在当天适用的）目标数量。 */
    val activeGoalCount: Int get() = goalProgress.size

    /** 已达标的目标数量。 */
    val achievedGoalCount: Int get() = goalProgress.count { it.isAchieved }

    /**
     * **已经过去**的那部分窗口 —— 界面画的轴、缺口、饼图分母都用它。
     *
     * 逻辑日是 04:00 ~ 次日 04:00，但今天的后半天**还没发生**。
     * 不裁剪的话，下午三点打开 App 会看到「今天未记录 12h53m」，
     * 而其实只是还没到晚上；饼图的分母（一天的账）也会跟着一起错。
     *
     * 它是 [durations] 的窗口，所以「有记录 + 没记录 = 已过去」在这一个地方成立，
     * 不会出现两处各算一遍、数字对不上的情况。
     */
    val observedWindow: MillisRange get() = durations.window
}
