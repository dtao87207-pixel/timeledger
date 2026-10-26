package com.timeledger.app.ui.day

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.domain.repository.RejectReason
import com.timeledger.app.domain.repository.SaveOutcome
import com.timeledger.app.domain.repository.TimeRecordRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 编辑框的原始状态（用户敲进去的表单 + 上一次的报错）。 */
data class RecordEditSnapshot(
    val form: RecordEditState? = null,
    val error: RecordEditError? = null,
)

/**
 * 记录编辑的状态与动作，**首页与日历共用**。
 *
 * ## 为什么抽出来
 * 「点时间轴上的一行 → 改这条记录」这件事，在首页和日历里是同一件事。
 * 各写一份的结果一定是两处慢慢分叉（一处清了活动、另一处没清），
 * 而这类差异要等用户踩到才发现。
 *
 * 它不是 ViewModel：没有 Android 依赖，只拿一个协程作用域。
 * 由 ViewModel 持有它，界面要显示什么仍由纯函数的 [RecordEditorUiMapper] 决定。
 *
 * ## 为什么取值要回仓库查一次
 * 界面上的那一行是**裁剪过**的（跨 4:00 的记录只显示属于当天的部分）。
 * 拿它当编辑起点，用户打开编辑框点保存就会把记录悄悄改短。
 */
class RecordEditController(
    private val repository: TimeRecordRepository,
    private val dayBoundary: DayBoundary,
    private val scope: CoroutineScope,
) {

    private val _snapshot = MutableStateFlow(RecordEditSnapshot())

    val snapshot: StateFlow<RecordEditSnapshot> = _snapshot.asStateFlow()

    /** 打开某条记录的编辑框。记录已经不在了就什么都不做（见类注释里的取舍）。 */
    fun open(recordId: Long) {
        scope.launch {
            val record = repository.getRecord(recordId)
            if (record == null) {
                // 时间轴上的这一行已经过期（记录在别处被删掉了）。此时不弹窗：
                // 复盘 Flow 会立刻把这一行从界面上抹掉，再弹一个「记录不存在」只会让人困惑。
                return@launch
            }
            _snapshot.value = RecordEditSnapshot(
                form = RecordEditForm.from(record, dayBoundary.zone()),
                error = null,
            )
        }
    }

    /** 用户改了表单里的任意一项。 */
    fun update(state: RecordEditState) {
        val previous = _snapshot.value.form
        // 换分类时清掉已选活动（规则在 RecordEditForm 里，可被测试覆盖）
        _snapshot.value = RecordEditSnapshot(
            form = RecordEditForm.normalizeChange(previous, state),
            // 一动输入就收起上一次的报错：留着旧报错会让人以为新填的也错
            error = null,
        )
    }

    fun save() {
        val form = _snapshot.value.form ?: return
        when (val result = RecordEditForm.validate(form, dayBoundary)) {
            is RecordEditResult.Invalid -> _snapshot.value = _snapshot.value.copy(error = result.error)

            is RecordEditResult.Valid -> scope.launch {
                when (val outcome = repository.updateRecord(form.recordId, result.draft)) {
                    is SaveOutcome.Saved -> dismiss()

                    // Skipped 表示「目标状态已经成立」，对更新而言等同成功；
                    // 它本来只在系统写入的幂等键命中时出现，这里只是不留未处理的空分支。
                    SaveOutcome.Skipped -> dismiss()

                    is SaveOutcome.Rejected -> _snapshot.value = _snapshot.value.copy(
                        error = if (outcome.reason == RejectReason.RECORD_NOT_FOUND) {
                            RecordEditError.RECORD_NOT_FOUND
                        } else {
                            RecordEditError.SAVE_FAILED
                        }
                    )
                }
            }
        }
    }

    /**
     * 删除正在编辑的记录。
     *
     * 二次确认由界面负责 —— 删除不可撤销，而「问一句」属于交互判断，
     * 不该藏在这里，否则同一段逻辑在不同页面上会问出不同的话。
     */
    fun delete() {
        val form = _snapshot.value.form ?: return
        scope.launch {
            repository.deleteRecord(form.recordId)
            dismiss()
        }
    }

    fun dismiss() {
        _snapshot.value = RecordEditSnapshot()
    }
}
