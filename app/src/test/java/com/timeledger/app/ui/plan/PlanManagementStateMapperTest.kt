package com.timeledger.app.ui.plan

import com.timeledger.app.domain.model.Activity
import com.timeledger.app.domain.model.Category
import com.timeledger.app.domain.model.PlanBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课程表状态映射测试。
 *
 * 课程表按「星期几」组织，所以最要紧的是分组与排序规则：
 * 排错了，用户会觉得「我明明把课放在周一」。
 */
class PlanManagementStateMapperTest {

    private val mapper = PlanManagementStateMapper()

    private val study = Category(id = 1L, name = "学习", colorArgb = 0xFF3F6BFF.toInt(), sortOrder = 0)
    private val funCategory = Category(id = 2L, name = "娱乐", colorArgb = 0xFFFF8A3D.toInt(), sortOrder = 1)
    private val java = Activity(id = 100L, categoryId = 1L, name = "Java", sortOrder = 0)
    private val algorithm = Activity(id = 101L, categoryId = 1L, name = "算法", sortOrder = 1)
    private val bilibili = Activity(id = 200L, categoryId = 2L, name = "B站", sortOrder = 0)

    private fun categories() = listOf(study, funCategory)
    private fun activities() = listOf(java, algorithm, bilibili)

    private fun plan(
        id: Long = 1L,
        title: String = "高等数学",
        dayOfWeek: Int? = 1,
        dateEpochDay: Long? = null,
        startMinuteOfDay: Int = 540,
        endMinuteOfDay: Int = 640,
        categoryId: Long? = 1L,
        activityId: Long? = null,
        location: String? = null,
        isActive: Boolean = true,
    ) = PlanBlock(
        id = id,
        title = title,
        dateEpochDay = dateEpochDay,
        dayOfWeek = dayOfWeek,
        startMinuteOfDay = startMinuteOfDay,
        endMinuteOfDay = endMinuteOfDay,
        categoryId = categoryId,
        activityId = activityId,
        location = location,
        isActive = isActive,
    )

    private fun map(
        plans: List<PlanBlock> = emptyList(),
        categories: List<Category> = categories(),
        activities: List<Activity> = activities(),
        form: PlanFormState? = null,
        formError: PlanFormError? = null,
    ) = mapper.map(plans, categories, activities, form, formError)

    // ---------------- 分组与排序 ----------------

    @Test
    fun `plans are grouped by weekday`() {
        val state = map(
            plans = listOf(
                plan(id = 1L, title = "周一课", dayOfWeek = 1),
                plan(id = 2L, title = "周三课", dayOfWeek = 3),
            )
        )

        assertEquals(7, state.weeklyGroups.size)
        assertEquals(listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日"),
            state.weeklyGroups.map { it.dayLabel })
        assertEquals(listOf("周一课"), state.weeklyGroups[0].rows.map { it.title })
        assertEquals(0, state.weeklyGroups[1].count)
        assertEquals(listOf("周三课"), state.weeklyGroups[2].rows.map { it.title })
    }

    @Test
    fun `rows are ordered by start time with inactive ones last`() {
        val state = map(
            plans = listOf(
                plan(id = 1L, title = "下午课", startMinuteOfDay = 840, endMinuteOfDay = 940),
                plan(id = 2L, title = "停用的早课", startMinuteOfDay = 540, endMinuteOfDay = 640, isActive = false),
                plan(id = 3L, title = "早课", startMinuteOfDay = 540, endMinuteOfDay = 660),
            )
        )

        // 停用的排最后：它还在列表里（可以恢复），但不该挡在正常课程前面
        assertEquals(
            listOf("早课", "下午课", "停用的早课"),
            state.weeklyGroups[0].rows.map { it.title },
        )
    }

    @Test
    fun `single date plans go to their own bucket`() {
        val state = map(
            plans = listOf(
                plan(id = 1L, title = "每周课", dayOfWeek = 1),
                plan(id = 2L, title = "临时讲座", dayOfWeek = null, dateEpochDay = 20_000L),
            )
        )

        // 单次日程没有「星期几」这个属性，单独一组；藏起来等于吞掉用户数据
        assertTrue(state.hasSingleDatePlans)
        assertEquals(listOf("临时讲座"), state.singleDateRows.map { it.title })
        assertEquals(1, state.weeklyGroups.sumOf { it.count })
    }

    @Test
    fun `empty plan list is flagged`() {
        val state = map()
        assertTrue(state.isEmpty)
        assertNull(state.summaryLabel)
        assertFalse(state.hasSingleDatePlans)
        assertTrue(state.weeklyGroups.all { it.count == 0 })
    }

    // ---------------- 行内容 ----------------

    @Test
    fun `row shows time range and duration`() {
        val state = map(plans = listOf(plan(startMinuteOfDay = 540, endMinuteOfDay = 640)))
        val row = state.weeklyGroups[0].rows.single()

        assertEquals("09:00–10:40", row.timeRangeLabel)
        assertEquals("1 小时 40 分", row.durationLabel)
        assertFalse(row.crossesMidnight)
    }

    @Test
    fun `row flags cross midnight plans`() {
        val state = map(plans = listOf(plan(startMinuteOfDay = 1410, endMinuteOfDay = 60)))
        val row = state.weeklyGroups[0].rows.single()

        assertEquals("23:30–01:00", row.timeRangeLabel)
        assertTrue(row.crossesMidnight)
        assertEquals("1 小时 30 分", row.durationLabel)
    }

    @Test
    fun `binding label prefers activity then category`() {
        val withActivity = map(
            plans = listOf(plan(activityId = 100L))
        ).weeklyGroups[0].rows.single()
        assertEquals("学习 / Java", withActivity.bindingLabel)

        val categoryOnly = map(
            plans = listOf(plan(activityId = null))
        ).weeklyGroups[0].rows.single()
        assertEquals("学习", categoryOnly.bindingLabel)

        val unbound = map(
            plans = listOf(plan(categoryId = null, activityId = null))
        ).weeklyGroups[0].rows.single()
        assertNull(unbound.bindingLabel)
    }

    @Test
    fun `colour falls back from activity to category`() {
        val categoryColoured = map(plans = listOf(plan(activityId = 100L)))
            .weeklyGroups[0].rows.single()
        assertEquals(0xFF3F6BFF.toInt(), categoryColoured.colorArgb)

        val activityColoured = map(
            plans = listOf(plan(activityId = 200L)),
            activities = listOf(bilibili.copy(colorArgb = 0xFF00A5A5.toInt())),
        ).weeklyGroups[0].rows.single()
        assertEquals(0xFF00A5A5.toInt(), activityColoured.colorArgb)
    }

    @Test
    fun `summary counts only active plans`() {
        val state = map(
            plans = listOf(
                plan(id = 1L, startMinuteOfDay = 540, endMinuteOfDay = 640),  // 100 分钟
                plan(id = 2L, startMinuteOfDay = 660, endMinuteOfDay = 720),  // 60 分钟
                plan(id = 3L, startMinuteOfDay = 840, endMinuteOfDay = 930, isActive = false),
            )
        )

        // 停用的课不该出现在「每周几节」里
        assertEquals("每周 2 节，共 2 小时 40 分", state.summaryLabel)
    }

    @Test
    fun `summary is absent when everything is inactive`() {
        val state = map(plans = listOf(plan(isActive = false)))
        assertNull(state.summaryLabel)
    }

    // ---------------- 表单 ----------------

    @Test
    fun `form day options cover the whole week`() {
        val state = map(form = PlanForm.empty())
        val form = state.form!!

        assertEquals(listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日"), form.dayOptions)
        assertTrue(state.isFormOpen)
    }

    @Test
    fun `form filters activities by the selected category`() {
        val studyForm = map(form = PlanForm.empty().copy(categoryId = 1L)).form!!
        assertEquals(listOf("Java", "算法"), studyForm.activities.map { it.label })

        val funForm = map(form = PlanForm.empty().copy(categoryId = 2L)).form!!
        assertEquals(listOf("B站"), funForm.activities.map { it.label })

        val noCategory = map(form = PlanForm.empty()).form!!
        assertTrue(noCategory.activities.isEmpty())
    }

    @Test
    fun `form duration preview updates with the typed time`() {
        val form = map(form = PlanForm.empty().copy(startText = "14:00", endText = "15:40")).form!!
        assertEquals("1 小时 40 分", form.durationLabel)
    }

    @Test
    fun `form shows the cross midnight hint only for crossing entries`() {
        val crossing = map(form = PlanForm.empty().copy(startText = "23:30", endText = "01:00")).form!!
        assertTrue(crossing.crossesMidnight)

        val normal = map(form = PlanForm.empty().copy(startText = "09:00", endText = "10:00")).form!!
        assertFalse(normal.crossesMidnight)

        // 22:00–24:00 结束时刻晚于开始，不算跨夜
        val toMidnight = map(form = PlanForm.empty().copy(startText = "22:00", endText = "24:00")).form!!
        assertFalse(toMidnight.crossesMidnight)
    }

    @Test
    fun `can save mirrors the form validation`() {
        val complete = map(
            form = PlanForm.empty().copy(title = "高数", startText = "09:00", endText = "10:40")
        )
        assertTrue(complete.form!!.canSave)

        val noTitle = map(form = PlanForm.empty().copy(startText = "09:00", endText = "10:40"))
        assertFalse(noTitle.form!!.canSave)

        val badTime = map(form = PlanForm.empty().copy(title = "高数", startText = "9点", endText = "10:40"))
        assertFalse(badTime.form!!.canSave)
    }

    @Test
    fun `error messages are readable`() {
        assertEquals(
            "请填写名称",
            map(form = PlanForm.empty(), formError = PlanFormError.EMPTY_TITLE).form!!.errorMessage,
        )
        assertEquals(
            "开始和结束不能是同一个时刻",
            map(form = PlanForm.empty(), formError = PlanFormError.ZERO_DURATION).form!!.errorMessage,
        )
        assertEquals(
            "时间请按 09:00 这样的格式填写（结束可以填 24:00）",
            map(form = PlanForm.empty(), formError = PlanFormError.INVALID_TIME).form!!.errorMessage,
        )
        assertNull(map(form = PlanForm.empty()).form!!.errorMessage)
    }
}
