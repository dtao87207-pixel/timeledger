package com.timeledger.app.ui.day

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.domain.model.RecordSource
import com.timeledger.app.domain.model.TimeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 记录编辑表单测试。
 *
 * 最要紧的两件事都不在界面上看得出来，所以必须在这里钉死：
 * 1. **结束时刻落在哪一天**。结束分钟数小于开始 = 跨夜；**相等 = 零长度瞬时记录**。
 *    把相等也当跨夜，「今天做了 2 题」会变成一条 24 小时的记录 ——
 *    界面上它只是一行「24h」，不特意看根本发现不了。
 * 2. **凌晨记录用哪个日期重建**。一条 02:00 开始的记录，逻辑日属于前一天，
 *    但它的挂钟时刻属于当天的日历日。用逻辑日重建会整整错一天。
 */
class RecordEditFormTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private val dayBoundary = DayBoundary(
        zoneId = shanghai,
        nowProvider = { 0L },
    )

    /** 2026-09-12 是周六。 */
    private val sept12: Long = LocalDate.of(2026, 9, 12).toEpochDay()
    private val sept13: Long = LocalDate.of(2026, 9, 13).toEpochDay()

    /** 独立于被测代码地算出一个绝对时刻，避免「用同一套算法验证自己」。 */
    private fun millis(epochDay: Long, hour: Int, minute: Int = 0): Long =
        LocalDate.ofEpochDay(epochDay).atTime(LocalTime.of(hour, minute))
            .atZone(shanghai).toInstant().toEpochMilli()

    private fun record(
        startMillis: Long,
        endMillis: Long = startMillis,
        categoryId: Long? = 1L,
        activityId: Long? = 100L,
        countValue: Double? = null,
        note: String? = null,
        id: Long = 5L,
    ) = TimeRecord(
        id = id,
        startMillis = startMillis,
        endMillis = endMillis,
        categoryId = categoryId,
        activityId = activityId,
        countValue = countValue,
        note = note,
        source = RecordSource.MANUAL,
    )

    private fun form(
        startText: String = "09:00",
        endText: String = "10:40",
        startCalendarDay: Long = sept12,
        categoryId: Long? = 1L,
        activityId: Long? = 100L,
        countText: String = "",
        note: String = "",
        recordId: Long = 5L,
    ) = RecordEditState(
        recordId = recordId,
        startCalendarDay = startCalendarDay,
        startText = startText,
        endText = endText,
        categoryId = categoryId,
        activityId = activityId,
        countText = countText,
        note = note,
    )

    private fun valid(state: RecordEditState) =
        RecordEditForm.validate(state, dayBoundary) as RecordEditResult.Valid

    // ---------------- 预填 ----------------

    @Test
    fun `existing record prefills start and end as wall clock text`() {
        val state = RecordEditForm.from(
            record(startMillis = millis(sept12, 9), endMillis = millis(sept12, 10, 40)),
            shanghai,
        )

        assertEquals("09:00", state.startText)
        assertEquals("10:40", state.endText)
        assertEquals(sept12, state.startCalendarDay)
        assertEquals(5L, state.recordId)
        assertEquals(1L, state.categoryId)
        assertEquals(100L, state.activityId)
    }

    @Test
    fun `prefilled form saves back to the very same instants`() {
        // 打开编辑框什么都不改就保存，记录必须原样不动 —— 否则用户每次「看一眼」都在改数据
        val original = record(startMillis = millis(sept12, 9), endMillis = millis(sept12, 10, 40))

        val draft = valid(RecordEditForm.from(original, shanghai)).draft

        assertEquals(original.startMillis, draft.startMillis)
        assertEquals(original.endMillis, draft.endMillis)
    }

    @Test
    fun `prefilled form round trips a record that started after midnight`() {
        // 02:00 开始：逻辑日属于 9月12日，但挂钟时刻属于 9月13日。
        // 重建时必须用日历日，否则会整整错一天。
        val original = record(startMillis = millis(sept13, 2), endMillis = millis(sept13, 3))

        val state = RecordEditForm.from(original, shanghai)
        assertEquals("02:00", state.startText)
        assertEquals(sept13, state.startCalendarDay)

        val draft = valid(state).draft
        assertEquals(original.startMillis, draft.startMillis)
        assertEquals(original.endMillis, draft.endMillis)
    }

    @Test
    fun `prefilled form round trips a record crossing midnight`() {
        val original = record(startMillis = millis(sept12, 23, 30), endMillis = millis(sept13, 1))

        val state = RecordEditForm.from(original, shanghai)
        assertEquals("23:30", state.startText)
        assertEquals("01:00", state.endText)

        val draft = valid(state).draft
        assertEquals(original.startMillis, draft.startMillis)
        assertEquals(original.endMillis, draft.endMillis)
    }

    @Test
    fun `prefilled form round trips a zero length record`() {
        // 只记了次数的记录：起止同一时刻。往返之后它仍然必须是零长度，不能变成 24 小时
        val original = record(
            startMillis = millis(sept12, 20),
            endMillis = millis(sept12, 20),
            countValue = 2.0,
        )

        val state = RecordEditForm.from(original, shanghai)
        assertEquals("20:00", state.startText)
        assertEquals("20:00", state.endText)
        assertEquals("2", state.countText)

        val draft = valid(state).draft
        assertEquals(original.startMillis, draft.startMillis)
        assertEquals(original.endMillis, draft.endMillis)
    }

    // ---------------- 时间重建 ----------------

    @Test
    fun `end later than start stays on the same calendar day`() {
        val draft = valid(form(startText = "14:00", endText = "15:30")).draft

        assertEquals(millis(sept12, 14), draft.startMillis)
        assertEquals(millis(sept12, 15, 30), draft.endMillis)
    }

    @Test
    fun `end earlier than start means crossing midnight`() {
        val draft = valid(form(startText = "23:30", endText = "01:00")).draft

        assertEquals(millis(sept12, 23, 30), draft.startMillis)
        assertEquals(millis(sept13, 1), draft.endMillis)
    }

    @Test
    fun `end equal to start is an instant not a full day`() {
        // 这条断言是本次编辑功能里最要紧的一条：
        // 相等被当成跨夜的话，一条 2 次练习会变成 24 小时的记录
        val draft = valid(form(startText = "20:00", endText = "20:00")).draft

        assertEquals(millis(sept12, 20), draft.startMillis)
        assertEquals(millis(sept12, 20), draft.endMillis)
    }

    @Test
    fun `an edited time keeps the record on its own calendar day`() {
        // 用户只改挂钟时刻，不改日期：编辑 02:00 的记录不该把它挪到别的日子
        val draft = valid(
            form(startText = "01:00", endText = "02:00", startCalendarDay = sept13)
        ).draft

        assertEquals(millis(sept13, 1), draft.startMillis)
        assertEquals(millis(sept13, 2), draft.endMillis)
    }

    // ---------------- 校验 ----------------

    @Test
    fun `unparsable time is rejected`() {
        listOf("", "abc", "25:00", "9:60", "24:30").forEach { text ->
            assertEquals(
                "「$text」应当被拒绝",
                RecordEditResult.Invalid(RecordEditError.INVALID_TIME),
                RecordEditForm.validate(form(startText = text), dayBoundary),
            )
            assertEquals(
                RecordEditResult.Invalid(RecordEditError.INVALID_TIME),
                RecordEditForm.validate(form(endText = text), dayBoundary),
            )
        }
    }

    @Test
    fun `count accepts decimals and rejects nonsense`() {
        assertEquals(2.5, valid(form(countText = "2.5")).draft.countValue)
        assertEquals(0.0, valid(form(countText = "0")).draft.countValue)

        listOf("abc", "-1", "NaN", "Infinity").forEach { text ->
            assertEquals(
                "「$text」应当被拒绝",
                RecordEditResult.Invalid(RecordEditError.INVALID_COUNT),
                RecordEditForm.validate(form(countText = text), dayBoundary),
            )
        }
    }

    @Test
    fun `empty count means no count is recorded`() {
        assertNull(valid(form(countText = "   ")).draft.countValue)
    }

    @Test
    fun `note is trimmed and blank becomes nothing`() {
        assertEquals("做完了第三章", valid(form(note = "  做完了第三章  ")).draft.note)
        assertNull(valid(form(note = "   ")).draft.note)
    }

    @Test
    fun `a record without category or activity is allowed`() {
        // 「未归类」本来就是合法状态（系统刚采集到的记录就是这样），
        // 编辑时把分类清掉等于回到那个状态，不该被拦住
        val draft = valid(form(categoryId = null, activityId = null)).draft

        assertNull(draft.categoryId)
        assertNull(draft.activityId)
    }

    // ---------------- 换分类清空活动 ----------------

    @Test
    fun `changing the category clears the selected activity`() {
        val previous = form(categoryId = 1L, activityId = 100L)
        val next = previous.copy(categoryId = 2L, activityId = 100L)

        val normalized = RecordEditForm.normalizeChange(previous, next)

        assertEquals(2L, normalized.categoryId)
        assertNull(normalized.activityId)
    }

    @Test
    fun `keeping the category keeps the activity`() {
        val previous = form(categoryId = 1L, activityId = 100L)
        val next = previous.copy(note = "换个备注")

        val normalized = RecordEditForm.normalizeChange(previous, next)

        assertEquals(100L, normalized.activityId)
        assertEquals("换个备注", normalized.note)
    }

    @Test
    fun `picking a category on a record that had none clears nothing unexpected`() {
        val previous = form(categoryId = null, activityId = null)
        val next = previous.copy(categoryId = 3L)

        val normalized = RecordEditForm.normalizeChange(previous, next)

        assertEquals(3L, normalized.categoryId)
        assertNull(normalized.activityId)
    }

    @Test
    fun `normalize keeps the rest of the form untouched`() {
        val previous = form()
        val next = previous.copy(categoryId = 9L)

        val normalized = RecordEditForm.normalizeChange(previous, next)

        assertTrue(normalized.startText == previous.startText)
        assertEquals(previous.startCalendarDay, normalized.startCalendarDay)
        assertEquals(previous.recordId, normalized.recordId)
    }

    @Test
    fun `normalize accepts a null previous state`() {
        // 理论上不会发生（先有表单才有改动），但这里不留一个会崩的分支
        val next = form(categoryId = 4L, activityId = 100L)
        assertEquals(next, RecordEditForm.normalizeChange(null, next))
    }
}
