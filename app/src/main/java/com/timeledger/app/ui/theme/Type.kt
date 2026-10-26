package com.timeledger.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 排版层级（模块 27 UI 鲜活化）。
 *
 * ## 为什么重做这一层
 * 改造前全 App 用了 **9 个** Material3 默认层级（bodySmall 37 处、bodyMedium 26 处、
 * labelSmall 23 处……），而默认字号之间的差距很小 —— 结果就是「所有文字权重一样」，
 * 一页看下来找不到重点。
 *
 * 现在收敛成 **6 级**，并直接覆盖 M3 的槽位：既有页面**一行都不用改**就获得层次
 * （因为大家本来就在用 `MaterialTheme.typography.*`），新代码再用 [AppType] 的语义名。
 *
 * | 语义 | M3 槽位 | 用途 |
 * |---|---|---|
 * | `metric` | displaySmall | 一屏最多一个的大数字：`8h32m`、`72%` |
 * | `pageTitle` | headlineMedium | 页面标题：`今天`、`统计` |
 * | `title` | titleLarge | 区块标题 |
 * | `cardTitle` | titleMedium | 卡片标题、时间轴主标题 |
 * | `body` | bodyMedium | 正文、列表内容 |
 * | `bodySmall` | bodySmall | 次要说明（灰） |
 * | `label` | labelMedium | 标签、按钮内文字 |
 * | `caption` | labelSmall | 最小一级：时间戳、单位、脚注 |
 *
 * 字号跨度刻意拉大（34 / 24 / 20 / 16 / 15 / 13 / 13 / 11）：
 * **层次靠对比，不靠加粗**。
 */
private val Default = Typography()

/** 大数字：整个 App 的视觉锚点，一屏只留一个。 */
private val Metric = TextStyle(
    fontWeight = FontWeight.SemiBold,
    fontSize = 34.sp,
    lineHeight = 40.sp,
    letterSpacing = (-0.5).sp,
)

private val PageTitle = TextStyle(
    fontWeight = FontWeight.SemiBold,
    fontSize = 24.sp,
    lineHeight = 30.sp,
    letterSpacing = (-0.2).sp,
)

private val Title = TextStyle(
    fontWeight = FontWeight.SemiBold,
    fontSize = 20.sp,
    lineHeight = 26.sp,
)

private val CardTitle = TextStyle(
    fontWeight = FontWeight.Medium,
    fontSize = 16.sp,
    lineHeight = 22.sp,
    letterSpacing = 0.1.sp,
)

private val Body = TextStyle(
    fontWeight = FontWeight.Normal,
    fontSize = 15.sp,
    lineHeight = 21.sp,
)

private val BodySmall = TextStyle(
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 18.sp,
)

private val Label = TextStyle(
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.2.sp,
)

private val Caption = TextStyle(
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    letterSpacing = 0.3.sp,
)

internal val AppTypography = Typography(
    displaySmall = Metric,
    headlineMedium = PageTitle,
    headlineSmall = Title,
    titleLarge = Title,
    titleMedium = CardTitle,
    titleSmall = CardTitle,
    bodyLarge = Body,
    bodyMedium = Body,
    bodySmall = BodySmall,
    labelLarge = Label,
    labelMedium = Label,
    labelSmall = Caption,
    // 用不到的大字号仍沿用默认，避免出现「比 metric 还大」的意外层级
    displayLarge = Default.displayLarge,
    displayMedium = Default.displayMedium,
    headlineLarge = Default.headlineLarge,
)

/**
 * 语义化的排版入口 —— 新代码用这些名字，读起来能看出「这是几级」。
 *
 * 它们就是上面那几个槽位，所以不存在「两套字号」的风险。
 */
object AppType {
    val metric get() = AppTypography.displaySmall
    val pageTitle get() = AppTypography.headlineMedium
    val title get() = AppTypography.titleLarge
    val cardTitle get() = AppTypography.titleMedium
    val body get() = AppTypography.bodyMedium
    val bodySmall get() = AppTypography.bodySmall
    val label get() = AppTypography.labelMedium
    val caption get() = AppTypography.labelSmall
}
