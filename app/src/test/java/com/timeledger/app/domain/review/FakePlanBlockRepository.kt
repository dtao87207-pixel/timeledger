package com.timeledger.app.domain.review

import com.timeledger.app.domain.model.PlanBlock
import com.timeledger.app.domain.repository.PlanBlockRepository
import com.timeledger.app.domain.repository.RejectReason
import com.timeledger.app.domain.repository.SaveOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 计划仓库的内存替身。
 *
 * 与 FakeGoalRepository 同样的理由用 `MutableStateFlow`：
 * 只有真正会「数据变化后重新发射」的 Flow，才能验证复盘随计划编辑而变化。
 */
class FakePlanBlockRepository(initial: List<PlanBlock> = emptyList()) : PlanBlockRepository {

    private val state = MutableStateFlow(initial)

    fun setPlans(plans: List<PlanBlock>) {
        state.value = plans
    }

    fun all(): List<PlanBlock> = state.value

    override fun observePlans(activeOnly: Boolean): Flow<List<PlanBlock>> =
        state.map { plans -> if (activeOnly) plans.filter { it.isActive } else plans }

    override suspend fun getPlan(id: Long): PlanBlock? = state.value.firstOrNull { it.id == id }

    override suspend fun createPlan(plan: PlanBlock): SaveOutcome {
        val id = if (plan.id == 0L) (state.value.maxOfOrNull { it.id } ?: 0L) + 1L else plan.id
        state.value = state.value + plan.copy(id = id)
        return SaveOutcome.Saved(id)
    }

    override suspend fun createPlans(plans: List<PlanBlock>): Int {
        var saved = 0
        plans.forEach { if (createPlan(it) is SaveOutcome.Saved) saved++ }
        return saved
    }

    override suspend fun updatePlan(plan: PlanBlock): SaveOutcome {
        val index = state.value.indexOfFirst { it.id == plan.id }
        if (index < 0) return SaveOutcome.Rejected(RejectReason.RECORD_NOT_FOUND)
        state.value = state.value.toMutableList().also { it[index] = plan }
        return SaveOutcome.Saved(plan.id)
    }

    override suspend fun deletePlan(id: Long): Boolean {
        val remaining = state.value.filterNot { it.id == id }
        if (remaining.size == state.value.size) return false
        state.value = remaining
        return true
    }
}
