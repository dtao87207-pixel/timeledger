package com.timeledger.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.timeledger.app.ui.day.DayViewSections
import com.timeledger.app.ui.day.RecordEditorDialog

/**
 * 日历页（需求二节）：月视图 + 每天的目标完成情况 + 点某天看完整时间轴与统计。
 *
 * ## 一个只有真机才能发现、所以从结构上避免的问题
 * 这一页是「月份网格 + 选中日详情」共用一个滚动区域。
 * 如果下方详情直接复用带 `verticalScroll` 的 `DayViewContent`，
 * 就会出现**同方向嵌套滚动** —— 表现是滚不动或者滚起来很怪，
 * 而这种问题恰恰是本机（没有模拟器与设备）验证不了的。
 * 所以详情用的是不带滚动容器的 `DayViewSections`，整个页面只有一个滚动区域。
 *
 * 数据来源：当月逐日统计来自 `StatisticsService`（月周期），
 * 每日目标完成情况来自 `GoalProgressService.observeRangeProgress`，
 * 选中日详情来自 `DayReviewService` —— 三者都已在数据层验证过。
 */
@Composable
fun CalendarScreen(
    container: AppContainer,
    onManagePlans: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: CalendarViewModel = viewModel(factory = CalendarViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recordEditor by viewModel.recordEditor.collectAsStateWithLifecycle()
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
            .verticalScroll(rememberScrollState()),
    ) {
        MonthHeader(
            state = current,
            onPreviousMonth = viewModel::showPreviousMonth,
            onNextMonth = viewModel::showNextMonth,
            onToday = viewModel::showToday,
            // 课程表放在这里而不是首页：课表本来就是「按周 / 按月」看的东西，
            // 用户翻月的时候最可能想起「我该把课表补上」
            onManagePlans = onManagePlans,
        )
        WeekdayHeaderRow(headers = current.weekdayHeaders)
        MonthGrid(weeks = current.weeks, onSelectDay = viewModel::selectDay)
        Text(
            text = current.monthSummaryLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        // 自己画一条分隔线：Material3 的 Divider 在近几版改名为 HorizontalDivider，
        // 用错名字直接编译失败，而这里只需要一条 1dp 的线
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )

        current.selectedDetail?.let { detail ->
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DayViewSections(
                    state = detail,
                    showCategoryBreakdown = true,
                    // 点时间轴上的一行 → 改时间、改归属、加次数或备注，或者删掉它。
                    // 与首页共用同一个控制器，两处行为完全一致。
                    onEditRecord = viewModel::startEditRecord,
                )
            }
        }
    }

    recordEditor?.let { editor ->
        RecordEditorDialog(
            editor = editor,
            onChange = viewModel::updateRecordEdit,
            onSave = viewModel::saveRecordEdit,
            onDelete = viewModel::deleteRecordEdit,
            onDismiss = viewModel::dismissRecordEdit,
        )
    }
}

@Composable
private fun MonthHeader(
    state: CalendarUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToday: () -> Unit,
    onManagePlans: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 用文字箭头而不是图标：整个 App 只多两个符号，不值得为它引入图标库
        IconButton(onClick = onPreviousMonth) {
            Text("‹", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            text = state.monthLabel,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNextMonth) {
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
        TextButton(onClick = onManagePlans) {
            Text(stringResource(R.string.calendar_action_plans))
        }
        TextButton(onClick = onToday) {
            Text(stringResource(R.string.calendar_today))
        }
    }
}

@Composable
private fun WeekdayHeaderRow(headers: List<String>) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        headers.forEach { header ->
            Text(
                text = header,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MonthGrid(weeks: List<List<CalendarCell>>, onSelectDay: (Long) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        weeks.forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { cell ->
                    DayCell(
                        cell = cell,
                        onClick = { onSelectDay(cell.epochDay) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    cell: CalendarCell,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = when {
        cell.isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        cell.isToday -> MaterialTheme.colorScheme.surfaceVariant
        else -> Color.Transparent
    }
    val dayColor = if (cell.inDisplayedMonth) {
        MaterialTheme.colorScheme.onSurface
    } else {
        // 补齐网格用的相邻月日期：淡化但仍显示，让用户知道它们不属于本月
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }

    Column(
        modifier = modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(text = cell.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium, color = dayColor)

        // 强度条：没记录就不亮，整月扫一眼就能看出哪几天是空的
        Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(cell.intensity.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
            )
        }

        // 两行文字用固定高度的容器包住：内容是空的时候高度也不能变，
        // 否则同一行里各格子会因为有没有记录而高度不一，网格看起来就歪了
        Box(modifier = Modifier.height(16.dp), contentAlignment = Alignment.Center) {
            cell.recordedLabel?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Box(modifier = Modifier.height(16.dp), contentAlignment = Alignment.Center) {
            cell.goalLabel?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
    }
}
