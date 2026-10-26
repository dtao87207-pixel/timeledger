package com.timeledger.app.ui.confirm

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.model.Activity
import com.timeledger.app.domain.model.Category
import com.timeledger.app.domain.model.RecordSource
import com.timeledger.app.domain.model.TimeRecord
import com.timeledger.app.domain.review.DayReview
import com.timeledger.app.domain.review.TimelineAggregator
import com.timeledger.app.domain.timeline.TimelineResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 事后确认页映射测试（需求三节 C）。
 *
 * 这一页的产品要求是「只问说不清的部分」，所以测试的重点是**挑得准不准**：
 * 哪些该问、哪些不该问、多短的缺口不值得问。
 * 问得太多，用户会直接关掉通知 —— 而这个 App 一旦没了通知，闭环就断了。
 */
class ConfirmationStateMapperTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private val fixedNow = Instant.parse("2026-09-12T06:00:00Z").toEpochMilli()
    private val dayBoundary = DayBoundary(zoneId = shanghai, nowProvider = { fixedNow })

    private val sept12: Long = LocalDate.of(2026, 9, 12).toEpochDay()
    private val window: MillisRange = dayBoundary.dayRange(sept12)
    private val hour = 3600_000L

    private val mapper = ConfirmationStateMapper(zone = shanghai)

    private val study = Category(id = 1L, name = "学习", colorArgb = 0xFF3F6BFF.toInt(), sortOrder = 0)
    private val funCategory = Category(id = 2L, name = "娱乐", colorArgb = 0xFFFF8A3D.toInt(), sortOrder = 1)
    private val java = Activity(id = 100L, categoryId = 1L, name = "Java", sortOrder = 0)
    private val bilibili = Activity(id = 200L, categoryId = 2L, name = "B站", sortOrder = 0)

    private fun categories() = listOf(study, funCategory)
    private fun activities() = listOf(java, bilibili)

    /** 逻辑日窗口内、距 04:00 的第 N 小时。 */
    private fun at(hoursFrom4am: Double): Long = window.startMillis + (hoursFrom4am * hour).toLong()

    private fun record(
        fromHours: Double,
        toHours: Double,
        categoryId: Long? = 1L,
        activityId: Long? = 100L,
        source: RecordSource = RecordSource.MANUAL,
        confidence: Double = 1.0,
        id: Long = 0L,
    ) = TimeRecord(
        id = id,
        startMillis = at(fromHours),
        endMillis = at(toHours),
        categoryId = categoryId,
        activityId = activityId,
        source = source,
        confidence = confidence,
    )

    private fun review(records: List<TimeRecord>): DayReview {
        val timeline = TimelineResolver.resolve(records)
        return DayReview(
            epochDay = sept12,
            window = window,
            timeline = timeline,
            durations = TimelineAggregator().aggregate(timeline, window),
            planComparisons = emptyList(),
            goalProgress = emptyList(),
            pendingRecordCount = records.count { it.needsConfirmation },
        )
    }

    private fun map(
        records: List<TimeRecord>,
        editor: ConfirmEditorState? = null,
        editorError: ConfirmEditorError? = null,
        mapperToUse: ConfirmationStateMapper = mapper,
    ) = mapperToUse.map(
        review = review(records),
        categories = categories(),
        activities = activities(),
        editor = editor,
        editorError = editorError,
    )

    // ---------------- 该问什么 ----------------

    @Test
    fun `pending records are listed sorted by start time`() {
        val state = map(
            listOf(
                record(0.0, 1.0, source = RecordSource.MANUAL),                        // 已确认
                record(12.0, 13.0, categoryId = 2L, activityId = 200L, source = RecordSource.SUGGESTED, confidence = 0.3),
                record(10.0, 11.0, categoryId = 2L, activityId = 200L, source = RecordSource.SUGGESTED, confidence = 0.4),
            )
        )

        assertEquals(2, state.pendingRecords.size)
        // 按时间排序：较早的那条在前
        assertEquals("14:00–15:00", state.pendingRecords[0].rangeLabel)
        assertEquals("16:00–17:00", state.pendingRecords[1].rangeLabel)
        assertEquals("B站", state.pendingRecords[0].title)
        assertEquals("系统推测 · 置信度 40%", state.pendingRecords[0].confidenceLabel)
        assertEquals("系统推测 · 置信度 30%", state.pendingRecords[1].confidenceLabel)
        assertEquals("1h", state.pendingRecords[0].durationLabel)
        assertTrue(state.hasPending)
    }

    @Test
    fun `manually recorded entries are not asked about`() {
        val state = map(listOf(record(0.0, 1.0, source = RecordSource.MANUAL)))
        assertTrue(state.pendingRecords.isEmpty())
    }

    @Test
    fun `a suggestion fully covered by a manual record is not asked about`() {
        // 用户已经手写了 14:00–15:00，系统那条同样的推测在时间轴上根本不存在，
        // 再拿出来问「这段时间你做了什么」只会让人莫名其妙
        val state = map(
            listOf(
                record(10.0, 11.0, source = RecordSource.MANUAL),
                record(10.0, 11.0, categoryId = 2L, activityId = 200L, source = RecordSource.SUGGESTED),
            )
        )

        assertTrue(state.pendingRecords.isEmpty())
        // 这一页不是「无事可确认」：这一天仍然有大量没记录的时段。
        // 这条测试只关心「被覆盖的推测不该再问」，所以断言到这里为止。
        assertTrue(state.hasGaps)
    }

    @Test
    fun `unclassified suggestions fall back to a readable title`() {
        val state = map(
            listOf(record(10.0, 11.0, categoryId = null, activityId = null, source = RecordSource.SUGGESTED))
        )
        assertEquals("未归类", state.pendingRecords.single().title)
        assertNull(state.pendingRecords.single().colorArgb)
    }

    // ---------------- 缺口 ----------------

    @Test
    fun `gaps are the uncovered stretches of the day`() {
        val state = map(
            listOf(
                record(5.0, 6.0, source = RecordSource.MANUAL),   // 09:00–10:00
                record(10.0, 11.0, source = RecordSource.SUGGESTED, categoryId = 2L, activityId = 200L),
                record(12.0, 13.0, source = RecordSource.SUGGESTED, categoryId = 2L, activityId = 200L),
            )
        )

        // 缺口：04–09（5h）、10–14（4h）、15–16（1h）、17–次日04（11h）
        assertEquals(4, state.gaps.size)
        assertEquals("04:00–09:00", state.gaps[0].rangeLabel)
        assertEquals("17:00–次日 04:00", state.gaps[3].rangeLabel)
        assertEquals("11h", state.gaps[3].durationLabel)
    }

    @Test
    fun `short gaps are not worth asking about`() {
        // 十分钟阈值：三分钟的空档问一次，用户很快就会关掉通知
        val strict = ConfirmationStateMapper(zone = shanghai, minGapMinutes = 600)
        val state = map(
            records = listOf(
                record(5.0, 6.0, source = RecordSource.MANUAL),
                record(13.0, 14.0, source = RecordSource.MANUAL), // 留下一段很短的缺口
            ),
            mapperToUse = strict,
        )

        // 阈值 10 小时下只剩最长的那一段（次日 04:00 前）
        assertEquals(1, state.gaps.size)
        assertEquals("18:00–次日 04:00", state.gaps.single().rangeLabel)
    }

    @Test
    fun `default threshold is ten minutes`() {
        assertEquals(10, ConfirmationStateMapper.DEFAULT_MIN_GAP_MINUTES)
    }

    @Test
    fun `cross midnight gap is labelled with next day`() {
        val state = map(listOf(record(0.0, 1.0, source = RecordSource.MANUAL)))
        // 05:00 一直到次日 04:00
        assertTrue(state.gaps.any { it.rangeLabel == "05:00–次日 04:00" })
    }

    // ---------------- 汇总与空状态 ----------------

    @Test
    fun `summary mentions both pending and gaps`() {
        val state = map(
            listOf(record(10.0, 11.0, categoryId = 2L, activityId = 200L, source = RecordSource.SUGGESTED))
        )

        assertTrue(state.summaryLabel.startsWith("1 条待确认，2 段缺失时间"))
        assertFalse(state.isEmpty)
        assertTrue(state.canConfirmAll.not()) // 只有一条时不给「全部确认」
    }

    @Test
    fun `confirm all is offered only when there are several pending records`() {
        val state = map(
            listOf(
                record(10.0, 11.0, source = RecordSource.SUGGESTED, categoryId = 2L, activityId = 200L),
                record(12.0, 13.0, source = RecordSource.SUGGESTED, categoryId = 2L, activityId = 200L),
            )
        )
        assertTrue(state.canConfirmAll)
    }

    @Test
    fun `a fully recorded day has nothing to ask`() {
        val state = map(listOf(record(0.0, 24.0, source = RecordSource.MANUAL)))

        assertTrue(state.isEmpty)
        assertTrue(state.gaps.isEmpty())
        assertEquals("这一天的记录都已经确认过了", state.summaryLabel)
    }

    // ---------------- 表单 ----------------

    @Test
    fun `gap editor offers categories and says it is a fill in`() {
        val state = map(
            records = emptyList(),
            editor = ConfirmEditorState(
                recordId = null,
                startMillis = at(10.0),
                endMillisExclusive = at(11.0),
            ),
        )

        val editor = state.editor!!
        assertTrue(state.isEditorOpen)
        assertTrue(editor.isGap)
        assertEquals("补记这段时间", editor.title)
        assertEquals("14:00–15:00", editor.rangeLabel)
        assertEquals("1h", editor.durationLabel)
        assertEquals(listOf("学习", "娱乐"), editor.categories.map { it.label })
        // 还没选分类 → 不能保存（确认一件事却什么都没说，没有意义）
        assertFalse(editor.canSave)
        assertTrue(editor.activities.isEmpty())
    }

    @Test
    fun `record editor is prefilled and immediately savable`() {
        val state = map(
            records = emptyList(),
            editor = ConfirmEditorState(
                recordId = 7L,
                startMillis = at(10.0),
                endMillisExclusive = at(11.0),
                categoryId = 1L,
                activityId = 100L,
                note = "看了并发",
            ),
        )

        val editor = state.editor!!
        assertFalse(editor.isGap)
        assertEquals("确认这段做了什么", editor.title)
        // 预填好了就能直接保存 —— 常见情形只需要点一下
        assertTrue(editor.canSave)
        assertEquals(listOf("Java"), editor.activities.map { it.label })
        assertEquals("看了并发", editor.note)
    }

    @Test
    fun `editor activities follow the selected category`() {
        val state = map(
            records = emptyList(),
            editor = ConfirmEditorState(
                recordId = null,
                startMillis = at(10.0),
                endMillisExclusive = at(11.0),
                categoryId = 2L,
            ),
        )
        assertEquals(listOf("B站"), state.editor!!.activities.map { it.label })
        // 只绑分类也能保存
        assertTrue(state.editor!!.canSave)
    }

    @Test
    fun `editor shows readable errors`() {
        assertEquals(
            "请选择分类，或指定一个具体活动",
            map(
                records = emptyList(),
                editor = ConfirmEditorState(
                    recordId = null,
                    startMillis = at(10.0),
                    endMillisExclusive = at(11.0),
                ),
                editorError = ConfirmEditorError.MISSING_TARGET,
            ).editor!!.errorMessage,
        )
        assertNull(
            map(
                records = emptyList(),
                editor = ConfirmEditorState(
                    recordId = null,
                    startMillis = at(10.0),
                    endMillisExclusive = at(11.0),
                ),
            ).editor!!.errorMessage,
        )
    }

    @Test
    fun `no editor means the dialog is closed`() {
        val state = map(listOf(record(0.0, 1.0, source = RecordSource.MANUAL)))
        assertFalse(state.isEditorOpen)
        assertNull(state.editor)
    }
}
