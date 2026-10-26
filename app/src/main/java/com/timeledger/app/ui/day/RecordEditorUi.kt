package com.timeledger.app.ui.day

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.domain.model.Activity
import com.timeledger.app.domain.model.Category
import com.timeledger.app.ui.format.DisplayFormat
import com.timeledger.app.ui.format.TimeOfDay

/**
 * 记录编辑框的界面状态。
 *
 * [form] 把原始输入原样带出来，是为了让对话框用 `form.copy(startText = ...)` 产生下一次输入、
 * 只经**一个**回调交回 ViewModel。六个字段配六个回调看起来更「规范」，
 * 但会让这个对话框变成一个需要六条线才能接上的插座，而它本身没有任何判断逻辑。
 */
data class RecordEditorUi(
    val form: RecordEditState,
    val title: String,
    /** 这条记录开始所在的日期。编辑的是挂钟时刻，所以显示的必须是日历日。 */
    val dateLabel: String,
    val categories: List<PickerOption>,
    /** 当前所选分类下的活动。 */
    val activities: List<PickerOption>,
    /** 实时时长预览；零长度记录没有时长，为 null。 */
    val durationLabel: String?,
    /** 只记了次数的记录（起止相同）：界面上要说明它不占时间。 */
    val isInstant: Boolean,
    /** 结束早于开始 = 跨过午夜，提示出来免得用户以为填错。 */
    val crossesMidnight: Boolean,
    val canSave: Boolean,
    val errorMessage: String?,
)

/**
 * 记录编辑表单 → 界面状态的**纯函数**映射。
 *
 * 与其他界面映射器同样的理由：本机无法目视验证界面，
 * 所以「选项怎么筛、能不能保存、提示什么」这些判断都在这里被测试钉死。
 */
class RecordEditorUiMapper(private val dayBoundary: DayBoundary) {

    fun map(
        form: RecordEditState?,
        error: RecordEditError?,
        categories: List<Category>,
        activities: List<Activity>,
    ): RecordEditorUi? {
        if (form == null) return null

        val activityOptions = activities
            .filterNot { it.isArchived }
            // 只列所选分类下的活动：不可能选出「活动与分类不一致」的组合（会被仓库拒绝）
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

        return RecordEditorUi(
            form = form,
            title = "编辑这条记录",
            dateLabel = DisplayFormat.dateLabel(form.startCalendarDay),
            categories = categories
                .filterNot { it.isArchived }
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))
                .map { PickerOption(it.id, it.name, it.colorArgb) },
            activities = activityOptions,
            durationLabel = TimeOfDay.durationText(form.startText, form.endText),
            isInstant = start != null && end != null && start == end,
            // 结束严格小于开始才是跨夜；相等是瞬时记录，不是 24 小时
            crossesMidnight = start != null && end != null && end < start,
            canSave = RecordEditForm.validate(form, dayBoundary) is RecordEditResult.Valid,
            errorMessage = error?.let(::errorMessage),
        )
    }

    private fun errorMessage(error: RecordEditError): String = when (error) {
        RecordEditError.INVALID_TIME -> "时间填得不对，照 9:00 这样写就行"
        RecordEditError.INVALID_COUNT -> "次数要填 0 或更大的数，不记次数就留空"
        RecordEditError.RECORD_NOT_FOUND -> "这条记录已经不在了，可能刚被删掉"
        RecordEditError.SAVE_FAILED -> "保存失败：所选的分类或活动已不可用，请重新选择"
    }
}
