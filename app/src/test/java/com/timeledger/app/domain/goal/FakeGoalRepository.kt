package com.timeledger.app.domain.goal

import com.timeledger.app.domain.model.Goal
import com.timeledger.app.domain.repository.GoalRepository
import com.timeledger.app.domain.repository.RejectReason
import com.timeledger.app.domain.repository.SaveOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 目标仓库的内存替身。
 *
 * 用 `MutableStateFlow` 作为数据源，是为了让 `observeGoals` 返回的 Flow
 * 真的具有「数据变化会重新发射」的行为 —— 如果用 `flowOf`，
 * 测试就永远只能验证第一次读取，测不出「改了目标之后进度跟着变」。
 */
class FakeGoalRepository(initial: List<Goal> = emptyList()) : GoalRepository {

    private val state = MutableStateFlow(initial)

    /** 便于测试在运行中改变目标集合。 */
    fun setGoals(goals: List<Goal>) {
        state.value = goals
    }

    fun all(): List<Goal> = state.value

    override fun observeGoals(activeOnly: Boolean): Flow<List<Goal>> =
        state.map { goals -> if (activeOnly) goals.filter { it.isActive } else goals }

    override suspend fun getGoal(id: Long): Goal? = state.value.firstOrNull { it.id == id }

    override suspend fun getActiveGoals(): List<Goal> = state.value.filter { it.isActive }

    override suspend fun createGoal(goal: Goal): SaveOutcome {
        val id = if (goal.id == 0L) (state.value.maxOfOrNull { it.id } ?: 0L) + 1L else goal.id
        state.value = state.value + goal.copy(id = id)
        return SaveOutcome.Saved(id)
    }

    override suspend fun updateGoal(goal: Goal): SaveOutcome {
        val index = state.value.indexOfFirst { it.id == goal.id }
        if (index < 0) return SaveOutcome.Rejected(RejectReason.RECORD_NOT_FOUND)
        state.value = state.value.toMutableList().also { it[index] = goal }
        return SaveOutcome.Saved(goal.id)
    }

    override suspend fun deactivateGoal(id: Long): Boolean {
        val index = state.value.indexOfFirst { it.id == id }
        if (index < 0) return false
        state.value = state.value.toMutableList().also { it[index] = it[index].copy(isActive = false) }
        return true
    }

    override suspend fun deleteGoal(id: Long): Boolean {
        val remaining = state.value.filterNot { it.id == id }
        if (remaining.size == state.value.size) return false
        state.value = remaining
        return true
    }
}
