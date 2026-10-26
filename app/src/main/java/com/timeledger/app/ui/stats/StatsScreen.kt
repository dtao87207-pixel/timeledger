package com.timeledger.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.timeledger.app.R
import com.timeledger.app.di.AppContainer
import com.timeledger.app.domain.statistics.StatisticsPeriod
import com.timeledger.app.ui.chart.DonutChart
import com.timeledger.app.ui.chart.PieLegend
import com.timeledger.app.ui.chart.PieSlice
import com.timeledger.app.ui.day.GoalProgressRow
import com.timeledger.app.ui.day.HintText
import com.timeledger.app.ui.day.SectionTitle
import com.timeledger.app.ui.day.ThinBar

/**
 * 统计页（需求十三节）。
 *
 * ## 为什么没有图表库，也没有饼图
 * 需求里写着「不要为了图表而图表」。这里的两张图都是手绘的：
 * - **排行**用横向条形列表：按时长排序的条形比饼图更易读（能直接比较长短、能放下文字），
 *   也不需要为「8 个分类的颜色怎么区分」额外设计一套色板 —— 颜色直接用分类自己的颜色。
 * - **趋势**用逐日/逐月柱状：柱子的高低就是投入的多少，一眼能看出「哪天断了」。
 *
 * 为此没有引入任何图表库：画出来就是两类 Box，既没有额外依赖，也不会有版本兼容问题。
 *
 * ## 与日历页同样的结构约束
 * 整页只有一个滚动区域，各分组都是不带滚动容器的普通 Column，
 * 避免同方向嵌套滚动 —— 那种问题本机验证不了，所以从结构上不让它出现。
 */
@Composable
fun StatsScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val viewModel: StatsViewModel = viewModel(factory = StatsViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val current = state

    if (current == null) {
        Box(modifier = modifier.fillMaxSize()) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PeriodSelector(selected = current.period, onSelect = viewModel::selectPeriod)
        PeriodNavigator(
            rangeLabel = current.rangeLabel,
            onPrevious = viewModel::showPreviousPeriod,
            onNext = viewModel::showNextPeriod,
            onCurrent = viewModel::showCurrentPeriod,
        )

        if (current.isEmpty) {
            HintText(stringResource(R.string.stats_no_data))
        }

        SummaryCard(current)
        InsightsSection(current)
        TrendSection(current)
        CategoryPieSection(current)
        RankSection(
            title = stringResource(R.string.stats_section_categories),
            rows = current.categoryRows,
            emptyHint = stringResource(R.string.stats_no_categories),
        )
        RankSection(
            title = stringResource(R.string.stats_section_activities),
            rows = current.activityRows,
            emptyHint = stringResource(R.string.stats_no_activities),
            footnote = if (current.hiddenActivityCount > 0) {
                stringResource(R.string.stats_hidden_activities, current.hiddenActivityCount)
            } else {
                null
            },
        )
        RankSection(
            title = stringResource(R.string.stats_section_apps),
            rows = current.appRows,
            emptyHint = stringResource(R.string.stats_no_apps),
            footnote = if (current.hiddenAppCount > 0) {
                stringResource(R.string.stats_hidden_apps, current.hiddenAppCount)
            } else {
                null
            },
        )

        SectionTitle(stringResource(R.string.stats_section_goals))
        if (!current.hasGoals) {
            HintText(stringResource(R.string.stats_no_goals))
        } else {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    current.goalRows.forEach { row -> GoalProgressRow(row) }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 分类占比饼图（环形）。
 *
 * 画法在 `ui/chart/PieChart.kt` 里，与「某一天」的饼图**共用同一份实现** ——
 * 同一个东西的两个尺度，各写一份迟早会跑偏（而本机看不到界面，跑偏了也发现不了）。
 *
 * 数据直接复用分类排行的 [RankRow.fraction]（已经是占已确认总时长的比例），
 * 所以图和下面的条形排行榜**保证是同一份数字**，不会出现「图里 30%、列表里 25%」。
 */
@Composable
private fun CategoryPieSection(state: StatsUiState) {
    SectionTitle(stringResource(R.string.stats_section_pie))
    if (!state.hasCategories) {
        HintText(stringResource(R.string.stats_no_categories))
        return
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DonutChart(
                slices = state.categoryRows.toPieSlices(),
                centerTitle = state.totalLabel,
                centerLabel = stringResource(R.string.stats_pie_total),
            )
            PieLegend(slices = state.categoryRows.toPieSlices())
        }
    }
}

/** 排行行 → 饼图的一片。两者本来就是同一份数字，转换只做一次。 */
private fun List<RankRow>.toPieSlices(): List<PieSlice> = map { row ->
    PieSlice(
        label = row.name,
        fraction = row.fraction,
        colorArgb = row.colorArgb,
        durationLabel = row.durationLabel,
    )
}

@Composable
private fun PeriodSelector(
    selected: StatisticsPeriod,
    onSelect: (StatisticsPeriod) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PERIOD_CHOICES.forEach { (period, labelRes) ->
            val label = stringResource(labelRes)
            if (period == selected) {
                Button(onClick = { onSelect(period) }) { Text(label) }
            } else {
                OutlinedButton(onClick = { onSelect(period) }) { Text(label) }
            }
        }
    }
}

@Composable
private fun PeriodNavigator(
    rangeLabel: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCurrent: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 文字箭头而不是图标：整个 App 只多两个符号，不值得引入图标库
        IconButton(onClick = onPrevious) { Text("‹", style = MaterialTheme.typography.titleLarge) }
        Text(
            text = rangeLabel,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNext) { Text("›", style = MaterialTheme.typography.titleLarge) }
        TextButton(onClick = onCurrent) { Text(stringResource(R.string.stats_current_period)) }
    }
}

@Composable
private fun SummaryCard(state: StatsUiState) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(state.periodLabel, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(state.totalLabel, style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.stats_total_unit),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Text(
                text = state.averageLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = state.recordedDaysLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.pendingLabel?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun InsightsSection(state: StatsUiState) {
    if (state.insights.isEmpty()) return
    
    SectionTitle("关键信息")
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.insights.forEach { insight ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "• $insight",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun TrendSection(state: StatsUiState) {
    SectionTitle(stringResource(R.string.stats_section_trend))
    state.trendPeakLabel?.let {
        HintText(stringResource(R.string.stats_trend_peak, it))
    }
    if (!state.hasTrend) return

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            state.trendBars.forEach { bar ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // 固定高度的容器 + 按比例填充：没有记录时高度为 0，
                    // 于是「这天是空的」在图上就是一片空白，不需要额外标注
                    Box(
                        modifier = Modifier.height(BAR_AREA_HEIGHT).fillMaxWidth(0.6f),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(bar.fraction.coerceIn(0f, 1f))
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    if (bar.showLabel) {
                        Text(
                            text = bar.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    } else {
                        // 不显示标签时也要占位，否则柱子的基线会参差不齐
                        Spacer(Modifier.height(LABEL_HEIGHT))
                    }
                }
            }
        }
    }
}

@Composable
private fun RankSection(
    title: String,
    rows: List<RankRow>,
    emptyHint: String,
    footnote: String? = null,
) {
    SectionTitle(title)
    if (rows.isEmpty()) {
        HintText(emptyHint)
        return
    }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            rows.forEach { row ->
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = row.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = row.durationLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ThinBar(
                        fraction = row.fraction,
                        color = row.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.outline,
                    )
                }
            }
            footnote?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** 趋势柱的高度上限。 */
private val BAR_AREA_HEIGHT = 96.dp

/** 标签行的固定高度：不显示标签的柱子也要占这个高度，基线才不会参差。 */
private val LABEL_HEIGHT = 16.dp

/** 周期选择器的三个选项。 */
private val PERIOD_CHOICES = listOf(
    StatisticsPeriod.WEEK to R.string.stats_period_week,
    StatisticsPeriod.MONTH to R.string.stats_period_month,
    StatisticsPeriod.YEAR to R.string.stats_period_year,
)
