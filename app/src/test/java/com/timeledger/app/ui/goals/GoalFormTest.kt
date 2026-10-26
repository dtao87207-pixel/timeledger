package com.timeledger.app.ui.goals

import com.timeledger.app.domain.model.Goal
import com.timeledger.app.domain.model.GoalPeriod
import com.timeledger.app.domain.model.GoalType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目标表单解析与校验测试。
 *
 * 最要紧的一条是 **TIME 的「小时 ↔ 秒」换算**：
 * 界面让用户填小时（「Java 每天 3 小时」），领域模型存秒。
 * 这个换算算错了，用户看到的目标进度会整体差 3600 倍 ——
 * 而界面上「3h」的字样看起来完全正常，肉眼发现不了。
 */
class GoalFormTest {

    private fun form(
        title: String = "Java",
        type: GoalType = GoalType.TIME,
        period: GoalPeriod = GoalPeriod.DAY,
        targetText: String = "3",
        categoryId: Long? = 1L,
        activityId: Long? = null,
        goalId: Long? = null,
    ) = GoalFormState(
        goalId = goalId,
        title = title,
        type = type,
        period = period,
        targetText = targetText,
        categoryId = categoryId,
        activityId = activityId,
    )

    // ---------------- 校验 ----------------

    @Test
    fun `empty form is rejected for an empty title`() {
        assertEquals(
            GoalFormResult.Invalid(GoalFormError.EMPTY_TITLE),
            GoalForm.validate(GoalForm.empty()),
        )
    }

    @Test
    fun `title alone is not enough because a binding is required`() {
        // 目标必须绑定分类或活动，否则无法计算完成度
        assertEquals(
            GoalFormResult.Invalid(GoalFormError.MISSING_BINDING),
            GoalForm.validate(form(categoryId = null, activityId = null)),
        )
    }

    @Test
    fun `binding to an activity alone is enough`() {
        val result = GoalForm.validate(form(categoryId = null, activityId = 100L))
        assertTrue(result is GoalFormResult.Valid)
        assertEquals(100L, (result as GoalFormResult.Valid).goal.activityId)
    }

    @Test
    fun `target must be a positive finite number`() {
        val cases = listOf("", "  ", "abc", "0", "-1", "NaN", "Infinity")
        cases.forEach { text ->
            assertEquals(
                "目标值「$text」应当被拒绝",
                GoalFormResult.Invalid(GoalFormError.INVALID_TARGET),
                GoalForm.validate(form(targetText = text)),
            )
        }
    }

    @Test
    fun `blank title is rejected even with spaces`() {
        assertEquals(
            GoalFormResult.Invalid(GoalFormError.EMPTY_TITLE),
            GoalForm.validate(form(title = "   ")),
        )
    }

    @Test
    fun `title is trimmed`() {
        val result = GoalForm.validate(form(title = "  Java  "))
        assertEquals("Java", (result as GoalFormResult.Valid).goal.title)
    }

    // ---------------- 小时 ↔ 秒 ----------------

    @Test
    fun `time goal stores seconds while the form shows hours`() {
        val result = GoalForm.validate(form(type = GoalType.TIME, targetText = "3"))

        val goal = (result as GoalFormResult.Valid).goal
        // 用户填「3」（小时），领域存 10800 秒
        assertEquals(10800.0, goal.targetValue, 0.0001)
        assertEquals(GoalType.TIME, goal.type)
    }

    @Test
    fun `fractional hours are converted`() {
        val result = GoalForm.validate(form(targetText = "1.5"))
        assertEquals(5400.0, (result as GoalFormResult.Valid).goal.targetValue, 0.0001)
    }

    @Test
    fun `count and frequency goals keep the raw number`() {
        val count = GoalForm.validate(form(type = GoalType.COUNT, targetText = "2"))
        assertEquals(2.0, (count as GoalFormResult.Valid).goal.targetValue, 0.0001)

        val frequency = GoalForm.validate(form(type = GoalType.FREQUENCY, targetText = "3"))
        assertEquals(3.0, (frequency as GoalFormResult.Valid).goal.targetValue, 0.0001)
    }

    @Test
    fun `editing keeps the goal id`() {
        val result = GoalForm.validate(form(goalId = 7L))
        assertEquals(7L, (result as GoalFormResult.Valid).goal.id)
    }

    @Test
    fun `type and period survive validation`() {
        val result = GoalForm.validate(
            form(type = GoalType.FREQUENCY, period = GoalPeriod.WEEK, targetText = "3")
        )
        val goal = (result as GoalFormResult.Valid).goal
        assertEquals(GoalType.FREQUENCY, goal.type)
        assertEquals(GoalPeriod.WEEK, goal.period)
    }

    // ---------------- 回填 ----------------

    @Test
    fun `editing a stored goal shows hours again`() {
        val goal = Goal(
            id = 7L,
            title = "Java",
            type = GoalType.TIME,
            period = GoalPeriod.DAY,
            targetValue = 10800.0,
            categoryId = 1L,
        )

        val state = GoalForm.from(goal)

        assertEquals(7L, state.goalId)
        assertTrue(state.isEditing)
        assertEquals("Java", state.title)
        // 10800 秒回填成「3」而不是「10800」
        assertEquals("3", state.targetText)
    }

    @Test
    fun `round trip through the form preserves the target`() {
        val original = Goal(
            id = 0L,
            title = "Java",
            type = GoalType.TIME,
            period = GoalPeriod.WEEK,
            targetValue = 54000.0, // 15 小时
            categoryId = 1L,
        )

        val state = GoalForm.from(original)
        assertEquals("15", state.targetText)

        val result = GoalForm.validate(state)
        assertEquals(54000.0, (result as GoalFormResult.Valid).goal.targetValue, 0.0001)
    }

    @Test
    fun `target text trims trailing decimals`() {
        assertEquals("3", GoalForm.targetTextOf(10800.0, GoalType.TIME))
        assertEquals("1.5", GoalForm.targetTextOf(5400.0, GoalType.TIME))
        assertEquals("2", GoalForm.targetTextOf(2.0, GoalType.COUNT))
        assertEquals("0.5", GoalForm.targetTextOf(0.5, GoalType.COUNT))
    }

    @Test
    fun `unit labels follow the goal type`() {
        assertEquals("小时", GoalForm.targetUnitLabel(GoalType.TIME))
        assertEquals("个", GoalForm.targetUnitLabel(GoalType.COUNT))
        assertEquals("次", GoalForm.targetUnitLabel(GoalType.FREQUENCY))
    }

    @Test
    fun `parse target returns null for unparsable input`() {
        assertEquals(null, GoalForm.parseTarget("abc", GoalType.TIME))
        assertEquals(null, GoalForm.parseTarget("", GoalType.COUNT))
        assertEquals(7200.0, GoalForm.parseTarget(" 2 ", GoalType.TIME)!!, 0.0001)
    }
}
