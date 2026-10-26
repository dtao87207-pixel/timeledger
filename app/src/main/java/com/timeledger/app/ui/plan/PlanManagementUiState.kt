package com.timeledger.app.ui.plan

import com.timeledger.app.ui.day.PickerOption

/** 课程表里的一行。 */
data class PlanRowUi(
    val planBlockId: Long,
    val title: String,
    /** `09:00–10:40`。 */
    val timeRangeLabel: String,
    /** `1 小时 40 分`。 */
    val durationLabel: String,
    val location: String?,
    /** 绑定的分类 / 活动，例如 `学习`；没绑定时为 null。 */
    val bindingLabel: String?,
    val colorArgb: Int?,
    val crossesMidnight: Boolean,
    val isInactive: Boolean,
)

/** 某个星期几的课程分组。 */
data class PlanDayGroupUi(
    /** 1=周一 … 7=周日。 */
    val dayOfWeek: Int,
    val dayLabel: String,
    val rows: List<PlanRowUi>,
) {
    val count: Int get() = rows.size
}

/** 课程表表单的界面状态。 */
data class PlanFormUiState(
    val isEditing: Boolean,
    val title: String,
    /** 七个星期几的标签，顺序固定。 */
    val dayOptions: List<String>,
    val selectedDayOfWeek: Int,
    val startText: String,
    val endText: String,
    /** 实时时长预览：填完就能看到「1 小时 40 分」，不用自己心算。 */
    val durationLabel: String?,
    val categories: List<PickerOption>,
    val activities: List<PickerOption>,
    val selectedCategoryId: Long?,
    val selectedActivityId: Long?,
    val location: String,
    /** 这一条会跨过午夜（结束时间不晚于开始时间）。 */
    val crossesMidnight: Boolean,
    val canSave: Boolean,
    val errorMessage: String?,
)

/**
 * 课程表管理页状态（需求五节）。
 *
 * 与其它界面一样是**纯数据**，由 [PlanManagementStateMapper] 算出来。
 * 课程表按「星期几」组织，正是它和普通任务列表最大的不同：用户是按周来想事情的。
 */
data class PlanManagementUiState(
    val weeklyGroups: List<PlanDayGroupUi>,
    /**
     * 单次日程（指定具体日期、不重复）。
     *
     * 单独一组而不是塞进星期分组：它没有「星期几」这个属性。
     * V1 的表单还不会创建这类条目，但数据层支持、将来导入课表也会用到 ——
     * 界面直接把它们藏起来等于悄悄吞掉用户数据。
     */
    val singleDateRows: List<PlanRowUi>,
    val isEmpty: Boolean,
    /** `每周 4 节，共 6 小时 40 分`；没有生效条目时为 null。 */
    val summaryLabel: String?,
    val form: PlanFormUiState?,
) {
    val isFormOpen: Boolean get() = form != null

    val hasSingleDatePlans: Boolean get() = singleDateRows.isNotEmpty()
}
