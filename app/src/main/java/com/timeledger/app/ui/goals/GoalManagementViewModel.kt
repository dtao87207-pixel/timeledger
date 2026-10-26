package com.timeledger.app.ui.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.di.AppContainer
import com.timeledger.app.domain.goal.GoalProgressService
import com.timeledger.app.domain.model.GoalPeriod
import com.timeledger.app.domain.model.GoalType
import com.timeledger.app.domain.repository.ActivityRepository
import com.timeledger.app.domain.repository.CategoryRepository
import com.timeledger.app.domain.repository.GoalRepository
import com.timeledger.app.domain.repository.RejectReason
import com.timeledger.app.domain.repository.SaveOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 目标管理 ViewModel（需求四节）。
 *
 * 目标是产品闭环的第一环：没有它，「记录 → 复盘 → 调整目标」这条链条就断了，
 * 用户装了这个 App 只能记录与统计。
 *
 * 三件事：列表（含当前进度）、表单（新建/编辑）、启停与删除。
 * 所有「显示什么、能不能保存」的判断在 [GoalManagementStateMapper] 与 [GoalForm] 里，
 * 那两者都是纯函数、可在 JVM 上测 —— 本机没有模拟器与设备，这是唯一可靠的验证方式。
 */
class GoalManagementViewModel(
    private val goalRepository: GoalRepository,
    private val goalProgressService: GoalProgressService,
    private val categoryRepository: CategoryRepository,
    private val activityRepository: ActivityRepository,
    private val dayBoundary: DayBoundary,
) : ViewModel() {

    private val mapper = GoalManagementStateMapper()
    private val form = MutableStateFlow<GoalFormState?>(null)
    private val formError = MutableStateFlow<GoalFormError?>(null)

    /** 列表里的进度按「今天」算，进入页面时取一次即可。 */
    private val today = dayBoundary.todayEpochDay()

    private data class FormSnapshot(val form: GoalFormState?, val error: GoalFormError?)

    private val formSnapshot: Flow<FormSnapshot> =
        combine(form, formError) { state, error -> FormSnapshot(state, error) }

    val state: StateFlow<GoalManagementUiState?> = combine(
        goalRepository.observeGoals(activeOnly = false),
        goalProgressService.observeDayProgress(today),
        // 目录取**全部**（含已归档）：归档分类的名字仍要能显示出来，
        // 否则一个绑定到已归档分类的旧目标会显示成「未指定」，
        // 用户会以为目标坏了。选择器里再过滤掉已归档项即可（见映射器）。
        categoryRepository.observeCategories(includeArchived = true),
        activityRepository.observeActivities(includeArchived = true),
        formSnapshot,
    ) { goals, progress, categories, activities, snapshot ->
        mapper.map(
            goals = goals,
            progress = progress,
            categories = categories,
            activities = activities,
            form = snapshot.form,
            formError = snapshot.error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    // ---------------- 表单 ----------------

    fun startCreate() {
        form.value = GoalForm.empty()
        formError.value = null
    }

    fun startEdit(goalId: Long) {
        viewModelScope.launch {
            val goal = goalRepository.getGoal(goalId) ?: return@launch
            form.value = GoalForm.from(goal)
            formError.value = null
        }
    }

    fun dismissForm() {
        form.value = null
        formError.value = null
    }

    fun updateTitle(text: String) = mutateForm { it.copy(title = text) }

    /**
     * 切换类型。
     *
     * 目标值不跟着清空：用户在 TIME 里填的「3」切到 COUNT 后就是「3 个」，
     * 保留输入比强制重填更省事，而单位标签会同步变化，不会被误读。
     */
    fun updateType(type: GoalType) = mutateForm { it.copy(type = type) }

    fun updatePeriod(period: GoalPeriod) = mutateForm { it.copy(period = period) }

    fun updateTarget(text: String) = mutateForm { it.copy(targetText = text) }

    /** 换分类时必须清掉已选活动，否则会留下「活动与分类不一致」的组合，保存必被拒绝。 */
    fun selectCategory(categoryId: Long) =
        mutateForm { it.copy(categoryId = categoryId, activityId = null) }

    fun selectActivity(activityId: Long?) = mutateForm { it.copy(activityId = activityId) }

    fun save() {
        val current = form.value ?: return
        when (val result = GoalForm.validate(current)) {
            is GoalFormResult.Invalid -> formError.value = result.error

            is GoalFormResult.Valid -> {
                formError.value = null
                viewModelScope.launch {
                    val outcome = if (current.isEditing) {
                        goalRepository.updateGoal(result.goal)
                    } else {
                        goalRepository.createGoal(result.goal)
                    }
                    when (outcome) {
                        is SaveOutcome.Saved, SaveOutcome.Skipped -> form.value = null
                        is SaveOutcome.Rejected -> formError.value = outcome.reason.toFormError()
                    }
                }
            }
        }
    }

    // ---------------- 启停与删除 ----------------

    fun setActive(goalId: Long, active: Boolean) {
        viewModelScope.launch {
            if (!active) {
                goalRepository.deactivateGoal(goalId)
                return@launch
            }
            // 没有单独的「启用」接口：取回目标改状态再存，时间戳由仓库统一补
            val goal = goalRepository.getGoal(goalId) ?: return@launch
            goalRepository.updateGoal(goal.copy(isActive = true))
        }
    }

    fun delete(goalId: Long) {
        viewModelScope.launch {
            goalRepository.deleteGoal(goalId)
        }
    }

    private fun mutateForm(transform: (GoalFormState) -> GoalFormState) {
        val current = form.value ?: return
        form.value = transform(current)
        // 用户一改输入就清掉上一次的报错，否则提示会一直挂在那里
        formError.value = null
    }

    private fun RejectReason.toFormError(): GoalFormError = when (this) {
        RejectReason.INVALID_NAME -> GoalFormError.EMPTY_TITLE
        RejectReason.UNKNOWN_CATEGORY_OR_ACTIVITY,
        RejectReason.ACTIVITY_CATEGORY_MISMATCH,
        -> GoalFormError.MISSING_BINDING

        else -> GoalFormError.SAVE_FAILED
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = GoalManagementViewModel(
                    goalRepository = container.goalRepository,
                    goalProgressService = container.goalProgressService,
                    categoryRepository = container.categoryRepository,
                    activityRepository = container.activityRepository,
                    dayBoundary = container.dayBoundary,
                ) as T
            }
    }
}
