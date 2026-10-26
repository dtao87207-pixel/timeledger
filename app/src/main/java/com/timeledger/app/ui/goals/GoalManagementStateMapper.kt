package com.timeledger.app.ui.goals

import com.timeledger.app.domain.goal.GoalProgress
import com.timeledger.app.domain.model.Activity
import com.timeledger.app.domain.model.Category
import com.timeledger.app.domain.model.Goal
import com.timeledger.app.domain.model.GoalPeriod
import com.timeledger.app.domain.model.GoalType
import com.timeledger.app.ui.day.CatalogLookup
import com.timeledger.app.ui.day.PickerOption
import com.timeledger.app.ui.format.DisplayFormat

/**
 * 目标与目录数据 → 目标管理页界面状态的**纯函数**映射。
 *
 * 与其他界面映射器同样的理由：本机无法目视验证界面，
 * 所以「卡片显示什么、表单能不能保存、活动选项按什么过滤」这些判断
 * 全部集中在这里，用单元测试钉死。
 *
 * 文案硬编码而不放 `strings.xml`，同样是为了让这个类保持纯 JVM 类、
 * 测试不需要 Android 环境（详见 README 的取舍说明）。
 */
class GoalManagementStateMapper {

    fun map(
        goals: List<Goal>,
        progress: List<GoalProgress>,
        categories: List<Category>,
        activities: List<Activity>,
        form: GoalFormState?,
        formError: GoalFormError?,
    ): GoalManagementUiState {
        val catalog = CatalogLookup.from(categories, activities)
        val progressByGoalId = progress.associateBy { it.goal.id }

        val cards = goals.map { goal -> card(goal, progressByGoalId[goal.id], catalog) }

        return GoalManagementUiState(
            goalCards = cards,
            isEmpty = cards.isEmpty(),
            form = form?.let { formUi(it, categories, activities, formError) },
        )
    }

    private fun card(
        goal: Goal,
        progress: GoalProgress?,
        catalog: CatalogLookup,
    ): GoalCardUi = GoalCardUi(
        goalId = goal.id,
        title = goal.title,
        typeLabel = typeLabel(goal.type),
        periodLabel = periodLabel(goal.period),
        bindingLabel = bindingLabel(goal, catalog),
        targetLabel = DisplayFormat.goalTargetLabel(goal.targetValue, goal.type),
        progressLabel = progress?.let {
            "${DisplayFormat.goalAchievedLabel(it.achieved, goal.type)} / " +
                DisplayFormat.goalTargetLabel(it.target, goal.type)
        },
        progressFraction = progress?.progressFraction?.toFloat() ?: 0f,
        isAchieved = progress?.isAchieved ?: false,
        isActive = goal.isActive,
        // 活动没配颜色就退回分类色，卡片上的色条仍能看出归属
        colorArgb = catalog.activityColor(goal.activityId) ?: catalog.categoryColor(goal.categoryId),
    )

    private fun formUi(
        form: GoalFormState,
        categories: List<Category>,
        activities: List<Activity>,
        error: GoalFormError?,
    ): GoalFormUiState {
        val selectableCategories = categories.filterNot { it.isArchived }
        val selectedCategoryId = form.categoryId

        val activityOptions = activities
            .filterNot { it.isArchived }
            // 只列当前所选分类下的活动：这样用户不可能选出「活动与分类不一致」的组合，
            // 而那种组合会被仓库直接拒绝
            .filter { it.categoryId == selectedCategoryId }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
            .map { activity ->
                PickerOption(
                    id = activity.id,
                    label = activity.name,
                    colorArgb = activity.colorArgb
                        ?: selectableCategories.firstOrNull { it.id == activity.categoryId }?.colorArgb,
                )
            }

        // 按钮置灰与保存时的校验用同一套规则，避免出现「按钮能点但保存失败」
        val canSave = GoalForm.validate(form) is GoalFormResult.Valid

        return GoalFormUiState(
            isEditing = form.isEditing,
            title = form.title,
            type = form.type,
            period = form.period,
            targetText = form.targetText,
            targetUnitLabel = GoalForm.targetUnitLabel(form.type),
            categories = selectableCategories
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))
                .map { PickerOption(it.id, it.name, it.colorArgb) },
            activities = activityOptions,
            selectedCategoryId = selectedCategoryId,
            selectedActivityId = form.activityId,
            canSave = canSave,
            errorMessage = error?.let(::errorMessage),
        )
    }

    private fun typeLabel(type: GoalType): String = when (type) {
        GoalType.TIME -> "时长"
        GoalType.COUNT -> "计数"
        GoalType.FREQUENCY -> "频次"
    }

    /** 注意这里是「每日 / 每周 / 每月」（定义目标时的说法），
     *  与展示进度时的「今日 / 本周 / 本月」不同 —— 前者描述规则，后者描述当前这一期。 */
    private fun periodLabel(period: GoalPeriod): String = when (period) {
        GoalPeriod.DAY -> "每日"
        GoalPeriod.WEEK -> "每周"
        GoalPeriod.MONTH -> "每月"
    }

    private fun bindingLabel(goal: Goal, catalog: CatalogLookup): String {
        val activityName = catalog.activityName(goal.activityId)
        val categoryName = catalog.categoryName(goal.categoryId)
        return when {
            activityName != null && categoryName != null -> "$categoryName / $activityName"
            activityName != null -> activityName
            categoryName != null -> categoryName
            else -> "未指定"
        }
    }

    private fun errorMessage(error: GoalFormError): String = when (error) {
        GoalFormError.EMPTY_TITLE -> "请填写目标名称"
        GoalFormError.INVALID_TARGET -> "目标值要填一个大于 0 的数字"
        GoalFormError.MISSING_BINDING -> "请选择分类（可以再指定具体活动）"
        GoalFormError.SAVE_FAILED -> "保存失败：所选分类或活动已不可用，请重新选择"
    }
}
