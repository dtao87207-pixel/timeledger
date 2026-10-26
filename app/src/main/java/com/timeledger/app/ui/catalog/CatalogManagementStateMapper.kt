package com.timeledger.app.ui.catalog

import com.timeledger.app.domain.model.Activity
import com.timeledger.app.domain.model.Category

/**
 * 目录数据 → 分类 / 活动管理页界面状态的**纯函数**映射。
 *
 * 与其他界面映射器同样的理由：本机无法目视验证界面，
 * 所以「卡片显示什么、活动如何归属、表单能不能保存」这些判断全部集中在这里，
 * 用单元测试钉死。文案硬编码也是同一个取舍（详见 README）。
 */
class CatalogManagementStateMapper {

    fun map(
        categories: List<Category>,
        activities: List<Activity>,
        categoryForm: CategoryFormState?,
        activityForm: ActivityFormState?,
        formError: CatalogFormError?,
    ): CatalogManagementUiState {
        val cards = categories
            // 已归档的排到最后：它们平时不该占据视线，但又要能找回来
            .sortedWith(compareBy({ it.isArchived }, { it.sortOrder }, { it.id }))
            .map { category ->
                CategoryCardUi(
                    categoryId = category.id,
                    name = category.name,
                    colorArgb = category.colorArgb,
                    isArchived = category.isArchived,
                    isBuiltIn = category.isBuiltIn,
                    activities = activities
                        .filter { it.categoryId == category.id }
                        .sortedWith(compareBy({ it.isArchived }, { it.sortOrder }, { it.id }))
                        .map { activity -> activityRow(activity, category.colorArgb) },
                )
            }

        return CatalogManagementUiState(
            categoryCards = cards,
            isEmpty = cards.isEmpty(),
            categoryForm = categoryForm?.let { categoryFormUi(it, categories, formError) },
            activityForm = activityForm?.let { activityFormUi(it, categories, activities, formError) },
        )
    }

    private fun activityRow(activity: Activity, categoryColorArgb: Int?): ActivityRowUi = ActivityRowUi(
        activityId = activity.id,
        name = activity.name,
        // 活动没配颜色就用分类色：界面上仍能看出它属于哪一类
        colorArgb = activity.colorArgb ?: categoryColorArgb,
        isArchived = activity.isArchived,
        isBuiltIn = activity.isBuiltIn,
    )

    private fun categoryFormUi(
        form: CategoryFormState,
        categories: List<Category>,
        error: CatalogFormError?,
    ): CategoryFormUiState {
        // 重名校验要排除自己：编辑时名称没改，不该被判成重名
        val otherNames = categories.filter { it.id != form.categoryId }.map { it.name }

        return CategoryFormUiState(
            isEditing = form.isEditing,
            name = form.name,
            selectedColorArgb = form.colorArgb,
            palette = CatalogPalette.COLORS,
            isBuiltIn = form.isBuiltIn,
            // 按钮置灰与保存时的校验用同一套规则（含重名校验），
            // 避免出现「按钮能点、点了才报错」
            canSave = CatalogForm.validateCategory(form, otherNames) is CategoryFormResult.Valid,
            errorMessage = error?.let(::errorMessage),
        )
    }

    private fun activityFormUi(
        form: ActivityFormState,
        categories: List<Category>,
        activities: List<Activity>,
        error: CatalogFormError?,
    ): ActivityFormUiState {
        val category = categories.firstOrNull { it.id == form.categoryId }
        // 同一分类下的其他活动名 —— 唯一索引是 (category_id, name)，所以只比同分类
        val siblings = activities
            .filter { it.categoryId == form.categoryId && it.id != form.activityId }
            .map { it.name }

        return ActivityFormUiState(
            isEditing = form.isEditing,
            name = form.name,
            categoryName = category?.name ?: "未知分类",
            selectedColorArgb = form.colorArgb,
            palette = CatalogPalette.COLORS,
            canSave = CatalogForm.validateActivity(form, siblings) is ActivityFormResult.Valid,
            errorMessage = error?.let(::errorMessage),
        )
    }

    private fun errorMessage(error: CatalogFormError): String = when (error) {
        CatalogFormError.EMPTY_NAME -> "请填写名称"
        CatalogFormError.NAME_TOO_LONG ->
            "名称最多 ${CatalogForm.MAX_NAME_LENGTH} 个字，再短一点吧"

        CatalogFormError.DUPLICATE_NAME -> "已经有同名的了，换一个吧"
        CatalogFormError.MISSING_CATEGORY -> "请先选择所属分类"
        CatalogFormError.SAVE_FAILED -> "保存失败：这一项可能已被归档或删除，请返回重试"
    }
}
