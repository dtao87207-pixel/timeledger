package com.timeledger.app.domain.model

/**
 * 一级分类（需求八节）。例如：学习 / 工作 / 娱乐 / 生活 / 运动 / 社交 / 交通 / 睡眠 / 个人 / 其他。
 *
 * 设计要点：
 * 1. 分类与 Activity 是**两个独立概念**，分别有各自的表，方便按层级统计。
 * 2. [isBuiltIn] 标记种子数据。内置分类不允许硬删除，只能 [isArchived] 归档，
 *    否则历史 TimeRecord 会失去归属，统计口径永远对不上。
 * 3. 时间字段统一为毫秒时间戳（需求十六节第 9 条），只在 UI 层转成人类可读格式。
 *
 * 这是**领域模型**，不含任何 Room/Android 注解；数据库实体与它的映射放在 data 层。
 */
data class Category(
    val id: Long = 0L,
    /** 显示名称，例如「学习」。 */
    val name: String,
    /** 主题色 ARGB，例如 0xFF4CAF50.toInt()；为空表示由 UI 自动分配。 */
    val colorArgb: Int? = null,
    /** 图标标识，与具体图标库解耦（UI 层做映射）。 */
    val iconKey: String? = null,
    /** 排序权重，越小越靠前。 */
    val sortOrder: Int = 0,
    /** 是否为内置分类（种子数据）。 */
    val isBuiltIn: Boolean = false,
    /** 是否已归档。归档后不出现在选择器，但历史记录仍可正常展示与统计。 */
    val isArchived: Boolean = false,
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
)
