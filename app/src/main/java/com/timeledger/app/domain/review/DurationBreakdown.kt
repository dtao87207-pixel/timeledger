package com.timeledger.app.domain.review

import com.timeledger.app.core.time.MillisRange

/**
 * 一条聚合结果：某个分类（或某个活动）在统计窗口内的时长。
 *
 * [categoryId] 与 [activityId] 的用法：
 * - 分类聚合：填 [categoryId]，[activityId] 为 null；
 * - 活动聚合：两者都填 —— 带上所属分类是为了让界面能直接按分类分组显示，
 *   不需要再回表查一次归属。
 *
 * 允许 [categoryId] 为 null：系统刚采集到、还没推断出分类的记录就是这种状态
 * （需求七节：系统数据只代表「手机发生了什么」）。
 * 把它单独成一桶而不是丢掉，是为了保证「各分类时长之和 = 已确认总时长」这个等式成立，
 * 否则用户会看到一堆数字加不起来。
 */
data class DurationBucket(
    val categoryId: Long?,
    val activityId: Long?,
    val durationMillis: Long,
)

/**
 * 某个统计窗口内的时长分布 —— 每日复盘与统计分析共用的聚合结果。
 *
 * ## 四个时长字段的区别（不能混）
 * - [coveredMillis]：窗口内**有记录覆盖**的总时长（含未确认的推测）。
 * - [pendingMillis]：其中来自 `SUGGESTED`（系统猜的、还没经用户确认）的部分。
 * - [confirmedMillis]：可以信任的部分，等于 `covered - pending`。
 *   分类/活动分布统计的就是它 —— 未确认的推测不换成绩（与目标系统口径一致）。
 * - [unrecordedMillis]：窗口内**完全没有记录**的时长。
 *
 * 恒等式（测试逐条验证）：
 * `confirmedMillis + pendingMillis == coveredMillis`
 * `coveredMillis + unrecordedMillis == window.durationMillis`
 * `byCategory.sumOf { durationMillis } == confirmedMillis`
 */
data class DurationBreakdown(
    val window: MillisRange,
    val coveredMillis: Long,
    val confirmedMillis: Long,
    val pendingMillis: Long,
    val unrecordedMillis: Long,
    /** 已确认时长按分类聚合，时长降序（并列时按 id 升序，保证渲染顺序稳定）。 */
    val byCategory: List<DurationBucket>,
    /** 已确认时长按活动聚合，时长降序。 */
    val byActivity: List<DurationBucket>,
    /**
     * 窗口内**没有记录**的时间段，按时间升序、互不重叠。
     *
     * 这是需求三节 C「事后确认」的输入：系统只要求用户确认这些缺口
     * （例如「15:30–16:00 做了什么？」），而不是让用户重新记录一整天。
     *
     * 这里返回的是**原始缺口**，不做「太短就不问」之类的过滤 ——
     * 那个阈值属于确认流程的业务规则，放在事后确认模块里决定，
     * 本类只负责如实报告哪些时间没数据。
     */
    val unrecordedSegments: List<MillisRange>,
) {

    /** 某个分类的已确认时长；[categoryId] 传 null 表示「未分类」那一桶。 */
    fun durationOfCategory(categoryId: Long?): Long =
        byCategory.firstOrNull { it.categoryId == categoryId }?.durationMillis ?: 0L

    /** 某个活动的已确认时长，不区分所属分类。 */
    fun durationOfActivity(activityId: Long): Long =
        byActivity.filter { it.activityId == activityId }.sumOf { it.durationMillis }

    /** 是否整天没有任何记录。 */
    val isEntirelyEmpty: Boolean get() = coveredMillis == 0L
}
