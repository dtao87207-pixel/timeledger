package com.timeledger.app.ui.mapping

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.timeledger.app.R
import com.timeledger.app.di.AppContainer
import com.timeledger.app.ui.day.PickerList
import com.timeledger.app.ui.day.HintText
import com.timeledger.app.ui.day.SectionTitle

/**
 * App 归类页（需求九节）。
 *
 * ## 它为什么重要
 * 系统知道「你用了微信 40 分钟」，但不知道「微信 = 社交」。
 * 用户在这一页说一次，之后微信的所有记录就自动归类、不再问他 ——
 * **这是「越用越少确认」的开关**。没有它，用户只能在一堆「未归类」的记录里逐条确认，
 * 那不是少记录，那是换了个方式记账。
 *
 * 入口有两个：首页系统感知状态块上的「去归类」，以及二级页面的返回栈。
 * 是一个二级页面而不是第四个 Tab —— 它是把系统数据整理干净的
 * 一次**一次性动作**，不该占常驻入口。
 */
@Composable
fun AppMappingScreen(
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: AppMappingViewModel = viewModel(factory = AppMappingViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val current = state

    Column(modifier = modifier.fillMaxSize()) {
        HeaderBar(summary = current?.summaryLabel, onBack = onBack)

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
                    text = current.emptyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            // 待归类在最上面：这一页存在的意义就是让它一眼可见、且越用越少
            SectionTitle(stringResource(R.string.mapping_section_pending, current.pendingRows.size))
            if (current.pendingRows.isEmpty()) {
                HintText(stringResource(R.string.mapping_no_pending))
            } else {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        current.pendingRows.forEach { row ->
                            AppMappingRowItem(
                                row = row,
                                actionLabel = stringResource(R.string.mapping_action_assign),
                                onClick = { viewModel.startEdit(row) },
                            )
                        }
                    }
                }
            }

            if (current.handledRows.isNotEmpty()) {
                SectionTitle(stringResource(R.string.mapping_section_handled))
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        current.handledRows.forEach { row ->
                            AppMappingRowItem(
                                row = row,
                                actionLabel = stringResource(R.string.mapping_action_change),
                                onClick = { viewModel.startEdit(row) },
                            )
                        }
                    }
                }
            }
        }
    }

    current?.form?.let { form ->
        FormDialog(
            form = form,
            onCategoryChange = viewModel::selectCategory,
            onActivityChange = viewModel::selectActivity,
            onSave = viewModel::save,
            onDismiss = viewModel::dismissForm,
        )
    }
}

@Composable
private fun HeaderBar(summary: String?, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.goals_back))
            }
            Text(
                text = stringResource(R.string.mapping_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }
        summary?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun AppMappingRowItem(
    row: AppMappingRowUi,
    actionLabel: String,
    onClick: () -> Unit,
) {
    val barColor = row.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(barColor),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.bindingLabel,
                style = MaterialTheme.typography.bodySmall,
                color = if (row.categoryId == null && row.activityId == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            row.usageLabel?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onClick) { Text(actionLabel) }
    }
}

@Composable
private fun FormDialog(
    form: AppMappingFormUi,
    onCategoryChange: (Long?) -> Unit,
    onActivityChange: (Long?) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(form.title) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.mapping_field_category),
                    style = MaterialTheme.typography.labelMedium,
                )
                PickerList(
                    options = form.categories,
                    selectedId = form.form.categoryId,
                    // 再点一次已选项表示取消：只指定活动、不指定分类是合法的
                    onSelect = { id ->
                        onCategoryChange(if (form.form.categoryId == id) null else id)
                    },
                )

                Text(
                    text = stringResource(R.string.mapping_field_activity),
                    style = MaterialTheme.typography.labelMedium,
                )
                PickerList(
                    options = form.activities,
                    selectedId = form.form.activityId,
                    onSelect = { id ->
                        onActivityChange(if (form.form.activityId == id) null else id)
                    },
                    emptyHint = stringResource(R.string.goals_no_activities),
                )

                Text(
                    text = form.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            TextButton(onClick = onSave) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
