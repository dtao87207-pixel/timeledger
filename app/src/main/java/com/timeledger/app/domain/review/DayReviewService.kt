package com.timeledger.app.domain.review

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.goal.GoalProgress
import com.timeledger.app.domain.goal.GoalProgressService
import com.timeledger.app.domain.model.PlanBlock
import com.timeledger.app.domain.model.TimeRecord
import com.timeledger.app.domain.model.UsageEvent
import com.timeledger.app.domain.plan.PlanBlockExpander
import com.timeledger.app.domain.repository.PlanBlockRepository
import com.timeledger.app.domain.repository.TimeRecordRepository
import com.timeledger.app.domain.repository.UsageEventRepository
import com.timeledger.app.domain.timeline.TimelineResolver
import com.timeledger.app.domain.usage.GapActivity
import com.timeledger.app.domain.usage.NonUsageAppResolver
import com.timeledger.app.domain.usage.UnrecordedGapClassifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * 每日复盘的读取入口（用例层）。
 *
 * 把三路数据合成一份 [DayReview]：
 * - 当天的**时间记录**（时间轴 → 时长分布、未记录缺口、未确认条数）；
 * - 当天的**计划时段**（展开后与时间轴比对）；
 * - 当天的**目标进度**（复用 [GoalProgressService]，不在这里重算一遍口径）。
 *
 * ## 为什么三路都监听、而不是拉一次快照
 * 记录可能被用户修改、系统可能补采、计划可能被编辑、目标可能被调整。
 * 任何一路变化，复盘都应该立刻反映出来。用 Flow 组合天然做到这件事，
 * 代价是每次变化都重新计算 —— 而一整天的数据量（几十条记录）重算成本可以忽略，
 * 换来的是「永远不会显示过期数字」。
 *
 * ## 重叠消解只做一次
 * 三路数据最终都建立在**同一条** [TimelineResolver] 输出上：
 * 时长分布与计划比对用的是同一份时间轴，因此
 * 「学习类合计」与「计划内学习时长」不会互相矛盾。
 *
 * ## 名字在哪里补
 * 本模型只带 id（分类、活动），不带名称 —— 名称与颜色属于「分类目录」，
 * 界面本来就要订阅它来做选择器和配色，顺手 join 即可，不需要在复盘里冗余一份。
 */
class DayReviewService(
    private val timeRecordRepository: TimeRecordRepository,
    private val planBlockRepository: PlanBlockRepository,
    private val goalProgressService: GoalProgressService,
    private val planBlockExpander: PlanBlockExpander,
    private val dayBoundary: DayBoundary,
    /**
     * 原始事件（模块 24）：只用来给「没有记录的时间」写一句解释。
     *
     * 用的是**同一个**事件源，而不是另建一套：空白的解释必须和会话/熄屏
     * 看到的是同一批事实，否则会出现「记录说没在用、解释说有 12 次打开」这种自相矛盾。
     */
    private val usageEventRepository: UsageEventRepository,
    /**
     * 哪些包名不算「用户打开的应用」（桌面、系统界面、输入法）。
     *
     * 由平台层判定后传进来 —— 不同 ROM 的桌面包名不一样，domain 层不该知道这些。
     */
    private val nonUsageAppResolver: NonUsageAppResolver = NonUsageAppResolver { emptySet() },
    private val gapClassifier: UnrecordedGapClassifier = UnrecordedGapClassifier(),
) {

    private val aggregator = TimelineAggregator()
    private val comparator = PlanActualComparator()

    /** 观察 [epochDay] 这一天的复盘数据。 */
    fun observeDayReview(epochDay: Long): Flow<DayReview> = combine(
        timeRecordRepository.observeRecordsOfDay(epochDay),
        planBlockRepository.observePlans(activeOnly = true),
        goalProgressService.observeDayProgress(epochDay),
    ) { records, plans, goals ->
        // 读原始事件是挂起调用，放在 combine 的变换里：
        // 记录一变（系统补采、用户编辑）就重新读一次，解释永远跟着最新的记录走。
        val events = readEvents(epochDay)
        build(epochDay, records, plans, goals, events)
    }

    private suspend fun readEvents(epochDay: Long): List<UsageEvent> {
        val window = dayBoundary.dayRange(epochDay)
        return usageEventRepository.getEventsIn(
            MillisRange(window.startMillis - EVENT_LOOKBACK_MILLIS, window.endMillisExclusive)
        )
    }

    private suspend fun build(
        epochDay: Long,
        records: List<TimeRecord>,
        plans: List<PlanBlock>,
        goals: List<GoalProgress>,
        events: List<UsageEvent>,
    ): DayReview {
        val window = dayBoundary.dayRange(epochDay)
        val timeline = TimelineResolver.resolve(records)
        val slots = planBlockExpander.expandForDay(plans, epochDay)

        // 只复盘**已经过去**的那一段：今天到此刻为止，过去的日子是完整的一天。
        // 不裁剪的话，下午三点会把「今天还没到晚上」算成「12 小时没记录」。
        //
        // 唯一要往前放一放的例外：用户自己写了一条**延伸到此刻之后**的记录
        // （比如下午就先把「今晚 20:00–22:00 自习」记上）。那种情况下窗口要跟到它的结尾，
        // 否则用户写的记录会出现在时间轴上、却不进任何统计 —— 这正是最难解释的一类不一致。
        val latestRecordEnd = timeline.segments
            .filterNot { it.isInstant }
            .maxOfOrNull { it.range.endMillisExclusive }
            ?: window.startMillis
        val observedUntil = maxOf(dayBoundary.nowMillis(), latestRecordEnd)
            .coerceIn(window.startMillis, window.endMillisExclusive)
        val observed = MillisRange(window.startMillis, observedUntil)
        val durations = aggregator.aggregate(timeline, observed)

        return DayReview(
            epochDay = epochDay,
            window = window,
            timeline = timeline,
            durations = durations,
            planComparisons = comparator.compareAll(slots, timeline),
            goalProgress = goals,
            // 待确认条数按**记录**数，而不是按推测时段数：
            // 用户要处理的是一张张记录，与界面上列出的条数保持一致。
            pendingRecordCount = records.count { it.needsConfirmation },
            gapActivities = gapActivities(events, durations.unrecordedSegments),
            hasAnyActivePlan = plans.isNotEmpty(),
        )
    }

    /**
     * 给每一段空白算一句解释。
     *
     * 「哪些包名不算用户打开的应用」要先问平台层：桌面、系统界面、输入法的包名
     * 各家 ROM 都不一样，写死在这里迟早会错（模块 20 的教训）。
     */
    private suspend fun gapActivities(
        events: List<UsageEvent>,
        gaps: List<MillisRange>,
    ): List<GapActivity> {
        if (gaps.isEmpty()) return emptyList()
        val ignored = nonUsageAppResolver.resolve(
            events.mapNotNullTo(mutableSetOf()) { it.packageName }
        )
        return gapClassifier.classify(events, gaps, ignored)
    }

    private companion object {
        /**
         * 读事件时往前多读多久。
         *
         * 和采集层用同一个理由、同一个量级：够覆盖一次跨 4:00 的连续使用，
         * 这样「昨天 23:00 开始的那段黑屏」在今天的空白里也能被正确解释。
         */
        const val EVENT_LOOKBACK_MILLIS = 6 * 60 * 60 * 1000L
    }
}
