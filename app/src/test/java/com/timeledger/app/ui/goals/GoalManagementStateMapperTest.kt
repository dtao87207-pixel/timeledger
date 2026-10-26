package com.timeledger.app.ui.goals

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.domain.goal.GoalProgress
import com.timeledger.app.domain.model.Activity
import com.timeledger.app.domain.model.Category
import com.timeledger.app.domain.model.Goal
import com.timeledger.app.domain.model.GoalPeriod
import com.timeledger.app.domain.model.GoalType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 目标管理页状态映射测试。
 *
 * 与其它界面映射器同样的立场：本机没有模拟器与设备，界面无法目视验证，
 * 所以「卡片显示什么、表单能选什么、能不能保存」这些判断必须由测试钉死。
 */
class GoalManagementStateMapperTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private val fixedNow = Instant.parse("2026-09-12T06:00:00Z").toEpochMilli()
    private val dayBoundary = DayBoundary(zoneId = shanghai, nowProvider = { fixedNow })
    private val today: Long = LocalDate.of(2026, 9, 12).toEpochDay()

    private val mapper = GoalManagementStateMapper()

    private val study = Category(id = 1L, name = "学习", colorArgb = 0xFF3F6BFF.toInt(), sortOrder = 0)
    private val funCategory = Category(id = 2L, name = "娱乐", colorArgb = 0xFFFF8A3D.toInt(), sortOrder = 1)
    private val java = Activity(id = 100L, categoryId = 1L, name = "Java", sortOrder = 0)
    private val bilibili = Activity(id = 200L, categoryId = 2L, name = "B站", sortOrder = 0)

    private fun categories() = listOf(study, funCategory)
    private fun activities() = listOf(java, bilibili)

    private fun goal(
        id: Long = 1L,
        title: String = "Java",
        type: GoalType = GoalType.TIME,
        period: GoalPeriod = GoalPeriod.DAY,
        target: Double = 10800.0,
        categoryId: Long? = 1L,
        activityId: Long? = 100L,
        isActive: Boolean = true,
    ) = Goal(
        id = id,
        title = title,
        type = type,
        period = period,
        targetValue = target,
        categoryId = categoryId,
        activityId = activityId,
        isActive = isActive,
    )

    private fun progress(
        goalId: Long = 1L,
        achieved: Double = 7200.0,
        target: Double = 10800.0,
        type: GoalType = GoalType.TIME,
    ) = GoalProgress(
        goal = Goal(
            id = goalId,
            title = "Java",
            type = type,
            period = GoalPeriod.DAY,
            targetValue = target,
            categoryId = 1L,
        ),
        window = dayBoundary.dayRange(today),
        achieved = achieved,
        target = target,
        contributingRecordCount = 1,
    )

    private fun map(
        goals: List<Goal> = emptyList(),
        progress: List<GoalProgress> = emptyList(),
        categories: List<Category> = categories(),
        activities: List<Activity> = activities(),
        form: GoalFormState? = null,
        formError: GoalFormError? = null,
    ) = mapper.map(goals, progress, categories, activities, form, formError)

    // ---------------- 卡片 ----------------

    @Test
    fun `card shows title binding type and period labels`() {
        val state = map(goals = listOf(goal()))

        val card = state.goalCards.single()
        assertEquals("Java", card.title)
        assertEquals("时长", card.typeLabel)
        assertEquals("每日", card.periodLabel)
        assertEquals("学习 / Java", card.bindingLabel)
        assertEquals("3h", card.targetLabel)
        assertTrue(card.isActive)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `card shows progress when the goal applies today`() {
        val state = map(goals = listOf(goal()), progress = listOf(progress()))

        val card = state.goalCards.single()
        assertEquals("2h / 3h", card.progressLabel)
        assertEquals(7200.0 / 10800.0, card.progressFraction.toDouble(), 0.0001)
        assertFalse(card.isAchieved)
    }

    @Test
    fun `card has no progress label when the goal does not apply today`() {
        val state = map(goals = listOf(goal()), progress = emptyList())

        val card = state.goalCards.single()
        assertNull(card.progressLabel)
        assertEquals(0f, card.progressFraction, 0.0001f)
        assertFalse(card.isAchieved)
    }

    @Test
    fun `inactive goal is flagged`() {
        val state = map(goals = listOf(goal(isActive = false)))
        assertFalse(state.goalCards.single().isActive)
    }

    @Test
    fun `binding falls back to the category then to a placeholder`() {
        val categoryOnly = map(goals = listOf(goal(activityId = null))).goalCards.single()
        assertEquals("学习", categoryOnly.bindingLabel)

        // 引用了一个目录里不存在的分类：仍要给出可读的占位，而不是空白
        val unknown = map(
            goals = listOf(goal(categoryId = 999L, activityId = null)),
            categories = emptyList(),
            activities = emptyList(),
        ).goalCards.single()
        assertEquals("未指定", unknown.bindingLabel)
    }

    @Test
    fun `card colour follows the activity then its category`() {
        // 活动没配颜色 → 用分类色
        assertEquals(0xFF3F6BFF.toInt(), map(goals = listOf(goal())).goalCards.single().colorArgb)

        // 活动有颜色 → 用活动色
        val coloured = map(
            goals = listOf(goal()),
            activities = listOf(java.copy(colorArgb = 0xFF00A5A5.toInt()), bilibili),
        ).goalCards.single()
        assertEquals(0xFF00A5A5.toInt(), coloured.colorArgb)
    }

    @Test
    fun `count and frequency cards use their own units`() {
        val count = map(goals = listOf(goal(type = GoalType.COUNT, target = 2.0)))
            .goalCards.single()
        assertEquals("计数", count.typeLabel)
        assertEquals("2个", count.targetLabel)

        val frequency = map(
            goals = listOf(goal(type = GoalType.FREQUENCY, target = 3.0, period = GoalPeriod.WEEK))
        ).goalCards.single()
        assertEquals("频次", frequency.typeLabel)
        assertEquals("每周", frequency.periodLabel)
        assertEquals("3次", frequency.targetLabel)
    }

    @Test
    fun `empty goal list is flagged`() {
        val state = map()
        assertTrue(state.isEmpty)
        assertTrue(state.goalCards.isEmpty())
    }

    // ---------------- 表单 ----------------

    @Test
    fun `form lists selectable categories in sort order`() {
        val state = map(form = GoalForm.empty())

        val form = state.form!!
        assertEquals(listOf("学习", "娱乐"), form.categories.map { it.label })
        // 选项带上分类颜色，界面能显示色点
        assertEquals(0xFF3F6BFF.toInt(), form.categories[0].colorArgb)
    }

    @Test
    fun `form filters activities by the selected category`() {
        val studyForm = map(form = GoalForm.empty().copy(categoryId = 1L)).form!!
        assertEquals(listOf("Java"), studyForm.activities.map { it.label })

        val funForm = map(form = GoalForm.empty().copy(categoryId = 2L)).form!!
        assertEquals(listOf("B站"), funForm.activities.map { it.label })

        // 还没选分类时活动列表为空
        val noCategory = map(form = GoalForm.empty()).form!!
        assertTrue(noCategory.activities.isEmpty())
    }

    @Test
    fun `archived categories are excluded from the picker but still resolve names`() {
        val archivedStudy = study.copy(isArchived = true)

        val state = map(
            goals = listOf(goal()),
            categories = listOf(archivedStudy, funCategory),
        )

        // 选择器里不该出现已归档分类
        val form = map(
            categories = listOf(archivedStudy, funCategory),
            form = GoalForm.empty(),
        ).form!!
        assertEquals(listOf("娱乐"), form.categories.map { it.label })

        // 但绑定到它的旧目标仍要显示出名字，否则用户会以为目标坏了
        assertEquals("学习 / Java", state.goalCards.single().bindingLabel)
    }

    @Test
    fun `can save mirrors the form validation`() {
        // 填完整 → 可以保存
        val valid = map(form = GoalForm.empty().copy(title = "Java", categoryId = 1L, targetText = "3"))
        assertTrue(valid.form!!.canSave)

        // 缺目标值 → 不能保存（按钮置灰，而不是点了才报错）
        val missingTarget = map(form = GoalForm.empty().copy(title = "Java", categoryId = 1L))
        assertFalse(missingTarget.form!!.canSave)

        // 缺绑定 → 不能保存
        val missingBinding = map(form = GoalForm.empty().copy(title = "Java", targetText = "3"))
        assertFalse(missingBinding.form!!.canSave)
    }

    @Test
    fun `form carries editing flag and current values`() {
        val form = GoalFormState(
            goalId = 7L,
            title = "Java",
            type = GoalType.COUNT,
            period = GoalPeriod.WEEK,
            targetText = "2",
            categoryId = 1L,
            activityId = 100L,
        )

        val ui = map(form = form).form!!
        assertTrue(ui.isEditing)
        assertEquals("Java", ui.title)
        assertEquals(GoalType.COUNT, ui.type)
        assertEquals(GoalPeriod.WEEK, ui.period)
        assertEquals("2", ui.targetText)
        assertEquals("个", ui.targetUnitLabel)
        assertEquals(1L, ui.selectedCategoryId)
        assertEquals(100L, ui.selectedActivityId)
    }

    @Test
    fun `form unit label follows the chosen type`() {
        val timeForm = map(form = GoalForm.empty().copy(type = GoalType.TIME)).form!!
        assertEquals("小时", timeForm.targetUnitLabel)

        val frequencyForm = map(form = GoalForm.empty().copy(type = GoalType.FREQUENCY)).form!!
        assertEquals("次", frequencyForm.targetUnitLabel)
    }

    @Test
    fun `no form means the dialog is closed`() {
        val state = map(goals = listOf(goal()))
        assertFalse(state.isFormOpen)
        assertNull(state.form)
    }

    @Test
    fun `error messages are readable`() {
        assertEquals(
            "请填写目标名称",
            map(form = GoalForm.empty(), formError = GoalFormError.EMPTY_TITLE).form!!.errorMessage,
        )
        assertEquals(
            "目标值要填一个大于 0 的数字",
            map(form = GoalForm.empty(), formError = GoalFormError.INVALID_TARGET).form!!.errorMessage,
        )
        assertEquals(
            "请选择分类（可以再指定具体活动）",
            map(form = GoalForm.empty(), formError = GoalFormError.MISSING_BINDING).form!!.errorMessage,
        )
        assertNull(map(form = GoalForm.empty()).form!!.errorMessage)
    }

    @Test
    fun `activity picker falls back to the category colour`() {
        val form = map(form = GoalForm.empty().copy(categoryId = 1L)).form!!
        // Java 自己没有颜色 → 用「学习」的颜色
        assertEquals(0xFF3F6BFF.toInt(), form.activities.single().colorArgb)
    }
}
