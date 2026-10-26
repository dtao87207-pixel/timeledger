package com.timeledger.app.domain.goal

import com.timeledger.app.core.time.TimeBoundary
import com.timeledger.app.domain.repository.GoalRepository
import com.timeledger.app.domain.repository.TimeRecordRepository
import com.timeledger.app.domain.timeline.TimelineResolver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * 目标进度的读取入口（用例层）。
 *
 * 它的职责只有一件事：**替调用方算出「该查哪一段时间」**，然后把结果交给
 * [GoalProgressCalculator] 现算。
 *
 * 为什么这件事值得单独一层：首页要同时显示「今日目标」和「本周目标进度」，
 * 而周目标的窗口是周一到下周一 —— 只查「今天」的记录会漏掉周一的数据，
 * 而且每个目标各查一次数据库纯属浪费。
 * 窗口并集的计算放在这里，界面层就不需要知道「周目标的窗口有多宽」这种细节，
 * 也就不会各自实现一套、各漏各的数据。
 *
 * ## 为什么完全用 Flow 而不返回一次性结果
 * 记录随时可能被用户修改或由系统补采，进度必须跟着变。
 * 这里没有做任何「结果缓存」：每次上游变化都重新现算，
 * 与需求十三节「避免修改记录后统计数据过期」一致。
 */
class GoalProgressService(
    private val goalRepository: GoalRepository,
    private val timeRecordRepository: TimeRecordRepository,
    private val calculator: GoalProgressCalculator,
) {

    /**
     * 观察 [epochDay] 这一天的所有生效目标进度。
     *
     * 首页的两块内容都由它提供：
     * - 「今日目标」= 返回列表里 [com.timeledger.app.domain.model.GoalPeriod.DAY] 的那些；
     * - 「本周目标进度」= 返回列表里 [com.timeledger.app.domain.model.GoalPeriod.WEEK] 的那些
     *   （窗口自动是本周一到下周一，不需要额外接口）。
     *
     * 关于「今天」是谁：由调用方传入。跨过 04:00 时如何自动切到新的一天，
     * 属于界面层的刷新节奏问题（需要一个定时器），不在本层职责内 ——
     * 这样本类保持纯粹，测试里可以任意指定日期。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeDayProgress(epochDay: Long): Flow<List<GoalProgress>> =
        goalRepository.observeGoals(activeOnly = true).flatMapLatest { goals ->
            val union = calculator.windowUnion(goals, epochDay)
            if (union == null) {
                // 没有任何适用目标：不去查记录，直接给空结果。
                flowOf(emptyList())
            } else {
                timeRecordRepository.observeRecordsIn(union).map { records ->
                    calculator.calculateAll(goals, records, epochDay)
                }
            }
        }

    /**
     * 观察一段日期范围内**每一天**的目标进度。
     *
     * 用于日历视图（点某一天看当天目标完成情况）与统计里的「目标趋势」。
     *
     * 两个实现要点：
     * 1. 记录只查一次 —— 取所有目标在所有日期上窗口的并集，避免按天各查一遍；
     * 2. 时间轴只消解一次 —— 一个月 31 天共用同一条时间轴，
     *    保证同一条记录不会因为分天计算而在两天里得出不同的胜负。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeRangeProgress(
        fromEpochDay: Long,
        toEpochDay: Long,
    ): Flow<List<DailyGoalProgress>> {
        require(toEpochDay >= fromEpochDay) { "结束日期不能早于开始日期" }
        return goalRepository.observeGoals(activeOnly = true).flatMapLatest { goals ->
            val union = calculator.windowUnion(goals, fromEpochDay, toEpochDay)
            if (union == null) {
                flowOf(emptyList())
            } else {
                timeRecordRepository.observeRecordsIn(union).map { records ->
                    val timeline = TimelineResolver.resolve(records)
                    TimeBoundary.epochDaysIn(fromEpochDay, toEpochDay).map { day ->
                        DailyGoalProgress(
                            epochDay = day,
                            progress = calculator.calculateAll(goals, timeline, day),
                        )
                    }
                }
            }
        }
    }
}
