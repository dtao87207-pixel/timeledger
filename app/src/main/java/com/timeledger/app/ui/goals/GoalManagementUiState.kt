package com.timeledger.app.ui.goals

import com.timeledger.app.domain.model.GoalPeriod
import com.timeledger.app.domain.model.GoalType
import com.timeledger.app.ui.day.PickerOption

/** 目标列表里的一张卡片。 */
data class GoalCardUi(
    val goalId: Long,
    val title: String,
    /** 时长 / 计数 / 频次。 */
    val typeLabel: String,
    /** 每日 / 每周 / 每月。 */
    val periodLabel: String,
    /** `学习 / Java` 或 `学习`。 */
    val bindingLabel: String,
    /** 目标值：`3h` / `2个` / `3次`。 */
    val targetLabel: String,
    /** 当前进度：`2h20m / 3h`；今天不适用该目标时为 null。 */
    val progressLabel: String?,
    val progressFraction: Float,
    val isAchieved: Boolean,
    val isActive: Boolean,
    val colorArgb: Int?,
)

/** 表单的界面状态。 */
data class GoalFormUiState(
    val isEditing: Boolean,
    val title: String,
    val type: GoalType,
    val period: GoalPeriod,
    val targetText: String,
    /** 目标值旁边的单位：小时 / 个 / 次。 */
    val targetUnitLabel: String,
    val categories: List<PickerOption>,
    /** 只列当前所选分类下的活动。 */
    val activities: List<PickerOption>,
    val selectedCategoryId: Long?,
    val selectedActivityId: Long?,
    /** 表单填完整了才允许保存（按钮据此置灰）。 */
    val canSave: Boolean,
    val errorMessage: String?,
)

/**
 * 目标管理页状态。
 *
 * 和其他界面一样是**纯数据**，由 [GoalManagementStateMapper] 算出来 ——
 * 本机没有模拟器与设备，界面无法目视验证，把「显示什么、能不能保存」的判断
 * 放进可测的映射器是唯一可靠的做法。
 */
data class GoalManagementUiState(
    val goalCards: List<GoalCardUi>,
    /** 一个目标都没有。 */
    val isEmpty: Boolean,
    /** 非空表示表单已打开。 */
    val form: GoalFormUiState?,
) {
    val isFormOpen: Boolean get() = form != null
}
