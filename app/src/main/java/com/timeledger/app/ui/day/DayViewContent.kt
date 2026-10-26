package com.timeledger.app.ui.day

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.timeledger.app.R
import com.timeledger.app.ui.chart.DonutChart
import com.timeledger.app.ui.chart.PieLegend
import com.timeledger.app.ui.chart.PieSlice
import com.timeledger.app.ui.design.AppCard
import com.timeledger.app.ui.design.AppCardVariant
import com.timeledger.app.ui.design.EmptyState
import com.timeledger.app.ui.design.EmptySymbolDot
import com.timeledger.app.ui.design.GoalProgressLine
import com.timeledger.app.ui.design.MetricCard
import com.timeledger.app.ui.design.ProgressBar
import com.timeledger.app.ui.design.ProgressRing
import com.timeledger.app.ui.design.StatusPill
import com.timeledger.app.ui.design.TimelineBand
import com.timeledger.app.ui.design.TimelineGapBand
import com.timeledger.app.ui.theme.Spacing

/**
 * 「某一天」的完整内容排版 —— 首页（今天）与日历（选中的那天）共用。
 *
 * 共用而不是各写一份的理由：两处要展示的东西本质相同，
 * 各写一份的结果一定是慢慢跑偏（一个补了缺口排序、另一个漏了跨夜文案），
 * 而这类不一致在界面上很难被发现。
 *
 * 这里**只有绘制**：所有「显示什么」的判断都在 [DayViewStateMapper] 里。
 */
@Composable
fun DayViewContent(
    state: DayViewUiState,
    modifier: Modifier = Modifier,
    /**
     * 是否显示分类分布（需求二节的「每日时间统计」）。
     * 首页按需求一节只列目标与时间轴，日历的某天详情才展开分类分布。
     */
    showCategoryBreakdown: Boolean = false,
    /** 底部留白：首页要绕过右下角的快速添加按钮。 */
    bottomPadding: Dp = 16.dp,
    /** 目标分组的「管理」入口；为 null 时不显示（日历里不需要）。 */
    onManageGoals: (() -> Unit)? = null,
    /** 计划分组的「课表」入口；为 null 时不显示。 */
    onManagePlans: (() -> Unit)? = null,
    /** 待确认记录 / 缺失时间的「去确认」入口；为 null 时不显示。 */
    onReviewPending: (() -> Unit)? = null,
    /**
     * 点某一行记录 → 打开它的编辑框（参数是记录 id）。
     *
     * 为 null 时时间轴照样显示，只是点不动 —— 这样同一份排版既能用于「可编辑」的场景，
     * 也能用于只读场景，不需要另写一个「只读版」再慢慢跑偏。
     */
    onEditRecord: ((Long) -> Unit)? = null,
    /**
     * 点目标行 → 打开目标进度编辑（参数是目标行）
     */
    onEditGoal: ((GoalRow) -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DayViewSections(
            state = state,
            showCategoryBreakdown = showCategoryBreakdown,
            onManageGoals = onManageGoals,
            onManagePlans = onManagePlans,
            onReviewPending = onReviewPending,
            onEditRecord = onEditRecord,
            onEditGoal = onEditGoal,
        )
    }
}

/**
 * 日视图的各个分组，**不带滚动容器**。
 *
 * 拆出来的原因很实际：日历页是「月份网格 + 选中日详情」放在同一个滚动区域里，
 * 如果直接复用带 `verticalScroll` 的 [DayViewContent]，
 * 就会出现同方向嵌套滚动 —— 这种问题在真机上才看得出怪异，而本机无法目视验证，
 * 所以从结构上避免它，而不是指望它「大概能跑」。
 */
@Composable
fun DayViewSections(
    state: DayViewUiState,
    showCategoryBreakdown: Boolean = false,
    onManageGoals: (() -> Unit)? = null,
    onManagePlans: (() -> Unit)? = null,
    onReviewPending: (() -> Unit)? = null,
    onEditRecord: ((Long) -> Unit)? = null,
    onEditGoal: ((GoalRow) -> Unit)? = null,
) {
    // 顺序按 V1.1 P0-1 排定：概览 → 此刻 → **时间轴** → 目标 → 计划 → 统计 → 缺失。
    //
    // 为什么把时间轴提到目标与计划之前：它是这个 App 与普通待办/时间管理应用最大的差别，
    // 也是「3 秒知道今天怎么样」最主要的答案来源。「计划 vs 实际」偏偏要在**看过实际**
    // 之后才有意义 —— 先给实际，再给偏差。
    HeaderCard(state = state, onReviewPending = onReviewPending)
    TimelineSection(state = state, onEditRecord = onEditRecord, nowLabel = state.nowLabel)
    GoalsSection(state = state, onManageGoals = onManageGoals, onEditGoal = onEditGoal)
    PlanSection(state = state, onManagePlans = onManagePlans)
    MissingSection(state = state, onReviewPending = onReviewPending)
    // 饼图是「结论」而不是「明细」：放在最后，想深挖的人往下滚就能看到
    if (showCategoryBreakdown) CategorySection(state)
}

/**
 * 计划分组。
 *
 * 即使今天没有计划也要显示这一块（带一句提示与「课表」入口）：
 * 「计划 vs 实际」是产品核心，如果用户从没见过这一块，就永远不会去录课表。
 */
@Composable
private fun PlanSection(state: DayViewUiState, onManagePlans: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionTitle(stringResource(R.string.day_section_plan))
        Spacer(Modifier.weight(1f))
        onManagePlans?.let { manage ->
            TextButton(onClick = manage) {
                Text(stringResource(R.string.day_action_manage_plans))
            }
        }
    }

    val note = state.planNote
    if (note == null) {
        // 区分「有课表但今天没课」和「还没有课表」—— 两个情况含义完全不同（V1.1）。
        val emptyText = if (state.hasAnyPlan) {
            stringResource(R.string.day_plan_none_today)
        } else {
            stringResource(R.string.day_no_plans)
        }
        EmptyState(
            text = emptyText,
            symbol = { EmptySymbolDot() },
            actionLabel = stringResource(R.string.day_action_manage_plans),
            onAction = onManagePlans,
        )
        return
    }
    PlanNoteCard(note)

    // 每个计划时段一张卡：**计划 → 实际 → 偏在哪**（V1.1 P0-5）。
    // 完成率只说明「差了」，这里要说的是「差在哪一段」——
    // 用户看完就知道下一步改什么。
    state.planRows.forEach { row -> PlanActualCard(row) }
}

/** 一个计划时段的「计划 vs 实际」。 */
@Composable
private fun PlanActualCard(row: PlanActualRow) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.rangeLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(Spacing.s))
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 偏差用警示色点出来 —— 这是这张卡存在的理由
            row.deviationLabel?.let { deviation ->
                StatusPill(text = deviation, color = MaterialTheme.colorScheme.error)
            }
        }

        ProgressBar(
            fraction = row.matchedFraction,
            color = MaterialTheme.colorScheme.tertiary,
        )

        Text(
            text = "照计划 ${row.matchedLabel} / 计划 ${row.plannedLabel}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (row.actualLines.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                row.actualLines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 首页顶部那张卡 —— 一屏的视觉锚点（模块 27 改用 [MetricCard] + [ProgressRing]）。
 *
 * 三件事一次说清：**今天已记录多久**（大数字）、**这一天已经过去多少被说清楚了**（环）、
 * **还有多少没记录 / 多少待确认**（小字 + 入口）。
 *
 * 环的分子分母都是现成的：`dayShares` 里除了「没有记录」那一片，其余加起来
 * 就是「已经过去的时间里被记录覆盖的比例」—— 它比「今日完成度」这种自造指标诚实，
 * 因为它就是这一天的账本身。
 */
@Composable
private fun HeaderCard(state: DayViewUiState, onReviewPending: (() -> Unit)?) {
    val coveredFraction = state.dayShares
        .filterNot { it.isUnrecorded }
        .sumOf { it.fraction.toDouble() }
        .toFloat()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        MetricCard(
            value = state.recordedLabel,
            unit = stringResource(R.string.day_recorded_unit),
            caption = stringResource(R.string.day_unrecorded_hint, state.unrecordedLabel),
            trailing = {
                ProgressRing(fraction = coveredFraction, size = 64.dp, thickness = 7.dp) {
                    Text(
                        text = "${(coveredFraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
        )

        // 「此刻」——首页第一眼要知道**现在正在发生什么**（V1.1 P0）。
        // 它紧跟在概览下面，因为它回答的正是「我现在是什么状态」。
        state.currentMoment?.let { moment -> CurrentMomentCard(moment) }

        // 有未确认的内容时，在这里直接给一个入口 ——
        // 只说「1h 待确认」却不给处理办法，用户只能干看着
        state.pendingLabel?.let { pending ->
            AppCard(variant = AppCardVariant.Tinted, accent = MaterialTheme.colorScheme.primary) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pending,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    onReviewPending?.let { review ->
                        TextButton(onClick = review) {
                            Text(stringResource(R.string.day_action_review_pending))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanNoteCard(note: String) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.day_section_plan),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(note, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun GoalsSection(
    state: DayViewUiState, 
    onManageGoals: (() -> Unit)?, 
    onEditGoal: ((GoalRow) -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionTitle(stringResource(R.string.day_section_goals))
        Spacer(Modifier.weight(1f))
        onManageGoals?.let { manage ->
            TextButton(onClick = manage) {
                Text(stringResource(R.string.day_action_manage_goals))
            }
        }
    }
    if (!state.hasGoals) {
        HintText(stringResource(R.string.day_no_goals))
        return
    }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            state.goals.forEach { row -> GoalProgressRow(row, onGoalEditClick = onEditGoal) }
        }
    }
}

/**
 * 目标行（模块 27 改用统一的 [GoalProgressLine]）。
 *
 * 「今日」这个小标签留在标题前，其余交给组件：标题左、数字右、细条横贯下方 ——
 * 列表里最容易扫读的形状。达成时多一个绿色「已达成」胶囊，且条转成成功色。
 *
 * 统计页也用同一份，所以不是 private —— 两个页面展示同一件事，
 * 排版与配色必须一致，各写一遍迟早会不一致。
 */
@Composable
fun GoalProgressRow(
    row: GoalRow, 
    modifier: Modifier = Modifier,
    onGoalEditClick: ((GoalRow) -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.periodLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = Spacing.s),
        )
        GoalProgressLine(
            title = row.title,
            fraction = row.progressFraction,
            progressLabel = row.achievedLabel,
            targetLabel = row.targetLabel,
            color = if (row.isAchieved) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.primary
            },
            achieved = row.isAchieved,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 「此刻」卡（V1.1 P0）：正在发生什么 / 刚刚结束了什么 / 尚未确认。
 *
 * 三种状态用**同一张卡的三种长相**表达，不另外发明组件：
 * - 正在进行：Tinted 卡（分类色 12% 底 + 4dp 侧带）+ 「正在进行」小标 —— 最有存在感；
 * - 刚刚结束：同样的卡但用中性色，标题写「刚刚」—— 说明这一页在跟着时间走，但不抢戏；
 * - 尚未确认：最轻的一行，如实说不知道（**绝不编一个活动出来**）。
 */
@Composable
private fun CurrentMomentCard(moment: CurrentMoment) {
    val unknown = moment.state == CurrentMoment.State.UNKNOWN
    val accent = moment.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary

    AppCard(
        variant = if (unknown) AppCardVariant.Plain else AppCardVariant.Tinted,
        accent = if (unknown) null else accent,
        contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = when (moment.state) {
                        CurrentMoment.State.RUNNING -> stringResource(R.string.day_current_running)
                        CurrentMoment.State.RECENT -> stringResource(R.string.day_current_recent)
                        CurrentMoment.State.UNKNOWN -> stringResource(R.string.day_current_unknown)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = moment.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                moment.subtitle?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = moment.detailLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 一天的时间去向：环形饼图 + 图例（模块 24）。
 *
 * 用户的原话：「需要一天的时间饼图」。分母是**已经过去的时间**，
 * 所以最后总会有一片「没有记录」—— 那一片正是用户问的「有些空的没有分类」，
 * 在这里变成一个看得见的灰色扇形，而不是一个说不清的空洞。
 *
 * 模块 25 又加了一张**按应用**的：同一份时间、同一个分母，换个尺度看
 * （用户的原话：「单看分类感觉不是很直观」）。两张图并排摆在同一个界面里，
 * 是因为它们回答的是同一个问题的两个层次：娱乐占多少 / 娱乐里到底是谁。
 */
@Composable
private fun CategorySection(state: DayViewUiState) {
    SectionTitle(stringResource(R.string.day_section_categories))
    if (!state.hasShares) {
        HintText(stringResource(R.string.day_no_categories))
        return
    }

    ShareCard(
        rows = state.dayShares,
        centerTitle = state.elapsedLabel,
        centerLabel = state.elapsedNote,
        footer = stringResource(R.string.day_share_unrecorded_hint)
            .takeIf { state.hasUnrecordedShare },
    )

    if (state.hasAppShares) {
        Spacer(Modifier.height(12.dp))
        SectionTitle(stringResource(R.string.day_section_apps))
        ShareCard(
            rows = state.appShares,
            centerTitle = state.elapsedLabel,
            centerLabel = state.elapsedNote,
            footer = null,
        )
    }
}

/** 一张「时间去向」卡片：环形图 + 图例 +（可选）一句脚注。 */
@Composable
private fun ShareCard(
    rows: List<DayShareRow>,
    centerTitle: String,
    centerLabel: String,
    footer: String?,
) {
    val slices = rows.map { row ->
        PieSlice(
            label = row.name,
            fraction = row.fraction,
            colorArgb = row.colorArgb,
            durationLabel = row.durationLabel,
        )
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DonutChart(slices = slices, centerTitle = centerTitle, centerLabel = centerLabel)
            PieLegend(slices = slices)

            // 「没有记录」那一片不该被忽略：它常常是最大的一片。
            // 这里给一句直白的解释，免得用户以为数据丢了。
            footer?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 手写的条形，而不是 Material3 的 LinearProgressIndicator。
 *
 * 原因：LinearProgressIndicator 的 `progress` 参数在近几个版本里从 `Float` 改成了 `() -> Float`，
 * 用错版本会直接编译失败；而这里需要的东西非常简单，用两个 Box 画出来既无版本风险，
 * 也能自由控制圆角与颜色（分类色来自数据库，必须能任意指定）。
 *
 * 统计页的分类/活动排行条也用它，所以不是 private。
 */
@Composable
fun ThinBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
    }
}

@Composable
private fun TimelineSection(
    state: DayViewUiState,
    onEditRecord: ((Long) -> Unit)?,
    nowLabel: String? = null,
) {
    SectionTitle(stringResource(R.string.day_section_timeline))
    if (!state.hasTimeline) {
        EmptyState(
            text = stringResource(R.string.day_no_records),
            symbol = { EmptySymbolDot() },
        )
        return
    }
    AppCard(modifier = Modifier.fillMaxWidth()) {
        DayTimelineAxis(
            timeline = state.timeline,
            onEditRecord = onEditRecord,
            nowLabel = nowLabel,
        )
    }
}

/**
 * **竖轴**：从上到下排一天。
 *
 * ## 为什么去掉了那条贯穿全高的竖线与左侧两列留白
 * 模块 22 的做法是「左列时刻 52dp + 轴列 34dp + 块」，块因此从 86dp 处才开始 ——
 * 一屏只有 360dp 宽，留给内容的只剩一半，用户第一眼就说「太挤了，把内容都遮住了」。
 * 现在**时刻放进段内部**、块的左侧只留一条 4dp 的分类色带：
 * 刻度仍然在（每段自带开始时刻，见决策 103），但内容宽度多出近 80dp。
 * 那条竖线也跟着去掉了：色带已经把「这是一段」说清楚了，再压一条线只是噪音。
 *
 * ## 展开状态放在哪里
 * 门槛降到 10 秒之后一天有几百条记录，映射器把它们聚合成块（见
 * [DayViewStateMapper.chunkForDisplay]）。哪些块被展开了属于**用户临时状态**，
 * 所以存在这里（`rememberSaveable`）；高度怎么算仍然在映射器里
 * （[DayTimeline.heightDp]），界面只负责画。
 */
@Composable
private fun DayTimelineAxis(
    timeline: DayTimeline,
    onEditRecord: ((Long) -> Unit)?,
    nowLabel: String? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(emptySet<Int>()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        timeline.blocks.forEachIndexed { index, block ->
            val isOpen = index in expanded && block.isExpandable
            TimelineBlockItem(
                block = block,
                isExpanded = isOpen,
                onToggleExpand = if (block.isExpandable) {
                    { expanded = if (isOpen) expanded - index else expanded + index }
                } else {
                    null
                },
                onEditRecord = onEditRecord,
            )
            // 展开之后，块里每一条记录各自占一个自己的段 —— 这样每一条都能点到
            if (isOpen) {
                block.children.forEach { child -> TimelineBlockItem(child, false, null, onEditRecord) }
            }
        }

        // 末端那道「此刻」横线：轴裁到此刻，所以它说的就是「数据读到这里」
        nowLabel?.let { label -> TimelineNowLine(label) }
    }
}

/**
 * 时间轴末端的「此刻」横线（V1.1 P0-2）。
 *
 * 克制到只有一条细线 + 一个小标签，用主色的低透明度：它是一条**边界**，
 * 不是一条记录。用户看到它就知道「这一页跟着时间在走，读到此刻为止」。
 */
@Composable
private fun TimelineNowLine(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.s, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = Spacing.s),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        )
    }
}

/**
 * 竖轴上的一个块（模块 27 换成 [TimelineBand]）。
 *
 * 改造前这里是「一行文字 + 一个 26dp 色点」：分类只活在那个点上，
 * 于是睡眠那种 5 小时的大块**看起来就是一片空白卡片**。
 * 现在每段有 12% 分类色底 + 4dp 满色侧边带，长段自然更醒目，
 * 一屏里同时有七八段也不会打架（分类色只做点睛、不做填充）。
 *
 * ## 聚合块怎么点
 * 段数胶囊上带一个 `⌄`，点整块就能展开/收起（[TimelineBlock.isExpandable]）；
 * 展开后里面每一条各自成段，那一条点下去才是**改时间**。
 */
@Composable
private fun TimelineBlockItem(
    block: TimelineBlock,
    isExpanded: Boolean,
    onToggleExpand: (() -> Unit)?,
    onEditRecord: ((Long) -> Unit)?,
) {
    val height = block.heightDp.dp
    if (block.isGap) {
        TimelineGapBlock(block.gap!!, height)
        return
    }

    val row = block.row!!
    val accent = row.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val icon = rememberAppIcon(row.packageName)

    TimelineBand(
        startLabel = row.startLabel,
        title = row.title,
        subtitle = row.subtitle,
        durationLabel = row.durationLabel,
        trailingLabel = row.spilloverLabel,
        // 聚合块的可展开提示就长在段数胶囊上：`5 段 ⌄`
        badge = row.countLabel?.let { count ->
            if (onToggleExpand != null) "$count ${if (isExpanded) "⌃" else "⌄"}" else count
        },
        height = height,
        compact = block.compact,
        accent = accent,
        dimmed = row.isPending,
        labelColumnWidth = TIMELINE_LABEL_COLUMN,
        marker = {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    // 图标是装饰，说明文字就在右边，读屏再念一遍只是啰嗦
                    contentDescription = null,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .alpha(if (row.isPending) 0.55f else 1f),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .then(
                // 只有给了入口才可点：一个能点但没反应的区域，比不可点更让人困惑
                when {
                    onToggleExpand != null -> Modifier.clickable { onToggleExpand() }
                    onEditRecord != null -> Modifier.clickable { onEditRecord(row.recordId) }
                    else -> Modifier
                }
            ),
    )
}

/**
 * 空白块：那段时间里没有任何记录。
 *
 * 除了「空 Xh」，还要说清楚**为什么**空（模块 24）。用户的原话是
 * 「有些空的没有分类是怎么回事」—— 空白把所有情况混成了一种，
 * 而这一行把它们分开：手机真的没在用 / 你在零碎地划手机 / 系统没有这段数据。
 *
 * 视觉上与 [TimelineBand] 对齐（同样的圆角与左列），所以整条轴看起来是连续的，
 * 而不是「两套风格拼起来」。空白段刻意**不用分类色** —— 它不属于任何分类。
 */
@Composable
private fun TimelineGapBlock(gap: TimelineGap, height: Dp) {
    TimelineGapBand(
        startLabel = gap.startLabel,
        durationLabel = gap.durationLabel,
        hint = gap.hintLabel,
        height = height,
        labelColumnWidth = 48.dp,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 时间轴段的公共尺寸。
 *
 * 模块 27 把「左列 52 + 轴列 34」那两层留白去掉了：时刻移进段内部、
 * 分类只用一条 4dp 色带表达 —— 内容宽度多出近 80dp，这是「太挤」的直接修复。
 */
private val TIMELINE_LABEL_COLUMN = 48.dp

@Composable
private fun MissingSection(state: DayViewUiState, onReviewPending: (() -> Unit)?) {
    // 「待确认」已经在顶部卡片里显示，这里只列真正没有记录的时间段，避免同一信息出现两次
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionTitle(stringResource(R.string.day_section_missing))
        Spacer(Modifier.weight(1f))
        if (state.hasGaps) {
            onReviewPending?.let { review ->
                TextButton(onClick = review) {
                    Text(stringResource(R.string.day_action_fill_gaps))
                }
            }
        }
    }
    if (state.isEmptyDay) {
        HintText(stringResource(R.string.day_empty_day))
        return
    }
    if (!state.hasGaps) {
        HintText(stringResource(R.string.day_no_gaps))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.gaps.forEach { gap ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = gap.rangeLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = gap.durationLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 分组小标题。日历页也会用到，因此不是 private。 */
@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
