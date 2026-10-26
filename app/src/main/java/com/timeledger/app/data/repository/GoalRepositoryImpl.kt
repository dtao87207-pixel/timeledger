package com.timeledger.app.data.repository

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.data.local.dao.ActivityDao
import com.timeledger.app.data.local.dao.CategoryDao
import com.timeledger.app.data.local.dao.GoalDao
import com.timeledger.app.data.mapper.toDomain
import com.timeledger.app.data.mapper.toEntity
import com.timeledger.app.domain.model.Goal
import com.timeledger.app.domain.repository.GoalRepository
import com.timeledger.app.domain.repository.RejectReason
import com.timeledger.app.domain.repository.SaveOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 目标仓库实现。
 *
 * 校验分工（与 SaveOutcome 的说明一致）：
 * - 「目标值为正」「必须绑定分类或活动」由 [Goal] 的构造函数保证，这里不重复判断；
 * - 这里只查数据库才知道的事：名称是否为空、引用是否存在且未归档、日期是否颠倒。
 */
class GoalRepositoryImpl(
    private val goalDao: GoalDao,
    private val categoryDao: CategoryDao,
    private val activityDao: ActivityDao,
    private val dayBoundary: DayBoundary,
) : GoalRepository {

    override fun observeGoals(activeOnly: Boolean): Flow<List<Goal>> {
        val source = if (activeOnly) goalDao.observeActive() else goalDao.observeAll()
        return source.map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun getGoal(id: Long): Goal? = goalDao.getById(id)?.toDomain()

    override suspend fun getActiveGoals(): List<Goal> = goalDao.getActive().map { it.toDomain() }

    override suspend fun createGoal(goal: Goal): SaveOutcome {
        val normalized = when (val result = normalize(goal)) {
            is NormalizeResult.Invalid -> return SaveOutcome.Rejected(result.reason)
            is NormalizeResult.Ok -> result.goal
        }
        val now = dayBoundary.nowMillis()
        val id = goalDao.insert(
            normalized.copy(id = 0L, createdAtMillis = now, updatedAtMillis = now).toEntity()
        )
        return SaveOutcome.Saved(id)
    }

    override suspend fun updateGoal(goal: Goal): SaveOutcome {
        val existing = goalDao.getById(goal.id)
            ?: return SaveOutcome.Rejected(RejectReason.RECORD_NOT_FOUND)

        val normalized = when (val result = normalize(goal)) {
            is NormalizeResult.Invalid -> return SaveOutcome.Rejected(result.reason)
            is NormalizeResult.Ok -> result.goal
        }

        goalDao.update(
            existing.copy(
                title = normalized.title,
                type = normalized.type.name,
                period = normalized.period.name,
                targetValue = normalized.targetValue,
                categoryId = normalized.categoryId,
                activityId = normalized.activityId,
                startEpochDay = normalized.startEpochDay,
                endEpochDay = normalized.endEpochDay,
                isActive = normalized.isActive,
                updatedAtMillis = dayBoundary.nowMillis(),
            )
        )
        return SaveOutcome.Saved(goal.id)
    }

    override suspend fun deactivateGoal(id: Long): Boolean {
        val existing = goalDao.getById(id) ?: return false
        if (!existing.isActive) return true
        goalDao.deactivate(id, dayBoundary.nowMillis())
        return true
    }

    override suspend fun deleteGoal(id: Long): Boolean {
        val existing = goalDao.getById(id) ?: return false
        goalDao.deleteById(existing.id)
        return true
    }

    private sealed interface NormalizeResult {
        data class Ok(val goal: Goal) : NormalizeResult
        data class Invalid(val reason: RejectReason) : NormalizeResult
    }

    /** 校验引用并补全绑定：只给活动时自动补上活动所属分类。 */
    private suspend fun normalize(goal: Goal): NormalizeResult {
        val trimmedTitle = goal.title.trim()
        if (trimmedTitle.isEmpty()) return NormalizeResult.Invalid(RejectReason.INVALID_NAME)

        val start = goal.startEpochDay
        val end = goal.endEpochDay
        if (start != null && end != null && end < start) {
            return NormalizeResult.Invalid(RejectReason.INVALID_DATE_RANGE)
        }

        var categoryId = goal.categoryId
        val activityId = goal.activityId

        if (activityId != null) {
            val activity = activityDao.getById(activityId)
            if (activity == null || activity.isArchived) {
                return NormalizeResult.Invalid(RejectReason.UNKNOWN_CATEGORY_OR_ACTIVITY)
            }
            if (categoryId != null && categoryId != activity.categoryId) {
                return NormalizeResult.Invalid(RejectReason.ACTIVITY_CATEGORY_MISMATCH)
            }
            categoryId = categoryId ?: activity.categoryId
        }

        // Goal 的构造函数已经保证「分类或活动至少有一个」，且活动分支会把分类补全，
        // 所以走到这里 categoryId 逻辑上一定非空。但 Kotlin 无法对 var 做智能转换，
        // 因此显式兜底：真出现空值就按引用不存在处理，而不是强行 !! 冒崩溃风险。
        val effectiveCategoryId = categoryId
            ?: return NormalizeResult.Invalid(RejectReason.UNKNOWN_CATEGORY_OR_ACTIVITY)
        val category = categoryDao.getById(effectiveCategoryId)
        if (category == null || category.isArchived) {
            return NormalizeResult.Invalid(RejectReason.UNKNOWN_CATEGORY_OR_ACTIVITY)
        }

        return NormalizeResult.Ok(
            goal.copy(title = trimmedTitle, categoryId = effectiveCategoryId, activityId = activityId)
        )
    }
}
