package com.timeledger.app.ui.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.timeledger.app.R
import com.timeledger.app.di.AppContainer
import com.timeledger.app.domain.model.GoalPeriod
import com.timeledger.app.domain.model.GoalType
import com.timeledger.app.ui.day.PickerList
import com.timeledger.app.ui.day.PickerOption
import com.timeledger.app.ui.day.ThinBar
import com.timeledger.app.ui.day.horizontalScrollable

/**
 * 目标管理页（需求四节）。
 *
 * 从首页目标分组的「管理」进入，是一个二级页面而不是第四个 Tab ——
 * 需求里明确说首页是「每日控制中心」、日历是「第二个主 Tab」，
 * 目标管理属于偶尔用到的设置类操作，不该占一个常驻入口。
 *
 * 所有判断都在 [GoalManagementStateMapper] 与 [GoalForm] 里（纯函数、可测），
 * 这里只负责绘制与把用户操作转成 ViewModel 调用。
 */
@Composable
fun GoalManagementScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onManageCatalog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: GoalManagementViewModel =
        viewModel(factory = GoalManagementViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val current = state
    var pendingDelete by remember { mutableStateOf<GoalCardUi?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        HeaderBar(
            onBack = onBack,
            onNew = viewModel::startCreate,
            onManageCatalog = onManageCatalog,
        )

        if (current == null) {
            Box(modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            return@Column
        }

        if (current.isEmpty) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.goals_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = viewModel::startCreate) {
                    Text(stringResource(R.string.goals_action_new))
                }
            }
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            current.goalCards.forEach { card ->
                GoalCard(
                    card = card,
                    onEdit = { viewModel.startEdit(card.goalId) },
                    onToggleActive = { viewModel.setActive(card.goalId, !card.isActive) },
                    onDelete = { pendingDelete = card },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    current?.form?.let { form ->
        GoalFormDialog(
            form = form,
            onDismiss = viewModel::dismissForm,
            onTitleChange = viewModel::updateTitle,
            onTypeChange = viewModel::updateType,
            onPeriodChange = viewModel::updatePeriod,
            onTargetChange = viewModel::updateTarget,
            onCategoryChange = viewModel::selectCategory,
            onActivityChange = viewModel::selectActivity,
            onSave = viewModel::save,
        )
    }

    pendingDelete?.let { card ->
        DeleteConfirmDialog(
            title = card.title,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                viewModel.delete(card.goalId)
                pendingDelete = null
            },
        )
    }
}

@Composable
private fun HeaderBar(onBack: () -> Unit, onNew: () -> Unit, onManageCatalog: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.goals_back)) }
        Text(
            text = stringResource(R.string.goals_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        // 建目标时经常发现「没有我想要的活动」，所以把目录管理的入口放在这里
        TextButton(onClick = onManageCatalog) {
            Text(stringResource(R.string.goals_action_catalog))
        }
        TextButton(onClick = onNew) { Text(stringResource(R.string.goals_action_new)) }
    }
}

@Composable
private fun GoalCard(
    card: GoalCardUi,
    onEdit: () -> Unit,
    onToggleActive: () -> Unit,
    onDelete: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(card.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.outline),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = card.targetLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = "${card.typeLabel} · ${card.periodLabel} · ${card.bindingLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            card.progressLabel?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (card.isAchieved) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                ThinBar(
                    fraction = card.progressFraction,
                    color = if (card.isAchieved) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    },
                )
            }

            if (!card.isActive) {
                Text(
                    text = stringResource(R.string.goals_inactive),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit) { Text(stringResource(R.string.goals_action_edit)) }
                TextButton(onClick = onToggleActive) {
                    Text(
                        stringResource(
                            if (card.isActive) R.string.goals_action_deactivate
                            else R.string.goals_action_activate
                        )
                    )
                }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.goals_action_delete)) }
            }
        }
    }
}

@Composable
private fun DeleteConfirmDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.goals_delete_title)) },
        // 明确说明影响范围：用户最担心的是「删了目标会不会把历史记录一起删掉」
        text = { Text(stringResource(R.string.goals_delete_message, title)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.goals_action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun GoalFormDialog(
    form: GoalFormUiState,
    onDismiss: () -> Unit,
    onTitleChange: (String) -> Unit,
    onTypeChange: (GoalType) -> Unit,
    onPeriodChange: (GoalPeriod) -> Unit,
    onTargetChange: (String) -> Unit,
    onCategoryChange: (Long) -> Unit,
    onActivityChange: (Long?) -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (form.isEditing) R.string.goals_form_edit else R.string.goals_form_create
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = form.title,
                    onValueChange = onTitleChange,
                    label = { Text(stringResource(R.string.goals_field_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                FieldLabel(stringResource(R.string.goals_field_type))
                ChoiceRow(
                    options = TYPE_CHOICES.map { (type, labelRes) -> type to stringResource(labelRes) },
                    selected = form.type,
                    onSelect = onTypeChange,
                )

                FieldLabel(stringResource(R.string.goals_field_period))
                ChoiceRow(
                    options = PERIOD_CHOICES.map { (period, labelRes) ->
                        period to stringResource(labelRes)
                    },
                    selected = form.period,
                    onSelect = onPeriodChange,
                )

                FieldLabel(stringResource(R.string.goals_field_target))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = form.targetText,
                        onValueChange = onTargetChange,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    // 单位跟着类型走：TIME 填的是小时，其余是原始个数
                    Text(form.targetUnitLabel, style = MaterialTheme.typography.bodyMedium)
                }

                FieldLabel(stringResource(R.string.goals_field_category))
                PickerList(
                    options = form.categories,
                    selectedId = form.selectedCategoryId,
                    onSelect = onCategoryChange,
                )

                FieldLabel(stringResource(R.string.goals_field_activity))
                PickerList(
                    options = form.activities,
                    selectedId = form.selectedActivityId,
                    // 再点一次已选项表示取消选择 —— 目标只绑分类是完全合法的
                    onSelect = { id -> onActivityChange(if (form.selectedActivityId == id) null else id) },
                    emptyHint = stringResource(R.string.goals_no_activities),
                )
                if (form.activities.isNotEmpty()) {
                    TextButton(onClick = { onActivityChange(null) }) {
                        Text(stringResource(R.string.goals_activity_none))
                    }
                }

                form.errorMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = form.canSave, onClick = onSave) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun <T> ChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScrollable(),
    ) {
        options.forEach { (value, label) ->
            if (value == selected) {
                Button(onClick = { onSelect(value) }) { Text(label) }
            } else {
                OutlinedButton(onClick = { onSelect(value) }) { Text(label) }
            }
        }
    }
}

private val TYPE_CHOICES = listOf(
    GoalType.TIME to R.string.goals_type_time,
    GoalType.COUNT to R.string.goals_type_count,
    GoalType.FREQUENCY to R.string.goals_type_frequency,
)

private val PERIOD_CHOICES = listOf(
    GoalPeriod.DAY to R.string.goals_period_day,
    GoalPeriod.WEEK to R.string.goals_period_week,
    GoalPeriod.MONTH to R.string.goals_period_month,
)
