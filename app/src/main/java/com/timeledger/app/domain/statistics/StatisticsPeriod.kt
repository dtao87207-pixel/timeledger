package com.timeledger.app.domain.statistics

/**
 * 统计分析的时间范围（需求十三节）。
 *
 * **刻意没有 DAY**：单日统计已经由
 * [com.timeledger.app.domain.review.DayReview] 提供（总时长、分类、Activity、目标完成率、
 * 计划 vs 实际、未确认与缺口）。再加一个 `DAY` 只会产生第二套「一天怎么统计」的实现，
 * 而两套实现迟早会对不上。日视图用 DayReview，周/月/年视图用这里的统计。
 *
 * [YEAR] 在需求里只要求「基础统计」，因此与月统计共用同一套口径，不另做特殊处理 ——
 * 数据量（一年几千条记录）完全撑得住。
 */
enum class StatisticsPeriod {
    WEEK,
    MONTH,
    YEAR;

    companion object {
        fun fromNameOrNull(value: String?): StatisticsPeriod? =
            value?.let { name -> entries.firstOrNull { it.name == name } }
    }
}
