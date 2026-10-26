package com.timeledger.app.ui.home

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.timeledger.app.R
import com.timeledger.app.di.AppContainer
import com.timeledger.app.ui.OnResumeEffect
import com.timeledger.app.ui.day.DayViewContent
import com.timeledger.app.ui.day.DayViewUiState
import com.timeledger.app.ui.day.QuickAddOption
import com.timeledger.app.ui.day.RecordEditorDialog
import com.timeledger.app.ui.usage.UsageAccessCard
import com.timeledger.app.domain.model.GoalType

/**
 * 首页（需求一节）。
 *
 * 结构上刻意很薄：真正的「某一天长什么样」由 [DayViewContent] 负责，
 * 日历页复用同一套排版。首页这里只多两样东西：
 * **快速添加按钮**与它的对话框。
 *
 * 所有「显示什么」的判断都在 [com.timeledger.app.ui.day.DayViewStateMapper] 里 ——
 * 本机没有模拟器与设备，界面无法目视验证，把判断放进可测的纯函数是唯一可靠的做法。
 */
@Composable
fun HomeScreen(
    container: AppContainer,
    onManageGoals: () -> Unit,
    onManagePlans: () -> Unit,
    onReviewPending: () -> Unit,
    onManageAppMapping: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recordEditor by viewModel.recordEditor.collectAsStateWithLifecycle()
    val usageAccess by viewModel.usageAccess.collectAsStateWithLifecycle()
    var showQuickAdd by remember { mutableStateOf(false) }
    val current = state
    val context = LocalContext.current

    // 回到前台就重读一次系统记录：用户很可能刚去系统设置里开了「使用情况访问」再回来，
    // 那一下必须让卡片消失，否则他会以为授权没生效
    OnResumeEffect { viewModel.refreshUsage() }

    // 目标编辑状态
    var showGoalEditDialog by remember { mutableStateOf<GoalEditInfo?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        usageAccess?.takeIf { it.isVisible }?.let { card ->
            UsageAccessCard(
                state = card,
                onAction = {
                    if (card.granted) {
                        viewModel.refreshUsage()
                    } else {
                        // 特殊权限没有对话框可弹，只能跳系统设置。
                        // 极少数设备没有这个页面，此时给出可照做的文字路径，而不是静默失败
                        val opened = runCatching {
                            context.startActivity(container.usageAccessSettingsIntent())
                        }.isSuccess
                        if (!opened) {
                            Toast.makeText(
                                context,
                                "没找到系统设置页面，请手动到「设置 → 应用 → 特殊权限 → 使用情况访问」里打开",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                onSecondaryAction = onManageAppMapping,
            )
        }

        // 今日洞察部分
        current?.let { currentState ->
            if (currentState.todayInsights.isNotEmpty()) {
                TodayInsightsSection(
                    insights = currentState.todayInsights,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (current == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                DayViewContent(
                    state = current,
                    modifier = Modifier.fillMaxSize(),
                    // 给右下角的快速添加按钮留出空间，避免挡住最后一行
                    bottomPadding = 88.dp,
                    onManageGoals = onManageGoals,
                    onManagePlans = onManagePlans,
                    onReviewPending = onReviewPending,
                    // 点时间轴上的一行 → 改时间、改归属、加次数或备注，或者删掉它
                    onEditRecord = viewModel::startEditRecord,
                    // 目标编辑功能
                    onEditGoal = { goalRow -> 
                        showGoalEditDialog = GoalEditInfo(
                            goalId = goalRow.goalId,
                            title = goalRow.title,
                            achievedValue = goalRow.achievedValue,
                            goalType = goalRow.goalType
                        )
                    }
                )
            }

            if (current != null && current.canQuickAdd) {
                FloatingActionButton(
                    onClick = { showQuickAdd = true },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                ) {
                    Text(stringResource(R.string.action_quick_add))
                }
            }
        }
    }

    if (showQuickAdd && current != null) {
        QuickAddDialog(
            options = current.quickAddOptions,
            onDismiss = { showQuickAdd = false },
            onConfirm = { activityId, minutes ->
                viewModel.quickAdd(activityId, minutes)
                showQuickAdd = false
            },
        )
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

    // 目标编辑对话框
    showGoalEditDialog?.let { info ->
        ManualGoalAdjustmentDialog(
            goalTitle = info.title,
            goalType = info.goalType ?: com.timeledger.app.domain.model.GoalType.TIME,
            currentValue = info.achievedValue,
            onConfirm = { newValue ->
                val dayBoundary = com.timeledger.app.core.time.DayBoundary.system()
                viewModel.startGoalEdit(
                    goalId = info.goalId,
                    epochDay = dayBoundary.todayEpochDay(),
                    achievedValue = newValue
                )
                showGoalEditDialog = null
            },
            onDismiss = { showGoalEditDialog = null }
        )
    }
}

/**
 * 快速添加（需求一节）。
 *
 * 交互上刻意只问两件事：**做了什么**、**多久**。
 * 不填起止时刻，时间从「现在」往前推 —— 这是「低操作成本」原则的直接体现：
 * 用户刚学完半小时 Java，他想说的是「我刚学了半小时」，而不是「14:05 到 14:35」。
 *
 * 也正因为它以「此刻」为基准，日历里的历史日期不提供这个按钮
 * （映射器会传空的选项列表，见 [com.timeledger.app.ui.day.DayViewUiState.quickAddOptions]）。
 */
@Composable
private fun QuickAddDialog(
    options: List<QuickAddOption>,
    onDismiss: () -> Unit,
    onConfirm: (activityId: Long, minutes: Int) -> Unit,
) {
    var selectedActivityId by remember { mutableStateOf(options.firstOrNull()?.activityId) }
    var selectedMinutes by remember { mutableIntStateOf(30) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quick_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.quick_add_duration),
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    // 时长选项在窄屏上横向滚动，而不是挤成两行
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    DURATION_CHOICES.forEach { minutes ->
                        if (selectedMinutes == minutes) {
                            Button(onClick = { selectedMinutes = minutes }) { Text("${minutes}m") }
                        } else {
                            OutlinedButton(onClick = { selectedMinutes = minutes }) { Text("${minutes}m") }
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.quick_add_activity),
                    style = MaterialTheme.typography.labelMedium,
                )
                Column(
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    options.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedActivityId = option.activityId }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedActivityId == option.activityId,
                                onClick = { selectedActivityId = option.activityId },
                            )
                            Text(option.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            val activityId = selectedActivityId
            TextButton(
                enabled = activityId != null,
                onClick = { if (activityId != null) onConfirm(activityId, selectedMinutes) },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** 快速添加的时长选项（分钟）。 */
private val DURATION_CHOICES = listOf(15, 30, 45, 60, 90)

/**
 * 今日洞察部分，显示关键信息摘要。
 */
@Composable
private fun TodayInsightsSection(
    insights: List<String>,
    modifier: Modifier = Modifier
) {
    if (insights.isEmpty()) return
    
    com.timeledger.app.ui.day.SectionTitle("今日洞察")
    com.timeledger.app.ui.design.AppCard(
        variant = com.timeledger.app.ui.design.AppCardVariant.Elevated,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            insights.forEach { insight ->
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

/** 目标编辑状态信息 - 用于在 HomeScreen 中传递目标编辑参数 */
data class GoalEditInfo(
    val goalId: Long,
    val title: String,
    val achievedValue: Double,
    val goalType: com.timeledger.app.domain.model.GoalType?
)
