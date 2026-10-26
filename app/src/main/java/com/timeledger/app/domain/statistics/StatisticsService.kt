package com.timeledger.app.domain.statistics

import com.timeledger.app.domain.repository.TimeRecordRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 统计数据的读取入口（用例层）。
 *
 * 职责很窄：算出该查哪一段，取数，交给 [StatisticsCalculator] 现算。
 * 与 [com.timeledger.app.domain.goal.GoalProgressService] 的分工一致 ——
 * 「窗口怎么算」这件事只在用例层出现一次，界面层不需要知道周从哪天开始。
 *
 * ## 目标完成率与目标趋势不在这里
 * 它们由 [com.timeledger.app.domain.goal.GoalProgressService] 提供：
 * 目标的周期窗口（日/周/月）与目标的绑定关系都属于目标领域，
 * 放到统计里会变成第二套「目标怎么算完成度」的实现。
 * 界面同时订阅两个 Flow 即可，两个服务的口径来源不同但互不重叠。
 */
class StatisticsService(
    private val timeRecordRepository: TimeRecordRepository,
    private val calculator: StatisticsCalculator,
) {

    /**
     * 观察 [epochDay] 所在的周 / 月 / 年统计。
     *
     * `epochDay` 是「落在这个区间里的任意一天」，不要求是区间的第一天 ——
     * 首页想看「本周」时直接传今天即可。
     */
    fun observeStatistics(
        period: StatisticsPeriod,
        epochDay: Long,
    ): Flow<RangeStatistics> {
        // 窗口只算一次：它由时区与日历决定，不会在订阅期间变化。
        val window = calculator.windowFor(period, epochDay)
        return timeRecordRepository.observeRecordsIn(window)
            .map { records -> calculator.compute(records, period, epochDay) }
    }
}
