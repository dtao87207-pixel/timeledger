package com.timeledger.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.timeledger.app.data.local.entity.AppMappingEntity
import kotlinx.coroutines.flow.Flow

/**
 * App 映射 DAO。
 *
 * 用 `package_name` 唯一索引 + 「先查再写」而不是 `INSERT OR REPLACE`：
 * REPLACE 会先删后插，导致主键变化、并可能连带触发级联，
 * 而且会把用户确认过的映射静默覆盖成默认值。
 * 「是否允许覆盖」是业务规则，必须留在 Repository 里显式判断。
 */
@Dao
interface AppMappingDao {

    @Query("SELECT * FROM app_mappings ORDER BY package_name ASC")
    fun observeAll(): Flow<List<AppMappingEntity>>

    @Query("SELECT * FROM app_mappings WHERE package_name = :packageName LIMIT 1")
    suspend fun getByPackageName(packageName: String): AppMappingEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: AppMappingEntity): Long

    @Update
    suspend fun update(entity: AppMappingEntity)

    /**
     * 仍未确定归属的映射数量（分类和活动都为空），用于提示用户去处理。
     *
     * 注意：这里只统计**已经有映射行**但归属为空的 App。
     * 从没见过的 App 连行都没有，要等系统采集时通过 ensureDefaultMapping 建行，
     * 不属于「等待用户确认」的范畴。
     */
    @Query("SELECT COUNT(*) FROM app_mappings WHERE category_id IS NULL AND activity_id IS NULL")
    suspend fun countUnresolved(): Int
}
