package com.timeledger.app.domain.repository

import com.timeledger.app.domain.model.Category
import kotlinx.coroutines.flow.Flow

/**
 * 分类仓库。
 *
 * 所有方法都是 suspend 或返回 Flow：
 * - 查询用 Flow，UI 只订阅不轮询，数据变化自动刷新；
 * - 写入用 suspend，由 ViewModel 的协程作用域调度。
 * 接口里没有出现任何 Room / Android 类型，保证 UI 与 ViewModel 不依赖数据库实现。
 */
interface CategoryRepository {

    /** 观察分类列表。[includeArchived] 为 false 时只返回未归档分类（选择器用）。 */
    fun observeCategories(includeArchived: Boolean = false): Flow<List<Category>>

    suspend fun getCategory(id: Long): Category?

    /** 按名称精确查找，用于种子数据与「避免建重复分类」的校验。 */
    suspend fun getCategoryByName(name: String): Category?

    suspend fun createCategory(
        name: String,
        colorArgb: Int? = null,
        iconKey: String? = null,
        sortOrder: Int = 0,
    ): SaveOutcome

    suspend fun updateCategory(category: Category): SaveOutcome

    /** 归档而不是删除，保证历史记录仍有归属。 */
    suspend fun archiveCategory(id: Long): Boolean
}

// 内置分类/活动的种子写入刻意**不放在这里**：种子数据同时涉及分类与活动两张表，
// 挂在 CategoryRepository 上会让它凭空依赖 ActivityDao。
// 种子逻辑单独放在 CatalogSeeder，见 domain/repository/CatalogSeeder.kt。
