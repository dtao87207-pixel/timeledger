package com.timeledger.app.ui.mapping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.di.AppContainer
import com.timeledger.app.domain.repository.ActivityRepository
import com.timeledger.app.domain.repository.CategoryRepository
import com.timeledger.app.domain.repository.SaveOutcome
import com.timeledger.app.domain.usage.AppMappingService
import com.timeledger.app.domain.usage.AppUsageHint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * App 归类页 ViewModel（需求九节）。
 *
 * 很薄：把「映射表 + 最近使用情况 + 分类目录」三路数据合成一份状态，
 * 所有判断都在 [AppMappingStateMapper] 里（可测）。
 * 唯一的动作是「给一个 App 指定归属」，写完映射表会自己推新值，
 * 界面因此立刻变成「已归类」—— 不需要手动刷新。
 */
class AppMappingViewModel(
    private val appMappingService: AppMappingService,
    private val categoryRepository: CategoryRepository,
    private val activityRepository: ActivityRepository,
    dayBoundary: DayBoundary,
) : ViewModel() {

    private val mapper = AppMappingStateMapper(nowMillis = { dayBoundary.nowMillis() })

    /** 最近使用情况。进页面时取一次即可：它是用来排序的，不需要秒级新鲜。 */
    private val hints = MutableStateFlow<Map<String, AppUsageHint>>(emptyMap())

    private val form = MutableStateFlow<AppMappingFormState?>(null)
    private val formError = MutableStateFlow<AppMappingFormError?>(null)

    private data class FormSnapshot(
        val form: AppMappingFormState?,
        val error: AppMappingFormError?,
    )

    val state: StateFlow<AppMappingUiState?> = combine(
        appMappingService.observeMappings(),
        hints,
        categoryRepository.observeCategories(includeArchived = true),
        activityRepository.observeActivities(includeArchived = true),
        combine(form, formError) { f, e -> FormSnapshot(f, e) },
    ) { mappings, usageHints, categories, activities, snapshot ->
        mapper.map(
            mappings = mappings,
            hints = usageHints,
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

    init {
        viewModelScope.launch {
            hints.value = appMappingService.usageHints()
        }
    }

    // ---------------- 表单 ----------------

    /** 打开某个 App 的编辑框，预填它当前的归属。 */
    fun startEdit(row: AppMappingRowUi) {
        form.value = AppMappingFormState(
            packageName = row.packageName,
            displayName = row.displayName,
            categoryId = row.categoryId,
            activityId = row.activityId,
        )
        formError.value = null
    }

    /** 换分类时清掉已选活动：否则会留下「活动不属于该分类」的组合，仓库会直接拒绝。 */
    fun selectCategory(categoryId: Long?) = mutateForm {
        it.copy(categoryId = categoryId, activityId = null)
    }

    fun selectActivity(activityId: Long?) = mutateForm { it.copy(activityId = activityId) }

    fun dismissForm() {
        form.value = null
        formError.value = null
    }

    /**
     * 保存。**什么都不选也是合法输入** —— 那表示「这个 App 不用归类」，
     * 仓库会把 `isUserConfirmed` 置为 true，之后系统不再拿它来问用户。
     */
    fun save() {
        val current = form.value ?: return
        viewModelScope.launch {
            val outcome = appMappingService.assign(
                packageName = current.packageName,
                categoryId = current.categoryId,
                activityId = current.activityId,
            )
            when (outcome) {
                is SaveOutcome.Saved, SaveOutcome.Skipped -> dismissForm()
                is SaveOutcome.Rejected -> formError.value = AppMappingFormError.SAVE_FAILED
            }
        }
    }

    private fun mutateForm(transform: (AppMappingFormState) -> AppMappingFormState) {
        val current = form.value ?: return
        form.value = transform(current)
        formError.value = null
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = AppMappingViewModel(
                    appMappingService = container.appMappingService,
                    categoryRepository = container.categoryRepository,
                    activityRepository = container.activityRepository,
                    dayBoundary = container.dayBoundary,
                ) as T
            }
    }
}
