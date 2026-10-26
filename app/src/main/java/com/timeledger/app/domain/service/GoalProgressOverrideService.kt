package com.timeledger.app.domain.service

import com.timeledger.app.data.local.dao.GoalProgressOverrideDao
import com.timeledger.app.data.local.entity.toDomain
import com.timeledger.app.data.local.entity.toEntity
import com.timeledger.app.domain.model.GoalProgressOverride
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 目标进度覆盖服务，允许手动调整目标进度。
 */
class GoalProgressOverrideService(
    private val dao: GoalProgressOverrideDao
) {
    
    /**
     * 获取特定目标和日期的手动覆盖进度
     */
    suspend fun getOverride(goalId: Long, epochDay: Long): GoalProgressOverride? {
        return dao.findByGoalAndDay(goalId, epochDay)?.toDomain()
    }
    
    /**
     * 保存目标进度的覆盖值
     */
    suspend fun saveOverride(override: GoalProgressOverride): Long {
        return if (override.id == 0L) {
            dao.insert(override.toEntity())
        } else {
            dao.update(override.toEntity())
            override.id
        }
    }
    
    /**
     * 删除目标进度的覆盖值
     */
    suspend fun removeOverride(id: Long) {
        dao.softDelete(id)
    }
    
    /**
     * 观察特定目标的所有覆盖记录
     */
    fun observeOverridesByGoal(goalId: Long): Flow<List<GoalProgressOverride>> {
        return dao.observeByGoal(goalId).map { entities -> entities.map { it.toDomain() } }
    }
    
    /**
     * 观察所有覆盖记录
     */
    fun observeAllOverrides(): Flow<List<GoalProgressOverride>> {
        return dao.observeAll().map { entities -> entities.map { it.toDomain() } }
    }
}