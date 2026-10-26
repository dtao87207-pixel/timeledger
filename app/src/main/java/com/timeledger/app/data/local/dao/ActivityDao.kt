package com.timeledger.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.timeledger.app.data.local.entity.ActivityEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityDao {

    @Query("SELECT * FROM activities ORDER BY category_id ASC, sort_order ASC, id ASC")
    fun observeAll(): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE is_archived = 0 ORDER BY category_id ASC, sort_order ASC, id ASC")
    fun observeActive(): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE category_id = :categoryId AND is_archived = 0 ORDER BY sort_order ASC, id ASC")
    fun observeActiveByCategory(categoryId: Long): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE id = :id")
    suspend fun getById(id: Long): ActivityEntity?

    @Query("SELECT * FROM activities WHERE category_id = :categoryId AND name = :name")
    suspend fun getByCategoryAndName(categoryId: Long, name: String): ActivityEntity?

    @Query("SELECT COUNT(*) FROM activities")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ActivityEntity): Long

    /** 冲突时静默跳过，返回 -1 表示同一分类下已有同名活动。语义同 CategoryDao.insertIgnore。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: ActivityEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<ActivityEntity>): List<Long>

    @Update
    suspend fun update(entity: ActivityEntity)

    @Query("UPDATE activities SET is_archived = 1, updated_at_millis = :updatedAtMillis WHERE id = :id")
    suspend fun archive(id: Long, updatedAtMillis: Long)
}
