package com.timeledger.app.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import com.timeledger.app.ui.day.horizontalScrollable

/**
 * 分类与活动管理页（需求八节）。
 *
 * 从目标管理页进入，是二级页面的第二层。分类与活动是「时间记录」的词汇表：
 * 没有它，用户只能往种子里塞的时间记账，用户自己的活动（比如「跑步」「练琴」）无处安放。
 *
 * ## 归档而不是删除
 * 界面上的「删除」是**归档**：数据层刻意不物理删除分类/活动，
 * 因为历史 TimeRecord 依赖它们的归属 —— 真删了，往年统计就会出现无法归属的时间。
 * 界面上因此用「归档 / 恢复」的说法，并在提示里说明历史记录不受影响，
 * 避免用户以为「归档」是没删干净。
 */
@Composable
fun CatalogManagementScreen(
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: CatalogManagementViewModel =
        viewModel(factory = CatalogManagementViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val current = state

    Column(modifier = modifier.fillMaxSize()) {
        HeaderBar(onBack = onBack, onCreateCategory = viewModel::startCreateCategory)

        if (current == null) {
            Box(modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            return@Column
        }

        if (current.isEmpty) {
            HintText(
                text = stringResource(R.string.catalog_empty),
                modifier = Modifier.padding(24.dp),
            )
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (current.archivedCategoryCount > 0) {
                HintText(stringResource(R.string.catalog_archived_hint))
            }

            current.categoryCards.forEach { card ->
                CategoryCard(
                    card = card,
                    onEdit = { viewModel.startEditCategory(card.categoryId) },
                    onToggleArchived = {
                        viewModel.setCategoryArchived(card.categoryId, !card.isArchived)
                    },
                    onAddActivity = { viewModel.startCreateActivity(card.categoryId) },
                    onEditActivity = viewModel::startEditActivity,
                    onToggleActivityArchived = viewModel::setActivityArchived,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    current?.categoryForm?.let { form ->
        CategoryFormDialog(
            form = form,
            onDismiss = viewModel::dismissForm,
            onNameChange = viewModel::updateCategoryName,
            onColorChange = viewModel::selectCategoryColor,
            onSave = viewModel::saveCategory,
        )
    }

    current?.activityForm?.let { form ->
        ActivityFormDialog(
            form = form,
            onDismiss = viewModel::dismissForm,
            onNameChange = viewModel::updateActivityName,
            onColorChange = viewModel::selectActivityColor,
            onSave = viewModel::saveActivity,
        )
    }
}

@Composable
private fun HeaderBar(onBack: () -> Unit, onCreateCategory: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.goals_back)) }
        Text(
            text = stringResource(R.string.catalog_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCreateCategory) {
            Text(stringResource(R.string.catalog_action_new_category))
        }
    }
}

@Composable
private fun CategoryCard(
    card: CategoryCardUi,
    onEdit: () -> Unit,
    onToggleArchived: () -> Unit,
    onAddActivity: () -> Unit,
    onEditActivity: (Long) -> Unit,
    onToggleActivityArchived: (Long, Boolean) -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColorDot(card.colorArgb)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = card.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.catalog_activity_count, card.activityCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BadgeIf(card.isBuiltIn, stringResource(R.string.catalog_builtin))
                BadgeIf(card.isArchived, stringResource(R.string.catalog_archived))
            }

            Text(
                text = card.activities.joinToString("、") { it.name }
                    .ifEmpty { stringResource(R.string.catalog_no_activities) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit) { Text(stringResource(R.string.catalog_action_edit)) }
                TextButton(onClick = onAddActivity) {
                    Text(stringResource(R.string.catalog_action_add_activity))
                }
                TextButton(onClick = onToggleArchived) {
                    Text(
                        stringResource(
                            if (card.isArchived) R.string.catalog_action_restore
                            else R.string.catalog_action_archive
                        )
                    )
                }
            }

            card.activities.forEach { activity ->
                ActivityRow(
                    activity = activity,
                    onEdit = { onEditActivity(activity.activityId) },
                    onToggleArchived = {
                        onToggleActivityArchived(activity.activityId, !activity.isArchived)
                    },
                )
            }
        }
    }
}

@Composable
private fun ActivityRow(
    activity: ActivityRowUi,
    onEdit: () -> Unit,
    onToggleArchived: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorDot(activity.colorArgb, size = 10)
        Spacer(Modifier.width(10.dp))
        Text(
            text = activity.name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (activity.isArchived) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
        if (activity.isArchived) {
            BadgeIf(true, stringResource(R.string.catalog_archived))
            Spacer(Modifier.width(4.dp))
        }
        TextButton(onClick = onEdit) { Text(stringResource(R.string.catalog_action_edit)) }
        TextButton(onClick = onToggleArchived) {
            Text(
                stringResource(
                    if (activity.isArchived) R.string.catalog_action_restore
                    else R.string.catalog_action_archive
                )
            )
        }
    }
}

@Composable
private fun ColorDot(colorArgb: Int?, size: Int = 14) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun BadgeIf(visible: Boolean, text: String) {
    if (!visible) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun CategoryFormDialog(
    form: CategoryFormUiState,
    onDismiss: () -> Unit,
    onNameChange: (String) -> Unit,
    onColorChange: (Int?) -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (form.isEditing) R.string.catalog_form_category_edit
                    else R.string.catalog_form_category_create
                )
            )
        },
        text = {
            FormBody(
                name = form.name,
                onNameChange = onNameChange,
                errorMessage = form.errorMessage,
                hint = if (form.isBuiltIn) stringResource(R.string.catalog_builtin_hint) else null,
            ) {
                Text(stringResource(R.string.catalog_field_color), style = MaterialTheme.typography.labelMedium)
                ColorPickerRow(
                    colors = form.palette,
                    selected = form.selectedColorArgb,
                    onSelect = onColorChange,
                )
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
private fun ActivityFormDialog(
    form: ActivityFormUiState,
    onDismiss: () -> Unit,
    onNameChange: (String) -> Unit,
    onColorChange: (Int?) -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (form.isEditing) R.string.catalog_form_activity_edit
                    else R.string.catalog_form_activity_create
                )
            )
        },
        text = {
            FormBody(
                name = form.name,
                onNameChange = onNameChange,
                errorMessage = form.errorMessage,
                hint = null,
            ) {
                Text(
                    text = stringResource(R.string.catalog_field_category, form.categoryName),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(stringResource(R.string.catalog_field_color), style = MaterialTheme.typography.labelMedium)
                ColorPickerRow(
                    colors = form.palette,
                    selected = form.selectedColorArgb,
                    onSelect = onColorChange,
                    // 活动可以不给颜色，跟着分类走 —— 换分类配色时它会一起变
                    inheritLabel = stringResource(R.string.catalog_color_inherit),
                    onSelectInherit = { onColorChange(null) },
                )
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

/** 两个表单共用的主体：名称输入 + 错误提示 + 提示语 + 各自的附加内容。 */
@Composable
private fun FormBody(
    name: String,
    onNameChange: (String) -> Unit,
    errorMessage: String?,
    hint: String?,
    extra: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.catalog_field_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        extra()
        hint?.let { HintText(it) }
        errorMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ColorPickerRow(
    colors: List<Int>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    inheritLabel: String? = null,
    onSelectInherit: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.horizontalScrollable(),
        ) {
            colors.forEach { argb ->
                val isSelected = argb == selected
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape,
                        )
                        .clickable { onSelect(argb) },
                )
            }
        }
        if (inheritLabel != null && onSelectInherit != null) {
            if (selected == null) {
                Button(onClick = onSelectInherit) { Text(inheritLabel) }
            } else {
                OutlinedButton(onClick = onSelectInherit) { Text(inheritLabel) }
            }
        }
    }
}
