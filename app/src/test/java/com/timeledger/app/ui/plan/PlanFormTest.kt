package com.timeledger.app.ui.plan

import com.timeledger.app.domain.model.PlanBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课程表表单解析与校验测试。
 *
 * 时间用文本输入，所以「用户实际会敲出来的写法」必须逐个覆盖：
 * `9:00`、`09:00`、`9`、全角冒号、甚至 `24:00`。
 * 这些如果靠肉眼在界面上试，是试不全的。
 */
class PlanFormTest {

    private fun form(
        title: String = "高等数学",
        dayOfWeek: Int = 1,
        startText: String = "09:00",
        endText: String = "10:40",
        categoryId: Long? = 1L,
        activityId: Long? = null,
        location: String = "",
        validFromEpochDay: Long? = null,
        validToEpochDay: Long? = null,
        isActive: Boolean = true,
        planBlockId: Long? = null,
    ) = PlanFormState(
        planBlockId = planBlockId,
        title = title,
        dayOfWeek = dayOfWeek,
        startText = startText,
        endText = endText,
        categoryId = categoryId,
        activityId = activityId,
        location = location,
        validFromEpochDay = validFromEpochDay,
        validToEpochDay = validToEpochDay,
        isActive = isActive,
    )

    // ---------------- 时间解析 ----------------
    // 解析 / 格式化 / 时长的测试已随实现一起移到 TimeOfDayTest；
    // 这里只保留「表单怎么用它们做判断」的用例。

    // ---------------- 校验 ----------------

    @Test
    fun `title is required and trimmed`() {
        assertEquals(
            PlanFormResult.Invalid(PlanFormError.EMPTY_TITLE),
            PlanForm.validate(form(title = "   ")),
        )

        val result = PlanForm.validate(form(title = "  高等数学  "))
        assertEquals("高等数学", (result as PlanFormResult.Valid).planBlock.title)
    }

    @Test
    fun `title has a length limit`() {
        val tooLong = "课".repeat(PlanForm.MAX_TITLE_LENGTH + 1)
        assertEquals(
            PlanFormResult.Invalid(PlanFormError.TITLE_TOO_LONG),
            PlanForm.validate(form(title = tooLong)),
        )
        assertEquals(20, PlanForm.MAX_TITLE_LENGTH)
    }

    @Test
    fun `invalid or missing time is rejected`() {
        assertEquals(
            PlanFormResult.Invalid(PlanFormError.INVALID_TIME),
            PlanForm.validate(form(startText = "")),
        )
        assertEquals(
            PlanFormResult.Invalid(PlanFormError.INVALID_TIME),
            PlanForm.validate(form(endText = "25:00")),
        )
    }

    @Test
    fun `start cannot be 24 00 while end can`() {
        // 领域模型要求 开始 0..1439、结束 1..1440
        assertEquals(
            PlanFormResult.Invalid(PlanFormError.INVALID_TIME),
            PlanForm.validate(form(startText = "24:00", endText = "24:00")),
        )
        // 22:00–24:00 是合法的：表示上到当天结束
        val result = PlanForm.validate(form(startText = "22:00", endText = "24:00"))
        val plan = (result as PlanFormResult.Valid).planBlock
        assertEquals(1320, plan.startMinuteOfDay)
        assertEquals(1440, plan.endMinuteOfDay)
        assertEquals(120, plan.durationMinutes)
        assertFalse(plan.crossesMidnight)
    }

    @Test
    fun `end cannot be midnight`() {
        assertEquals(
            PlanFormResult.Invalid(PlanFormError.INVALID_TIME),
            PlanForm.validate(form(startText = "00:00", endText = "0:00")),
        )
    }

    @Test
    fun `zero duration is rejected`() {
        assertEquals(
            PlanFormResult.Invalid(PlanFormError.ZERO_DURATION),
            PlanForm.validate(form(startText = "09:00", endText = "09:00")),
        )
    }

    @Test
    fun `cross midnight is allowed`() {
        // 需求五节明确支持跨夜；23:30–01:00 是 90 分钟而不是负数
        val result = PlanForm.validate(form(startText = "23:30", endText = "01:00"))

        val plan = (result as PlanFormResult.Valid).planBlock
        assertEquals(1410, plan.startMinuteOfDay)
        assertEquals(60, plan.endMinuteOfDay)
        assertTrue(plan.crossesMidnight)
        assertEquals(90, plan.durationMinutes)
    }

    // ---------------- 其余字段 ----------------

    @Test
    fun `form always creates weekly recurring entries`() {
        val result = PlanForm.validate(form(dayOfWeek = 3))
        val plan = (result as PlanFormResult.Valid).planBlock
        // V1 的表单只做每周重复
        assertNull(plan.dateEpochDay)
        assertEquals(3, plan.dayOfWeek)
    }

    @Test
    fun `passthrough fields are preserved`() {
        val result = PlanForm.validate(
            form(
                planBlockId = 9L,
                validFromEpochDay = 100L,
                validToEpochDay = 200L,
                isActive = false,
            )
        )

        val plan = (result as PlanFormResult.Valid).planBlock
        assertEquals(9L, plan.id)
        // 少了这两个，编辑一次课程就会把「生效区间」清掉
        assertEquals(100L, plan.validFromEpochDay)
        assertEquals(200L, plan.validToEpochDay)
        assertFalse(plan.isActive)
    }

    @Test
    fun `blank location becomes null`() {
        val result = PlanForm.validate(form(location = "   "))
        assertNull((result as PlanFormResult.Valid).planBlock.location)

        val withLocation = PlanForm.validate(form(location = " 文一 203 "))
        assertEquals("文一 203", (withLocation as PlanFormResult.Valid).planBlock.location)
    }

    @Test
    fun `binding may be absent`() {
        val result = PlanForm.validate(form(categoryId = null, activityId = null))
        val plan = (result as PlanFormResult.Valid).planBlock
        // 只写「自习」不归类是合法的
        assertNull(plan.categoryId)
        assertNull(plan.activityId)
    }

    // ---------------- 回填与预览 ----------------

    @Test
    fun `form is filled from an existing plan block`() {
        val plan = PlanBlock(
            id = 9L,
            title = "夜间自习",
            dateEpochDay = null,
            dayOfWeek = 6,
            startMinuteOfDay = 1410,
            endMinuteOfDay = 60,
            categoryId = 1L,
            location = "图书馆",
            validFromEpochDay = 100L,
            isActive = false,
        )

        val form = PlanForm.from(plan)

        assertEquals(9L, form.planBlockId)
        assertTrue(form.isEditing)
        assertEquals("夜间自习", form.title)
        assertEquals(6, form.dayOfWeek)
        // 分钟数回填成人能读的时间
        assertEquals("23:30", form.startText)
        assertEquals("01:00", form.endText)
        assertEquals("图书馆", form.location)
        assertEquals(100L, form.validFromEpochDay)
        assertFalse(form.isActive)
    }

    @Test
    fun `round trip through the form preserves the plan`() {
        val plan = PlanBlock(
            id = 0L,
            title = "软件工程",
            dateEpochDay = null,
            dayOfWeek = 2,
            startMinuteOfDay = 840,
            endMinuteOfDay = 940,
            categoryId = 1L,
        )

        val result = PlanForm.validate(PlanForm.from(plan))
        val restored = (result as PlanFormResult.Valid).planBlock

        assertEquals(plan.title, restored.title)
        assertEquals(plan.dayOfWeek, restored.dayOfWeek)
        assertEquals(plan.startMinuteOfDay, restored.startMinuteOfDay)
        assertEquals(plan.endMinuteOfDay, restored.endMinuteOfDay)
        assertEquals(plan.durationMinutes, restored.durationMinutes)
    }
}
