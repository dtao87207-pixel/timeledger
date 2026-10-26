package com.timeledger.app.domain.service

import com.timeledger.app.domain.goal.GoalProgressCalculator
import com.timeledger.app.domain.goal.GoalProgress
import com.timeledger.app.domain.model.Goal
import com.timeledger.app.domain.model.TimeRecord
import com.timeledger.app.domain.repository.GoalRepository
import com.timeledger.app.domain.repository.TimeRecordRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * 增强版目标进度服务，支持手动覆盖功能。
 */
class EnhancedGoalProgressService(
    private val goalRepository: GoalRepository,
    private val timeRecordRepository: TimeRecordRepository,
    private val calculator: GoalProgressCalculator,
    private val overrideService: GoalProgressOverrideService,
) {

    /**
     * 观察 [epochDay] 这一天的所有生效目标进度，支持手动覆盖。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeDayProgressWithOverride(epochDay: Long): Flow<List<GoalProgress>> =
        goalRepository.observeGoals(activeOnly = true).flatMapLatest { goals ->
            val union = calculator.windowUnion(goals, epochDay)
            if (union == null) {
                flowOf(emptyList())
            } else {
                timeRecordRepository.observeRecordsIn(union).flatMapLatest { records ->
                    overrideService.observeAllOverrides().map { overrides ->
                        val overrideMap = overrides.associateBy { Pair(it.goalId, it.epochDay) }
                        goals.mapNotNull { goal ->
                            val override = overrideMap[Pair(goal.id, epochDay)]
                            val manualOverride = override?.achievedValue
                            calculator.calculateWithOverride(goal, records, epochDay, manualOverride)
                        }
                    }
                }
            }
        }

    /**
     * 设置目标进度的覆盖值
     */
    suspend fun setManualOverride(goalId: Long, epochDay: Long, achievedValue: Double) {
        val existingOverride = overrideService.getOverride(goalId, epochDay)
        val newOverride = if (existingOverride != null) {
            existingOverride.copy(achievedValue = achievedValue, updatedAt = System.currentTimeMillis())
        } else {
            com.timeledger.app.domain.model.GoalProgressOverride(
                goalId = goalId,
                epochDay = epochDay,
                achievedValue = achievedValue
            )
        }
        overrideService.saveOverride(newOverride)
    }

    /**
     * 移除目标进度的覆盖值
     */
    suspend fun removeManualOverride(goalId: Long, epochDay: Long) {
        val override = overrideService.getOverride(goalId, epochDay)
        override?.let { overrideService.removeOverride(it.id) }
    }
}