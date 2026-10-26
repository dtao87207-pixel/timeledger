package com.timeledger.app.data.repository

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.data.local.dao.ActivityDao
import com.timeledger.app.data.local.dao.CategoryDao
import com.timeledger.app.data.local.entity.ActivityEntity
import com.timeledger.app.data.local.entity.CategoryEntity
import com.timeledger.app.data.local.seed.DefaultCatalog
import com.timeledger.app.domain.repository.CatalogSeeder

/**
 * 内置分类/活动种子写入。
 *
 * 逐条幂等（见 [CatalogSeeder] 的说明）：按名称查一次、缺了才写，
 * 写入用 `INSERT OR IGNORE`，因此并发或中断重跑都不会产生重复。
 * 已经存在的条目**一个字段都不动**，用户改过的颜色和排序不会被启动流程覆盖。
 */
class CatalogSeederImpl(
    private val categoryDao: CategoryDao,
    private val activityDao: ActivityDao,
    private val dayBoundary: DayBoundary,
    private val catalog: List<DefaultCatalog.SeedCategory> = DefaultCatalog.categories,
) : CatalogSeeder {

    private data class EnsureResult(val id: Long?, val inserted: Boolean)

    override suspend fun seedDefaults(): Boolean {
        val now = dayBoundary.nowMillis()
        var insertedAny = false

        catalog.forEachIndexed { categoryIndex, seedCategory ->
            val categoryResult = ensureCategory(seedCategory, categoryIndex, now)
            if (categoryResult.inserted) insertedAny = true
            val categoryId = categoryResult.id ?: return@forEachIndexed

            seedCategory.activities.forEachIndexed { activityIndex, seedActivity ->
                if (ensureActivity(categoryId, seedActivity, activityIndex, now).inserted) {
                    insertedAny = true
                }
            }
        }
        return insertedAny
    }

    private suspend fun ensureCategory(
        seed: DefaultCatalog.SeedCategory,
        sortOrder: Int,
        nowMillis: Long,
    ): EnsureResult {
        categoryDao.getByName(seed.name)?.let { return EnsureResult(it.id, false) }

        val id = categoryDao.insertIgnore(
            CategoryEntity(
                name = seed.name,
                colorArgb = seed.colorArgb,
                iconKey = seed.iconKey,
                sortOrder = sortOrder,
                isBuiltIn = true,
                isArchived = false,
                createdAtMillis = nowMillis,
                updatedAtMillis = nowMillis,
            )
        )
        if (id != -1L) return EnsureResult(id, true)

        // 走到这里说明并发下被别的协程抢先插入了，回查一次即可。
        return EnsureResult(categoryDao.getByName(seed.name)?.id, false)
    }

    private suspend fun ensureActivity(
        categoryId: Long,
        seed: DefaultCatalog.SeedActivity,
        sortOrder: Int,
        nowMillis: Long,
    ): EnsureResult {
        activityDao.getByCategoryAndName(categoryId, seed.name)
            ?.let { return EnsureResult(it.id, false) }

        val id = activityDao.insertIgnore(
            ActivityEntity(
                categoryId = categoryId,
                name = seed.name,
                // 活动不单独指定颜色：默认继承分类色，UI 层按需再覆盖。
                colorArgb = null,
                iconKey = null,
                sortOrder = sortOrder,
                isBuiltIn = true,
                isArchived = false,
                createdAtMillis = nowMillis,
                updatedAtMillis = nowMillis,
            )
        )
        if (id != -1L) return EnsureResult(id, true)

        return EnsureResult(activityDao.getByCategoryAndName(categoryId, seed.name)?.id, false)
    }
}
