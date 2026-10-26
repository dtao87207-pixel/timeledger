package com.timeledger.app.data.repository

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.data.local.dao.ActivityDao
import com.timeledger.app.data.local.dao.CategoryDao
import com.timeledger.app.data.local.dao.PlanBlockDao
import com.timeledger.app.data.local.entity.PlanBlockEntity
import com.timeledger.app.data.mapper.toDomain
import com.timeledger.app.data.mapper.toEntity
import com.timeledger.app.domain.model.PlanBlock
import com.timeledger.app.domain.repository.PlanBlockRepository
import com.timeledger.app.domain.repository.RejectReason
import com.timeledger.app.domain.repository.SaveOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 计划 / 课程表仓库实现。
 *
 * [PlanBlock] 的构造函数已经保证分钟数在合法区间、每周重复必须有星期几，
 * 因此这里只补两件事：
 * 1. 标题非空；
 * 2. 时长不为零 —— 结束分钟数等于开始分钟数时，`crossesMidnight` 判定会把它算成
 *    跨夜 24 小时，这几乎一定是误操作，直接拒绝比存下一条 24 小时的课更安全。
 */
class PlanBlockRepositoryImpl(
    private val planBlockDao: PlanBlockDao,
    private val categoryDao: CategoryDao,
    private val activityDao: ActivityDao,
    private val dayBoundary: DayBoundary,
) : PlanBlockRepository {

    override fun observePlans(activeOnly: Boolean): Flow<List<PlanBlock>> {
        val source = if (activeOnly) planBlockDao.observeActive() else planBlockDao.observeAll()
        return source.map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun getPlan(id: Long): PlanBlock? = planBlockDao.getById(id)?.toDomain()

    override suspend fun createPlan(plan: PlanBlock): SaveOutcome {
        validate(plan)?.let { return SaveOutcome.Rejected(it) }
        val binding = resolveBinding(plan.categoryId, plan.activityId)
            ?: return SaveOutcome.Rejected(RejectReason.UNKNOWN_CATEGORY_OR_ACTIVITY)

        val now = dayBoundary.nowMillis()
        val entity = plan.copy(
            id = 0L,
            categoryId = binding.first,
            activityId = binding.second,
            createdAtMillis = now,
            updatedAtMillis = now,
        ).toEntity()
        return SaveOutcome.Saved(planBlockDao.insert(entity))
    }

    override suspend fun createPlans(plans: List<PlanBlock>): Int {
        var saved = 0
        plans.forEach { plan ->
            if (createPlan(plan) is SaveOutcome.Saved) saved++
        }
        return saved
    }

    override suspend fun updatePlan(plan: PlanBlock): SaveOutcome {
        val existing = planBlockDao.getById(plan.id)
            ?: return SaveOutcome.Rejected(RejectReason.RECORD_NOT_FOUND)
        validate(plan)?.let { return SaveOutcome.Rejected(it) }
        val binding = resolveBinding(plan.categoryId, plan.activityId)
            ?: return SaveOutcome.Rejected(RejectReason.UNKNOWN_CATEGORY_OR_ACTIVITY)

        planBlockDao.update(
            existing.copy(
                title = plan.title.trim(),
                dateEpochDay = plan.dateEpochDay,
                dayOfWeek = plan.dayOfWeek,
                startMinuteOfDay = plan.startMinuteOfDay,
                endMinuteOfDay = plan.endMinuteOfDay,
                categoryId = binding.first,
                activityId = binding.second,
                location = plan.location?.trim()?.takeIf { it.isNotEmpty() },
                note = plan.note?.trim()?.takeIf { it.isNotEmpty() },
                validFromEpochDay = plan.validFromEpochDay,
                validToEpochDay = plan.validToEpochDay,
                isActive = plan.isActive,
                updatedAtMillis = dayBoundary.nowMillis(),
            )
        )
        return SaveOutcome.Saved(plan.id)
    }

    override suspend fun deletePlan(id: Long): Boolean {
        val existing = planBlockDao.getById(id) ?: return false
        planBlockDao.delete(existing)
        return true
    }

    private fun validate(plan: PlanBlock): RejectReason? {
        if (plan.title.trim().isEmpty()) return RejectReason.INVALID_NAME
        if (plan.endMinuteOfDay == plan.startMinuteOfDay) return RejectReason.INVALID_PLAN_DURATION
        return null
    }

    /**
     * 校验并补全分类/活动绑定。
     * 计划条目的绑定是**可选**的（可以先只写「高等数学」不归类），
     * 所以两边都为空是合法的；但只要给了，就必须真实存在。
     */
    private suspend fun resolveBinding(
        categoryId: Long?,
        activityId: Long?,
    ): Pair<Long?, Long?>? {
        if (activityId != null) {
            val activity = activityDao.getById(activityId) ?: return null
            if (activity.isArchived) return null
            if (categoryId != null && categoryId != activity.categoryId) return null
            val effectiveCategoryId = categoryId ?: activity.categoryId
            val category = categoryDao.getById(effectiveCategoryId) ?: return null
            if (category.isArchived) return null
            return effectiveCategoryId to activityId
        }
        if (categoryId != null) {
            val category = categoryDao.getById(categoryId) ?: return null
            if (category.isArchived) return null
            return categoryId to null
        }
        return null to null
    }
}
