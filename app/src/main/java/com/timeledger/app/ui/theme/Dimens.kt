package com.timeledger.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 尺寸与动效 token（模块 27 UI 鲜活化）。
 *
 * 改造前全 App 的间距有 **12 种取值**（16/8/12/4 为主，另有 2/3/6/10/14/32），
 * 圆角只有 2/3/4/6/8dp（都用在细条和小圆点上，**卡片圆角从来没定义过**）。
 * 这两个东西一旦没有刻度，页面之间就会「各自为政」—— 单看每页都不丑，
 * 连起来看就是拼的。
 *
 * ## 圆角
 * 四档，按「这块东西有多大」来选，而不是按好看选：
 * 小控件 10、常规块 14、**卡片 20**、条与点用全圆角。
 * 卡片给到 20 是有意的：圆角越大越「软」，越不像表单。
 *
 * ## 间距
 * 4 的倍数，六个名字，页面只许用这些。
 * `screen = 16`（页面左右边距）、`card = 16`（卡片内边距）、`gap = 12`（卡片之间）、
 * `section = 24`（区块之间）。改一处就能整体调松紧。
 *
 * ## 动效
 * 三档时长 + 一条纪律：**只对「状态变化」做动画，不做装饰动画**。
 * 数值变化 200ms、按下反馈 120ms、页面/日期切换 320ms。
 * 没有无限循环的动画 —— 那是最容易让工具 App 显得廉价的东西。
 */
object Shape {
    /** 小控件：胶囊、小色块、输入框内的标记。 */
    val small = RoundedCornerShape(10.dp)

    /** 常规块：按钮、列表项、时间轴上的段。 */
    val medium = RoundedCornerShape(14.dp)

    /** 卡片：这是全 App 最常出现的圆角。 */
    val large = RoundedCornerShape(20.dp)

    /** 大面板、对话框。 */
    val extraLarge = RoundedCornerShape(28.dp)

    /** 条与点：全圆角。 */
    val full = RoundedCornerShape(percent = 50)
}

internal val AppShapes = Shapes(
    extraSmall = Shape.small,
    small = Shape.small,
    medium = Shape.medium,
    large = Shape.large,
    extraLarge = Shape.extraLarge,
)

/** 间距刻度：只许用这六个。 */
object Spacing {
    /** 4dp：图标与紧邻文字之间。 */
    val xs: Dp = 4.dp

    /** 8dp：同一行内的元素间距。 */
    val s: Dp = 8.dp

    /** 12dp：卡片之间、列表项之间。 */
    val m: Dp = 12.dp

    /** 16dp：页面左右边距、卡片内边距。 */
    val l: Dp = 16.dp

    /** 24dp：区块之间。 */
    val xl: Dp = 24.dp

    /** 32dp：页面顶部的大留白。 */
    val xxl: Dp = 32.dp
}

/** 动效时长与曲线。 */
object Motion {
    /** 120ms：按下、勾选这种「手指底下的反馈」。 */
    const val fast = 120

    /** 200ms：数值变化（环、条、大数字）。 */
    const val base = 200

    /** 320ms：页面切换、日期切换。 */
    const val slow = 320

    /** 标准缓动：快出慢入，符合「东西自己滑过去」的直觉。 */
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 进入用：稍微「过一点」再落定，用于卡片浮现。 */
    val emphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
}
