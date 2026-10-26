package com.timeledger.app.domain.repository

import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.model.RecordSource
import com.timeledger.app.domain.model.TimeRecord
import com.timeledger.app.domain.model.TimeRecordDraft
import kotlinx.coroutines.flow.Flow

/**
 * 时间记录仓库 —— 数据层最重要的接口。
 *
 * ## 两条不可违背的产品规则，都在这里落地
 * 1. **手动记录优先**：任何系统写入都不得覆盖用户手动记录覆盖的时段（需求三节优先级）。
 *    [saveSystemRecord] 命中手动记录时返回 [RejectReason.OVERLAPS_MANUAL_RECORD]，
 *    [replaceSystemGeneratedIn] 的清理范围也永远不含 MANUAL / MERGED。
 * 2. **统计永远现算**：本仓库只提供原始记录与区间查询，
 *    不提供任何「今日总时长」之类的缓存字段，避免修改记录后统计过期（需求十三节）。
 *
 * ## 逻辑日 vs 绝对时间
 * 区间查询一律用绝对时间 [MillisRange]；以「天/周/月」为单位的便捷方法
 * 由实现类借助 [com.timeledger.app.core.time.DayBoundary] 换算，
 * 保证 UI 不会各自实现一套边界算法。
 */
interface TimeRecordRepository {

    /** 观察与给定区间有关的记录（含重叠、含区间内的瞬时事件）。 */
    fun observeRecordsIn(range: MillisRange): Flow<List<TimeRecord>>

    /** 观察某个逻辑日的全部记录（边界由 DayBoundary 统一决定）。 */
    fun observeRecordsOfDay(epochDay: Long): Flow<List<TimeRecord>>

    suspend fun getRecordsIn(range: MillisRange): List<TimeRecord>

    suspend fun getRecord(id: Long): TimeRecord?

    /** 与该区间重叠的用户手动记录。UI 用它解释「为什么系统没有覆盖这段」。 */
    suspend fun getOverlappingManualRecords(range: MillisRange): List<TimeRecord>

    /**
     * 新建用户手动记录。来源固定为 [RecordSource.MANUAL]，置信度固定 1.0。
     * 会校验时间范围、分类/活动是否存在、活动归属是否与分类一致。
     */
    suspend fun createManualRecord(draft: TimeRecordDraft): SaveOutcome

    /**
     * 修改一条记录（含把系统/推测记录改成用户确认的结果）。
     *
     * 用户确认后来源会变成 [RecordSource.MERGED]（需求三节：
     * MERGED 表示用户确认后的记录），但已经是 MANUAL 的记录保持 MANUAL 不被降级。
     */
    suspend fun updateRecord(id: Long, draft: TimeRecordDraft): SaveOutcome

    /** 软删除。返回 false 表示记录不存在。 */
    suspend fun deleteRecord(id: Long): Boolean

    /**
     * 写入一条系统来源的记录。
     *
     * - [source] 必须是 [RecordSource.SYSTEM] 或 [RecordSource.SUGGESTED]；
     * - 必须提供 [TimeRecordDraft.externalKey]，否则重复采集会写入重复数据；
     * - 幂等键命中已有记录时返回 [SaveOutcome.Skipped]；
     * - 与手动记录重叠时返回 [SaveOutcome.Rejected]。
     */
    suspend fun saveSystemRecord(draft: TimeRecordDraft, source: RecordSource): SaveOutcome

    /**
     * 只清空区间内的系统/推测记录，**不写入**（重新分析某一天的第一步）。
     *
     * 为什么不能直接用 [replaceSystemGeneratedIn] 分两次调用（先 SYSTEM 后 SUGGESTED）：
     * 那个方法清空的是**区间内所有**系统/推测记录，第二次调用会把第一次刚写进去的结果
     * 一起清掉。而一次分析同时会产出两种来源的记录（用户确认过的 App → SYSTEM，
     * 没确认过的 → SUGGESTED），所以「先清空、再逐条按各自来源写入」才是正确的顺序。
     *
     * 手动记录与用户确认过的记录（MERGED）永远不在清理范围内 —— 这条规则写在 SQL 里。
     * 返回实际软删除的条数。
     */
    suspend fun clearSystemGeneratedIn(range: MillisRange): Int

    /**
     * 清空区间内的系统/推测记录后写入新的分析结果，用于「重新分析这一天」。
     *
     * 返回实际写入成功的条数。手动记录与已确认记录不受影响。
     */
    suspend fun replaceSystemGeneratedIn(
        range: MillisRange,
        drafts: List<TimeRecordDraft>,
        source: RecordSource,
    ): Int
}
