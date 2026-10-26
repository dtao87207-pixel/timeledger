package com.timeledger.app.data.repository

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.data.local.dao.ActivityDao
import com.timeledger.app.data.local.dao.CategoryDao
import com.timeledger.app.data.local.entity.ActivityEntity
import com.timeledger.app.data.mapper.toDomain
import com.timeledger.app.domain.model.Activity
import com.timeledger.app.domain.repository.ActivityRepository
import com.timeledger.app.domain.repository.RejectReason
import com.timeledger.app.domain.repository.SaveOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 活动仓库实现。
 *
 * 唯一比分类多出来的事：**校验归属**。
 * 活动必须挂在一个存在且未归档的分类下 —— 挂到已归档分类上，
 * 新建的记录在界面上会找不到入口，用户只会觉得「数据丢了」。
 * 这里直接拒绝，并把原因交给调用方。
 */
class ActivityRepositoryImpl(
    private val activityDao: ActivityDao,
    private val categoryDao: CategoryDao,
    private val dayBoundary: DayBoundary,
) : ActivityRepository {

    override fun observeActivities(
        categoryId: Long?,
        includeArchived: Boolean,
    ): Flow<List<Activity>> {
        val source = when {
            categoryId != null && !includeArchived -> activityDao.observeActiveByCategory(categoryId)
            else -> if (includeArchived) activityDao.observeAll() else activityDao.observeActive()
        }
        return source.map { entities ->
            // categoryId 过滤放在内存里做，避免为「全部分类 + 指定分类」的四种组合
            // 各写一条 SQL。活动数量是几十条级别，过滤成本可以忽略。
            val filtered = if (categoryId != null) entities.filter { it.categoryId == categoryId } else entities
            filtered.map { it.toDomain() }
        }
    }

    override suspend fun getActivity(id: Long): Activity? = activityDao.getById(id)?.toDomain()

    override suspend fun createActivity(
        categoryId: Long,
        name: String,
        colorArgb: Int?,
        iconKey: String?,
        sortOrder: Int,
    ): SaveOutcome {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return SaveOutcome.Rejected(RejectReason.INVALID_NAME)

        val category = categoryDao.getById(categoryId)
            ?: return SaveOutcome.Rejected(RejectReason.UNKNOWN_CATEGORY_OR_ACTIVITY)
        if (category.isArchived) return SaveOutcome.Rejected(RejectReason.UNKNOWN_CATEGORY_OR_ACTIVITY)

        if (activityDao.getByCategoryAndName(categoryId, trimmed) != null) {
            return SaveOutcome.Rejected(RejectReason.DUPLICATE_NAME)
        }

        val now = dayBoundary.nowMillis()
        val id = activityDao.insertIgnore(
            ActivityEntity(
                categoryId = categoryId,
                name = trimmed,
                colorArgb = colorArgb,
                iconKey = iconKey,
                sortOrder = sortOrder,
                isBuiltIn = false,
                isArchived = false,
                createdAtMillis = now,
                updatedAtMillis = now,
            )
        )
        if (id == -1L) return SaveOutcome.Rejected(RejectReason.DUPLICATE_NAME)
        return SaveOutcome.Saved(id)
    }

    override suspend fun updateActivity(activity: Activity): SaveOutcome {
        val existing = activityDao.getById(activity.id)
            ?: return SaveOutcome.Rejected(RejectReason.RECORD_NOT_FOUND)

        val trimmed = activity.name.trim()
        if (trimmed.isEmpty()) return SaveOutcome.Rejected(RejectReason.INVALID_NAME)

        val category = categoryDao.getById(activity.categoryId)
            ?: return SaveOutcome.Rejected(RejectReason.UNKNOWN_CATEGORY_OR_ACTIVITY)
        if (category.isArchived) return SaveOutcome.Rejected(RejectReason.UNKNOWN_CATEGORY_OR_ACTIVITY)

        val sameName = activityDao.getByCategoryAndName(activity.categoryId, trimmed)
        if (sameName != null && sameName.id != activity.id) {
            return SaveOutcome.Rejected(RejectReason.DUPLICATE_NAME)
        }

        activityDao.update(
            existing.copy(
                categoryId = activity.categoryId,
                name = trimmed,
                colorArgb = activity.colorArgb,
                iconKey = activity.iconKey,
                sortOrder = activity.sortOrder,
                isArchived = activity.isArchived,
                updatedAtMillis = dayBoundary.nowMillis(),
            )
        )
        return SaveOutcome.Saved(activity.id)
    }

    override suspend fun archiveActivity(id: Long): Boolean {
        val existing = activityDao.getById(id) ?: return false
        if (existing.isArchived) return true
        activityDao.archive(id, dayBoundary.nowMillis())
        return true
    }
}
