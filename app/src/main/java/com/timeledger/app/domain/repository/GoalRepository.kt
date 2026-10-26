package com.timeledger.app.domain.repository

import com.timeledger.app.domain.model.Goal
import kotlinx.coroutines.flow.Flow

/**
 * 目标仓库（需求四节）。
 *
 * 只负责目标的增删改查与生效判断；**完成度计算不在这里**，
 * 因为那需要读取 TimeRecord 并按时长/计数/频次三种口径聚合，
 * 属于目标模块的计算逻辑，放在专门的计算器里以便单独测试。
 */
interface GoalRepository {

    fun observeGoals(activeOnly: Boolean = true): Flow<List<Goal>>

    suspend fun getGoal(id: Long): Goal?

    /** 当前生效的目标（isActive = 1），供首页与复盘计算完成度。 */
    suspend fun getActiveGoals(): List<Goal>

    suspend fun createGoal(goal: Goal): SaveOutcome

    suspend fun updateGoal(goal: Goal): SaveOutcome

    /** 停用而非删除：历史周/月统计仍需要知道当时的周期定义。 */
    suspend fun deactivateGoal(id: Long): Boolean

    /** 真删除，仅用于刚建错的空目标（不参与任何历史统计）。 */
    suspend fun deleteGoal(id: Long): Boolean
}
