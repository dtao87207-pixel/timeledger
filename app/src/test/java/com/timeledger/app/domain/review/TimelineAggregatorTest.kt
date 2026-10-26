package com.timeledger.app.domain.review

import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.model.RecordSource
import com.timeledger.app.domain.model.TimeRecord
import com.timeledger.app.domain.timeline.TimelineResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间轴聚合测试。
 *
 * 窗口用合成的毫秒区间（0 点起 24 小时），与真实时区解耦 ——
 * 这里要验证的是聚合口径，而不是日期换算（那由 TimeBoundaryTest 覆盖）。
 * 小时数允许写小数，读起来就是「14:30」这种直观时间。
 */
class TimelineAggregatorTest {

    private val hour = 3600_000L
    private val dayWindow = MillisRange(0L, 24 * hour)
    private val aggregator = TimelineAggregator()

    private fun at(hours: Double): Long = (hours * hour).toLong()

    private fun record(
        startHours: Double,
        endHours: Double,
        categoryId: Long? = 1L,
        activityId: Long? = null,
        source: RecordSource = RecordSource.MANUAL,
        id: Long = 0L,
    ) = TimeRecord(
        id = id,
        startMillis = at(startHours),
        endMillis = at(endHours),
        categoryId = categoryId,
        activityId = activityId,
        source = source,
    )

    private fun aggregate(records: List<TimeRecord>): DurationBreakdown =
        aggregator.aggregate(TimelineResolver.resolve(records), dayWindow)

    // ---------------- 空与满 ----------------

    @Test
    fun `empty timeline reports the whole window as unrecorded`() {
        val result = aggregate(emptyList())

        assertEquals(0L, result.coveredMillis)
        assertEquals(0L, result.confirmedMillis)
        assertEquals(0L, result.pendingMillis)
        assertEquals(24 * hour, result.unrecordedMillis)
        assertTrue(result.byCategory.isEmpty())
        assertTrue(result.byActivity.isEmpty())
        assertEquals(listOf(dayWindow), result.unrecordedSegments)
        assertTrue(result.isEntirelyEmpty)
    }

    @Test
    fun `nothing is unrecorded when the whole window is covered`() {
        val result = aggregate(listOf(record(0.0, 24.0)))

        assertEquals(24 * hour, result.coveredMillis)
        assertEquals(0L, result.unrecordedMillis)
        assertTrue(result.unrecordedSegments.isEmpty())
    }

    // ---------------- 分类与活动分布 ----------------

    @Test
    fun `durations are grouped by category and sorted descending`() {
        val result = aggregate(
            listOf(
                record(9.0, 11.0, categoryId = 1L),   // 学习 2h
                record(11.0, 12.0, categoryId = 2L),  // 娱乐 1h
                record(14.0, 15.0, categoryId = 1L),  // 学习 再 1h
            )
        )

        assertEquals(4 * hour, result.coveredMillis)
        assertEquals(4 * hour, result.confirmedMillis)
        assertEquals(
            listOf(DurationBucket(1L, null, 3 * hour), DurationBucket(2L, null, 1 * hour)),
            result.byCategory,
        )
        assertEquals(3 * hour, result.durationOfCategory(1L))
        assertEquals(1 * hour, result.durationOfCategory(2L))
    }

    @Test
    fun `activity buckets carry their category and are sorted by duration`() {
        val result = aggregate(
            listOf(
                record(8.0, 9.0, categoryId = 1L, activityId = 100L),   // Java 1h
                record(9.0, 9.5, categoryId = 1L, activityId = 200L),   // 算法 0.5h
                record(10.0, 12.0, categoryId = 2L, activityId = 300L), // B站 2h
            )
        )

        assertEquals(
            listOf(
                DurationBucket(2L, 300L, 2 * hour),
                DurationBucket(1L, 100L, 1 * hour),
                DurationBucket(1L, 200L, 30 * 60_000L),
            ),
            result.byActivity,
        )
        assertEquals(1 * hour, result.durationOfActivity(100L))
    }

    @Test
    fun `records without activity do not appear in the activity breakdown`() {
        val result = aggregate(listOf(record(9.0, 10.0, categoryId = 1L, activityId = null)))
        assertTrue(result.byActivity.isEmpty())
        assertEquals(1 * hour, result.durationOfCategory(1L))
    }

    @Test
    fun `unclassified time is kept as its own bucket so totals add up`() {
        // 系统刚采集到、还没推断出分类的记录（categoryId 为 null）不能丢，
        // 否则「各分类时长之和」会小于「已确认总时长」，用户看到一堆加不起来的数字
        val result = aggregate(
            listOf(
                record(9.0, 10.0, categoryId = null),
                record(10.0, 11.0, categoryId = 1L),
            )
        )

        assertEquals(2 * hour, result.confirmedMillis)
        assertEquals(2 * hour, result.byCategory.sumOf { it.durationMillis })
        assertEquals(1 * hour, result.durationOfCategory(null))
    }

    // ---------------- 待确认 ----------------

    @Test
    fun `suggestions are excluded from category totals but reported as pending`() {
        val result = aggregate(
            listOf(
                record(9.0, 10.0, categoryId = 1L, source = RecordSource.MANUAL),
                record(10.0, 10.5, categoryId = 2L, source = RecordSource.SUGGESTED),
            )
        )

        assertEquals(90 * 60_000L, result.coveredMillis)
        assertEquals(1 * hour, result.confirmedMillis)
        assertEquals(30 * 60_000L, result.pendingMillis)
        // 待确认的那半小时不出现在分类分布里
        assertEquals(listOf(DurationBucket(1L, null, 1 * hour)), result.byCategory)
    }

    @Test
    fun `pending time is not counted as a gap`() {
        // 待确认 ≠ 没记录。这一段有数据（只是还没确认），不该塞进「缺口」里
        // 否则用户会被要求为一段时间重复确认两次
        val result = aggregate(
            listOf(record(14.0, 15.0, categoryId = 2L, source = RecordSource.SUGGESTED))
        )

        assertEquals(1 * hour, result.pendingMillis)
        assertEquals(0L, result.confirmedMillis)
        assertEquals(23 * hour, result.unrecordedMillis)
        // 缺口是 0~14 点 和 15~24 点两段，不含 14~15 点
        assertEquals(
            listOf(MillisRange(0L, at(14.0)), MillisRange(at(15.0), 24 * hour)),
            result.unrecordedSegments,
        )
    }

    // ---------------- 窗口裁剪 ----------------

    @Test
    fun `records crossing the window edges are clipped`() {
        val result = aggregate(
            listOf(
                record(-1.0, 1.0),  // 只有 0~1 点在窗口内
                record(23.0, 25.0), // 只有 23~24 点在窗口内
            )
        )

        assertEquals(2 * hour, result.coveredMillis)
        assertEquals(22 * hour, result.unrecordedMillis)
        assertEquals(2 * hour, result.durationOfCategory(1L))
    }

    // ---------------- 缺口 ----------------

    @Test
    fun `unrecorded segments are found before between and after records`() {
        val result = aggregate(listOf(record(1.0, 2.0), record(4.0, 5.0)))

        assertEquals(
            listOf(
                MillisRange(0L, at(1.0)),
                MillisRange(at(2.0), at(4.0)),
                MillisRange(at(5.0), 24 * hour),
            ),
            result.unrecordedSegments,
        )
        assertEquals(22 * hour, result.unrecordedMillis)
        // 缺口之和 = 未记录总时长
        assertEquals(result.unrecordedMillis, result.unrecordedSegments.sumOf { it.durationMillis })
    }

    @Test
    fun `adjacent records leave no gap between them`() {
        val result = aggregate(listOf(record(1.0, 2.0), record(2.0, 3.0)))
        assertEquals(2, result.unrecordedSegments.size)
        assertEquals(result.unrecordedMillis, result.unrecordedSegments.sumOf { it.durationMillis })
    }

    @Test
    fun `instant records contribute no duration and do not split the gap`() {
        val result = aggregate(
            listOf(
                record(3.0, 3.0, activityId = 100L), // 只记了次数的瞬时事件
                record(5.0, 6.0),
            )
        )

        assertEquals(1 * hour, result.coveredMillis)
        assertEquals(
            listOf(MillisRange(0L, at(5.0)), MillisRange(at(6.0), 24 * hour)),
            result.unrecordedSegments,
        )
    }

    // ---------------- 与消解器的配合 ----------------

    @Test
    fun `overlapping manual and system records are not double counted`() {
        val result = aggregate(
            listOf(
                record(0.0, 2.0, categoryId = 1L, source = RecordSource.MANUAL),
                record(1.0, 3.0, categoryId = 2L, source = RecordSource.SYSTEM),
            )
        )

        // 覆盖 3 小时而不是 4 小时：手动记录赢下 0~2 点，系统记录只剩 2~3 点
        assertEquals(3 * hour, result.coveredMillis)
        assertEquals(2 * hour, result.durationOfCategory(1L))
        assertEquals(1 * hour, result.durationOfCategory(2L))
    }

    // ---------------- 恒等式 ----------------

    @Test
    fun `breakdown invariants hold for a mixed day`() {
        val result = aggregate(
            listOf(
                record(1.0, 2.5, categoryId = 1L, activityId = 100L, source = RecordSource.MANUAL),
                record(3.0, 4.0, categoryId = 2L, activityId = 200L, source = RecordSource.SYSTEM),
                record(4.5, 5.0, categoryId = 2L, source = RecordSource.SUGGESTED),
                record(6.0, 7.0, categoryId = null, source = RecordSource.SYSTEM),
                record(20.0, 21.0, categoryId = 1L, source = RecordSource.SYSTEM),
            )
        )

        // 已确认 + 待确认 = 有记录
        assertEquals(result.coveredMillis, result.confirmedMillis + result.pendingMillis)
        // 有记录 + 没记录 = 整个窗口
        assertEquals(dayWindow.durationMillis, result.coveredMillis + result.unrecordedMillis)
        // 分类之和 = 已确认总时长（null 分类单独成桶）
        assertEquals(result.confirmedMillis, result.byCategory.sumOf { it.durationMillis })
        // 缺口不重叠且按时间升序
        result.unrecordedSegments.forEach { gap -> assertTrue(gap.durationMillis > 0L) }
        result.unrecordedSegments.zipWithNext().forEach { (a, b) ->
            assertTrue(a.endMillisExclusive <= b.startMillis)
        }
    }
}
