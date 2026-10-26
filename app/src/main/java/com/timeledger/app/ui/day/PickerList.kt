package com.timeledger.app.ui.day

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 选择器列表（单选一行一项）—— 目标表单与课程表表单共用。
 *
 * 抽出来的理由和 [PickerOption] 一样：两个表单要做的是同一件事，
 * 各写一遍的结果是两处细节慢慢不一致（一个有颜色点、一个没有）。
 */
@Composable
fun PickerList(
    options: List<PickerOption>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
    emptyHint: String? = null,
) {
    if (options.isEmpty()) {
        emptyHint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(modifier = modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
        options.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(option.id) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selectedId == option.id, onClick = { onSelect(option.id) })
                option.colorArgb?.let { argb ->
                    Box(
                        modifier = Modifier
                            .width(8.dp)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(argb)),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(option.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** 让一行选项在窄屏上横向滚动，而不是换行挤成两排。 */
@Composable
fun Modifier.horizontalScrollable(): Modifier = this.horizontalScroll(rememberScrollState())
