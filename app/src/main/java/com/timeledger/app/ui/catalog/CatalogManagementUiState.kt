package com.timeledger.app.ui.catalog

/** 分类下的一个活动。 */
data class ActivityRowUi(
    val activityId: Long,
    val name: String,
    val colorArgb: Int?,
    val isArchived: Boolean,
    val isBuiltIn: Boolean,
)

/** 一个分类及其下的活动。 */
data class CategoryCardUi(
    val categoryId: Long,
    val name: String,
    val colorArgb: Int?,
    val isArchived: Boolean,
    val isBuiltIn: Boolean,
    val activities: List<ActivityRowUi>,
) {
    val activityCount: Int get() = activities.size
}

/** 分类表单的界面状态。 */
data class CategoryFormUiState(
    val isEditing: Boolean,
    val name: String,
    val selectedColorArgb: Int?,
    val palette: List<Int>,
    /** 内置分类的提示：可以改名字和颜色，但不会被删除。 */
    val isBuiltIn: Boolean,
    val canSave: Boolean,
    val errorMessage: String?,
)

/** 活动表单的界面状态。 */
data class ActivityFormUiState(
    val isEditing: Boolean,
    val name: String,
    /** 归属分类（只读展示，由上下文带入）。 */
    val categoryName: String,
    val selectedColorArgb: Int?,
    val palette: List<Int>,
    val canSave: Boolean,
    val errorMessage: String?,
)

/**
 * 分类 / 活动管理页状态。
 *
 * 与其它界面一样是**纯数据**，由 [CatalogManagementStateMapper] 算出来 ——
 * 本机没有模拟器与设备，界面无法目视验证，把「显示什么、能不能保存」的判断
 * 放进可测的映射器是唯一可靠的做法。
 */
data class CatalogManagementUiState(
    val categoryCards: List<CategoryCardUi>,
    /** 一个分类都没有（正常情况下不会发生，种子数据至少有 10 个）。 */
    val isEmpty: Boolean,
    val categoryForm: CategoryFormUiState?,
    val activityForm: ActivityFormUiState?,
) {
    val isFormOpen: Boolean get() = categoryForm != null || activityForm != null

    /** 已归档的分类数量，界面上用一句提示说明它们被排在最后。 */
    val archivedCategoryCount: Int get() = categoryCards.count { it.isArchived }
}
