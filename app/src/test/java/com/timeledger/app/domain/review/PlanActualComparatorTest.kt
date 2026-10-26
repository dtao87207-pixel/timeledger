package com.timeledger.app.domain.review

import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.model.RecordSource
import com.timeledger.app.domain.model.TimeRecord
import com.timeledger.app.domain.plan.PlannedSlot
import com.timeledger.app.domain.timeline.TimelineResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计划 vs 实际比对测试（需求十节，产品核心）。
 *
 * 时间同样用合成的分钟/小时表示，断言里直接能读出「19:00–20:00」这种区间。
 */
class PlanActualComparatorTest {

    private val minute = 60_000L
    private val comparator = PlanActualComparator()

    private fun min(minutes: Int): Long = minutes * minute

    private fun record(
        startMinute: Int,
        endMinute: Int,
        categoryId: Long? = 1L,
        activityId: Long? = 100L,
        source: RecordSource = RecordSource.MANUAL,
        id: Long = 0L,
    ) = TimeRecord(
        id = id,
        startMillis = min(startMinute),
        endMillis = min(endMinute),
        categoryId = categoryId,
        activityId = activityId,
        source = source,
    )

    private fun slot(
        startMinute: Int,
        endMinute: Int,
        categoryId: Long? = 1L,
        activityId: Long? = 100L,
    ) = PlannedSlot(
        planBlockId = 1L,
        title = "Java",
        logicalDay = 0L,
        range = MillisRange(min(startMinute), min(endMinute)),
        categoryId = categoryId,
        activityId = activityId,
        location = null,
        note = null,
        crossesMidnight = false,
    )

    private fun compare(
        plannedSlot: PlannedSlot,
        records: List<TimeRecord>,
    ): PlanActualComparison = comparator.compare(plannedSlot, TimelineResolver.resolve(records))

    // ---------------- 需求十节的例子 ----------------

    @Test
    fun `spec example distinguishes planned work from deviation`() {
        // 计划 19:00–21:00 Java；
        // 实际：Android Studio 19:02–19:45、Chrome 19:45–20:20（都算 Java）、
        //       B站 20:20–20:40（娱乐）、Chrome 20:40–21:00（又回到 Java）
        val planned = slot(19 * 60, 21 * 60, categoryId = 1L, activityId = 100L)
        val records = listOf(
            record(19 * 60 + 2, 19 * 60 + 45, activityId = 100L),
            record(19 * 60 + 45, 20 * 60 + 20, activityId = 100L),
            record(20 * 60 + 20, 20 * 60 + 40, categoryId = 2L, activityId = 200L),
            record(20 * 60 + 40, 21 * 60, activityId = 100L),
        )

        val result = compare(planned, records)

        // 一致：43 + 35 + 20 = 98 分钟
        assertEquals(min(98), result.matchedMillis)
        // 计划时段内有记录的：98 + 20 = 118 分钟
        assertEquals(min(118), result.recordedMillis)
        // 计划共 120 分钟，其中 2 分钟没有任何记录
        assertEquals(min(2), result.unrecordedMillis)
        assertEquals(min(120), result.plannedMillis)
        // 明细按时间升序，且能看出「哪一段偏离了计划」
        assertEquals(4, result.segments.size)
        assertEquals(RecordSource.MANUAL, result.segments[2].source)
        assertEquals(2L, result.segments[2].categoryId)
        assertEquals(98.0 / 120.0, result.adherenceRatio, 0.0001)
    }

    // ---------------- 基本判定 ----------------

    @Test
    fun `matching record counts as matched`() {
        val result = compare(slot(19 * 60, 21 * 60), listOf(record(19 * 60, 20 * 60)))

        assertEquals(min(60), result.recordedMillis)
        assertEquals(min(60), result.matchedMillis)
        assertEquals(min(60), result.unrecordedMillis)
        assertEquals(0L, result.deviatedMillis)
        assertTrue(result.hasBinding)
    }

    @Test
    fun `record of another activity is recorded but not matched`() {
        val result = compare(
            slot(19 * 60, 21 * 60, activityId = 100L),
            listOf(record(19 * 60, 19 * 60 + 30, activityId = 200L)),
        )

        assertEquals(min(30), result.recordedMillis)
        assertEquals(0L, result.matchedMillis)
        assertEquals(min(30), result.deviatedMillis)
        assertEquals(0.0, result.adherenceRatio, 0.0001)
    }

    @Test
    fun `category binding matches every activity under it`() {
        val result = compare(
            slot(19 * 60, 21 * 60, categoryId = 1L, activityId = null),
            listOf(record(19 * 60, 20 * 60, categoryId = 1L, activityId = 999L)),
        )

        assertEquals(min(60), result.matchedMillis)
    }

    @Test
    fun `activity binding does not match sibling activities`() {
        val result = compare(
            slot(19 * 60, 21 * 60, categoryId = 1L, activityId = 100L),
            listOf(record(19 * 60, 20 * 60, categoryId = 1L, activityId = 101L)),
        )

        assertEquals(0L, result.matchedMillis)
        assertEquals(min(60), result.recordedMillis)
    }

    @Test
    fun `plan without binding reports what happened but refuses to judge adherence`() {
        // 只写了「自习」、没归类：能显示这段时间干了什么，但不能说「你偏离了计划」
        val result = compare(
            slot(19 * 60, 21 * 60, categoryId = null, activityId = null),
            listOf(record(19 * 60, 20 * 60)),
        )

        assertFalse(result.hasBinding)
        assertEquals(min(60), result.recordedMillis)
        assertEquals(0L, result.matchedMillis)
        // 无法判断时返回 0，而不是给一个误导性的「偏离 60 分钟」
        assertEquals(0L, result.deviatedMillis)
        assertEquals(0.0, result.adherenceRatio, 0.0001)
    }

    // ---------------- 裁剪与重叠 ----------------

    @Test
    fun `segments are clipped to the planned slot`() {
        // 记录比计划时段长，只有落在计划内的部分算数
        val result = compare(slot(19 * 60, 20 * 60), listOf(record(18 * 60, 21 * 60)))

        assertEquals(1, result.segments.size)
        assertEquals(min(19 * 60), result.segments.single().range.startMillis)
        assertEquals(min(20 * 60), result.segments.single().range.endMillisExclusive)
        assertEquals(min(60), result.recordedMillis)
        assertEquals(min(60), result.matchedMillis)
    }

    @Test
    fun `records outside the slot are ignored`() {
        val result = compare(slot(19 * 60, 20 * 60), listOf(record(21 * 60, 22 * 60)))

        assertTrue(result.segments.isEmpty())
        assertEquals(0L, result.recordedMillis)
        assertEquals(min(60), result.unrecordedMillis)
    }

    @Test
    fun `overlapping manual and system records are resolved before comparison`() {
        val planned = slot(19 * 60, 21 * 60, activityId = 100L)
        val result = compare(
            planned,
            listOf(
                record(19 * 60, 20 * 60, activityId = 100L, source = RecordSource.MANUAL),
                record(19 * 60 + 30, 20 * 60 + 30, activityId = 200L, source = RecordSource.SYSTEM),
            ),
        )

        // 手动记录赢下 19:00–20:00；系统记录只剩 20:00–20:30
        // 直接用原始记录相加会得到重叠后的错误结果（1.5 小时里有 0.5 小时重复计算）
        assertEquals(2, result.segments.size)
        assertEquals(min(90), result.recordedMillis)
        assertEquals(min(60), result.matchedMillis)
        assertEquals(min(30), result.deviatedMillis)
        assertEquals(min(30), result.unrecordedMillis)
    }

    // ---------------- 待确认 ----------------

    @Test
    fun `pending segments count as recorded but not as matched`() {
        // 与目标系统口径一致：未确认的推测不换成绩，但确实「这段时间有数据」
        val result = compare(
            slot(19 * 60, 20 * 60),
            listOf(record(19 * 60, 20 * 60, source = RecordSource.SUGGESTED)),
        )

        assertEquals(min(60), result.recordedMillis)
        assertEquals(0L, result.matchedMillis)
        assertEquals(min(60), result.pendingMillis)
        assertEquals(0L, result.deviatedMillis)
        // 待确认 ≠ 没记录：这一段确实有数据（只是还没确认），所以不算缺口
        assertEquals(0L, result.unrecordedMillis)
        assertEquals(0.0, result.adherenceRatio, 0.0001)
    }

    @Test
    fun `pending and confirmed segments are both listed with their source`() {
        val result = compare(
            slot(19 * 60, 21 * 60),
            listOf(
                record(19 * 60, 20 * 60, source = RecordSource.MANUAL),
                record(20 * 60, 21 * 60, source = RecordSource.SUGGESTED),
            ),
        )

        assertEquals(2, result.segments.size)
        assertEquals(
            listOf(RecordSource.MANUAL, RecordSource.SUGGESTED),
            result.segments.map { it.source },
        )
        assertEquals(min(120), result.recordedMillis)
        assertEquals(min(60), result.matchedMillis)
        assertEquals(min(60), result.pendingMillis)
        assertEquals(0L, result.deviatedMillis)
    }

    // ---------------- 批量与边界 ----------------

    @Test
    fun `compareAll keeps the incoming slot order`() {
        val slots = listOf(slot(8 * 60, 9 * 60), slot(19 * 60, 20 * 60), slot(14 * 60, 15 * 60))
        val result = comparator.compareAll(slots, TimelineResolver.resolve(emptyList()))

        assertEquals(3, result.size)
        assertEquals(slots.map { it.planBlockId }, result.map { it.slot.planBlockId })
        assertEquals(slots.map { it.range }, result.map { it.slot.range })
    }

    @Test
    fun `empty timeline yields zero recorded and full unrecorded`() {
        val result = compare(slot(19 * 60, 20 * 60), emptyList())

        assertTrue(result.segments.isEmpty())
        assertEquals(0L, result.recordedMillis)
        assertEquals(0L, result.matchedMillis)
        assertEquals(min(60), result.unrecordedMillis)
    }

    @Test
    fun `instant records inside the slot do not occupy time`() {
        val result = compare(
            slot(19 * 60, 20 * 60),
            listOf(record(19 * 60 + 10, 19 * 60 + 10, activityId = 100L)),
        )

        assertTrue(result.segments.isEmpty())
        assertEquals(0L, result.recordedMillis)
        assertEquals(min(60), result.unrecordedMillis)
    }
}
