package com.timeledger.app.domain.repository

import com.timeledger.app.domain.model.AppMapping
import kotlinx.coroutines.flow.Flow

/**
 * App → 活动映射仓库（需求九节）。
 *
 * 核心规则：**用户确认过的映射不会被默认规则静默覆盖**。
 * 这条规则放在这里而不是 UI 里，因为系统首次采集 App、批量刷新标签等
 * 都会走 [ensureDefaultMapping]，只靠 UI 自觉是守不住的。
 */
interface AppMappingRepository {

    fun observeMappings(): Flow<List<AppMapping>>

    suspend fun getMapping(packageName: String): AppMapping?

    /**
     * 确保存在一个默认映射（系统采集到新 App 时调用）。
     *
     * - 已存在且是**用户确认过**的映射：原样返回，不做任何修改；
     * - 已存在但只是默认值：只补全缺失的展示名/更低的置信度，不覆盖已有归属；
     * - 不存在：插入一条新映射（[categoryId] 可以为 null，表示「未确定」，
     *   例如 Chrome —— 需求明确要求不能把 App 固定映射成某个行为）。
     */
    suspend fun ensureDefaultMapping(
        packageName: String,
        appLabel: String? = null,
        categoryId: Long? = null,
        activityId: Long? = null,
        confidence: Double = 0.0,
    ): AppMapping

    /**
     * 用户确认/修改某个 App 的归属，写入后 [AppMapping.isUserConfirmed] 为 true、
     * 置信度为 1.0。
     *
     * 返回 [SaveOutcome] 而不是 AppMapping：这是用户动作，
     * 引用了不存在的分类/活动时必须能明确告诉对方为什么失败，
     * 而不是静默返回 null 让 UI 猜。
     */
    suspend fun confirmMapping(
        packageName: String,
        categoryId: Long?,
        activityId: Long?,
    ): SaveOutcome

    /** 仍未确定归属的映射数量，用于提示用户处理（例如「Chrome 需要你确认」）。 */
    suspend fun countUnresolved(): Int
}
