package com.timeledger.app.ui.day

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.domain.model.Activity
import com.timeledger.app.domain.model.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 记录编辑框状态映射测试。
 *
 * 与其他界面映射器同样的理由：本机无法目视验证界面，
 * 所以「选项怎么筛、能不能保存、提示什么话」这些判断在这里逐条钉死。
 */
class RecordEditorUiMapperTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private val dayBoundary = DayBoundary(zoneId = shanghai, nowProvider = { 0L })
    private val mapper = RecordEditorUiMapper(dayBoundary)

    private val sept12: Long = LocalDate.of(2026, 9, 12).toEpochDay()

    private val categories = listOf(
        Category(id = 1L, name = "学习", colorArgb = 0xFF3F6BFF.toInt(), sortOrder = 1),
        Category(id = 2L, name = "娱乐", colorArgb = 0xFFE0574A.toInt(), sortOrder = 2),
        Category(id = 3L, name = "旧分类", colorArgb = null, sortOrder = 3, isArchived = true),
    )

    private val activities = listOf(
        Activity(id = 100L, categoryId = 1L, name = "Java", colorArgb = 0xFF00AA00.toInt(), sortOrder = 1),
        Activity(id = 101L, categoryId = 1L, name = "算法", colorArgb = null, sortOrder = 2),
        Activity(id = 200L, categoryId = 2L, name = "游戏", colorArgb = null, sortOrder = 1),
        Activity(id = 300L, categoryId = 1L, name = "已归档活动", colorArgb = null, sortOrder = 3, isArchived = true),
    )

    private fun form(
        startText: String = "09:00",
        endText: String = "10:40",
        categoryId: Long? = 1L,
        activityId: Long? = 100L,
        countText: String = "",
        note: String = "",
        startCalendarDay: Long = sept12,
    ) = RecordEditState(
        recordId = 5L,
        startCalendarDay = startCalendarDay,
        startText = startText,
        endText = endText,
        categoryId = categoryId,
        activityId = activityId,
        countText = countText,
        note = note,
    )

    private fun map(form: RecordEditState?, error: RecordEditError? = null) =
        mapper.map(form, error, categories, activities)

    // ---------------- 开关 ----------------

    @Test
    fun `no form means no dialog`() {
        assertNull(mapper.map(null, null, categories, activities))
    }

    @Test
    fun `no form means no dialog even when there is an error`() {
        assertNull(mapper.map(null, RecordEditError.SAVE_FAILED, categories, activities))
    }

    @Test
    fun `title and date describe which record is being edited`() {
        val ui = map(form())!!
        assertEquals("编辑这条记录", ui.title)
        assertEquals("9月12日 周六", ui.dateLabel)
    }

    @Test
    fun `date comes from the records own calendar day`() {
        // 凌晨 02:00 的记录属于当天的日历日（逻辑日是前一天），提示里必须写它自己那天
        val ui = map(form(startText = "02:00", endText = "03:00", startCalendarDay = sept12 + 1))!!
        assertEquals("9月13日 周日", ui.dateLabel)
    }

    // ---------------- 选项 ----------------

    @Test
    fun `archived entries are not offered`() {
        val ui = map(form())!!

        assertFalse(ui.categories.any { it.id == 3L })
        assertFalse(ui.activities.any { it.id == 300L })
    }

    @Test
    fun `categories are ordered and keep their colours`() {
        val ui = map(form())!!

        assertEquals(listOf(1L, 2L), ui.categories.map { it.id })
        assertEquals(0xFF3F6BFF.toInt(), ui.categories.first().colorArgb)
    }

    @Test
    fun `activities are limited to the selected category`() {
        val ui = map(form(categoryId = 2L, activityId = null))!!

        assertEquals(listOf(200L), ui.activities.map { it.id })
    }

    @Test
    fun `an activity without its own colour inherits the category colour`() {
        val ui = map(form())!!

        assertEquals(0xFF3F6BFF.toInt(), ui.activities.first { it.id == 101L }.colorArgb)
        assertEquals(0xFF00AA00.toInt(), ui.activities.first { it.id == 100L }.colorArgb)
    }

    @Test
    fun `no category means no activity options`() {
        // 没有分类就没有可选活动：活动必须挂在某个分类下
        val ui = map(form(categoryId = null, activityId = null))!!
        assertTrue(ui.activities.isEmpty())
    }

    // ---------------- 时长与两种特殊形态 ----------------

    @Test
    fun `duration preview is shown for a normal range`() {
        val ui = map(form(startText = "09:00", endText = "10:40"))!!
        assertEquals("1 小时 40 分", ui.durationLabel)
        assertFalse(ui.isInstant)
        assertFalse(ui.crossesMidnight)
    }

    @Test
    fun `equal times read as an instant record`() {
        val ui = map(form(startText = "20:00", endText = "20:00"))!!

        assertTrue(ui.isInstant)
        assertFalse(ui.crossesMidnight)
        // 零长度没有时长可言，不该显示「0 分钟」让人以为记录有问题
        assertNull(ui.durationLabel)
    }

    @Test
    fun `end earlier than start reads as crossing midnight`() {
        val ui = map(form(startText = "23:30", endText = "01:00"))!!

        assertTrue(ui.crossesMidnight)
        assertFalse(ui.isInstant)
        assertEquals("1 小时 30 分", ui.durationLabel)
    }

    @Test
    fun `unparsable times produce no preview and no hints`() {
        val ui = map(form(startText = "abc", endText = "10:00"))!!

        assertNull(ui.durationLabel)
        assertFalse(ui.isInstant)
        assertFalse(ui.crossesMidnight)
    }

    // ---------------- 能否保存 ----------------

    @Test
    fun `a well formed form can be saved`() {
        assertTrue(map(form())!!.canSave)
    }

    @Test
    fun `a form with a bad time cannot be saved`() {
        assertFalse(map(form(startText = "25:00"))!!.canSave)
        assertFalse(map(form(endText = ""))!!.canSave)
    }

    @Test
    fun `a form with a bad count cannot be saved`() {
        assertFalse(map(form(countText = "-3"))!!.canSave)
    }

    @Test
    fun `a form without category or activity can still be saved`() {
        // 清空归属 = 回到「未归类」，这是合法状态（系统刚采集到的记录就是这样）
        assertTrue(map(form(categoryId = null, activityId = null))!!.canSave)
    }

    // ---------------- 报错文案 ----------------

    @Test
    fun `every error has its own message`() {
        val messages = RecordEditError.entries.map { error ->
            map(form(), error)!!.errorMessage
        }

        assertTrue(messages.all { it != null })
        // 四种原因对应四句不同的话：都写成「保存失败」等于什么都没说
        assertEquals(RecordEditError.entries.size, messages.toSet().size)
    }

    @Test
    fun `no error means no message`() {
        assertNull(map(form())!!.errorMessage)
    }
}
