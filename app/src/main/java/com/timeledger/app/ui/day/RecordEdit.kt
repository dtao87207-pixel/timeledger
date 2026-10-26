package com.timeledger.app.ui.day

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.domain.model.TimeRecord
import com.timeledger.app.domain.model.TimeRecordDraft
import com.timeledger.app.ui.format.TimeOfDay
import java.time.Instant
import java.time.ZoneId

/**
 * 记录编辑表单的原始输入。
 *
 * ## 为什么用「分钟数 + 日历日」而不是直接编辑时间戳
 * 用户看到的是「14:00–15:30」，所以编辑也应该是这两个时刻。
 * 但它们要落回绝对时间戳，必须知道**属于哪一天** —— 这个信息由 [startCalendarDay] 带着。
 *
 * 注意这里存的是**日历日**，不是逻辑日：一条凌晨 02:00 开始的记录，
 * 它的逻辑日属于前一天，但它的挂钟时刻 02:00 属于当天的日历日。
 * 用逻辑日去重建时间戳会整整错一天。
 */
data class RecordEditState(
    val recordId: Long,
    /** 记录开始所在的日历日（epochDay）。 */
    val startCalendarDay: Long,
    val startText: String,
    val endText: String,
    val categoryId: Long? = null,
    val activityId: Long? = null,
    /** 计数（例如「做了 2 题」），空表示不记次数。 */
    val countText: String = "",
    val note: String = "",
)

enum class RecordEditError {
    INVALID_TIME,
    INVALID_COUNT,

    /** 软删除的记录不能再编辑，仓库也会拒绝。 */
    RECORD_NOT_FOUND,
    SAVE_FAILED,
}

sealed interface RecordEditResult {
    data class Valid(val draft: TimeRecordDraft) : RecordEditResult
    data class Invalid(val error: RecordEditError) : RecordEditResult
}

/**
 * 记录编辑的解析与校验 —— 纯逻辑，可完整用单元测试覆盖。
 *
 * ## 最要紧的一条：结束时刻落在哪一天
 * 只有「结束分钟数 **小于** 开始分钟数」才表示跨过午夜（例如 23:30–01:00）。
 * 两者**相等**表示零长度记录（只记了次数的瞬时事件），不是 24 小时。
 * 把相等也当成跨夜，会把「今天做了 2 题」变成一条 24 小时的记录 ——
 * 而这种错误在界面上看起来只是一行「24h」，很容易被忽略。
 */
object RecordEditForm {

    fun from(record: TimeRecord, zone: ZoneId): RecordEditState {
        val startAt = Instant.ofEpochMilli(record.startMillis).atZone(zone)
        val endAt = Instant.ofEpochMilli(record.endMillis).atZone(zone)

        return RecordEditState(
            recordId = record.id,
            startCalendarDay = startAt.toLocalDate().toEpochDay(),
            startText = TimeOfDay.minuteOfDayText(startAt.hour * 60 + startAt.minute),
            endText = TimeOfDay.minuteOfDayText(endAt.hour * 60 + endAt.minute),
            categoryId = record.categoryId,
            activityId = record.activityId,
            countText = record.countValue?.let(::trimNumber).orEmpty(),
            note = record.note.orEmpty(),
        )
    }

    /**
     * 用户改动一步之后的规范化：**换了分类就清掉已选活动**。
     *
     * 不清的话会留下「活动不属于所选分类」的组合，仓库会直接拒绝写入，
     * 而用户在界面上看到的只是「保存失败」—— 一条他自己没法理解的报错。
     * 这条规则放在这里而不是放在对话框里，是为了它能被单元测试覆盖。
     */
    fun normalizeChange(previous: RecordEditState?, next: RecordEditState): RecordEditState =
        if (previous != null && previous.categoryId != next.categoryId) {
            next.copy(activityId = null)
        } else {
            next
        }

    fun validate(state: RecordEditState, dayBoundary: DayBoundary): RecordEditResult {
        val startMinute = TimeOfDay.parseMinuteOfDay(state.startText)
            ?: return RecordEditResult.Invalid(RecordEditError.INVALID_TIME)
        val endMinute = TimeOfDay.parseMinuteOfDay(state.endText)
            ?: return RecordEditResult.Invalid(RecordEditError.INVALID_TIME)

        val count = state.countText.trim().let { text ->
            if (text.isEmpty()) {
                null
            } else {
                val parsed = text.toDoubleOrNull() ?: return RecordEditResult.Invalid(RecordEditError.INVALID_COUNT)
                // "NaN" 会被 toDoubleOrNull 成功解析，而 NaN 参与的比较全是 false
                if (!parsed.isFinite() || parsed < 0.0) {
                    return RecordEditResult.Invalid(RecordEditError.INVALID_COUNT)
                }
                parsed
            }
        }

        val startMillis = dayBoundary.millisAtMinuteOfDay(state.startCalendarDay, startMinute)
        val endMillis = when {
            endMinute > startMinute ->
                dayBoundary.millisAtMinuteOfDay(state.startCalendarDay, endMinute)

            // 相等 = 瞬时记录（只记了次数），不是跨夜
            endMinute == startMinute -> startMillis

            // 严格小于 = 跨过午夜
            else -> dayBoundary.millisAtMinuteOfDay(state.startCalendarDay + 1, endMinute)
        }

        return RecordEditResult.Valid(
            TimeRecordDraft(
                startMillis = startMillis,
                endMillis = endMillis,
                categoryId = state.categoryId,
                activityId = state.activityId,
                countValue = count,
                note = state.note.trim().ifEmpty { null },
            )
        )
    }

    /** 去掉多余小数位：`2.0` → `2`。 */
    private fun trimNumber(value: Double): String {
        val asLong = value.toLong()
        return if (value == asLong.toDouble()) asLong.toString() else value.toString()
    }
}
