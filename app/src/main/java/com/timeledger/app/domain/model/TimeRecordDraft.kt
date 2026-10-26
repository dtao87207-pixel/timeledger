package com.timeledger.app.domain.model

/**
 * 新建/修改时间记录时的入参。
 *
 * 为什么不直接让 UI 构造 [TimeRecord]：
 * 1. [TimeRecord.source] 与 [TimeRecord.confidence] 由**写入路径**决定，
 *    不能由调用方随口指定（用户手动记录不允许自封 SYSTEM，系统记录也不许自称 MANUAL）；
 * 2. 时间范围、分类归属等校验必须发生在仓库层，UI 不该知道这些不变式；
 * 3. [TimeRecord] 的 id / 创建时间 / 更新时间是仓库填写的，草稿里不应该出现。
 *
 * 手动记录与系统记录共用这一个类型，避免出现两个几乎一样的 Draft 类（需求十六节第 7 条）。
 * 两种场景下被忽略的字段：
 * - 手动记录：忽略 [confidence]（强制 1.0）与 [externalKey]（强制 null）；
 * - 系统记录：两者都必须显式给出。
 */
data class TimeRecordDraft(
    val startMillis: Long,
    /** 结束时刻（不含）。允许等于 [startMillis]，表示只记次数的瞬时事件。 */
    val endMillis: Long,
    val categoryId: Long? = null,
    val activityId: Long? = null,
    /** COUNT 类目标的计数，例如本次完成 2 题。 */
    val countValue: Double? = null,
    val note: String? = null,
    /** 仅系统记录使用：置信度 0.0~1.0。 */
    val confidence: Double = 1.0,
    /** 仅系统记录使用：幂等键，防止重复采集写入重复记录。 */
    val externalKey: String? = null,
)

// 刻意不在这里提供 overlaps(...) 一类的重叠判断：
// 写入前的冲突检查必须问数据库（TimeRecordDao.hasOverlappingManualRecord），
// 因为「到底有哪些记录存在」只有库知道。在草稿上再放一份重叠公式，
// 就等于维护两套判定口径，早晚会不一致。
// 需要纯几何意义上的区间运算时，用 core/time 里的 MillisRange。
