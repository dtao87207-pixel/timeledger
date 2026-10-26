package com.timeledger.app.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.timeledger.app.ui.theme.Motion
import com.timeledger.app.ui.theme.Shape
import com.timeledger.app.ui.theme.Spacing

/**
 * 进度环 —— 今日完成度、目标进度（模块 27）。
 *
 * 手感上的三件事：
 * 1. **数值变化会自己走过去**（[Motion.base] 200ms），不是直接跳 —— 记录一笔之后
 *    环「长了一截」本身就是反馈；
 * 2. 底色环用 `surfaceContainerHighest`（比卡片深一档），所以空进度也看得见形状；
 * 3. 中心留给调用方写数字：环只是**形状**，数字的层级由调用方用 metric 级文字给。
 */
@Composable
fun ProgressRing(
    fraction: Float,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    thickness: Dp = 8.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: @Composable (() -> Unit)? = null,
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = Motion.base, easing = Motion.standard),
        label = "ring",
    )

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = thickness.toPx()
            val inset = stroke / 2f
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)

            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke),
            )
            if (animated > 0f) {
                drawArc(
                    color = color,
                    // 从 12 点方向开始，与人的直觉一致
                    startAngle = -90f,
                    sweepAngle = animated * 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        content?.invoke()
    }
}

/**
 * 进度条 —— 目标卡里的细条（取代原来的 `ThinBar`）。
 *
 * 与旧实现的两点差别：高度 6 → 8dp（更容易看出「还差一点」），两端全圆角，
 * 且**数值变化同样带 200ms 过渡** —— 用户改一条记录，条会自己动，而不是闪一下。
 */
@Composable
fun ProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    height: Dp = 8.dp,
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = Motion.base, easing = Motion.standard),
        label = "bar",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(Shape.full)
            .background(trackColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated)
                .fillMaxHeight()
                .clip(Shape.full)
                .background(color),
        )
    }
}

/**
 * 目标条 —— 一行「标题 + 进度 + 数字 + 细条」，App 里所有目标都用它。
 *
 * 数字右对齐、标题左对齐、细条横贯下方：这是列表里最容易扫读的形状
 * （比一行 `Java 2h20m / 3h` 更容易看出「还差多少」）。
 */
@Composable
fun GoalProgressLine(
    title: String,
    fraction: Float,
    progressLabel: String,
    targetLabel: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    achieved: Boolean = false,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (achieved) {
                StatusPill(
                    text = "已达成",
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.width(Spacing.s))
            }
            Text(
                text = progressLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = "/ $targetLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ProgressBar(fraction = fraction, color = color)
    }
}

/*
 * 可编辑的目标条 —— 带有手动调整按钮
 * 暂时注释掉以解决构建问题
 */
/*
@Composable
fun EditableGoalProgressLine(
    title: String,
    fraction: Float,
    progressLabel: String,
    targetLabel: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    achieved: Boolean = false,
    onEditClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (achieved) {
                StatusPill(
                    text = "已达成",
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.width(Spacing.s))
            }
            Text(
                text = progressLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = "/ $targetLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onEditClick != null) {
                Spacer(Modifier.width(Spacing.s))
                IconButton(onClick = onEditClick) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Edit,
                        contentDescription = "编辑目标进度",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        ProgressBar(fraction = fraction, color = color)
    }
}
*/
