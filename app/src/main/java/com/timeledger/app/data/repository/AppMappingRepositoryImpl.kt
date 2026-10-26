package com.timeledger.app.data.repository

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.data.local.dao.ActivityDao
import com.timeledger.app.data.local.dao.AppMappingDao
import com.timeledger.app.data.local.dao.CategoryDao
import com.timeledger.app.data.local.entity.AppMappingEntity
import com.timeledger.app.data.mapper.toDomain
import com.timeledger.app.domain.model.AppMapping
import com.timeledger.app.domain.repository.AppMappingRepository
import com.timeledger.app.domain.repository.RejectReason
import com.timeledger.app.domain.repository.SaveOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * App → 活动映射仓库实现（需求九节）。
 *
 * 核心规则在这里落地：**用户确认过的映射，默认规则一律不许改**。
 * 两条写入路径的权限刻意不对称：
 * - [ensureDefaultMapping]（系统自动调用）只能**补空缺**，不能覆盖已有归属；
 * - [confirmMapping]（用户主动调用）才能改写归属。
 *
 * 另外区分了两个容易混淆的概念：
 * - `isUserConfirmed` = 用户是否处理过这条映射；
 * - `confidence` = 这条归属有多可信。
 * 用户可以把一个 App 确认成「未确定」（例如 Chrome），此时前者为 true、后者仍为 0.0，
 * 这样系统不会再反复追问同一个 App。
 */
class AppMappingRepositoryImpl(
    private val appMappingDao: AppMappingDao,
    private val categoryDao: CategoryDao,
    private val activityDao: ActivityDao,
    private val dayBoundary: DayBoundary,
) : AppMappingRepository {

    override fun observeMappings(): Flow<List<AppMapping>> =
        appMappingDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getMapping(packageName: String): AppMapping? =
        appMappingDao.getByPackageName(packageName)?.toDomain()

    override suspend fun ensureDefaultMapping(
        packageName: String,
        appLabel: String?,
        categoryId: Long?,
        activityId: Long?,
        confidence: Double,
    ): AppMapping {
        require(packageName.isNotBlank()) { "包名不能为空" }
        val now = dayBoundary.nowMillis()
        val safeConfidence = confidence.coerceIn(0.0, 1.0)
        val existing = appMappingDao.getByPackageName(packageName)

        if (existing == null) {
            val fresh = AppMappingEntity(
                packageName = packageName,
                appLabel = appLabel,
                categoryId = categoryId,
                activityId = activityId,
                confidence = safeConfidence,
                isUserConfirmed = false,
                updatedAtMillis = now,
            )
            appMappingDao.insertIgnore(fresh)
            // 并发下可能已被抢先插入，回查一次拿库里的权威版本。
            return (appMappingDao.getByPackageName(packageName) ?: fresh).toDomain()
        }

        if (existing.isUserConfirmed) {
            // 用户已经处理过：最多补一个展示名，归属与置信度一个字都不动。
            if (appLabel != null && appLabel != existing.appLabel) {
                val updated = existing.copy(appLabel = appLabel, updatedAtMillis = now)
                appMappingDao.update(updated)
                return updated.toDomain()
            }
            return existing.toDomain()
        }

        // 只是默认值：只补空缺（?: 而不是覆盖），置信度取更大的那个。
        val merged = existing.copy(
            appLabel = existing.appLabel ?: appLabel,
            categoryId = existing.categoryId ?: categoryId,
            activityId = existing.activityId ?: activityId,
            confidence = maxOf(existing.confidence, safeConfidence),
            updatedAtMillis = now,
        )
        if (merged != existing) appMappingDao.update(merged)
        return merged.toDomain()
    }

    override suspend fun confirmMapping(
        packageName: String,
        categoryId: Long?,
        activityId: Long?,
    ): SaveOutcome {
        val existing = appMappingDao.getByPackageName(packageName)
            ?: return SaveOutcome.Rejected(RejectReason.RECORD_NOT_FOUND)

        // 绑定的校验口径与时间记录保持一致，避免出现
        // 「映射指向一个已归档活动」这种记录层查得到、界面进不去的状态。
        var resolvedCategoryId = categoryId
        if (activityId != null) {
            val activity = activityDao.getById(activityId)
            if (activity == null || activity.isArchived) {
                return SaveOutcome.Rejected(RejectReason.UNKNOWN_CATEGORY_OR_ACTIVITY)
            }
            if (categoryId != null && categoryId != activity.categoryId) {
                return SaveOutcome.Rejected(RejectReason.ACTIVITY_CATEGORY_MISMATCH)
            }
            resolvedCategoryId = categoryId ?: activity.categoryId
        }
        if (resolvedCategoryId != null) {
            val category = categoryDao.getById(resolvedCategoryId)
            if (category == null || category.isArchived) {
                return SaveOutcome.Rejected(RejectReason.UNKNOWN_CATEGORY_OR_ACTIVITY)
            }
        }

        val hasBinding = resolvedCategoryId != null || activityId != null
        appMappingDao.update(
            existing.copy(
                categoryId = resolvedCategoryId,
                activityId = activityId,
                // 用户处理过了（哪怕结论是「未确定」），但不能因此宣称归属是确定的。
                isUserConfirmed = true,
                confidence = if (hasBinding) 1.0 else 0.0,
                updatedAtMillis = dayBoundary.nowMillis(),
            )
        )
        return SaveOutcome.Saved(existing.id)
    }

    override suspend fun countUnresolved(): Int = appMappingDao.countUnresolved()
}
