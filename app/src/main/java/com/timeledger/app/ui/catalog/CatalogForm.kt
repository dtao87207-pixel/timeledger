package com.timeledger.app.ui.catalog

import com.timeledger.app.domain.model.Activity
import com.timeledger.app.domain.model.Category

/** 分类表单的原始输入。 */
data class CategoryFormState(
    /** null 表示新建；非空表示正在编辑该分类。 */
    val categoryId: Long? = null,
    val name: String = "",
    val colorArgb: Int? = null,
    /**
     * 下面几个字段用户改不了，但要**原样带回**仓库。
     *
     * 因为 `CategoryRepository.updateCategory` 是「整体更新」：它按传入对象的字段覆盖，
     * 并刻意以传入值为准（只有 isBuiltIn / createdAt 保留库里的）。
     * 如果表单不带上它们，编辑一次分类就会把它悄悄改成「未归档、排序归零」——
     * 这种副作用在界面上完全看不出来。
     */
    val isArchived: Boolean = false,
    val isBuiltIn: Boolean = false,
    val sortOrder: Int = 0,
    val iconKey: String? = null,
) {
    val isEditing: Boolean get() = categoryId != null
}

/** 活动表单的原始输入。 */
data class ActivityFormState(
    val activityId: Long? = null,
    /** 所属分类，必填（新建时由上下文带入）。 */
    val categoryId: Long? = null,
    val name: String = "",
    /** null 表示「继承分类色」。 */
    val colorArgb: Int? = null,
    /** 同 [CategoryFormState]：不编辑但要原样带回的字段。 */
    val isArchived: Boolean = false,
    val isBuiltIn: Boolean = false,
    val sortOrder: Int = 0,
) {
    val isEditing: Boolean get() = activityId != null
}

/** 分类 / 活动表单共用的失败原因。 */
enum class CatalogFormError {
    EMPTY_NAME,
    NAME_TOO_LONG,

    /** 该分类下已有同名活动 / 已存在同名分类。 */
    DUPLICATE_NAME,

    /** 活动没有归属分类（正常交互下不会出现，防御性保留）。 */
    MISSING_CATEGORY,

    /** 分类不存在或已不可用，仓库拒绝了写入。 */
    SAVE_FAILED,
}

/**
 * 表单校验结果。
 *
 * 分类与活动各有一个：两者校验规则相同，但返回的领域对象不同。
 * 本来可以写成一个泛型 `FormResult<T>`，但泛型密封类在调用处与测试里
 * 都得处理类型参数（`as FormResult.Valid` 这种写法根本不成立），
 * 反而比多写四行更难读。这里与模块 9 的 `GoalFormResult` 保持同一形状。
 */
sealed interface CategoryFormResult {
    data class Valid(val category: Category) : CategoryFormResult
    data class Invalid(val error: CatalogFormError) : CategoryFormResult
}

sealed interface ActivityFormResult {
    data class Valid(val activity: Activity) : ActivityFormResult
    data class Invalid(val error: CatalogFormError) : ActivityFormResult
}

/**
 * 分类与活动表单的解析与校验 —— 纯逻辑，可完整用单元测试覆盖。
 *
 * ## 名称长度为什么要限制
 * 需求没有规定长度，但分类名会出现在时间轴、日历格子、统计排行里。
 * 一个三十个字的分类名会把整个界面撑坏，而用户并不会因此得到任何好处。
 * 所以这里主动设一个上限（[MAX_NAME_LENGTH]），并把上限写进错误提示里，
 * 让用户知道该怎么改 —— 这比让界面变成一团乱要好。
 *
 * ## 重名校验必须和数据库的唯一索引完全一致
 * 数据库的唯一索引是**精确匹配**（区分大小写、不去空格）。所以这里的比较
 * 也必须是「trim 之后精确相等」：如果这里宽松、数据库严格，用户会看到
 * 「保存成功」的假象被一个底层报错打断；反过来则会放进重名数据。
 */
object CatalogForm {

    /** 分类与活动名称的长度上限（字符数）。 */
    const val MAX_NAME_LENGTH = 16

    fun emptyCategory(): CategoryFormState = CategoryFormState()

    fun emptyActivity(categoryId: Long): ActivityFormState = ActivityFormState(categoryId = categoryId)

    fun from(category: Category): CategoryFormState = CategoryFormState(
        categoryId = category.id,
        name = category.name,
        colorArgb = category.colorArgb,
        isArchived = category.isArchived,
        isBuiltIn = category.isBuiltIn,
        sortOrder = category.sortOrder,
        iconKey = category.iconKey,
    )

    fun from(activity: Activity): ActivityFormState = ActivityFormState(
        activityId = activity.id,
        categoryId = activity.categoryId,
        name = activity.name,
        colorArgb = activity.colorArgb,
        isArchived = activity.isArchived,
        isBuiltIn = activity.isBuiltIn,
        sortOrder = activity.sortOrder,
    )

    /**
     * 校验分类。
     *
     * [existingNames] 是**其他**分类的名称（不含正在编辑的这一个），由调用方提供 ——
     * 本类不查数据库，保持纯函数以便测试。
     */
    fun validateCategory(
        state: CategoryFormState,
        existingNames: Collection<String>,
    ): CategoryFormResult {
        val name = state.name.trim()
        nameError(name)?.let { return CategoryFormResult.Invalid(it) }
        if (existingNames.any { it == name }) {
            return CategoryFormResult.Invalid(CatalogFormError.DUPLICATE_NAME)
        }

        return CategoryFormResult.Valid(
            Category(
                id = state.categoryId ?: 0L,
                name = name,
                colorArgb = state.colorArgb,
                iconKey = state.iconKey,
                sortOrder = state.sortOrder,
                isBuiltIn = state.isBuiltIn,
                isArchived = state.isArchived,
            )
        )
    }

    /**
     * 校验活动。
     *
     * [existingNames] 是**同一分类下其他**活动的名称。
     */
    fun validateActivity(
        state: ActivityFormState,
        existingNames: Collection<String>,
    ): ActivityFormResult {
        val categoryId = state.categoryId
            ?: return ActivityFormResult.Invalid(CatalogFormError.MISSING_CATEGORY)

        val name = state.name.trim()
        nameError(name)?.let { return ActivityFormResult.Invalid(it) }
        if (existingNames.any { it == name }) {
            return ActivityFormResult.Invalid(CatalogFormError.DUPLICATE_NAME)
        }

        return ActivityFormResult.Valid(
            Activity(
                id = state.activityId ?: 0L,
                categoryId = categoryId,
                name = name,
                // null 表示继承分类色：不写死颜色，换分类配色时活动跟着变
                colorArgb = state.colorArgb,
                sortOrder = state.sortOrder,
                isBuiltIn = state.isBuiltIn,
                isArchived = state.isArchived,
            )
        )
    }

    private fun nameError(trimmedName: String): CatalogFormError? = when {
        trimmedName.isEmpty() -> CatalogFormError.EMPTY_NAME
        trimmedName.length > MAX_NAME_LENGTH -> CatalogFormError.NAME_TOO_LONG
        else -> null
    }
}

/**
 * 预设色板。
 *
 * 与内置分类（`DefaultCatalog`）用同一批颜色：用户自己加的分类
 * 应该和内置的看起来是「一家人」，而不是两套互不相干的配色。
 * 不做任意取色的取色器 —— V1 里「能区分开」就够了，加取色器属于为了功能而功能。
 */
object CatalogPalette {
    val COLORS: List<Int> = listOf(
        0xFF3F6BFF,
        0xFF6A4CFF,
        0xFFFF8A3D,
        0xFF2FB37A,
        0xFF00A5A5,
        0xFFFF5C8A,
        0xFF8A93A6,
        0xFF5B4B8A,
        0xFFB08A00,
        0xFF7A7A7A,
    ).map { it.toInt() }
}
