package com.timeledger.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.timeledger.app.data.local.entity.UsageEventEntity
import kotlinx.coroutines.flow.Flow

/**
 * 系统原始事件 DAO。
 *
 * 写入一律用 `INSERT OR IGNORE` + 唯一 event_key：
 * 不需要「先查存在性再插入」，因此在重复采集、并发重试下都不会产生重复数据，
 * 也不会有先查后写的竞态窗口。
 */
@Dao
interface UsageEventDao {

    /**
     * 批量写入，返回每条是否真正插入成功（-1 表示因幂等键冲突被忽略）。
     * 调用方可用它统计「本次实际新增多少条」。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(entities: List<UsageEventEntity>): List<Long>

    @Query(
        """
        SELECT * FROM usage_events
        WHERE timestamp_millis >= :rangeStartMillis AND timestamp_millis < :rangeEndMillis
        ORDER BY timestamp_millis ASC, id ASC
        """
    )
    suspend fun getInRange(rangeStartMillis: Long, rangeEndMillis: Long): List<UsageEventEntity>

    @Query(
        """
        SELECT * FROM usage_events
        WHERE timestamp_millis >= :rangeStartMillis AND timestamp_millis < :rangeEndMillis
        ORDER BY timestamp_millis ASC, id ASC
        """
    )
    fun observeInRange(rangeStartMillis: Long, rangeEndMillis: Long): Flow<List<UsageEventEntity>>

    /** 增量采集游标：已落库的最新事件时间。 */
    @Query("SELECT MAX(timestamp_millis) FROM usage_events")
    suspend fun getMaxTimestampMillis(): Long?

    @Query("SELECT COUNT(*) FROM usage_events")
    suspend fun count(): Int

    /**
     * 保留策略清理：系统事件量级远大于时间记录（一次前台切换就是两条），
     * 因此允许按时间淘汰已分析过的原始事件。
     * 删除前必须先确保推断结果（TimeRecord）已经落库。
     */
    @Query("DELETE FROM usage_events WHERE timestamp_millis < :beforeMillis")
    suspend fun deleteOlderThan(beforeMillis: Long): Int
}
