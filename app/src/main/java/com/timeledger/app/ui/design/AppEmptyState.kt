package com.timeledger.app.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.timeledger.app.ui.theme.Spacing

/**
 * 空状态 —— 全 App 的空页面都用它（模块 27）。
 *
 * ## 为什么值得单独做一个组件
 * 改造前所有空状态都是一行灰字（「这一天还没有记录」「还没有课程表」）。
 * 那是**数据管理工具**的语气：它宣告「没有数据」，然后把问题留给用户。
 * 这个 App 要的是「看自己的生活」，所以空状态应该是**一句有人味的说明 + 一个明确的下一步**。
 *
 * ## 文案的写法（调用方传进来）
 * - 说清「这里为什么空」，而不是「暂无数据」；
 * - 给一个动作，但**只在真的需要用户做事时给**。比如「今天没有待确认的时间」
 *   是好消息，不该配按钮 —— 给按钮等于把好消息变成了待办。
 */
@Composable
fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
    /** 可选的视觉符号，放在淡色圆底里。不传就只显示文字。 */
    symbol: (@Composable () -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xl, horizontal = Spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        if (symbol != null) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                symbol()
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Spacing.xs))
            Button(onClick = onAction) {
                Text(actionLabel, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** 空状态里的符号：一个柔和的圆点，用它就不用引图标库。 */
@Composable
fun EmptySymbolDot(color: Color = MaterialTheme.colorScheme.primary) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.5f)),
    )
}
