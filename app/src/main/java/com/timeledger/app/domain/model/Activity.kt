package com.timeledger.app.domain.model

/**
 * 二级活动（需求八节）。例如「学习 → Java / LeetCode / 英语」。
 *
 * 不变式：Activity 必须且只能属于一个 [Category]（[categoryId] 非空）。
 * 归档策略与分类一致：只归档不硬删，保证历史记录不会变成孤儿。
 */
data class Activity(
    val id: Long = 0L,
    /** 所属一级分类。非空，且必须指向一个存在的分类（由 Repository 校验）。 */
    val categoryId: Long,
    /** 显示名称，例如「Java」。同一分类下名称唯一。 */
    val name: String,
    val colorArgb: Int? = null,
    val iconKey: String? = null,
    val sortOrder: Int = 0,
    val isBuiltIn: Boolean = false,
    val isArchived: Boolean = false,
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
)
