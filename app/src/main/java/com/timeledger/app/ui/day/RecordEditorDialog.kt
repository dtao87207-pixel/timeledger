package com.timeledger.app.ui.day

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.timeledger.app.R

/**
 * 记录编辑框（需求十节「记录管理」）。
 *
 * ## 为什么要有它
 * 事后确认只能改「这条记录算哪个分类」，改不了时间本身。
 * 而现实里更需要改时间：忘了停表、两件事连着做、事后想起来时间记错了。
 * 没有这个入口，用户唯一的办法是删掉重记 —— 那等于把「记录」这件事变成了一件要小心翼翼的事。
 *
 * ## 它不管的三件事
 * - **不判断能不能填**：起止时刻的解析、跨夜与瞬时记录的区分都在 [RecordEditForm] 里（可测试）；
 * - **不筛选项**：分类与活动的列表、报错文案都由 [RecordEditorUiMapper] 算好；
 * - **不问「确定删除吗」**：删除确认在这里，但删除本身只发一个回调 ——
 *   追问属于交互判断，不该和「改了什么」混在同一处。
 *
 * 这里只做两件事：把状态画出来、把用户改的结果原样交回去（一个回调，见 [RecordEditorUi.form]）。
 */
@Composable
fun RecordEditorDialog(
    editor: RecordEditorUi,
    onChange: (RecordEditState) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val form = editor.form
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(editor.title)
                Text(
                    text = editor.dateLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.record_edit_field_time),
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = form.startText,
                        onValueChange = { onChange(form.copy(startText = it)) },
                        label = { Text(stringResource(R.string.record_edit_field_start)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = form.endText,
                        onValueChange = { onChange(form.copy(endText = it)) },
                        label = { Text(stringResource(R.string.record_edit_field_end)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                // 填完立刻能看到「这到底是多久」，不用自己心算；
                // 跨夜与瞬时这两种容易被填错的形态也在这里说清楚
                editor.durationLabel?.let {
                    Text(
                        text = stringResource(R.string.record_edit_duration, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (editor.isInstant) {
                    HintText(stringResource(R.string.record_edit_instant))
                }
                if (editor.crossesMidnight) {
                    HintText(stringResource(R.string.record_edit_cross_midnight))
                }

                Text(
                    text = stringResource(R.string.record_edit_field_category),
                    style = MaterialTheme.typography.labelMedium,
                )
                PickerList(
                    options = editor.categories,
                    selectedId = form.categoryId,
                    // 再点一次已选项表示取消：只指定活动、不指定分类是合法的
                    onSelect = { id ->
                        onChange(form.copy(categoryId = if (form.categoryId == id) null else id))
                    },
                )

                Text(
                    text = stringResource(R.string.record_edit_field_activity),
                    style = MaterialTheme.typography.labelMedium,
                )
                PickerList(
                    options = editor.activities,
                    selectedId = form.activityId,
                    onSelect = { id ->
                        onChange(form.copy(activityId = if (form.activityId == id) null else id))
                    },
                    emptyHint = stringResource(R.string.goals_no_activities),
                )

                OutlinedTextField(
                    value = form.countText,
                    onValueChange = { onChange(form.copy(countText = it)) },
                    label = { Text(stringResource(R.string.record_edit_field_count)) },
                    singleLine = true,
                    // Decimal 而不是 Number：计数允许小数（半套卷子也算数）
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = form.note,
                    onValueChange = { onChange(form.copy(note = it)) },
                    label = { Text(stringResource(R.string.record_edit_field_note)) },
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { confirmDelete = true }) {
                    Text(
                        text = stringResource(R.string.record_edit_action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.record_edit_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.record_edit_delete_message,
                        editor.dateLabel,
                        editor.form.startText,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.record_edit_action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
