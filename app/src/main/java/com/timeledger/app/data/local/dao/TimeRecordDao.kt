package com.timeledger.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.timeledger.app.data.local.entity.TimeRecordEntity
import kotlinx.coroutines.flow.Flow

/**
 * 时间记录 DAO。
 *
 * ## 区间查询的语义（全项目统一，改这里之前先读清楚）
 *
 * 取「与 `[rangeStart, rangeEnd)` 有关的记录」，包含两类：
 * 1. **有长度的记录**：与区间有真实重叠，即 `start < rangeEnd AND end > rangeStart`。
 * 2. **零长度记录（瞬时事件）**：它只代表一个时间点 `T`，
 *    只要 `rangeStart <= T < rangeEnd` 就应该被算进来。
 *
 * 只用重叠条件会漏掉恰好落在区间起点上的瞬时事件
 * （`end > rangeStart` 对 `end == start == rangeStart` 不成立），
 * 所以 SQL 里显式补了第二种情况，而不是在 Kotlin 侧补救。
 *
 * 跨 4:00 逻辑日的记录不会被这里切开：本 DAO 只按绝对时间查，
 * 「一条记录整体归属哪个逻辑日」由 TimeBoundary 在调用方决定。
 */
@Dao
interface TimeRecordDao {

    @Query(
        """
        SELECT * FROM time_records
        WHERE is_deleted = 0
          AND start_time_millis < :rangeEndMillis
          AND (
                end_time_millis > :rangeStartMillis
                OR (end_time_millis = start_time_millis AND start_time_millis >= :rangeStartMillis)
              )
        ORDER BY start_time_millis ASC, id ASC
        """
    )
    fun observeInRange(rangeStartMillis: Long, rangeEndMillis: Long): Flow<List<TimeRecordEntity>>

    @Query(
        """
        SELECT * FROM time_records
        WHERE is_deleted = 0
          AND start_time_millis < :rangeEndMillis
          AND (
                end_time_millis > :rangeStartMillis
                OR (end_time_millis = start_time_millis AND start_time_millis >= :rangeStartMillis)
              )
        ORDER BY start_time_millis ASC, id ASC
        """
    )
    suspend fun getInRange(rangeStartMillis: Long, rangeEndMillis: Long): List<TimeRecordEntity>

    @Query("SELECT * FROM time_records WHERE id = :id")
    suspend fun getById(id: Long): TimeRecordEntity?

    /**
     * 按幂等键查记录，**包括已软删除的行**。
     *
     * 为什么必须包含软删除的行：软删除只是把 is_deleted 置 1，
     * 那条行的 external_key 仍然占着唯一索引。如果这里过滤掉已删除的行，
     * 「重新分析某一天」就会变成：先软删除旧的推测记录，再用同样的幂等键插入，
     * 结果撞唯一索引被静默忽略 —— 新的分析结果一条都写不进去。
     * 因此调用方需要拿到这条行，把「复活并覆盖」和「真正重复」区分开。
     */
    @Query("SELECT * FROM time_records WHERE external_key = :externalKey LIMIT 1")
    suspend fun getByExternalKeyIncludingDeleted(externalKey: String): TimeRecordEntity?

    /**
     * 找出与该区间重叠的**用户手动记录**。
     *
     * 这是「手动记录不能被系统自动覆盖」这条产品原则的数据层落地点：
     * 写入系统/推测记录前先查一次，命中就不允许覆盖这一段。
     */
    @Query(
        """
        SELECT * FROM time_records
        WHERE is_deleted = 0
          AND source = 'MANUAL'
          AND start_time_millis < :endMillis
          AND end_time_millis > :startMillis
        ORDER BY start_time_millis ASC
        """
    )
    suspend fun getOverlappingManualRecords(startMillis: Long, endMillis: Long): List<TimeRecordEntity>

    /** 判断区间内是否已经有手动记录，供写入保护做快速判断。 */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM time_records
            WHERE is_deleted = 0
              AND source = 'MANUAL'
              AND start_time_millis < :endMillis
              AND end_time_millis > :startMillis
        )
        """
    )
    suspend fun hasOverlappingManualRecord(startMillis: Long, endMillis: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: TimeRecordEntity): Long

    /** 系统采集用：外部幂等键冲突时静默跳过，避免重复采集产生重复记录。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: TimeRecordEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(entities: List<TimeRecordEntity>): List<Long>

    @Update
    suspend fun update(entity: TimeRecordEntity)

    @Query("UPDATE time_records SET is_deleted = 1, updated_at_millis = :updatedAtMillis WHERE id = :id")
    suspend fun softDelete(id: Long, updatedAtMillis: Long)

    /**
     * 软删除**起始时刻落在区间内**的系统/推测记录，供重新分析某一天时「先清空再生成」。
     *
     * 手工记录（MANUAL）与用户确认过的记录（MERGED）不在删除范围内 ——
     * 这是产品硬要求，不是实现细节，因此写死在 SQL 里而不是靠调用方自觉。
     *
     * ## 为什么判据是「起始时刻」而不是「时间段重叠」
     * 系统记录永远是**裁剪到当天窗口**的（见 `ScreenOffPeriods` 与 `ForegroundSessionBuilder`），
     * 所以两种判据本该等价 —— 除了一个例外：一条**结束时刻正好等于区间起点**的记录，
     * 例如熄屏时段 `[00:43, 04:00]` 与次日窗口 `[04:00, …]`，会被「重叠」判据命中，
     * 于是分析次日时把**前一天**的记录删掉。
     *
     * 真机上踩到了：某夜 `00:43–08:20` 的熄屏被切成两天两段，
     * 属于前一天的那段每分析一次次日就消失一次。
     * 改成「起始时刻在区间内」之后，清理范围与全局的「按开始时刻归属」规则完全一致。
     */
    @Query(
        """
        UPDATE time_records
        SET is_deleted = 1, updated_at_millis = :updatedAtMillis
        WHERE is_deleted = 0
          AND source IN ('SYSTEM', 'SUGGESTED')
          AND start_time_millis >= :rangeStartMillis
          AND start_time_millis < :rangeEndMillis
        """
    )
    suspend fun softDeleteSystemGeneratedStartingIn(
        rangeStartMillis: Long,
        rangeEndMillis: Long,
        updatedAtMillis: Long,
    ): Int

    /** 增量采集游标：已落库的最大事件时间。 */
    @Query("SELECT MAX(start_time_millis) FROM time_records WHERE is_deleted = 0")
    suspend fun getMaxStartMillis(): Long?
}
