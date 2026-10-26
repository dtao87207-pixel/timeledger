package com.timeledger.app.ui.catalog

import com.timeledger.app.domain.model.Activity
import com.timeledger.app.domain.model.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类 / 活动表单校验测试。
 *
 * 两条最要紧的规则：
 * 1. **重名校验必须和数据库唯一索引一致**（trim 后精确比较）——
 *    这里宽松、数据库严格，用户就会遇到「以为保存成功了，结果底层报错」；
 * 2. **不编辑的字段要原样带回**——`updateCategory` 是整体更新，
 *    少带一个 `isArchived` 就会让「编辑一次名字」把分类悄悄取消归档。
 */
class CatalogFormTest {

    private fun categoryForm(
        categoryId: Long? = null,
        name: String = "练琴",
        colorArgb: Int? = 0xFF3F6BFF.toInt(),
        isArchived: Boolean = false,
        isBuiltIn: Boolean = false,
        sortOrder: Int = 0,
    ) = CategoryFormState(
        categoryId = categoryId,
        name = name,
        colorArgb = colorArgb,
        isArchived = isArchived,
        isBuiltIn = isBuiltIn,
        sortOrder = sortOrder,
    )

    private fun activityForm(
        activityId: Long? = null,
        categoryId: Long? = 1L,
        name: String = "跑步",
        colorArgb: Int? = null,
        isArchived: Boolean = false,
        isBuiltIn: Boolean = false,
        sortOrder: Int = 0,
    ) = ActivityFormState(
        activityId = activityId,
        categoryId = categoryId,
        name = name,
        colorArgb = colorArgb,
        isArchived = isArchived,
        isBuiltIn = isBuiltIn,
        sortOrder = sortOrder,
    )

    // ---------------- 名称 ----------------

    @Test
    fun `category name is required`() {
        assertEquals(
            CategoryFormResult.Invalid(CatalogFormError.EMPTY_NAME),
            CatalogForm.validateCategory(categoryForm(name = "   "), emptyList()),
        )
    }

    @Test
    fun `activity name is required`() {
        assertEquals(
            ActivityFormResult.Invalid(CatalogFormError.EMPTY_NAME),
            CatalogForm.validateActivity(activityForm(name = ""), emptyList()),
        )
    }

    @Test
    fun `name has a length limit`() {
        val tooLong = "一".repeat(CatalogForm.MAX_NAME_LENGTH + 1)
        assertEquals(
            CategoryFormResult.Invalid(CatalogFormError.NAME_TOO_LONG),
            CatalogForm.validateCategory(categoryForm(name = tooLong), emptyList()),
        )
        assertEquals(
            ActivityFormResult.Invalid(CatalogFormError.NAME_TOO_LONG),
            CatalogForm.validateActivity(activityForm(name = tooLong), emptyList()),
        )

        // 正好达到上限是允许的
        val atLimit = "一".repeat(CatalogForm.MAX_NAME_LENGTH)
        assertTrue(
            CatalogForm.validateCategory(categoryForm(name = atLimit), emptyList())
                is CategoryFormResult.Valid
        )
        assertEquals(16, CatalogForm.MAX_NAME_LENGTH)
    }

    @Test
    fun `name is trimmed`() {
        val result = CatalogForm.validateCategory(categoryForm(name = "  练琴  "), emptyList())
        assertEquals("练琴", (result as CategoryFormResult.Valid).category.name)
    }

    @Test
    fun `duplicate category name is rejected`() {
        assertEquals(
            CategoryFormResult.Invalid(CatalogFormError.DUPLICATE_NAME),
            CatalogForm.validateCategory(categoryForm(name = "学习"), listOf("学习", "娱乐")),
        )
    }

    @Test
    fun `duplicate check compares trimmed values exactly`() {
        // 数据库唯一索引是精确匹配：这里也必须精确，否则会出现「界面说可以、数据库拒绝」
        assertEquals(
            CategoryFormResult.Invalid(CatalogFormError.DUPLICATE_NAME),
            CatalogForm.validateCategory(categoryForm(name = " 学习 "), listOf("学习")),
        )
    }

    @Test
    fun `duplicate check is case sensitive like the database`() {
        // 与 SQLite 唯一索引一致：大小写不同视为不同名字
        val result = CatalogForm.validateCategory(categoryForm(name = "java"), listOf("Java"))
        assertTrue(result is CategoryFormResult.Valid)
    }

    // ---------------- 不编辑的字段要带回 ----------------

    @Test
    fun `category passthrough fields are preserved`() {
        val result = CatalogForm.validateCategory(
            categoryForm(
                categoryId = 5L,
                name = "练琴",
                isArchived = true,
                isBuiltIn = true,
                sortOrder = 7,
            ),
            emptyList(),
        )

        val category = (result as CategoryFormResult.Valid).category
        assertEquals(5L, category.id)
        // 少了这三个中的任何一个，编辑一次名字就会把分类「取消归档 / 排序归零 / 丢掉内置标记」
        assertTrue(category.isArchived)
        assertTrue(category.isBuiltIn)
        assertEquals(7, category.sortOrder)
    }

    @Test
    fun `activity passthrough fields are preserved`() {
        val result = CatalogForm.validateActivity(
            activityForm(activityId = 3L, name = "跑步", isArchived = true, sortOrder = 4)
                .copy(isBuiltIn = true),
            emptyList(),
        )

        val activity = (result as ActivityFormResult.Valid).activity
        assertEquals(3L, activity.id)
        assertEquals(1L, activity.categoryId)
        assertTrue(activity.isArchived)
        assertTrue(activity.isBuiltIn)
        assertEquals(4, activity.sortOrder)
    }

    @Test
    fun `activity colour null means inherit from the category`() {
        val result = CatalogForm.validateActivity(activityForm(colorArgb = null), emptyList())
        assertNull((result as ActivityFormResult.Valid).activity.colorArgb)
    }

    @Test
    fun `activity requires a category`() {
        assertEquals(
            ActivityFormResult.Invalid(CatalogFormError.MISSING_CATEGORY),
            CatalogForm.validateActivity(activityForm(categoryId = null), emptyList()),
        )
    }

    // ---------------- 回填 ----------------

    @Test
    fun `category form is filled from an existing category`() {
        val category = Category(
            id = 5L,
            name = "练琴",
            colorArgb = 0xFF2FB37A.toInt(),
            sortOrder = 7,
            isBuiltIn = true,
            isArchived = true,
        )

        val form = CatalogForm.from(category)

        assertEquals(5L, form.categoryId)
        assertTrue(form.isEditing)
        assertEquals("练琴", form.name)
        assertEquals(0xFF2FB37A.toInt(), form.colorArgb)
        assertTrue(form.isArchived)
        assertTrue(form.isBuiltIn)
        assertEquals(7, form.sortOrder)
    }

    @Test
    fun `activity form is filled from an existing activity`() {
        val activity = Activity(
            id = 3L,
            categoryId = 9L,
            name = "跑步",
            colorArgb = null,
            sortOrder = 4,
        )

        val form = CatalogForm.from(activity)

        assertEquals(3L, form.activityId)
        assertTrue(form.isEditing)
        assertEquals(9L, form.categoryId)
        assertEquals("跑步", form.name)
        assertNull(form.colorArgb)
    }

    @Test
    fun `round trip through the form preserves name and colour`() {
        val original = Category(id = 5L, name = "练琴", colorArgb = 0xFF00A5A5.toInt(), sortOrder = 2)

        val result = CatalogForm.validateCategory(CatalogForm.from(original), emptyList())

        val category = (result as CategoryFormResult.Valid).category
        assertEquals("练琴", category.name)
        assertEquals(0xFF00A5A5.toInt(), category.colorArgb)
        assertEquals(2, category.sortOrder)
    }

    @Test
    fun `empty forms are marked as creating`() {
        val categoryForm = CatalogForm.emptyCategory()
        assertEquals(null, categoryForm.categoryId)
        assertTrue(!categoryForm.isEditing)

        val activityForm = CatalogForm.emptyActivity(categoryId = 3L)
        assertEquals(3L, activityForm.categoryId)
        assertTrue(!activityForm.isEditing)
    }

    @Test
    fun `palette offers a bounded set of distinct colours`() {
        // 色板不做任意取色：能区分开就够了，因此颜色两两不同且数量有限
        assertEquals(CatalogPalette.COLORS.size, CatalogPalette.COLORS.distinct().size)
        assertTrue(CatalogPalette.COLORS.size in 6..16)
    }
}
