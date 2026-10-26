package com.timeledger.app.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.timeledger.app.di.AppContainer
import com.timeledger.app.domain.model.Activity
import com.timeledger.app.domain.model.Category
import com.timeledger.app.domain.repository.ActivityRepository
import com.timeledger.app.domain.repository.CategoryRepository
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
 * 分类 / 活动管理 ViewModel（需求八节）。
 *
 * 在分类/活动这一层，产品上只有两种「删除」：**归档**与**恢复**。
 * 归档而不是物理删除，是因为历史 TimeRecord 依赖分类归属 ——
 * 删掉分类会让往年统计出现无法归属的时间（见 CategoryRepository 的说明）。
 * 界面必须把这件事讲清楚，否则用户会以为「归档」是没删干净。
 */
class CatalogManagementViewModel(
    private val categoryRepository: CategoryRepository,
    private val activityRepository: ActivityRepository,
) : ViewModel() {

    private val mapper = CatalogManagementStateMapper()
    private val categoryForm = MutableStateFlow<CategoryFormState?>(null)
    private val activityForm = MutableStateFlow<ActivityFormState?>(null)
    private val formError = MutableStateFlow<CatalogFormError?>(null)

    /**
     * 最近一次算出的目录快照。
     *
     * 保存时要拿它做重名校验（名字集合必须是最新的），
     * 而 Flow 的组合结果不便于在事件回调里读取，所以顺手缓存一份。
     */
    private var latestCategories: List<Category> = emptyList()
    private var latestActivities: List<Activity> = emptyList()

    private data class FormSnapshot(
        val category: CategoryFormState?,
        val activity: ActivityFormState?,
        val error: CatalogFormError?,
    )

    private val formSnapshot: Flow<FormSnapshot> =
        combine(categoryForm, activityForm, formError) { category, activity, error ->
            FormSnapshot(category, activity, error)
        }

    val state: StateFlow<CatalogManagementUiState?> = combine(
        // 已归档的也要取：列表里要能看到并恢复它们
        categoryRepository.observeCategories(includeArchived = true),
        activityRepository.observeActivities(includeArchived = true),
        formSnapshot,
    ) { categories, activities, forms ->
        latestCategories = categories
        latestActivities = activities
        mapper.map(
            categories = categories,
            activities = activities,
            categoryForm = forms.category,
            activityForm = forms.activity,
            formError = forms.error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    // ---------------- 分类表单 ----------------

    fun startCreateCategory() {
        categoryForm.value = CatalogForm.emptyCategory()
        activityForm.value = null
        formError.value = null
    }

    fun startEditCategory(categoryId: Long) {
        val category = latestCategories.firstOrNull { it.id == categoryId } ?: return
        categoryForm.value = CatalogForm.from(category)
        activityForm.value = null
        formError.value = null
    }

    fun updateCategoryName(text: String) = mutateCategoryForm { it.copy(name = text) }

    fun selectCategoryColor(colorArgb: Int?) = mutateCategoryForm { it.copy(colorArgb = colorArgb) }

    fun saveCategory() {
        val current = categoryForm.value ?: return
        val otherNames = latestCategories.filter { it.id != current.categoryId }.map { it.name }

        when (val result = CatalogForm.validateCategory(current, otherNames)) {
            is CategoryFormResult.Invalid -> formError.value = result.error

            is CategoryFormResult.Valid -> {
                formError.value = null
                viewModelScope.launch {
                    val outcome = if (current.isEditing) {
                        categoryRepository.updateCategory(result.category)
                    } else {
                        categoryRepository.createCategory(
                            name = result.category.name,
                            colorArgb = result.category.colorArgb,
                            iconKey = result.category.iconKey,
                            sortOrder = result.category.sortOrder,
                        )
                    }
                    handleOutcome(outcome) { categoryForm.value = null }
                }
            }
        }
    }

    // ---------------- 活动表单 ----------------

    fun startCreateActivity(categoryId: Long) {
        activityForm.value = CatalogForm.emptyActivity(categoryId)
        categoryForm.value = null
        formError.value = null
    }

    fun startEditActivity(activityId: Long) {
        val activity = latestActivities.firstOrNull { it.id == activityId } ?: return
        activityForm.value = CatalogForm.from(activity)
        categoryForm.value = null
        formError.value = null
    }

    fun updateActivityName(text: String) = mutateActivityForm { it.copy(name = text) }

    /** [colorArgb] 传 null 表示「继承分类色」。 */
    fun selectActivityColor(colorArgb: Int?) = mutateActivityForm { it.copy(colorArgb = colorArgb) }

    fun saveActivity() {
        val current = activityForm.value ?: return
        val siblings = latestActivities
            .filter { it.categoryId == current.categoryId && it.id != current.activityId }
            .map { it.name }

        when (val result = CatalogForm.validateActivity(current, siblings)) {
            is ActivityFormResult.Invalid -> formError.value = result.error

            is ActivityFormResult.Valid -> {
                formError.value = null
                viewModelScope.launch {
                    val outcome = if (current.isEditing) {
                        activityRepository.updateActivity(result.activity)
                    } else {
                        activityRepository.createActivity(
                            categoryId = result.activity.categoryId,
                            name = result.activity.name,
                            colorArgb = result.activity.colorArgb,
                            sortOrder = result.activity.sortOrder,
                        )
                    }
                    handleOutcome(outcome) { activityForm.value = null }
                }
            }
        }
    }

    fun dismissForm() {
        categoryForm.value = null
        activityForm.value = null
        formError.value = null
    }

    // ---------------- 归档与恢复 ----------------

    fun setCategoryArchived(categoryId: Long, archived: Boolean) {
        viewModelScope.launch {
            if (archived) {
                categoryRepository.archiveCategory(categoryId)
                return@launch
            }
            // 没有单独的「恢复」接口：取回分类改状态再存，时间戳由仓库统一补
            val category = categoryRepository.getCategory(categoryId) ?: return@launch
            categoryRepository.updateCategory(category.copy(isArchived = false))
        }
    }

    fun setActivityArchived(activityId: Long, archived: Boolean) {
        viewModelScope.launch {
            if (archived) {
                activityRepository.archiveActivity(activityId)
                return@launch
            }
            val activity = activityRepository.getActivity(activityId) ?: return@launch
            activityRepository.updateActivity(activity.copy(isArchived = false))
        }
    }

    // ---------------- 内部 ----------------

    private fun mutateCategoryForm(transform: (CategoryFormState) -> CategoryFormState) {
        val current = categoryForm.value ?: return
        categoryForm.value = transform(current)
        formError.value = null
    }

    private fun mutateActivityForm(transform: (ActivityFormState) -> ActivityFormState) {
        val current = activityForm.value ?: return
        activityForm.value = transform(current)
        formError.value = null
    }

    private fun handleOutcome(outcome: SaveOutcome, onSuccess: () -> Unit) {
        when (outcome) {
            is SaveOutcome.Saved, SaveOutcome.Skipped -> onSuccess()
            is SaveOutcome.Rejected -> formError.value = outcome.reason.toFormError()
        }
    }

    private fun RejectReason.toFormError(): CatalogFormError = when (this) {
        RejectReason.DUPLICATE_NAME -> CatalogFormError.DUPLICATE_NAME
        RejectReason.INVALID_NAME -> CatalogFormError.EMPTY_NAME
        else -> CatalogFormError.SAVE_FAILED
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CatalogManagementViewModel(
                    categoryRepository = container.categoryRepository,
                    activityRepository = container.activityRepository,
                ) as T
            }
    }
}
