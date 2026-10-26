package com.timeledger.app.domain.timeline

import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.model.RecordSource
import com.timeledger.app.domain.model.TimeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重叠消解测试。
 *
 * 时间用「距某个基准的第 N 分钟」表示，读起来就是「14:00–15:00」这种直观区间，
 * 不需要在断言里心算毫秒。
 */
class TimelineResolverTest {

    private val minute = 60_000L
    private fun m(minutes: Int): Long = minutes * minute

    private fun record(
        start: Int,
        end: Int,
        source: RecordSource = RecordSource.MANUAL,
        categoryId: Long? = 1L,
        activityId: Long? = null,
        confidence: Double = 1.0,
        countValue: Double? = null,
        id: Long = 0L,
    ) = TimeRecord(
        id = id,
        startMillis = m(start),
        endMillis = m(end),
        categoryId = categoryId,
        activityId = activityId,
        source = source,
        confidence = confidence,
        countValue = countValue,
    )

    @Test
    fun `empty input yields an empty timeline`() {
        val timeline = TimelineResolver.resolve(emptyList())
        assertTrue(timeline.segments.isEmpty())
        assertTrue(timeline.survivingRecords.isEmpty())
        assertEquals(0L, timeline.coveredDurationMillis)
    }

    @Test
    fun `non overlapping records each keep their own segment`() {
        val a = record(0, 60)
        val b = record(90, 120, source = RecordSource.SYSTEM)
        val timeline = TimelineResolver.resolve(listOf(a, b))

        assertEquals(2, timeline.segments.size)
        assertEquals(MillisRange(m(0), m(60)), timeline.segments[0].range)
        assertEquals(MillisRange(m(90), m(120)), timeline.segments[1].range)
        assertEquals(90 * minute, timeline.coveredDurationMillis)
    }

    @Test
    fun `manual record wins over system record on the shared part`() {
        // 用户手写 0–60，系统测到 30–90：30–60 这一段归用户
        val manual = record(0, 60, source = RecordSource.MANUAL, categoryId = 1L)
        val system = record(30, 90, source = RecordSource.SYSTEM, categoryId = 2L)

        val timeline = TimelineResolver.resolve(listOf(manual, system))

        // 用户那条赢下 0–30 与 30–60 两段，相邻且同一条记录 → 合并成一段 0–60
        assertEquals(2, timeline.segments.size)
        assertEquals(MillisRange(m(0), m(60)), timeline.segments[0].range)
        assertEquals(RecordSource.MANUAL, timeline.segments[0].source)
        assertEquals(MillisRange(m(60), m(90)), timeline.segments[1].range)
        assertEquals(RecordSource.SYSTEM, timeline.segments[1].source)

        // 关键：覆盖时长是 90 分钟，而不是把两条记录相加得到 120 分钟
        assertEquals(90 * minute, timeline.coveredDurationMillis)
        assertEquals(120 * minute, manual.durationMillis + system.durationMillis)
    }

    @Test
    fun `merged beats system but loses to manual`() {
        val manual = record(0, 60, source = RecordSource.MANUAL)
        val merged = record(30, 90, source = RecordSource.MERGED)
        val system = record(60, 120, source = RecordSource.SYSTEM)

        val timeline = TimelineResolver.resolve(listOf(system, merged, manual))

        // 0–60 归 manual，60–90 归 merged（它压过 system），90–120 归 system
        assertEquals(3, timeline.segments.size)
        assertEquals(RecordSource.MANUAL, timeline.segments[0].source)
        assertEquals(MillisRange(m(0), m(60)), timeline.segments[0].range)
        assertEquals(RecordSource.MERGED, timeline.segments[1].source)
        assertEquals(MillisRange(m(60), m(90)), timeline.segments[1].range)
        assertEquals(RecordSource.SYSTEM, timeline.segments[2].source)
        assertEquals(MillisRange(m(90), m(120)), timeline.segments[2].range)
    }

    @Test
    fun `suggested never steals time from a system record`() {
        val suggested = record(0, 60, source = RecordSource.SUGGESTED)
        val system = record(0, 60, source = RecordSource.SYSTEM)

        val timeline = TimelineResolver.resolve(listOf(suggested, system))

        assertEquals(1, timeline.segments.size)
        assertEquals(RecordSource.SYSTEM, timeline.segments[0].source)
    }

    @Test
    fun `higher confidence wins when the source priority ties`() {
        val low = record(0, 60, source = RecordSource.SYSTEM, confidence = 0.4, id = 1L)
        val high = record(0, 60, source = RecordSource.SYSTEM, confidence = 0.9, id = 2L)

        val timeline = TimelineResolver.resolve(listOf(low, high))

        assertEquals(1, timeline.segments.size)
        assertEquals(2L, timeline.segments[0].record.id)
    }

    @Test
    fun `earlier start wins when priority and confidence tie`() {
        val early = record(0, 60, source = RecordSource.SYSTEM, id = 1L)
        val late = record(20, 80, source = RecordSource.SYSTEM, id = 2L)

        val timeline = TimelineResolver.resolve(listOf(late, early))

        // 0–60 归先开始的那条，60–80 只剩后开始的那条
        assertEquals(2, timeline.segments.size)
        assertEquals(1L, timeline.segments[0].record.id)
        assertEquals(MillisRange(m(0), m(60)), timeline.segments[0].range)
        assertEquals(2L, timeline.segments[1].record.id)
        assertEquals(MillisRange(m(60), m(80)), timeline.segments[1].range)
    }

    @Test
    fun `a record sliced by a lower priority record is merged back into one segment`() {
        // 用户手写 0–120 覆盖了系统的 30–60：30–60 依然归用户，
        // 但边界点会被切出来，必须合并回去，否则时间轴上会显示成三段不相干的事。
        val manual = record(0, 120, source = RecordSource.MANUAL)
        val system = record(30, 60, source = RecordSource.SYSTEM)

        val timeline = TimelineResolver.resolve(listOf(manual, system))

        assertEquals(1, timeline.segments.size)
        assertEquals(MillisRange(m(0), m(120)), timeline.segments[0].range)
        assertEquals(120 * minute, timeline.coveredDurationMillis)
    }

    @Test
    fun `a record split by a higher priority record appears in two segments but survives once`() {
        val outer = record(0, 120, source = RecordSource.SYSTEM, id = 10L)
        val inner = record(30, 60, source = RecordSource.MANUAL, id = 11L)

        val timeline = TimelineResolver.resolve(listOf(outer, inner))

        assertEquals(3, timeline.segments.size)
        // 同一条 SYSTEM 记录占了首尾两段
        assertEquals(listOf(10L, 11L, 10L), timeline.segments.map { it.record.id })
        // 但它只算「赢下一次」
        assertEquals(2, timeline.survivingRecords.size)
        assertEquals(120 * minute, timeline.coveredDurationMillis)
    }

    @Test
    fun `instant records are always kept and never compete`() {
        // 零长度记录只标记「这一刻发生了什么」，不占时间，因此不会被任何区间压掉
        val instant = record(30, 30, source = RecordSource.MANUAL, countValue = 2.0, id = 1L)
        val span = record(0, 60, source = RecordSource.SYSTEM, id = 2L)

        val timeline = TimelineResolver.resolve(listOf(instant, span))

        assertEquals(2, timeline.segments.size)
        val instantSegments = timeline.segments.filter { it.isInstant }
        assertEquals(1, instantSegments.size)
        assertEquals(MillisRange(m(30), m(30)), instantSegments.single().range)
        // 瞬时事件不贡献时长
        assertEquals(60 * minute, timeline.coveredDurationMillis)
        // 但它在「赢下的记录」里，所以计数类目标能统计到
        assertEquals(2, timeline.survivingRecords.size)
    }

    @Test
    fun `segments are sorted by start time`() {
        val a = record(60, 90)
        val b = record(0, 30)
        val c = record(30, 30, source = RecordSource.SYSTEM) // 瞬时事件夹在中间

        val timeline = TimelineResolver.resolve(listOf(a, b, c))

        val starts = timeline.segments.map { it.range.startMillis }
        assertEquals(starts.sorted(), starts)
        assertEquals(listOf(m(0), m(30), m(60)), starts)
    }

    @Test
    fun `segments never overlap except instants`() {
        val records = listOf(
            record(0, 60, source = RecordSource.MANUAL),
            record(30, 120, source = RecordSource.SYSTEM),
            record(100, 140, source = RecordSource.MERGED),
            record(50, 50, source = RecordSource.MANUAL),
        )

        val timeline = TimelineResolver.resolve(records)

        val spans = timeline.segments.filterNot { it.isInstant }
        for (i in spans.indices) {
            for (j in i + 1 until spans.size) {
                assertFalse(
                    "段落之间不允许重叠：${spans[i].range} vs ${spans[j].range}",
                    spans[i].range.overlaps(spans[j].range),
                )
            }
        }
        // 时长之和 = 覆盖时长（已互不重叠，所以可以直接相加）
        assertEquals(timeline.coveredDurationMillis, spans.sumOf { it.durationMillis })
    }

    @Test
    fun `tie is broken by input order which mirrors the repository ordering`() {
        // 两条记录优先级、置信度、开始时间全都一样时，由输入顺序决定胜负。
        // 仓库查询固定按 (start_time, id) 排序，所以生产环境下这个顺序是稳定的 ——
        // 这条测试把「顺序即胜负」这件事记录下来，避免以后有人换了排序却不知道会影响结果。
        val first = record(0, 60, source = RecordSource.MANUAL, id = 1L)
        val second = record(0, 60, source = RecordSource.MANUAL, id = 2L)

        assertEquals(1L, TimelineResolver.resolve(listOf(first, second)).segments.single().record.id)
        assertEquals(2L, TimelineResolver.resolve(listOf(second, first)).segments.single().record.id)
    }

    @Test
    fun `segmentsMatching filters by activity first then category`() {
        val java = record(0, 60, categoryId = 1L, activityId = 100L, id = 1L)
        val other = record(60, 120, categoryId = 1L, activityId = 200L, id = 2L)
        val elsewhere = record(120, 180, categoryId = 9L, activityId = 900L, id = 3L)

        val timeline = TimelineResolver.resolve(listOf(java, other, elsewhere))

        // 绑活动：只匹配那一个活动
        assertEquals(listOf(1L), timeline.segmentsMatching(1L, 100L).map { it.record.id })
        // 绑分类：分类下所有活动都算
        assertEquals(
            listOf(1L, 2L),
            timeline.segmentsMatching(1L, null).map { it.record.id },
        )
        // 都不绑：Goal 不允许，返回空
        assertTrue(timeline.segmentsMatching(null, null).isEmpty())
    }

    @Test
    fun `survivingRecordsMatching uses the record start for ownership`() {
        // A 从窗口之前就开始了，B 完全在窗口内；两者在时间上不重叠，所以都赢下自己那一段。
        val startedBeforeWindow = record(0, 8, categoryId = 1L, id = 1L)
        val insideWindow = record(10, 20, categoryId = 1L, id = 2L)

        val timeline = TimelineResolver.resolve(listOf(startedBeforeWindow, insideWindow))
        // 窗口从第 5 分钟开始
        val window = MillisRange(m(5), m(60))

        assertEquals(2, timeline.segments.size)
        // 时长统计关心「区间是否与窗口相交」：两条都相交，所以两条的时长都该算进窗口
        assertEquals(2, timeline.segments.filter { it.range.overlaps(window) }.size)
        // 计数/频次统计关心「记录归属哪个周期」：只有开始时间落在窗口内的那条算数，
        // 否则一次跨过边界的健身会被两个周期各算一次。
        assertEquals(
            listOf(2L),
            timeline.survivingRecordsMatching(1L, null, window).map { it.id },
        )
    }
}
