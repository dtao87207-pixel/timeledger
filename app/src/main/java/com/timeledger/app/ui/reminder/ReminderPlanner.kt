package com.timeledger.app.ui.reminder

import com.timeledger.app.domain.goal.GoalProgress
import com.timeledger.app.ui.format.DisplayFormat

/**
 * 提醒内容的规划器 —— 纯函数，可完整用单元测试覆盖。
 *
 * ## 这一层的重点其实是「什么时候**不**提醒」
 * 需求十二节要求「提醒必须低打扰，不能强迫用户完成记录」。
 * 一个每天定时响三次的 App，用户三天内就会把通知关掉 —— 而通知一关，
 * 「事后确认」这条闭环就彻底断了。所以每个节点都有明确的**跳过条件**：
 *
 * | 节点 | 提醒 | 跳过条件 |
 * |---|---|---|
 * | 中午 | 确认上午没记录的时间 | 上午不缺东西，也没有待确认记录 |
 * | 晚上 | 看今天没完成的目标 | 目标都达标了，也没有待确认记录 |
 * | 凌晨 | 进入每日复盘 | 这一天完全没有任何数据；且**永远静默** |
 *
 * ## 凌晨那条为什么必须静默
 * 凌晨 4 点响铃会把人吵醒。这既违背「低打扰」，也几乎必然导致卸载。
 * 需求原文也说「4:00 的通知不是强制结算，而是进入每日复盘」——
 * 它是「这一天整理好了」的告知，不是催办，所以只放进通知栏、不响不弹。
 */
class ReminderPlanner {

    fun plan(context: ReminderContext): ReminderPlan = when (context.kind) {
        ReminderKind.NOON -> noon(context)
        ReminderKind.EVENING -> evening(context)
        ReminderKind.REVIEW -> review(context)
    }

    /** 中午：只关心「上午」有没有说不清的时间。 */
    private fun noon(context: ReminderContext): ReminderPlan {
        // 缺口可能横跨中午，只把落在中午之前的那一段算进「上午」。
        // 起点在中午之后的缺口自然会被算成 0，不需要额外判断。
        val morningGapMillis = context.gaps.sumOf { gap ->
            val end = minOf(gap.endMillisExclusive, context.noonMillis)
            (end - gap.startMillis).coerceAtLeast(0L)
        }

        val pendingCount = context.pendingRecordCount
        val hasPending = pendingCount > 0 && context.pendingMillis > 0L

        if (!hasPending && morningGapMillis <= 0L) {
            // 上午记录得挺完整，这时候还去打扰，只会让用户把通知关掉
            return ReminderPlan.skip(context.kind, context.epochDay)
        }

        val parts = buildList {
            if (hasPending) add("$pendingCount 条记录待确认")
            if (morningGapMillis > 0L) {
                add("上午有 ${DisplayFormat.compactDuration(morningGapMillis)} 没记录")
            }
        }

        return ReminderPlan(
            kind = context.kind,
            epochDay = context.epochDay,
            shouldNotify = true,
            title = "上午的时间对一下",
            body = parts.joinToString("，") + "。点开确认一下，十秒就够。",
            silent = false,
        )
    }

    /** 晚上：只报「还没完成的」，做完了就别吭声。 */
    private fun evening(context: ReminderContext): ReminderPlan {
        val unfinished = context.goals.filterNot { it.isAchieved }
        val pendingCount = context.pendingRecordCount
        val hasPending = pendingCount > 0 && context.pendingMillis > 0L

        if (unfinished.isEmpty() && !hasPending) {
            // 该做的都做完了、也都确认过了 —— 这时候提醒只会变成骚扰
            return ReminderPlan.skip(context.kind, context.epochDay)
        }

        val parts = buildList {
            unfinished.take(MAX_GOALS_IN_BODY).forEach { add(remainingLabel(it)) }
            if (unfinished.size > MAX_GOALS_IN_BODY) {
                add("另有 ${unfinished.size - MAX_GOALS_IN_BODY} 个目标")
            }
            if (hasPending) add("$pendingCount 条待确认")
        }

        return ReminderPlan(
            kind = context.kind,
            epochDay = context.epochDay,
            shouldNotify = true,
            title = "今天还有什么没完成",
            // 只列前两个目标：通知栏放不下太多，而且列一长串本身就是在施压
            body = parts.joinToString("、"),
            silent = false,
        )
    }

    /** 凌晨：这一天整理好了，静默告知。 */
    private fun review(context: ReminderContext): ReminderPlan {
        if (!context.hasAnyData) {
            // 这一天完全没有任何数据，没有可复盘的内容
            return ReminderPlan.skip(context.kind, context.epochDay)
        }

        val parts = buildList {
            add("记录 ${DisplayFormat.compactDuration(context.confirmedMillis)}")
            if (context.pendingRecordCount > 0) {
                add("${context.pendingRecordCount} 条待确认")
            }
            if (context.unrecordedMillis > 0L) {
                add("未记录 ${DisplayFormat.compactDuration(context.unrecordedMillis)}")
            }
        }

        return ReminderPlan(
            kind = context.kind,
            epochDay = context.epochDay,
            shouldNotify = true,
            title = "${context.dateLabel} 整理好了",
            body = parts.joinToString("，"),
            silent = true,
        )
    }

    /** `Java 还差 40m`、`LeetCode 还差 1个`、`健身 还差 2次`。 */
    private fun remainingLabel(progress: GoalProgress): String =
        "${progress.goal.title} 还差 " +
            DisplayFormat.goalAchievedLabel(progress.remaining, progress.goal.type)

    private companion object {
        /** 通知正文里最多列几个目标。 */
        const val MAX_GOALS_IN_BODY = 2
    }
}
