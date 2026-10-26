package com.timeledger.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.timeledger.app.data.local.entity.PlanBlockEntity
import kotlinx.coroutines.flow.Flow

/**
 * 计划 / 课程表 DAO。
 *
 * 计划条目**允许真删除**（与分类/活动不同）：没有任何历史记录引用它，
 * 删掉不会让已有统计失去口径，产品上「删除课程」也应该立刻消失。
 */
@Dao
interface PlanBlockDao {

    @Query("SELECT * FROM plan_blocks WHERE is_active = 1 ORDER BY start_minute_of_day ASC, id ASC")
    fun observeActive(): Flow<List<PlanBlockEntity>>

    @Query("SELECT * FROM plan_blocks ORDER BY start_minute_of_day ASC, id ASC")
    fun observeAll(): Flow<List<PlanBlockEntity>>

    @Query("SELECT * FROM plan_blocks WHERE id = :id")
    suspend fun getById(id: Long): PlanBlockEntity?

    // 这里刻意**不提供**「按逻辑日取计划」的 SQL 查询。
    //
    // 原因：逻辑日 = 当天 04:00 ~ 次日 03:59，它横跨两个**日历日**。
    // 一个「周二 09:00」的课属于周二那一格，但一个「周三 01:00」的日程其实属于周二那一格。
    // 用 SQL 按 `day_of_week = ? AND date_epoch_day = ?` 去筛，只能匹配一个日历日，
    // 早上的条目必然漏掉或错位；而且 valid_from/valid_to 到底拿哪个日期比也无从判断。
    //
    // 正确的做法是：把计划条目整体取出（课表就几十条，全取完全没压力），
    // 由 PlanBlockExpander 结合时区与 4:00 边界换算后判断归属 —— 那里才是规则唯一的落点。
    // 加一条「看起来能用」的 SQL 只会变成第二个、且是错的那个答案来源。

    @Insert
    suspend fun insert(entity: PlanBlockEntity): Long

    @Update
    suspend fun update(entity: PlanBlockEntity)

    @Delete
    suspend fun delete(entity: PlanBlockEntity)

    @Query("DELETE FROM plan_blocks WHERE id = :id")
    suspend fun deleteById(id: Long)
}
