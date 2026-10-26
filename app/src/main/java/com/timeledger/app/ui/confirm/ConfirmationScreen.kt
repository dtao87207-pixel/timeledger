package com.timeledger.app.ui.confirm

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.timeledger.app.ui.day.PickerList
import com.timeledger.app.ui.day.SectionTitle

/**
 * 事后确认页（需求三节 C）。
 *
 * ## 这一页只问「说不清的部分」
 * 需求原文：
 * > 只要求用户确认：15:30–16:00 做了什么？而不是让用户重新记录整个下午。
 *
 * 所以列表只包含两类内容：**待确认的推测记录**与**十分钟以上的缺失时间**。
 * 已确认的记录、被覆盖掉的推测、以及太短的空档，都不会出现在这里 ——
 * 追问得越少，用户越愿意回答；问得太多，他会直接关掉通知。
 *
 * ## 一键确认是主路径
 * 待确认的每一条都先用系统推断的结果填好，用户觉得对就直接点「确认」，
 * 想改才点「修改」。这样常见情形只需要一次点击。
 */
@Composable
fun ConfirmationScreen(
    container: AppContainer,
    epochDay: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ConfirmationViewModel =
        viewModel(factory = ConfirmationViewModel.factory(container, epochDay))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val current = state

    Column(modifier = modifier.fillMaxSize()) {
        HeaderBar(onBack = onBack, onConfirmAll = viewModel::confirmAll, showConfirmAll = current?.canConfirmAll == true)

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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = current.dateLabel,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = current.summaryLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (current.isEmpty) {
                Text(
                    text = stringResource(R.string.confirm_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (current.hasPending) {
                SectionTitle(stringResource(R.string.confirm_section_pending))
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        current.pendingRecords.forEach { row ->
                            PendingRow(
                                row = row,
                                onConfirm = { viewModel.confirmAsIs(row.recordId) },
                                onEdit = { viewModel.startConfirm(row.recordId) },
                            )
                        }
                    }
                }
            }

            if (current.hasGaps) {
                SectionTitle(stringResource(R.string.confirm_section_gaps))
                Text(
                    text = stringResource(R.string.confirm_hint_gaps),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        current.gaps.forEach { gap ->
                            GapRow(
                                gap = gap,
                                onFill = { viewModel.startFillGap(gap) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    current?.editor?.let { editor ->
        EditorDialog(
            editor = editor,
            onDismiss = viewModel::dismissEditor,
            onCategoryChange = viewModel::selectCategory,
            onActivityChange = viewModel::selectActivity,
            onNoteChange = viewModel::updateNote,
            onSave = viewModel::save,
        )
    }
}

@Composable
private fun HeaderBar(onBack: () -> Unit, onConfirmAll: () -> Unit, showConfirmAll: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.goals_back)) }
        Text(
            text = stringResource(R.string.confirm_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        // 只有多条时才给「全部确认」：只有一条的话逐个点反而更快，多一个按钮只是噪音
        if (showConfirmAll) {
            TextButton(onClick = onConfirmAll) {
                Text(stringResource(R.string.confirm_action_confirm_all))
            }
        }
    }
}

@Composable
private fun PendingRow(row: PendingRecordUi, onConfirm: () -> Unit, onEdit: () -> Unit) {
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
                text = row.rangeLabel,
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
        Text(
            text = row.confidenceLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 14.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.confirm_action_confirm)) }
            TextButton(onClick = onEdit) { Text(stringResource(R.string.confirm_action_edit)) }
        }
    }
}

@Composable
private fun GapRow(gap: GapUi, onFill: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = gap.rangeLabel,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = gap.durationLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onFill) { Text(stringResource(R.string.confirm_action_fill)) }
    }
}

@Composable
private fun EditorDialog(
    editor: ConfirmEditorUiState,
    onDismiss: () -> Unit,
    onCategoryChange: (Long?) -> Unit,
    onActivityChange: (Long?) -> Unit,
    onNoteChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(editor.title) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = editor.rangeLabel,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = editor.durationLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    text = stringResource(R.string.confirm_form_category),
                    style = MaterialTheme.typography.labelMedium,
                )
                PickerList(
                    options = editor.categories,
                    selectedId = editor.selectedCategoryId,
                    // 再点一次已选项表示取消：只指定活动、不指定分类是合法的
                    onSelect = { id ->
                        onCategoryChange(if (editor.selectedCategoryId == id) null else id)
                    },
                )

                Text(
                    text = stringResource(R.string.confirm_form_activity),
                    style = MaterialTheme.typography.labelMedium,
                )
                PickerList(
                    options = editor.activities,
                    selectedId = editor.selectedActivityId,
                    onSelect = { id ->
                        onActivityChange(if (editor.selectedActivityId == id) null else id)
                    },
                    emptyHint = stringResource(R.string.goals_no_activities),
                )

                OutlinedTextField(
                    value = editor.note,
                    onValueChange = onNoteChange,
                    label = { Text(stringResource(R.string.confirm_form_note)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                editor.errorMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = editor.canSave, onClick = onSave) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
