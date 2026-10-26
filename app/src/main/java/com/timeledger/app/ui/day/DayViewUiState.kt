package com.timeledger.app.ui.day

import com.timeledger.app.domain.model.Activity
import com.timeledger.app.domain.model.Category

/**
 * 分类 / 活动的展示信息（名称与颜色）。
 *
 * 为什么在界面层做这个 join，而不是让领域模型带上名字：
 * 名称与颜色属于「分类目录」，界面本来就要订阅它来做选择器和配色；
 * 复盘与统计的只读模型只带 id，不冗余一份名称，避免改名后两处不一致。
 */
data class CatalogLookup(
    val categoryNames: Map<Long, String>,
    val categoryColors: Map<Long, Int>,
    val activityNames: Map<Long, String>,
    val activityColors: Map<Long, Int>,
) {

    fun categoryName(id: Long?): String? = id?.let { categoryNames[it] }

    fun categoryColor(id: Long?): Int? = id?.let { categoryColors[it] }

    fun activityName(id: Long?): String? = id?.let { activityNames[it] }

    fun activityColor(id: Long?): Int? = id?.let { activityColors[it] }

    companion object {
        val EMPTY = CatalogLookup(emptyMap(), emptyMap(), emptyMap(), emptyMap())

        fun from(categories: List<Category>, activities: List<Activity>): CatalogLookup = CatalogLookup(
            categoryNames = categories.associate { it.id to it.name },
            categoryColors = categories
                .mapNotNull { category -> category.colorArgb?.let { category.id to it } }
                .toMap(),
            activityNames = activities.associate { it.id to it.name },
            activityColors = activities
                .mapNotNull { activity -> activity.colorArgb?.let { activity.id to it } }
                .toMap(),
        )
    }
}

/** 目标行：`Java　2h20m / 3h`。 */
data class GoalRow(
    val goalId: Long,
    val title: String,
    val periodLabel: String,
    val achievedLabel: String,
    val targetLabel: String,
    val progressFraction: Float,
    val isAchieved: Boolean,
    // 手动调整目标进度所需
    val achievedValue: Double = 0.0,
    val goalType: com.timeledger.app.domain.model.GoalType? = null,
)

/** 时间轴的一行。 */
data class TimelineRow(
    /**
     * 这一行背后那条记录的 id —— 点进去编辑时用它取记录。
     *
     * 注意它**不唯一**：一条记录被更高优先级的记录从中间穿过时，会拆成多行显示，
     * 这几行指向同一条记录。所以编辑任一行都在编辑整条记录（编辑框里显示的是完整起止），
     * 而不是只改看起来的这一段。
     */
    val recordId: Long,
    /** 普通记录是 `09:00–10:40`；瞬时事件只有一个时刻。 */
    val rangeLabel: String,
    /**
     * 竖轴左列显示的时刻（`09:12`）。
     *
     * 由映射器按当天时区算好 —— 界面层不碰 `ZoneId`，
     * 这样「显示成几点」这件事仍然在能被测试的纯函数里决定。
     */
    val startLabel: String,
    /**
     * 最主要的一行字：**具体在做什么**。
     *
     * 系统记录里是 App 名（抖音极速版），手动记录里是活动名或分类名。
     * 用户的原话：「时间轴只有分类，没有具体某个应用，不知道我这段时间具体做了什么。」
     * 归类解决的是统计口径，时间轴要回答的是「这段时间具体在干什么」—— 两者不是一回事。
     */
    val title: String,
    /** 第二行：分类（或活动）。`娱乐`、`学习 / Java`；没有则为 null。 */
    val subtitle: String?,
    val durationLabel: String,
    /**
     * 这一块由几段合并显示而来（`3 段`）；只有一段时为 null。
     *
     * 用户的原话：「有些间隔一分钟然后是同一个应用的，可以直接并一起」——
     * 但合并只发生在**显示**上：块的高度按跨度画（轴才准），
     * [durationLabel] 写的仍然是**真实总时长**（40m，而不是跨度的 90m）。
     * 数据一行都没动，所以统计不会虚报。
     */
    val countLabel: String?,
    val colorArgb: Int?,
    /** 来自未确认的推测，界面上要弱化显示。 */
    val isPending: Boolean,
    val isInstant: Boolean,
    /** 这一段时长（毫秒），用来决定它够不够格单独占一行。 */
    val durationMillis: Long,
    /** 用户自己记的或确认过的（MANUAL / MERGED）：这种永远单独显示，不折叠。 */
    val isUserRecorded: Boolean,
    /**
     * 这条记录属于哪个 App；手动记录为 null。
     *
     * 用它去取应用图标画在时间轴上 —— 图标比一个色点好看得多，也一眼就认出是哪个 App。
     * 包名从记录的幂等键里反解（`usage|<哪天>|<开始时刻>|<包名>`），不需要给数据库加列。
     */
    val packageName: String?,
    /**
     * 这条是**熄屏**记录（模块 23/24）。
     *
     * 它没有包名，但它是「同一件事」的一部分：显示时相邻的熄屏段会并成一块，
     * 否则门槛降到 1 分钟之后，轴上一整天都会是「熄屏 · 熄屏 · 熄屏……」。
     */
    val isScreenOff: Boolean = false,
    /** 这一段的起止（绝对时间戳），用来算与相邻段落之间的空白有多长。 */
    val startMillis: Long,
    val endMillisExclusive: Long,
    /**
     * 这条记录**超出了当天的窗口**时，写出它的全天时长：`全天 10h`。
     *
     * 最典型的例子是睡眠：23:00 睡到次日 09:00，逻辑日（04:00~04:00）把它切成两半 ——
     * 4:00 之前那五个小时算前一天，之后那五个小时算今天。记录本身没有切开
     * （数据库里就一行），切开的是**显示**，而任何日界线都会切开一段跨界的睡眠。
     * 所以能做的不是"不切"，而是让用户看得到全貌：这一行明确写出全天有多长。
     */
    val spilloverLabel: String?,
)

/**
 * 时间轴上的一段空白 —— 那段时间里没有任何 App 在前台。
 *
 * 为什么要有它（用户的原话）：「时间线轴可以留出没有记录的时间，直接留白，
 * 因为肯定会有没使用手机的时间」。之前的做法是每行等距罗列，
 * 于是「上午 3 小时没碰手机」和「两个 5 分钟的记录」占一样的高度，
 * 时间轴看起来是满的 —— 而它其实一点也不满。
 */
data class TimelineGap(
    val rangeLabel: String,
    val durationLabel: String,
    /** 竖轴左列显示的时刻（这段空白从几点开始）。 */
    val startLabel: String,
    /** 时间范围（左边那列显示的时刻）。 */
    val startMillis: Long,
    val endMillisExclusive: Long,
    /** 视觉高度（dp）：按时长比例。 */
    val heightDp: Int,
    /**
     * 这段空白**为什么**是空白（模块 24）。
     *
     * 用户的原话：「有些空的没有分类是怎么回事」。空白把所有情况混成了一种 ——
     * 手机真的没在用、用户在零碎地划手机、系统压根没有这段数据，
     * 三者在界面上长得一模一样。这一行把它们区分开，比如
     * `零碎操作 46 次 · 每次不到 1 分钟` 或 `系统没有这段的原始数据`。
     *
     * 算不出来时为 null（界面就只显示「空 Xh」，与本模块之前的行为一致）。
     */
    val hintLabel: String? = null,
)

/**
 * 竖轴上的一个块：要么是一段记录（可能由多条合并显示），要么是一段空白。
 *
 * ## 为什么要「竖轴 + 按比例的高度」
 * 用户的原话：「我设想的时间轴是一条竖立的轴，从上到下……用户想看的不会是单纯的
 * 罗列使用时间段，而是将应用与时间轴一同打包，能够直观呈现」。
 * 于是整条轴从上（04:00）到下（次日 04:00）铺开，**块的高度就是那段时间的长度**：
 * 一眼就能看出「这一大块是娱乐」「这里空了三个小时」——这是等高的列表做不到的。
 *
 * 高度由 [DayTimeline] 的映射器算好（纯函数、可测），这里只装结果。
 */
data class TimelineBlock(
    /** 有记录的一块；空白块时为 null。 */
    val row: TimelineRow? = null,
    /** 空白块；有记录时为 null。 */
    val gap: TimelineGap? = null,
    /** 视觉高度（dp）。 */
    val heightDp: Int,
    /**
     * 高度太小，只放得下一行字。
     *
     * 由映射器决定而不是界面决定：本机看不到界面，这种「放不放得下」的判断
     * 必须放在能被测试覆盖的地方。
     */
    val compact: Boolean,
    /**
     * 这一块是**聚合块**（模块 24）：它代表一段连续在动的时间，
     * [children] 是它里面的每一条记录。
     *
     * 为什么要聚合：门槛降到 10 秒之后（见 `ForegroundSessionBuilder`），
     * 09-12 那一天有 369 条记录 —— 一条一块地画，轴会长到十几屏，
     * 而每一块都矮到点不准。聚合之后一天大约七八十块，仍然可读。
     */
    val children: List<TimelineBlock> = emptyList(),
) {
    val isGap: Boolean get() = gap != null

    /** 能不能展开：只有真正聚合了多条记录的块才需要展开。 */
    val isExpandable: Boolean get() = children.size > 1

    /** 展开时的高度 = 里面每一条自己的高度之和。 */
    val expandedHeightDp: Int get() = children.sumOf { it.heightDp }

    companion object {
        fun of(row: TimelineRow, heightDp: Int, compact: Boolean, children: List<TimelineBlock> = emptyList()) =
            TimelineBlock(row = row, heightDp = heightDp, compact = compact, children = children)

        fun gap(gap: TimelineGap, heightDp: Int) = TimelineBlock(gap = gap, heightDp = heightDp, compact = false)
    }
}

/**
 * 一整天的竖轴：块、刻度和总高度。
 *
 * [totalHeightDp] 是**未展开**时的高度。展开状态属于界面（用户点开的那些块），
 * 所以「展开之后多高」由 [heightDp] 算 —— 仍然是纯函数，能被测试钉住。
 */
data class DayTimeline(
    val blocks: List<TimelineBlock>,
    /** 整点刻度：`04:00`、`06:00`……放在左侧，告诉用户看到的是几点。 */
    val ticks: List<TimelineTick>,
    val totalHeightDp: Int,
) {
    val isEmpty: Boolean get() = blocks.isEmpty()

    val hasContent: Boolean get() = blocks.any { !it.isGap }

    /**
     * 展开状态下真实要画的高度。
     *
     * 界面不能自己加起来：那是「显示逻辑」，本机看不到界面，
     * 放在这里才能被测试覆盖（与高度、下限、刻度同样的理由）。
     */
    fun heightDp(expanded: Set<Int>): Int = blocks.mapIndexed { index, block ->
        if (index in expanded && block.isExpandable) block.expandedHeightDp else block.heightDp
    }.sum()

    companion object {
        val EMPTY = DayTimeline(emptyList(), emptyList(), 0)
    }
}

/** 竖轴左侧的一个整点刻度。 */
data class TimelineTick(
    val label: String,
    /** 距轴顶端的距离（dp）。 */
    val offsetDp: Int,
)

/** 一段没有记录的时间。 */
data class GapRow(
    val rangeLabel: String,
    val durationLabel: String,
)

/**
 * 一天里每一份时间的去向（饼图的一片，模块 24）。
 *
 * 与「分类分布」的区别在**分母**：分类分布算的是「占已确认时长的比例」，
 * 而这里是「占**今天已经过去的时间**的比例」—— 所以除了各分类，
 * 还会有「待确认」和「没有记录」两片。加起来正好是 100%，
 * 也就是「一天的账是平的」。
 *
 * 用户要的正是这个：「需要一天的时间饼图」。它顺带回答了另一个问题 ——
 * 「有些空的没有分类是怎么回事」在这里变成一个看得见的灰色扇形。
 */
data class DayShareRow(
    val name: String,
    val durationLabel: String,
    /** 占「已经过去的时间」的比例；所有片加起来是 1。 */
    val fraction: Float,
    val colorArgb: Int?,
    /** 这一片不是分类，而是「没有记录」的那部分：界面画得更淡。 */
    val isUnrecorded: Boolean = false,
)

/**
 * 一个计划时段的「计划 vs 实际」（V1.1 P0-5）。
 *
 * ## 为什么不是一张「完成率 66%」的卡
 * 完成率只告诉用户「差了」，不告诉他**差在哪一段**。用户真正要的是
 * 「计划 09:00–10:40 Java，实际 09:00–10:25 Java、10:25–10:40 什么都没记」——
 * 看完就知道下一步改什么。所以这里给的是**时段 + 明细 + 一句偏差点睛**。
 *
 * [actualLines] 里每一条是时段内的真实记录（时间 + 在干什么 + 来源）；
 * 计划与实际**都来自同一份消解后的时间轴**，所以这两栏永远对得上。
 */
data class PlanActualRow(
    val planBlockId: Long,
    /** `09:00–10:40`。 */
    val rangeLabel: String,
    /** 计划标题 + 绑定（`Java · 学习` / 未绑定时就是标题）。 */
    val title: String,
    val plannedLabel: String,
    val matchedLabel: String,
    /** 一句偏差点睛；完全照做时为 null（**没偏差就不啰嗦**）。 */
    val deviationLabel: String?,
    /** 照计划做的比例，画进度条用。 */
    val matchedFraction: Float,
    /** 时段内的实际明细（最多几条 + 一句「另有 N 段」）。 */
    val actualLines: List<String>,
    /** 计划没绑定分类/活动时无法判断偏离，界面要说清这一点。 */
    val hasBinding: Boolean,
)

/**
 * 「此刻」的状态（V1.1 P0）：用户在首页第一眼要知道**现在正在发生什么**。
 *
 * 三种情况分开说，因为它们的含义完全不同：
 * - [State.RUNNING]：那一段记录**还开着** —— 「正在进行」；
 * - [State.RECENT]：现在落在两段之间的空档里，但今天刚结束的那段值得说一句「刚刚」；
 * - [State.UNKNOWN]：什么都没有，这时必须如实说「尚未确认」，而不是编一个活动出来。
 *
 * [detailLabel] 里的时长说的是**这一段已经持续了多久**（现在 − 段开始），
 * 不是记录里那个时长 —— 那一段还没结束，时长还没定下来。
 */
data class CurrentMoment(
    val state: State,
    /** 主标题：具体在用什么（与时间轴同一口径：App 名优先）。 */
    val title: String,
    val subtitle: String?,
    val detailLabel: String,
    val colorArgb: Int?,
) {
    enum class State { RUNNING, RECENT, UNKNOWN }
}

/** 快速添加时可选的一项（活动 + 展示名）。 */
data class QuickAddOption(
    val activityId: Long,
    val label: String,
)

/**
 * **某一天**的界面状态 —— 首页显示「今天」，日历里显示「选中的那天」。
 *
 * 它被两个界面共用，所以不叫 HomeXxx：两处要展示的东西本质相同
 * （那一天的时长、目标、计划对比、时间轴、缺口、分类分布），
 * 各写一份必然会慢慢跑偏（需求十六节第 7 条：不要重复创建相似的数据类）。
 *
 * 它是一个**纯数据**结构，由 [DayViewStateMapper] 算出来 ——
 * 因此「到底显示成什么样」这件事可以在 JVM 上测，
 * 而本项目没有能看界面的环境，这一点尤其重要。
 */
data class DayViewUiState(
    val dateLabel: String,
    /** 已确认的有记录时长。 */
    val recordedLabel: String,
    /** 待确认时长文案；没有待确认时为 null（界面据此不显示这一行）。 */
    val pendingLabel: String?,
    /** 完全没有记录的时间。 */
    val unrecordedLabel: String,
    val goals: List<GoalRow>,
    /**
     * 「此刻」状态（V1.1 P0）。
     *
     * 今天才有意义（过去的日子「现在」落在窗口之外）；为空表示这一天不需要显示。
     */
    val currentMoment: CurrentMoment?,
    /** 每个计划时段的「计划 vs 实际」明细（V1.1 P0-5）。 */
    val planRows: List<PlanActualRow> = emptyList(),
    /**
     * 一天的时间去向（饼图）。
     *
     * 分母是**今天已经过去的时间**（过去的日子就是完整的一天），
     * 所以最后一片总是「没有记录」，加起来正好是全部时间。
     */
    val dayShares: List<DayShareRow>,
    /**
     * 同一天的**另一个尺度**：按具体应用拆（模块 25）。
     *
     * 用户的原话：「我觉得可以再做一个具体应用的饼图，单看分类感觉不是很直观」——
     * 分类回答的是「娱乐两小时」，而他要的是「这两小时到底是抖音还是王者」。
     * 两张饼图的分母完全相同（今天已经过去的时间），所以可以直接对着看。
     */
    val appShares: List<DayShareRow>,
    /** 饼图中心：已经过去多久。 */
    val elapsedLabel: String,
    /** 饼图中心的第二行：`今天已过去` / `一整天`。 */
    val elapsedNote: String,
    /**
     * 时间轴末端那道「此刻 HH:mm」横线的文案（V1.1 P0-2）。
     *
     * 轴是裁到此刻的（模块 24），所以这条线永远画在**末端** —— 它要说的正是
     * 「数据读到这里为止」。过去的日子没有这条线（`null`）。
     */
    val nowLabel: String? = null,
    /**
     * **竖轴**：从上（04:00）到下（次日 04:00），块高即时间长度。
     *
     * 见 [DayTimeline]。原来的做法是「等高罗列 + 折叠零碎片段」——
     * 用户看到之后明确否掉了：「我设想的时间轴是一条竖立的轴，从上到下，
     * 明细在用什么，哪些是空白……不会是单纯的罗列使用时间段」。
     * 于是零碎片段改由[显示分组][TimelineRow.countLabel]解决，不再折叠隐藏。
     */
    val timeline: DayTimeline,
    val gaps: List<GapRow>,
    /** 计划 vs 实际的一句话汇总；这一天没有计划时为 null。 */
    val planNote: String?,
    /** 这一天完全没有任何记录。 */
    val isEmptyDay: Boolean,
    /**
     * 快速添加的可选项。
     *
     * 只有「今天」才给值：快速添加是按「从此刻往前推 N 分钟」记录的，
     * 用在历史某一天上会把记录写到错误的时间，所以日历的历史日期会传空列表，
     * 界面据此不显示添加按钮。
     */
    val quickAddOptions: List<QuickAddOption>,
    /** 是否有任何生效中的计划块（不管今天适不适用）。 */
    val hasAnyPlan: Boolean = false,
    /** 今日洞察列表，用于快速了解今天的关键信息。 */
    val todayInsights: List<String> = emptyList(),
) {

    val hasGoals: Boolean get() = goals.isNotEmpty()

    val hasShares: Boolean get() = dayShares.isNotEmpty()

    val hasAppShares: Boolean get() = appShares.isNotEmpty()

    /** 这一天有「没有记录」的时间：饼图上的灰片，界面据此补一句解释。 */
    val hasUnrecordedShare: Boolean get() = dayShares.any { it.isUnrecorded }

    val hasTimeline: Boolean get() = timeline.hasContent

    /** 竖轴上真正的记录块（不含空白块）。 */
    val timelineRows: List<TimelineRow> get() = timeline.blocks.mapNotNull { it.row }

    /** 竖轴上的空白块。 */
    val timelineGaps: List<TimelineGap> get() = timeline.blocks.mapNotNull { it.gap }

    val hasGaps: Boolean get() = gaps.isNotEmpty()

    val canQuickAdd: Boolean get() = quickAddOptions.isNotEmpty()
}
