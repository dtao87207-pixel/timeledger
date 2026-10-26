package com.timeledger.app.ui.plan

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
import com.timeledger.app.ui.day.PickerList
import com.timeledger.app.ui.day.SectionTitle
import com.timeledger.app.ui.day.horizontalScrollable

/**
 * 课程表管理页（需求五节）。
 *
 * 按**星期**组织，而不是按日期：用户想的是「周一有什么课」，
 * 而不是「九月七号有什么课」—— 这也是课程表和普通日程列表最大的区别。
 *
 * 这一页是「计划 vs 实际」（需求十节，产品核心）的输入侧。
 * 没有它，首页那句「计划 1h40m，照计划做了……」永远不会出现。
 *
 * V1 只支持每周重复（需求五节把它列为优先项）；单次日程数据层与展开器都已支持，
 * 若已有这类条目也会单独列出，不会被悄悄藏起来。
 */
@Composable
fun PlanManagementScreen(
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: PlanManagementViewModel =
        viewModel(factory = PlanManagementViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val current = state
    var pendingDelete by remember { mutableStateOf<PlanRowUi?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        HeaderBar(onBack = onBack, onNew = viewModel::startCreate)

        if (current == null) {
            Box(modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            current.summaryLabel?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (current.isEmpty) {
                Text(
                    text = stringResource(R.string.plan_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = viewModel::startCreate) {
                    Text(stringResource(R.string.plan_action_new))
                }
            }

            current.weeklyGroups.forEach { group ->
                WeekdayGroup(
                    group = group,
                    onEdit = viewModel::startEdit,
                    onToggleActive = viewModel::setActive,
                    onDelete = { pendingDelete = it },
                )
            }

            if (current.hasSingleDatePlans) {
                SectionTitle(stringResource(R.string.plan_single_date_section))
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        current.singleDateRows.forEach { row ->
                            PlanRowItem(
                                row = row,
                                onEdit = { viewModel.startEdit(row.planBlockId) },
                                onToggleActive = { viewModel.setActive(row.planBlockId, row.isInactive) },
                                onDelete = { pendingDelete = row },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    current?.form?.let { form ->
        PlanFormDialog(
            form = form,
            onDismiss = viewModel::dismissForm,
            onTitleChange = viewModel::updateTitle,
            onDayChange = viewModel::updateDayOfWeek,
            onStartChange = viewModel::updateStart,
            onEndChange = viewModel::updateEnd,
            onCategoryChange = viewModel::selectCategory,
            onActivityChange = viewModel::selectActivity,
            onLocationChange = viewModel::updateLocation,
            onSave = viewModel::save,
        )
    }

    pendingDelete?.let { row ->
        DeleteConfirmDialog(
            title = row.title,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                viewModel.delete(row.planBlockId)
                pendingDelete = null
            },
        )
    }
}

@Composable
private fun HeaderBar(onBack: () -> Unit, onNew: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.goals_back)) }
        Text(
            text = stringResource(R.string.plan_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onNew) { Text(stringResource(R.string.plan_action_new)) }
    }
}

@Composable
private fun WeekdayGroup(
    group: PlanDayGroupUi,
    onEdit: (Long) -> Unit,
    onToggleActive: (Long, Boolean) -> Unit,
    onDelete: (PlanRowUi) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle(group.dayLabel)
        if (group.count > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.plan_count, group.count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (group.rows.isEmpty()) {
        // 空的日子也列出来：一周七格的骨架本身就在告诉用户「这里还能加课」
        Text(
            text = stringResource(R.string.plan_empty_day),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        return
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            group.rows.forEach { row ->
                PlanRowItem(
                    row = row,
                    onEdit = { onEdit(row.planBlockId) },
                    onToggleActive = { onToggleActive(row.planBlockId, row.isInactive) },
                    onDelete = { onDelete(row) },
                )
            }
        }
    }
}

@Composable
private fun PlanRowItem(
    row: PlanRowUi,
    onEdit: () -> Unit,
    onToggleActive: () -> Unit,
    onDelete: () -> Unit,
) {
    val barColor = row.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.outline
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(barColor),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = row.timeRangeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = row.durationLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val details = listOfNotNull(row.bindingLabel, row.location).joinToString(" · ")
        if (details.isNotEmpty()) {
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp),
            )
        }

        val notes = buildList {
            if (row.crossesMidnight) add(stringResource(R.string.plan_cross_midnight_badge))
            if (row.isInactive) add(stringResource(R.string.plan_inactive))
        }
        if (notes.isNotEmpty()) {
            Text(
                text = notes.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 14.dp),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onEdit) { Text(stringResource(R.string.plan_action_edit)) }
            TextButton(onClick = onToggleActive) {
                Text(
                    stringResource(
                        if (row.isInactive) R.string.plan_action_activate
                        else R.string.plan_action_deactivate
                    )
                )
            }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.plan_action_delete)) }
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
        title = { Text(stringResource(R.string.plan_delete_title)) },
        // 说明影响范围：用户最担心的是「删了课会不会把记录一起删掉」
        text = { Text(stringResource(R.string.plan_delete_message, title)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.plan_action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun PlanFormDialog(
    form: PlanFormUiState,
    onDismiss: () -> Unit,
    onTitleChange: (String) -> Unit,
    onDayChange: (Int) -> Unit,
    onStartChange: (String) -> Unit,
    onEndChange: (String) -> Unit,
    onCategoryChange: (Long?) -> Unit,
    onActivityChange: (Long?) -> Unit,
    onLocationChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (form.isEditing) R.string.plan_form_edit else R.string.plan_form_create
                )
            )
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = form.title,
                    onValueChange = onTitleChange,
                    label = { Text(stringResource(R.string.plan_field_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                FieldLabel(stringResource(R.string.plan_field_day))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScrollable(),
                ) {
                    form.dayOptions.forEachIndexed { index, label ->
                        val dayOfWeek = index + 1
                        if (dayOfWeek == form.selectedDayOfWeek) {
                            Button(onClick = { onDayChange(dayOfWeek) }) { Text(label) }
                        } else {
                            OutlinedButton(onClick = { onDayChange(dayOfWeek) }) { Text(label) }
                        }
                    }
                }

                FieldLabel(stringResource(R.string.plan_field_time))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = form.startText,
                        onValueChange = onStartChange,
                        label = { Text(stringResource(R.string.plan_field_start)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("–", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = form.endText,
                        onValueChange = onEndChange,
                        label = { Text(stringResource(R.string.plan_field_end)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                // 实时时长预览：用户填完就知道是不是想要的长度，不用自己心算
                form.durationLabel?.let {
                    Text(
                        text = stringResource(R.string.plan_duration_preview, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (form.crossesMidnight) {
                    Text(
                        text = stringResource(R.string.plan_cross_midnight),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                FieldLabel(stringResource(R.string.plan_field_category))
                PickerList(
                    options = form.categories,
                    selectedId = form.selectedCategoryId,
                    // 再点一次已选项表示取消：课程不绑分类是完全合法的
                    onSelect = { id -> onCategoryChange(if (form.selectedCategoryId == id) null else id) },
                )

                FieldLabel(stringResource(R.string.plan_field_activity))
                PickerList(
                    options = form.activities,
                    selectedId = form.selectedActivityId,
                    onSelect = { id -> onActivityChange(if (form.selectedActivityId == id) null else id) },
                    emptyHint = stringResource(R.string.goals_no_activities),
                )

                OutlinedTextField(
                    value = form.location,
                    onValueChange = onLocationChange,
                    label = { Text(stringResource(R.string.plan_field_location)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

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
