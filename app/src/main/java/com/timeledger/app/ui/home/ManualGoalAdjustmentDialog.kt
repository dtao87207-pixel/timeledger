package com.timeledger.app.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.timeledger.app.R
import com.timeledger.app.domain.model.GoalType
import com.timeledger.app.ui.format.DisplayFormat

/**
 * 目标进度手动调整对话框
 */
@Composable
fun ManualGoalAdjustmentDialog(
    goalTitle: String,
    goalType: GoalType,
    currentValue: Double,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var inputValue by remember { mutableStateOf(currentValue.toString()) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调整目标进度") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("目标: $goalTitle")
                
                OutlinedTextField(
                    value = inputValue,
                    onValueChange = { 
                        // 只允许数字和小数点
                        if (it.matches(Regex("^\\d*\\.?\\d*$"))) {
                            inputValue = it
                        }
                    },
                    label = { Text("进度值") },
                    supportingText = {
                        Text(getUnitText(goalType, currentValue))
                    },
                    isError = inputValue.isBlank() || inputValue.toDoubleOrNull() == null,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val value = inputValue.toDoubleOrNull()
                    if (value != null) {
                        onConfirm(value)
                    }
                },
                enabled = inputValue.isNotBlank() && inputValue.toDoubleOrNull() != null
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun getUnitText(goalType: GoalType, achievedValue: Double): String {
    return when (goalType) {
        GoalType.TIME -> "单位：${formatTimeValue(achievedValue)}"
        GoalType.COUNT -> "单位：个"
        GoalType.FREQUENCY -> "单位：次"
    }
}

private fun formatTimeValue(timeValue: Double): String {
    val seconds = timeValue.toLong()
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60

    return if (hours > 0) {
        "${hours}小时${minutes}分钟"
    } else if (minutes > 0) {
        "${minutes}分钟${secs}秒"
    } else {
        "${secs}秒"
    }
}