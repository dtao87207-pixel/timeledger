package com.timeledger.app.ui.catalog

import com.timeledger.app.domain.model.Activity
import com.timeledger.app.domain.model.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类 / 活动管理页状态映射测试。
 *
 * 与其他界面映射器同样的立场：本机无法目视验证界面，
 * 所以「卡片怎么排、活动归到哪一类、能不能保存」必须由测试钉死。
 *
 * 其中两条特别值得盯：
 * - **已归档的项排在最后**，否则「我归档了它，它还在最上面」会让人以为没生效；
 * - **重名校验要排除自己**，否则编辑一个分类、名字都没改，保存按钮却是灰的。
 */
class CatalogManagementStateMapperTest {

    private val mapper = CatalogManagementStateMapper()

    private val studyColor = 0xFF3F6BFF.toInt()
    private val funColor = 0xFFFF8A3D.toInt()

    private fun category(
        id: Long,
        name: String,
        colorArgb: Int? = studyColor,
        sortOrder: Int = 0,
        isBuiltIn: Boolean = true,
        isArchived: Boolean = false,
    ) = Category(
        id = id,
        name = name,
        colorArgb = colorArgb,
        sortOrder = sortOrder,
        isBuiltIn = isBuiltIn,
        isArchived = isArchived,
    )

    private fun activity(
        id: Long,
        categoryId: Long,
        name: String,
        colorArgb: Int? = null,
        sortOrder: Int = 0,
        isArchived: Boolean = false,
    ) = Activity(
        id = id,
        categoryId = categoryId,
        name = name,
        colorArgb = colorArgb,
        sortOrder = sortOrder,
        isArchived = isArchived,
    )

    private fun map(
        categories: List<Category> = emptyList(),
        activities: List<Activity> = emptyList(),
        categoryForm: CategoryFormState? = null,
        activityForm: ActivityFormState? = null,
        formError: CatalogFormError? = null,
    ) = mapper.map(categories, activities, categoryForm, activityForm, formError)

    // ---------------- 卡片与排序 ----------------

    @Test
    fun `cards put archived categories last`() {
        val state = map(
            categories = listOf(
                category(id = 1L, name = "学习", sortOrder = 0),
                category(id = 2L, name = "娱乐", sortOrder = 1),
                category(id = 3L, name = "生活", sortOrder = 0, isArchived = true),
            )
        )

        // 归档的排最后，其余按 sortOrder
        assertEquals(listOf("学习", "娱乐", "生活"), state.categoryCards.map { it.name })
        assertEquals(1, state.archivedCategoryCount)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `activities are grouped under their category`() {
        val state = map(
            categories = listOf(
                category(id = 1L, name = "学习", sortOrder = 0),
                category(id = 2L, name = "娱乐", colorArgb = funColor, sortOrder = 1),
            ),
            activities = listOf(
                activity(id = 100L, categoryId = 1L, name = "Java", sortOrder = 0),
                activity(id = 101L, categoryId = 1L, name = "算法", sortOrder = 1),
                activity(id = 200L, categoryId = 2L, name = "B站", sortOrder = 0),
            ),
        )

        val study = state.categoryCards.first { it.name == "学习" }
        val funCard = state.categoryCards.first { it.name == "娱乐" }
        assertEquals(listOf("Java", "算法"), study.activities.map { it.name })
        assertEquals(2, study.activityCount)
        assertEquals(listOf("B站"), funCard.activities.map { it.name })
    }

    @Test
    fun `archived activities are ordered last within their category`() {
        val state = map(
            categories = listOf(category(id = 1L, name = "运动")),
            activities = listOf(
                activity(id = 1L, categoryId = 1L, name = "健身", sortOrder = 0, isArchived = true),
                activity(id = 2L, categoryId = 1L, name = "跑步", sortOrder = 1),
            ),
        )

        // 「我归档了它，它却还在最上面」会让人以为归档没生效
        assertEquals(listOf("跑步", "健身"), state.categoryCards.single().activities.map { it.name })
    }

    @Test
    fun `activity colour falls back to the category colour`() {
        val state = map(
            categories = listOf(category(id = 1L, name = "学习", colorArgb = studyColor)),
            activities = listOf(
                activity(id = 100L, categoryId = 1L, name = "Java"),
                activity(id = 101L, categoryId = 1L, name = "算法", colorArgb = funColor),
            ),
        )

        val activities = state.categoryCards.single().activities
        // 没配颜色 → 用分类色；配了 → 用自己的
        assertEquals(studyColor, activities.first { it.name == "Java" }.colorArgb)
        assertEquals(funColor, activities.first { it.name == "算法" }.colorArgb)
    }

    @Test
    fun `empty catalog is flagged`() {
        val state = map()
        assertTrue(state.isEmpty)
        assertTrue(state.categoryCards.isEmpty())
        assertFalse(state.isFormOpen)
    }

    // ---------------- 分类表单 ----------------

    @Test
    fun `category form prefill shows current values`() {
        val state = map(
            categories = listOf(category(id = 1L, name = "学习", isBuiltIn = true)),
            categoryForm = CategoryFormState(
                categoryId = 1L,
                name = "学习",
                colorArgb = studyColor,
                isBuiltIn = true,
            ),
        )

        val form = state.categoryForm!!
        assertTrue(state.isFormOpen)
        assertTrue(form.isEditing)
        assertTrue(form.isBuiltIn)
        assertEquals("学习", form.name)
        assertEquals(studyColor, form.selectedColorArgb)
        assertEquals(CatalogPalette.COLORS, form.palette)
    }

    @Test
    fun `editing a category does not clash with its own name`() {
        val state = map(
            categories = listOf(category(id = 1L, name = "学习"), category(id = 2L, name = "娱乐")),
            categoryForm = CategoryFormState(categoryId = 1L, name = "学习"),
        )

        // 名字没改就应该是可保存的，否则用户会以为「什么都没动却存不了」
        assertTrue(state.categoryForm!!.canSave)
    }

    @Test
    fun `creating a category with an existing name is blocked`() {
        val state = map(
            categories = listOf(category(id = 1L, name = "学习")),
            categoryForm = CategoryFormState(name = "学习"),
        )

        assertFalse(state.categoryForm!!.canSave)
    }

    @Test
    fun `category form cannot save an empty name`() {
        val state = map(categoryForm = CategoryFormState(name = "  "))
        assertFalse(state.categoryForm!!.canSave)
    }

    // ---------------- 活动表单 ----------------

    @Test
    fun `activity form resolves the category name`() {
        val state = map(
            categories = listOf(category(id = 1L, name = "学习")),
            activityForm = ActivityFormState(categoryId = 1L, name = "Java"),
        )

        val form = state.activityForm!!
        assertEquals("学习", form.categoryName)
        assertTrue(form.canSave)
    }

    @Test
    fun `activity form falls back when the category is missing`() {
        val state = map(
            categories = emptyList(),
            activityForm = ActivityFormState(categoryId = 1L, name = "Java"),
        )
        assertEquals("未知分类", state.activityForm!!.categoryName)
    }

    @Test
    fun `activity duplicates are scoped to the same category`() {
        val categories = listOf(category(id = 1L, name = "学习"), category(id = 2L, name = "娱乐"))
        val activities = listOf(
            activity(id = 100L, categoryId = 1L, name = "Java"),
            activity(id = 200L, categoryId = 2L, name = "B站"),
        )

        // 别的分类里有同名活动不算重名（唯一索引是 (category_id, name)）
        val crossCategory = map(
            categories = categories,
            activities = activities,
            activityForm = ActivityFormState(categoryId = 1L, name = "B站"),
        )
        assertTrue(crossCategory.activityForm!!.canSave)

        // 同一分类下同名 → 不行
        val sameCategory = map(
            categories = categories,
            activities = activities,
            activityForm = ActivityFormState(categoryId = 1L, name = "Java"),
        )
        assertFalse(sameCategory.activityForm!!.canSave)

        // 编辑它自己 → 可以
        val editingSelf = map(
            categories = categories,
            activities = activities,
            activityForm = ActivityFormState(activityId = 100L, categoryId = 1L, name = "Java"),
        )
        assertTrue(editingSelf.activityForm!!.canSave)
    }

    @Test
    fun `activity form marks inherit colour when no colour is set`() {
        val state = map(
            categories = listOf(category(id = 1L, name = "学习")),
            activityForm = ActivityFormState(categoryId = 1L, name = "Java", colorArgb = null),
        )
        assertNull(state.activityForm!!.selectedColorArgb)
    }

    // ---------------- 错误提示 ----------------

    @Test
    fun `error messages are readable and state the length limit`() {
        assertEquals(
            "请填写名称",
            map(categoryForm = CategoryFormState(), formError = CatalogFormError.EMPTY_NAME)
                .categoryForm!!.errorMessage,
        )
        assertEquals(
            "名称最多 16 个字，再短一点吧",
            map(categoryForm = CategoryFormState(), formError = CatalogFormError.NAME_TOO_LONG)
                .categoryForm!!.errorMessage,
        )
        assertEquals(
            "已经有同名的了，换一个吧",
            map(categoryForm = CategoryFormState(), formError = CatalogFormError.DUPLICATE_NAME)
                .categoryForm!!.errorMessage,
        )
        assertEquals(
            "请先选择所属分类",
            map(activityForm = ActivityFormState(categoryId = 1L), formError = CatalogFormError.MISSING_CATEGORY)
                .activityForm!!.errorMessage,
        )
        assertNull(map(categoryForm = CategoryFormState()).categoryForm!!.errorMessage)
    }

    @Test
    fun `no form means both dialogs are closed`() {
        val state = map(categories = listOf(category(id = 1L, name = "学习")))
        assertFalse(state.isFormOpen)
        assertNull(state.categoryForm)
        assertNull(state.activityForm)
    }
}
