package com.timeledger.app.domain.usage

import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.model.AppMapping
import com.timeledger.app.domain.model.UsageEvent
import com.timeledger.app.domain.repository.AppMappingRepository
import com.timeledger.app.domain.repository.SaveOutcome
import com.timeledger.app.domain.repository.UsageEventRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 系统感知测试用的两个仓库替身。
 *
 * 它们比 DAO 替身简单得多：这两个仓库自己几乎没有判断逻辑
 * （`UsageEventRepository` 只做落库与按区间读取，`AppMappingRepository` 的规则
 * 已经在模块 1 单独验证过），所以内存实现足够真实。
 *
 * 时间记录仓库**不用替身**：那是产品规则最密集的一块（手动记录保护、幂等键、
 * 软删除复活），用真实现 + DAO 替身跑，才能验证「系统采集不会覆盖用户记录」这条硬规则。
 */
class FakeUsageEventHost : UsageEventRepository {

    private val events = mutableListOf<UsageEvent>()
    private val _all = MutableStateFlow<List<UsageEvent>>(emptyList())

    override suspend fun appendEvents(events: List<UsageEvent>): Int {
        val known = this.events.map { it.eventKey }.toSet()
        val fresh = events.filterNot { it.eventKey in known }
        this.events += fresh
        _all.value = this.events.toList()
        return fresh.size
    }

    override suspend fun getEventsIn(range: MillisRange): List<UsageEvent> =
        events
            // 与真实查询一致：区间重叠判定用左闭右开，端点相等算不属于
            .filter { it.timestampMillis >= range.startMillis && it.timestampMillis < range.endMillisExclusive }
            .sortedBy { it.timestampMillis }

    override suspend fun getLatestEventMillis(): Long? = events.maxOfOrNull { it.timestampMillis }

    override suspend fun pruneOlderThan(beforeMillis: Long): Int {
        val before = events.size
        events.removeAll { it.timestampMillis < beforeMillis }
        _all.value = events.toList()
        return before - events.size
    }

    override suspend fun count(): Int = events.size
}

class FakeAppMappingHost : AppMappingRepository {

    private val mappings = MutableStateFlow<List<AppMapping>>(emptyList())
    private var nextId = 1L

    override fun observeMappings(): Flow<List<AppMapping>> = mappings.map { it }

    override suspend fun getMapping(packageName: String): AppMapping? =
        mappings.value.firstOrNull { it.packageName == packageName }

    override suspend fun ensureDefaultMapping(
        packageName: String,
        appLabel: String?,
        categoryId: Long?,
        activityId: Long?,
        confidence: Double,
    ): AppMapping {
        val existing = getMapping(packageName)
        if (existing == null) {
            val created = AppMapping(
                id = nextId++,
                packageName = packageName,
                appLabel = appLabel,
                categoryId = categoryId,
                activityId = activityId,
                confidence = confidence,
            )
            mappings.value = mappings.value + created
            return created
        }

        // 对齐真实实现（AppMappingRepositoryImpl）的两条分支：
        // - 用户确认过的：**归属与置信度一个字都不动**，只允许刷新展示名；
        // - 只是默认值：只补空缺，不覆盖已有归属。
        // 替身比真实现宽松的话，测试就会给出一条假绿灯。
        val merged = if (existing.isUserConfirmed) {
            existing.copy(appLabel = appLabel ?: existing.appLabel)
        } else {
            existing.copy(
                appLabel = existing.appLabel ?: appLabel,
                categoryId = existing.categoryId ?: categoryId,
                activityId = existing.activityId ?: activityId,
                confidence = maxOf(existing.confidence, confidence),
            )
        }
        if (merged != existing) {
            mappings.value = mappings.value.map { if (it.packageName == packageName) merged else it }
        }
        return merged
    }

    override suspend fun confirmMapping(
        packageName: String,
        categoryId: Long?,
        activityId: Long?,
    ): SaveOutcome {
        val existing = getMapping(packageName)
            ?: return SaveOutcome.Rejected(com.timeledger.app.domain.repository.RejectReason.RECORD_NOT_FOUND)

        mappings.value = mappings.value.map {
            if (it.packageName == packageName) {
                it.copy(
                    categoryId = categoryId,
                    activityId = activityId,
                    isUserConfirmed = true,
                    confidence = if (categoryId != null || activityId != null) 1.0 else 0.0,
                )
            } else {
                it
            }
        }
        return SaveOutcome.Saved(existing.id)
    }

    override suspend fun countUnresolved(): Int = mappings.value.count { it.isUnresolved }

    /** 测试里直接放一条映射，省去走 ensure + confirm 两步。 */
    suspend fun put(mapping: AppMapping): AppMapping {
        val created = mapping.copy(id = nextId++)
        mappings.value = mappings.value + created
        return created
    }
}
