package com.timeledger.app.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.timeledger.app.ui.theme.Shape
import com.timeledger.app.ui.theme.Spacing

/**
 * 卡片 —— 全 App 唯一的「一块内容」容器（模块 27）。
 *
 * ## 为什么不用 Material3 的 Card
 * 改造前全 App 有 27 个 `OutlinedCard`：一律描边、一律 12dp 圆角、一律同样的分量。
 * 每一块长得一样重，页面读起来像表单。这里给出**三种有语义的分量**：
 *
 * | 变体 | 用法 | 长相 |
 * |---|---|---|
 * | [AppCardVariant.Plain] | 大多数卡片（列表、区块） | 暖白底、无边框、无阴影 |
 * | [AppCardVariant.Elevated] | 页面唯一的重点（首页 hero） | 更亮一档的底 |
 * | [AppCardVariant.Tinted] | 与某个分类/状态相关 | 12% 语义色底 + 一条 4dp 满色侧边带 |
 *
 * ## Tinted 为什么是「淡底 + 侧带」而不是「整块染色」
 * 分类色来自数据库，饱和度不受我们控制。整块刷满会打架；把颜色压到 12% 当底、
 * 另给一条满饱和度的带做身份，既分得清又不吵 —— 这就是
 * 「分类色只做点睛，不做填充」那条原则的落点。
 */
enum class AppCardVariant { Plain, Elevated, Tinted }

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    variant: AppCardVariant = AppCardVariant.Plain,
    /** 仅 [AppCardVariant.Tinted] 用：分类色或状态色。 */
    accent: Color? = null,
    contentPadding: PaddingValues = PaddingValues(Spacing.l),
    content: @Composable ColumnScope.() -> Unit,
) {
    val container = when (variant) {
        AppCardVariant.Plain -> MaterialTheme.colorScheme.surface
        AppCardVariant.Elevated -> MaterialTheme.colorScheme.surfaceContainerLowest
        AppCardVariant.Tinted -> (accent ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.12f)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Shape.large)
            .background(container)
            // 让侧边带能撑满卡片高度（否则只能在 Row 里居中一条短杠）
            .height(IntrinsicSize.Min),
    ) {
        if (variant == AppCardVariant.Tinted && accent != null) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accent),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
            content = content,
        )
    }
}

/**
 * 区块标题 —— 取代原来的 `SectionTitle`。
 *
 * 左右两栏：标题在左，动作在右，**动作永远比标题轻**（labelMedium + 主色）。
 * 改造前「管理」「课表」这些入口和标题一样大，视线会被牵着走。
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = Spacing.s, vertical = 0.dp),
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * 状态胶囊 —— 「待确认」「已达成」「5 段」这类小标记。
 *
 * 底是 14% 的语义色、字是同色的深版，所以它**看得见但不抢戏**。
 */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Surface(
        modifier = modifier,
        shape = Shape.full,
        color = color.copy(alpha = 0.14f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = Spacing.s, vertical = 2.dp),
        )
    }
}

/**
 * 大数字卡 —— 一屏最多一个的视觉锚点。
 *
 * 三级同时出现才叫层次：[value] 用 metric 级、[unit] 跟在后面用 bodySmall、
 * [caption] 退到最弱。改造前这三行几乎一样重，「今天过得怎么样」需要用户自己读，
 * 而不是一眼看出来。
 */
@Composable
fun MetricCard(
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    variant: AppCardVariant = AppCardVariant.Elevated,
    accent: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    AppCard(modifier = modifier, variant = variant, accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (unit != null) {
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            text = unit,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            trailing?.invoke()
        }
    }
}
