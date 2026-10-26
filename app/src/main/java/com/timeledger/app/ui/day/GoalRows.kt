package com.timeledger.app.ui.day

import com.timeledger.app.domain.goal.GoalProgress
import com.timeledger.app.ui.format.DisplayFormat

/**
 * 目标进度 → 界面行。
 *
 * 抽成共用的原因：首页的「今日目标」、日历某天的详情、统计页的「目标完成率」
 * 展示的是同一件事，格式化规则（周期前缀、达成/目标文案、进度比例）必须一致，
 * 各写一遍迟早会出现「首页写 2h20m、统计页写 2.3h」这种不一致。
 *
 * 排序规则：按周期（今日 → 本周 → 本月），同周期内按 id。
 * 不按完成度排序 —— 目标位置固定，用户扫一眼就知道该看哪一行；
 * 按完成度排会让顺序每天都变，反而找不到。
 */
fun List<GoalProgress>.toGoalRows(): List<GoalRow> =
    sortedWith(compareBy({ it.goal.period.ordinal }, { it.goal.id }))
        .map { progress ->
            GoalRow(
                goalId = progress.goal.id,
                title = progress.goal.title,
                periodLabel = DisplayFormat.periodLabel(progress.goal.period),
                achievedLabel = DisplayFormat.goalAchievedLabel(progress.achieved, progress.goal.type),
                // V1.1 P0-6：光看「已用 25m / 目标 1h」还得自己心算差多少，
                // 而用户真正想知道的是「还要再做多久才算完成」。
                // 单位复用 goalAchievedLabel —— 它对 TIME/COUNT/FREQUENCY 各有正确单位，
                // 自己拼「分钟」会在小时类和次数类目标上说错话。
                // 已达成就不加：「还差 0m」是废话，还会挤掉「已达成」的视觉重量。
                targetLabel = DisplayFormat.goalTargetLabel(progress.target, progress.goal.type) +
                    if (!progress.isAchieved && progress.remaining > 0.0) {
                        "  ·  还差 " +
                            DisplayFormat.goalAchievedLabel(progress.remaining, progress.goal.type)
                    } else {
                        ""
                    },
                progressFraction = progress.progressFraction.toFloat(),
                isAchieved = progress.isAchieved,
                achievedValue = progress.achieved,
                goalType = progress.goal.type,
            )
        }
