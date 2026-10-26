package com.timeledger.app.ui.confirm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.di.AppContainer
import com.timeledger.app.domain.model.TimeRecordDraft
import com.timeledger.app.domain.repository.ActivityRepository
import com.timeledger.app.domain.repository.CategoryRepository
import com.timeledger.app.domain.repository.RejectReason
import com.timeledger.app.domain.repository.SaveOutcome
import com.timeledger.app.domain.repository.TimeRecordRepository
import com.timeledger.app.domain.review.DayReviewService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 事后确认 ViewModel（需求三节 C）。
 *
 * 两个动作，正好对应两类「说不清的时间」：
 * - **确认**：系统推测的记录 → 用户点一下（或改一下）就变成已确认（`SUGGESTED` → `MERGED`）；
 * - **补记**：完全没记录的时段 → 用户说一句「在做什么」，就生成一条手动记录。
 *
 * 需求对这一页的要求是「只问说不清的部分」。所以：
 * - 只列**时间轴上真正占着位置**的推测记录（被别人覆盖掉的推测没什么可确认的）；
 * - 只列**十分钟以上**的缺口（更短的频繁追问只会让用户关掉提醒）。
 */
class ConfirmationViewModel(
    private val epochDay: Long,
    private val dayReviewService: DayReviewService,
    private val timeRecordRepository: TimeRecordRepository,
    private val categoryRepository: CategoryRepository,
    private val activityRepository: ActivityRepository,
    private val dayBoundary: DayBoundary,
) : ViewModel() {

    private val mapper = ConfirmationStateMapper(zone = dayBoundary.zone())
    private val editor = MutableStateFlow<ConfirmEditorState?>(null)
    private val editorError = MutableStateFlow<ConfirmEditorError?>(null)

    private data class EditorSnapshot(val editor: ConfirmEditorState?, val error: ConfirmEditorError?)

    private val editorSnapshot: Flow<EditorSnapshot> =
        combine(editor, editorError) { state, error -> EditorSnapshot(state, error) }

    val state: StateFlow<ConfirmationUiState?> = combine(
        dayReviewService.observeDayReview(epochDay),
        // 目录取全部（含已归档）：已归档分类的名字仍要能显示出来
        categoryRepository.observeCategories(includeArchived = true),
        activityRepository.observeActivities(includeArchived = true),
        editorSnapshot,
    ) { review, categories, activities, snapshot ->
        mapper.map(
            review = review,
            categories = categories,
            activities = activities,
            editor = snapshot.editor,
            editorError = snapshot.error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    // ---------------- 一键确认 ----------------

    /**
     * 用系统推断的结果直接确认这条记录。
     *
     * 这是最主要的使用路径：用户看一眼「14:00–14:35 学习 / Java」觉得对，点一下就好。
     * 要他每次都必须打开表单重选一遍分类，就违背了「低操作成本」。
     */
    fun confirmAsIs(recordId: Long) {
        viewModelScope.launch {
            val record = timeRecordRepository.getRecord(recordId) ?: return@launch
            timeRecordRepository.updateRecord(
                id = recordId,
                draft = TimeRecordDraft(
                    startMillis = record.startMillis,
                    endMillis = record.endMillis,
                    categoryId = record.categoryId,
                    activityId = record.activityId,
                    countValue = record.countValue,
                    note = record.note,
                ),
            )
        }
    }

    /** 把所有待确认记录按各自的推断结果确认掉。 */
    fun confirmAll() {
        val pending = state.value?.pendingRecords ?: return
        viewModelScope.launch {
            pending.forEach { confirmAsIs(it.recordId) }
        }
    }

    // ---------------- 表单 ----------------

    /** 打开「修改后确认」，表单预填这条记录当前的内容。 */
    fun startConfirm(recordId: Long) {
        viewModelScope.launch {
            val record = timeRecordRepository.getRecord(recordId) ?: return@launch
            editor.value = ConfirmEditorState(
                recordId = recordId,
                startMillis = record.startMillis,
                endMillisExclusive = record.endMillis,
                categoryId = record.categoryId,
                activityId = record.activityId,
                note = record.note.orEmpty(),
            )
            editorError.value = null
        }
    }

    /** 打开「补记缺失时间」，起始就是这段缺口。 */
    fun startFillGap(gap: GapUi) {
        editor.value = ConfirmEditorState(
            recordId = null,
            startMillis = gap.startMillis,
            endMillisExclusive = gap.endMillisExclusive,
        )
        editorError.value = null
    }

    fun dismissEditor() {
        editor.value = null
        editorError.value = null
    }

    /** 换分类时清掉已选活动，避免「活动与分类不一致」的组合（会被仓库拒绝）。 */
    fun selectCategory(categoryId: Long?) =
        mutateEditor { it.copy(categoryId = categoryId, activityId = null) }

    fun selectActivity(activityId: Long?) = mutateEditor { it.copy(activityId = activityId) }

    fun updateNote(text: String) = mutateEditor { it.copy(note = text) }

    fun save() {
        val current = editor.value ?: return
        if (current.categoryId == null && current.activityId == null) {
            editorError.value = ConfirmEditorError.MISSING_TARGET
            return
        }
        editorError.value = null

        val draft = TimeRecordDraft(
            startMillis = current.startMillis,
            endMillis = current.endMillisExclusive,
            categoryId = current.categoryId,
            activityId = current.activityId,
            note = current.note,
        )

        viewModelScope.launch {
            val outcome = if (current.recordId != null) {
                // 确认已有记录：来源会由仓库改成 MERGED（用户确认过的结果）
                timeRecordRepository.updateRecord(current.recordId, draft)
            } else {
                // 补记缺口：新建一条手动记录
                timeRecordRepository.createManualRecord(draft)
            }
            when (outcome) {
                is SaveOutcome.Saved, SaveOutcome.Skipped -> editor.value = null
                is SaveOutcome.Rejected -> editorError.value = outcome.reason.toEditorError()
            }
        }
    }

    private fun mutateEditor(transform: (ConfirmEditorState) -> ConfirmEditorState) {
        val current = editor.value ?: return
        editor.value = transform(current)
        editorError.value = null
    }

    private fun RejectReason.toEditorError(): ConfirmEditorError = when (this) {
        RejectReason.UNKNOWN_CATEGORY_OR_ACTIVITY,
        RejectReason.ACTIVITY_CATEGORY_MISMATCH,
        -> ConfirmEditorError.SAVE_FAILED

        else -> ConfirmEditorError.SAVE_FAILED
    }

    companion object {
        fun factory(container: AppContainer, epochDay: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ConfirmationViewModel(
                    epochDay = epochDay,
                    dayReviewService = container.dayReviewService,
                    timeRecordRepository = container.timeRecordRepository,
                    categoryRepository = container.categoryRepository,
                    activityRepository = container.activityRepository,
                    dayBoundary = container.dayBoundary,
                ) as T
            }
    }
}
