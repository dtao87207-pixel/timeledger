package com.timeledger.app.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.di.AppContainer
import com.timeledger.app.domain.repository.ActivityRepository
import com.timeledger.app.domain.repository.CategoryRepository
import com.timeledger.app.domain.repository.PlanBlockRepository
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
 * 课程表管理 ViewModel（需求五节）。
 *
 * 课程表是「计划 vs 实际」（需求十节，产品核心）的输入侧：
 * 没有它，首页那句「计划 1h40m，照计划做了……」永远不会出现。
 *
 * 界面只做每周重复（需求五节把「每周重复」列为 V1 优先支持）；
 * 单次日程数据层与展开器都已支持，将来放开只是表单的事。
 */
class PlanManagementViewModel(
    private val planBlockRepository: PlanBlockRepository,
    private val categoryRepository: CategoryRepository,
    private val activityRepository: ActivityRepository,
    private val dayBoundary: DayBoundary,
) : ViewModel() {

    private val mapper = PlanManagementStateMapper()
    private val form = MutableStateFlow<PlanFormState?>(null)
    private val formError = MutableStateFlow<PlanFormError?>(null)

    private data class FormSnapshot(val form: PlanFormState?, val error: PlanFormError?)

    private val formSnapshot: Flow<FormSnapshot> =
        combine(form, formError) { state, error -> FormSnapshot(state, error) }

    val state: StateFlow<PlanManagementUiState?> = combine(
        planBlockRepository.observePlans(activeOnly = false),
        // 目录取全部（含已归档）：归档分类的名字仍要能显示出来，否则旧课表会显示成无名条目
        categoryRepository.observeCategories(includeArchived = true),
        activityRepository.observeActivities(includeArchived = true),
        formSnapshot,
    ) { plans, categories, activities, forms ->
        mapper.map(
            plans = plans,
            categories = categories,
            activities = activities,
            form = forms.form,
            formError = forms.error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    // ---------------- 表单 ----------------

    fun startCreate() {
        // 默认填「今天 + 09:00–10:00」：用户多半是在给自己今天要上的课做记录，
        // 这样只需要改名字和时间，少填三项
        form.value = PlanForm.empty().copy(
            dayOfWeek = dayBoundary.dayOfWeek(dayBoundary.todayEpochDay()),
            startText = "09:00",
            endText = "10:00",
        )
        formError.value = null
    }

    fun startEdit(planBlockId: Long) {
        viewModelScope.launch {
            val plan = planBlockRepository.getPlan(planBlockId) ?: return@launch
            form.value = PlanForm.from(plan)
            formError.value = null
        }
    }

    fun dismissForm() {
        form.value = null
        formError.value = null
    }

    fun updateTitle(text: String) = mutateForm { it.copy(title = text) }

    fun updateDayOfWeek(dayOfWeek: Int) = mutateForm { it.copy(dayOfWeek = dayOfWeek) }

    fun updateStart(text: String) = mutateForm { it.copy(startText = text) }

    fun updateEnd(text: String) = mutateForm { it.copy(endText = text) }

    fun updateLocation(text: String) = mutateForm { it.copy(location = text) }

    /** 换分类时清掉已选活动，避免留下「活动与分类不一致」的组合（会被仓库拒绝）。 */
    fun selectCategory(categoryId: Long?) =
        mutateForm { it.copy(categoryId = categoryId, activityId = null) }

    fun selectActivity(activityId: Long?) = mutateForm { it.copy(activityId = activityId) }

    fun save() {
        val current = form.value ?: return
        when (val result = PlanForm.validate(current)) {
            is PlanFormResult.Invalid -> formError.value = result.error

            is PlanFormResult.Valid -> {
                formError.value = null
                viewModelScope.launch {
                    val outcome = if (current.isEditing) {
                        planBlockRepository.updatePlan(result.planBlock)
                    } else {
                        planBlockRepository.createPlan(result.planBlock)
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

    fun setActive(planBlockId: Long, active: Boolean) {
        viewModelScope.launch {
            // 没有单独的启用/停用接口：取回条目改状态再存，时间戳由仓库统一补
            val plan = planBlockRepository.getPlan(planBlockId) ?: return@launch
            planBlockRepository.updatePlan(plan.copy(isActive = active))
        }
    }

    fun delete(planBlockId: Long) {
        viewModelScope.launch {
            planBlockRepository.deletePlan(planBlockId)
        }
    }

    private fun mutateForm(transform: (PlanFormState) -> PlanFormState) {
        val current = form.value ?: return
        form.value = transform(current)
        // 用户一改输入就清掉上一次的报错
        formError.value = null
    }

    private fun RejectReason.toFormError(): PlanFormError = when (this) {
        RejectReason.INVALID_NAME -> PlanFormError.EMPTY_TITLE
        RejectReason.INVALID_PLAN_DURATION -> PlanFormError.ZERO_DURATION
        RejectReason.UNKNOWN_CATEGORY_OR_ACTIVITY,
        RejectReason.ACTIVITY_CATEGORY_MISMATCH,
        -> PlanFormError.SAVE_FAILED

        else -> PlanFormError.SAVE_FAILED
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = PlanManagementViewModel(
                    planBlockRepository = container.planBlockRepository,
                    categoryRepository = container.categoryRepository,
                    activityRepository = container.activityRepository,
                    dayBoundary = container.dayBoundary,
                ) as T
            }
    }
}
