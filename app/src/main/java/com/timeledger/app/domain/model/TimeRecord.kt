package com.timeledger.app.domain.model

import com.timeledger.app.core.time.MillisRange

/**
 * 时间记录 / 时间块（需求三节）—— 全 App 的核心数据单位。
 *
 * 语义约定（重要，后续所有模块都依赖这些约定）：
 *
 * 1. **区间左闭右开** `[startMillis, endMillis)`，与 [MillisRange] 完全一致。
 * 2. **存储不拆分，统计按窗口裁剪**（三条容易混淆的规则，分开说清）：
 *    - **存储**：跨 4:00 分界线的记录（例如 01:00–08:00 的睡眠）只存**一条**，
 *      绝不切成两条 —— 切了以后「这一觉到底睡了多久」就再也还原不出来。
 *    - **归属**：[startMillis] 落在哪个逻辑日，这条记录就归那一天（用于展示归属与编辑）。
 *    - **统计**：计算「今日总时长」时必须先把区间**裁剪到当天的 04:00~次日 04:00 窗口**
 *      再累加（`range.intersect(dayWindow)`）。不裁剪就会让某一天的时间超过 24 小时 ——
 *      对一个时间记账产品来说这是不可接受的错误。裁剪天然满足
 *      「同一条记录在各天之间的分摊之和 = 它的总时长」，既不重复也不遗漏。
 * 3. **允许重叠**：手动记录与系统记录可能覆盖同一时段，这是正常状态；
 *    重叠时按 [RecordSource.priority] 消解，见 RecordSource 的说明。
 *    因此「今日总时长」不能把原始记录直接相加，必须在时间轴消解之后统计。
 * 4. **允许零长度**：[endMillis] == [startMillis] 表示瞬时事件，
 *    用于「只记次数」的 COUNT 类目标（例如「今天做了 2 题」而不关心耗时）。
 * 5. 时间一律用毫秒时间戳存储，UI 层才转成小时/分钟（需求十六节第 9 条）。
 */
data class TimeRecord(
    val id: Long = 0L,
    val startMillis: Long,
    /** 结束时刻（不含）。必须 >= [startMillis]。 */
    val endMillis: Long,
    /** 一级分类。可以为空 —— 系统刚采集到、尚未推断出分类时就是空。 */
    val categoryId: Long? = null,
    /** 二级活动。可以为空。非空时其所属分类必须等于 [categoryId]（由 Repository 校验）。 */
    val activityId: Long? = null,
    val source: RecordSource,
    /** 置信度 0.0~1.0。MANUAL 恒为 1.0；系统推测值越低越需要用户确认。 */
    val confidence: Double = 1.0,
    /** COUNT 类目标的计数，例如本次记录完成 2 题；TIME/FREQUENCY 场景为 null。 */
    val countValue: Double? = null,
    val note: String? = null,
    /**
     * 系统采集的幂等键。
     *
     * 为什么需要：UsageStats 同步可能被重复触发（重启、补采、WorkManager 重试），
     * 没有幂等键就会产生重复记录。SYSTEM 来源的记录必须带上它；
     * MANUAL 记录为 null。
     */
    val externalKey: String? = null,
    /** 软删除标记。删除记录不物理删除，便于撤销与排查，也不破坏统计的可解释性。 */
    val isDeleted: Boolean = false,
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
) {

    /** 该记录覆盖的时间区间。 */
    val range: MillisRange get() = MillisRange(startMillis, endMillis)

    /** 时长（毫秒）。 */
    val durationMillis: Long get() = endMillis - startMillis

    /** 是否为瞬时事件（只记了次数、没有时长）。 */
    val isInstant: Boolean get() = endMillis == startMillis

    /** 是否等待用户确认（推测来源一律待确认，需求三节 C）。 */
    val needsConfirmation: Boolean get() = source.needsConfirmation

    /** 用户是否已确认过这条记录（用于「未确认/缺失时间」统计）。 */
    val isConfirmedByUser: Boolean get() = source == RecordSource.MANUAL || source == RecordSource.MERGED

    /** 有效时长：只有正时长的记录才计入时长统计。 */
    val effectiveDurationMillis: Long get() = durationMillis.coerceAtLeast(0L)

    init {
        require(endMillis >= startMillis) {
            "TimeRecord 结束时间不能早于开始时间: start=$startMillis end=$endMillis"
        }
        require(confidence in 0.0..1.0) {
            "置信度必须在 0.0~1.0 之间，实际为 $confidence"
        }
    }
}
