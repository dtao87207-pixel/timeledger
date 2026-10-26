package com.timeledger.app.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.timeledger.app.ui.theme.Shape
import com.timeledger.app.ui.theme.Spacing

/**
 * 时间轴上的一个段（模块 27）。
 *
 * ## 为什么值得单独做一个组件
 * 时间轴是这个 App 最核心的视觉件，而改造前它只是「一行行文字 + 一个色点」：
 * 分类只体现在那个 26dp 的点上，睡眠那种 5 小时的大块看起来就是**一片空白卡片**。
 *
 * 现在每段由四样东西组成，各司其职：
 *
 * | 元素 | 表达什么 |
 * |---|---|
 * | 左侧 4dp 满色带 | **属于哪个分类**（唯一的高饱和元素） |
 * | 整块 12% 同色底 | 同一件事的「面积」，让长段自然更醒目 |
 * | 开始时刻（左列） | 几点开始的 —— 这一列就是刻度 |
 * | 标题 / 副标题 / 时长 | 在做什么 / 归属 / 多久 |
 *
 * 分类色只做点睛、不做填充：一屏里会有七八个段，全刷满饱和度会打架。
 *
 * ## 块高由外部决定
 * 高度是**时间长度**（映射器算好的），这里只管把内容放进给定高度里 ——
 * 所以 `compact` 时只放一行，避免文字互相压住。
 */
@Composable
fun TimelineBand(
    startLabel: String,
    title: String,
    durationLabel: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    subtitle: String? = null,
    trailingLabel: String? = null,
    badge: String? = null,
    height: Dp = 56.dp,
    compact: Boolean = false,
    labelColumnWidth: Dp = 48.dp,
    marker: (@Composable () -> Unit)? = null,
    dimmed: Boolean = false,
) {
    val bandColor = accent ?: MaterialTheme.colorScheme.outline
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(Shape.medium)
            .background(bandColor.copy(alpha = if (dimmed) 0.06f else 0.12f))
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 分类的身份：唯一满饱和度的地方
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(bandColor.copy(alpha = if (dimmed) 0.5f else 1f)),
        )

        // 时刻列：它就是这条轴的刻度（放在段内部，色底把时刻一起包住，视觉上更像「一段」）
        Text(
            text = startLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier
                .width(labelColumnWidth)
                .padding(start = Spacing.m, end = Spacing.xs),
        )

        if (marker != null) {
            Box(
                modifier = Modifier.width(30.dp),
                contentAlignment = Alignment.Center,
            ) { marker() }
            Spacer(Modifier.width(Spacing.xs))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = Spacing.s),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badge != null) {
                    Spacer(Modifier.width(Spacing.s))
                    StatusPill(text = badge, color = bandColor)
                }
            }
            if (!compact && subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = Spacing.m),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = durationLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (!compact && trailingLabel != null) {
                Text(
                    text = trailingLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 空白段（没有记录的时间）。
 *
 * 刻意**不用分类色**：它不属于任何分类，用中性底 + 一句解释（为什么空）就够。
 * 内容与记录段对齐，所以整条轴看起来是连续的，而不是两种风格拼起来的。
 */
@Composable
fun TimelineGapBand(
    startLabel: String,
    durationLabel: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    height: Dp = 48.dp,
    labelColumnWidth: Dp = 52.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = Spacing.s),
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(Shape.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = startLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier
                .width(labelColumnWidth)
                .padding(start = Spacing.s, end = Spacing.xs),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = durationLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}
