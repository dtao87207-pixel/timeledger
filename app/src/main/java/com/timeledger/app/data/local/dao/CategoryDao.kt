package com.timeledger.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.timeledger.app.data.local.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    /** 所有分类（含已归档），按排序权重升序。 */
    @Query("SELECT * FROM categories ORDER BY sort_order ASC, id ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    /** 未归档分类，供选择器使用。 */
    @Query("SELECT * FROM categories WHERE is_archived = 0 ORDER BY sort_order ASC, id ASC")
    fun observeActive(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE name = :name")
    suspend fun getByName(name: String): CategoryEntity?

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: CategoryEntity): Long

    /**
     * 冲突时静默跳过，返回 -1 表示已存在同名分类。
     *
     * 与 [insert] 的区别：种子写入和「按名称确保存在」的场景要的是幂等，
     * 而不是让唯一索引冲突变成一个需要调用方捕获的异常。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: CategoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<CategoryEntity>): List<Long>

    @Update
    suspend fun update(entity: CategoryEntity)

    /**
     * 归档而不是删除：历史 TimeRecord 依赖分类归属，
     * 物理删除会让往年统计出现无法归属的时间。
     */
    @Query("UPDATE categories SET is_archived = 1, updated_at_millis = :updatedAtMillis WHERE id = :id")
    suspend fun archive(id: Long, updatedAtMillis: Long)
}
