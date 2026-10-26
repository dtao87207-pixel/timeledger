package com.timeledger.app.data.repository

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.data.local.dao.CategoryDao
import com.timeledger.app.data.local.entity.CategoryEntity
import com.timeledger.app.data.mapper.toDomain
import com.timeledger.app.domain.model.Category
import com.timeledger.app.domain.repository.CategoryRepository
import com.timeledger.app.domain.repository.RejectReason
import com.timeledger.app.domain.repository.SaveOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 分类仓库实现。
 *
 * 一个刻意的取舍：[updateCategory] 只允许改「用户可见的字段」
 * （名称、颜色、图标、排序、归档状态），
 * `isBuiltIn` 与 `createdAtMillis` 一律以库里已有的行为准。
 * 否则 UI 传进来一个 isBuiltIn=false 的对象，就能把内置分类降级成普通分类，
 * 后续「内置分类不可删除」的规则会莫名其妙失效。
 */
class CategoryRepositoryImpl(
    private val categoryDao: CategoryDao,
    private val dayBoundary: DayBoundary,
) : CategoryRepository {

    override fun observeCategories(includeArchived: Boolean): Flow<List<Category>> {
        val source = if (includeArchived) categoryDao.observeAll() else categoryDao.observeActive()
        return source.map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun getCategory(id: Long): Category? = categoryDao.getById(id)?.toDomain()

    override suspend fun getCategoryByName(name: String): Category? =
        categoryDao.getByName(name.trim())?.toDomain()

    override suspend fun createCategory(
        name: String,
        colorArgb: Int?,
        iconKey: String?,
        sortOrder: Int,
    ): SaveOutcome {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return SaveOutcome.Rejected(RejectReason.INVALID_NAME)
        if (categoryDao.getByName(trimmed) != null) {
            return SaveOutcome.Rejected(RejectReason.DUPLICATE_NAME)
        }

        val now = dayBoundary.nowMillis()
        val id = categoryDao.insertIgnore(
            CategoryEntity(
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
        // 上面查过一次，这里仍可能返回 -1：两个协程同时创建同名分类时，
        // 唯一索引会挡住后一个。把它当成「重名」回报，而不是让异常冒到 UI。
        if (id == -1L) return SaveOutcome.Rejected(RejectReason.DUPLICATE_NAME)
        return SaveOutcome.Saved(id)
    }

    override suspend fun updateCategory(category: Category): SaveOutcome {
        val existing = categoryDao.getById(category.id)
            ?: return SaveOutcome.Rejected(RejectReason.RECORD_NOT_FOUND)

        val trimmed = category.name.trim()
        if (trimmed.isEmpty()) return SaveOutcome.Rejected(RejectReason.INVALID_NAME)

        val sameName = categoryDao.getByName(trimmed)
        if (sameName != null && sameName.id != category.id) {
            return SaveOutcome.Rejected(RejectReason.DUPLICATE_NAME)
        }

        categoryDao.update(
            existing.copy(
                name = trimmed,
                colorArgb = category.colorArgb,
                iconKey = category.iconKey,
                sortOrder = category.sortOrder,
                isArchived = category.isArchived,
                updatedAtMillis = dayBoundary.nowMillis(),
            )
        )
        return SaveOutcome.Saved(category.id)
    }

    override suspend fun archiveCategory(id: Long): Boolean {
        val existing = categoryDao.getById(id) ?: return false
        // 已归档就直接返回成功：归档是幂等操作，重复点击不该报错。
        if (existing.isArchived) return true
        categoryDao.archive(id, dayBoundary.nowMillis())
        return true
    }
}
