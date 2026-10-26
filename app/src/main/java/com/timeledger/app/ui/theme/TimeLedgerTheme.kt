package com.timeledger.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * 应用主题（模块 27 UI 鲜活化之后的样子）。
 *
 * 改造前这个文件是全部「设计系统」：49 行、只覆盖 7 个颜色，排版与形状全用
 * Material3 默认值。现在它只做一件事 —— **把 token 装进 MaterialTheme**：
 *
 * - [LightScheme] / [DarkScheme]：清晨蓝，中性色分三层（页面/卡片/浮起）
 * - [AppTypography]：收敛成 6 级，直接覆盖 M3 槽位，既有页面不用改一行就获得层次
 * - [AppShapes]：圆角四档，卡片统一到 20dp
 *
 * 间距与动效不能用 MaterialTheme 承载（M3 没有这两个槽位），所以它们是
 * [Spacing] / [Motion] 两个对象，用的时候直接引 —— 少了些魔法，多了些明确。
 *
 * 仍然**不启用** Material You 动态取色：分类色来自数据库，整体色板随壁纸变化会撞色。
 */
@Composable
fun TimeLedgerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
