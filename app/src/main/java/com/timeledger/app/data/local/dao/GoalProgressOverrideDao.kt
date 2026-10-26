package com.timeledger.app.data.local.dao

import androidx.room.*
import com.timeledger.app.data.local.entity.GoalProgressOverrideEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GoalProgressOverrideDao {
    
    @Query("SELECT * FROM goal_progress_overrides WHERE goal_id = :goalId AND epoch_day = :epochDay AND is_deleted = 0 LIMIT 1")
    suspend fun findByGoalAndDay(goalId: Long, epochDay: Long): GoalProgressOverrideEntity?
    
    @Query("SELECT * FROM goal_progress_overrides WHERE epoch_day = :epochDay AND is_deleted = 0")
    suspend fun findByDay(epochDay: Long): List<GoalProgressOverrideEntity>
    
    @Query("SELECT * FROM goal_progress_overrides WHERE is_deleted = 0")
    fun observeAll(): Flow<List<GoalProgressOverrideEntity>>
    
    @Query("SELECT * FROM goal_progress_overrides WHERE goal_id = :goalId AND is_deleted = 0")
    fun observeByGoal(goalId: Long): Flow<List<GoalProgressOverrideEntity>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(override: GoalProgressOverrideEntity): Long
    
    @Update
    suspend fun update(override: GoalProgressOverrideEntity)
    
    @Query("UPDATE goal_progress_overrides SET is_deleted = 1, updated_at = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: Long, updatedAt: Long = System.currentTimeMillis())
    
    @Query("DELETE FROM goal_progress_overrides WHERE is_deleted = 1")
    suspend fun purgeDeleted()
}