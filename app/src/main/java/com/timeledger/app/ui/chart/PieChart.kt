package com.timeledger.app.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.timeledger.app.ui.format.DisplayFormat

/**
 * 环形饼图 + 图例 —— 统计页与首页/日历（某一天）共用。
 *
 * ## 为什么手画而不引图表库
 * 需求写着「不要为了图表而图表」，而这里需要的东西非常简单：
 * 一段圆弧就是一次 `drawArc`。每引一个库都要承担版本风险
 * （需求十六节第 14 条），而收益只是省下这三十行。
 *
 * ## 为什么是环形
 * 中间那圈留白用来放「这一共是多长时间」，比在图上到处标注更省地方。
 *
 * ## 为什么两处共用同一份代码
 * 统计页看的是「一段时间」，首页看的是「一天」——**同一件事的两个尺度**。
 * 各写一份的结果必然是「一个用环形、一个用实心」「一个从 12 点开始、
 * 一个从 3 点开始」这类慢慢跑偏的差异，而这台机器看不到界面，跑偏了也发现不了。
 */
data class PieSlice(
    val label: String,
    /** 占比，0~1。所有片加起来应当是 1（不是 1 也不会画错，只是圆环会有缺口）。 */
    val fraction: Float,
    val colorArgb: Int?,
    val durationLabel: String,
)

@Composable
fun DonutChart(
    slices: List<PieSlice>,
    centerTitle: String,
    centerLabel: String,
    modifier: Modifier = Modifier,
    size: Dp = 168.dp,
    thickness: Float = 40f,
) {
    val outline = MaterialTheme.colorScheme.outline
    val surface = MaterialTheme.colorScheme.surface

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val inset = thickness / 2f
            val arcSize = Size(this.size.width - thickness, this.size.height - thickness)

            // 先铺一层底色：片与片之间不留缝，总数不足 100% 时也能看出来「还差多少」
            drawArc(
                color = surface,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = thickness),
            )

            var startAngle = -90f // 从 12 点方向开始，与人的直觉一致
            slices.forEach { slice ->
                val sweep = slice.fraction.coerceIn(0f, 1f) * 360f
                if (sweep <= 0f) return@forEach
                drawArc(
                    color = slice.colorArgb?.let { Color(it) } ?: outline,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = thickness),
                )
                startAngle += sweep
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = centerTitle, style = MaterialTheme.typography.titleMedium)
            Text(
                text = centerLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 图例：颜色 + 名称 + 占比 + 时长。
 *
 * 什么图都得配图例：饼图上认不出哪一片是谁，颜色本身不携带名字。
 * 占比与时长都写出来，是为了让「图」和「数字」永远对得上 ——
 * 只画图不写数字，用户只能猜。
 */
@Composable
fun PieLegend(slices: List<PieSlice>, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outline

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        slices.forEach { slice ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(slice.colorArgb?.let { Color(it) } ?: outline),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = slice.label,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = DisplayFormat.percentLabel(slice.fraction.toDouble()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = slice.durationLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
