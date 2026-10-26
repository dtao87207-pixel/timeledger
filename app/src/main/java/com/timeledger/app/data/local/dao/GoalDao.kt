package com.timeledger.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.timeledger.app.data.local.entity.GoalEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GoalDao {

    @Query("SELECT * FROM goals ORDER BY id ASC")
    fun observeAll(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals WHERE is_active = 1 ORDER BY id ASC")
    fun observeActive(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals WHERE id = :id")
    suspend fun getById(id: Long): GoalEntity?

    @Query("SELECT * FROM goals WHERE is_active = 1")
    suspend fun getActive(): List<GoalEntity>

    @Insert
    suspend fun insert(entity: GoalEntity): Long

    @Update
    suspend fun update(entity: GoalEntity)

    @Query("UPDATE goals SET is_active = 0, updated_at_millis = :updatedAtMillis WHERE id = :id")
    suspend fun deactivate(id: Long, updatedAtMillis: Long)

    /** 真删除：目标不被任何历史记录引用，删掉不会破坏统计口径。 */
    @Query("DELETE FROM goals WHERE id = :id")
    suspend fun deleteById(id: Long)
}
