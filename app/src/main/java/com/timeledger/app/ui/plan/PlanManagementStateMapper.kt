package com.timeledger.app.ui.plan

import com.timeledger.app.domain.model.Activity
import com.timeledger.app.domain.model.Category
import com.timeledger.app.domain.model.PlanBlock
import com.timeledger.app.ui.day.CatalogLookup
import com.timeledger.app.ui.day.PickerOption
import com.timeledger.app.ui.format.TimeOfDay
import java.time.DayOfWeek

/**
 * 课程表数据 → 界面状态的**纯函数**映射。
 *
 * 与其他界面映射器同样的理由：本机无法目视验证界面，
 * 所以「按星期几分组、时间怎么显示、能不能保存」这些判断都在这里被测试钉死。
 */
class PlanManagementStateMapper {

    fun map(
        plans: List<PlanBlock>,
        categories: List<Category>,
        activities: List<Activity>,
        form: PlanFormState?,
        formError: PlanFormError?,
    ): PlanManagementUiState {
        val catalog = CatalogLookup.from(categories, activities)

        // 每周重复的按星期几分组；已停用的排在各自分组最后
        val weeklyGroups = (1..7).map { dayOfWeek ->
            val rows = plans
                .filter { it.dayOfWeek == dayOfWeek && it.dateEpochDay == null }
                .sortedWith(compareBy({ it.isActive.not() }, { it.startMinuteOfDay }, { it.id }))
                .map { row(it, catalog) }

            PlanDayGroupUi(
                dayOfWeek = dayOfWeek,
                dayLabel = weekdayLabel(dayOfWeek),
                rows = rows,
            )
        }

        val singleDateRows = plans
            .filter { it.dateEpochDay != null }
            .sortedWith(compareBy({ it.dateEpochDay }, { it.startMinuteOfDay }, { it.id }))
            .map { row(it, catalog) }

        return PlanManagementUiState(
            weeklyGroups = weeklyGroups,
            singleDateRows = singleDateRows,
            // 「空」以「一个条目都没有」为准，而不是「某个分组为空」——
            // 一周里只有周二有课是完全正常的
            isEmpty = plans.isEmpty(),
            summaryLabel = summaryLabel(plans),
            form = form?.let { formUi(it, categories, activities, formError) },
        )
    }

    private fun row(plan: PlanBlock, catalog: CatalogLookup): PlanRowUi = PlanRowUi(
        planBlockId = plan.id,
        title = plan.title,
        timeRangeLabel = "${TimeOfDay.minuteOfDayText(plan.startMinuteOfDay)}–" +
            TimeOfDay.minuteOfDayText(plan.endMinuteOfDay),
        durationLabel = TimeOfDay.durationText(
            TimeOfDay.minuteOfDayText(plan.startMinuteOfDay),
            TimeOfDay.minuteOfDayText(plan.endMinuteOfDay),
        ).orEmpty(),
        location = plan.location,
        bindingLabel = bindingLabel(plan, catalog),
        colorArgb = catalog.activityColor(plan.activityId) ?: catalog.categoryColor(plan.categoryId),
        crossesMidnight = plan.crossesMidnight,
        isInactive = !plan.isActive,
    )

    /** 只统计生效中的条目：停用的课不该出现在「每周几节」里。 */
    private fun summaryLabel(plans: List<PlanBlock>): String? {
        val active = plans.filter { it.isActive }
        if (active.isEmpty()) return null

        val totalMinutes = active.sumOf { it.durationMinutes }
        return "每周 ${active.size} 节，共 ${durationText(totalMinutes)}"
    }

    private fun durationText(totalMinutes: Int): String {
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "$hours 小时 $minutes 分"
            hours > 0 -> "$hours 小时"
            else -> "$minutes 分钟"
        }
    }

    private fun bindingLabel(plan: PlanBlock, catalog: CatalogLookup): String? {
        val activityName = catalog.activityName(plan.activityId)
        val categoryName = catalog.categoryName(plan.categoryId)
        return when {
            activityName != null && categoryName != null -> "$categoryName / $activityName"
            activityName != null -> activityName
            else -> categoryName
        }
    }

    private fun formUi(
        form: PlanFormState,
        categories: List<Category>,
        activities: List<Activity>,
        error: PlanFormError?,
    ): PlanFormUiState {
        val activityOptions = activities
            .filterNot { it.isArchived }
            // 只列所选分类下的活动：这样用户不可能选出「活动与分类不一致」的组合（会被仓库拒绝）
            .filter { it.categoryId == form.categoryId }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
            .map { activity ->
                PickerOption(
                    id = activity.id,
                    label = activity.name,
                    colorArgb = activity.colorArgb
                        ?: categories.firstOrNull { it.id == activity.categoryId }?.colorArgb,
                )
            }

        val start = TimeOfDay.parseMinuteOfDay(form.startText)
        val end = TimeOfDay.parseMinuteOfDay(form.endText)

        return PlanFormUiState(
            isEditing = form.isEditing,
            title = form.title,
            dayOptions = (1..7).map(::weekdayLabel),
            selectedDayOfWeek = form.dayOfWeek,
            startText = form.startText,
            endText = form.endText,
            durationLabel = TimeOfDay.durationText(form.startText, form.endText),
            categories = categories
                .filterNot { it.isArchived }
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))
                .map { PickerOption(it.id, it.name, it.colorArgb) },
            activities = activityOptions,
            selectedCategoryId = form.categoryId,
            selectedActivityId = form.activityId,
            location = form.location,
            // 结束不晚于开始 = 跨夜。提示出来，避免用户以为填错了却没被告知
            crossesMidnight = start != null && end != null && end <= start && end != start,
            canSave = PlanForm.validate(form) is PlanFormResult.Valid,
            errorMessage = error?.let(::errorMessage),
        )
    }

    private fun weekdayLabel(dayOfWeek: Int): String = when (dayOfWeek) {
        DayOfWeek.MONDAY.value -> "周一"
        DayOfWeek.TUESDAY.value -> "周二"
        DayOfWeek.WEDNESDAY.value -> "周三"
        DayOfWeek.THURSDAY.value -> "周四"
        DayOfWeek.FRIDAY.value -> "周五"
        DayOfWeek.SATURDAY.value -> "周六"
        else -> "周日"
    }

    private fun errorMessage(error: PlanFormError): String = when (error) {
        PlanFormError.EMPTY_TITLE -> "请填写名称"
        PlanFormError.TITLE_TOO_LONG ->
            "名称最多 ${PlanForm.MAX_TITLE_LENGTH} 个字，再短一点吧"

        PlanFormError.INVALID_TIME -> "时间请按 09:00 这样的格式填写（结束可以填 24:00）"
        PlanFormError.ZERO_DURATION -> "开始和结束不能是同一个时刻"
        PlanFormError.SAVE_FAILED -> "保存失败：所选的分类或活动已不可用，请重新选择"
    }
}
