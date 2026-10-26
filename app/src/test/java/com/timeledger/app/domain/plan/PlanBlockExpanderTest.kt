package com.timeledger.app.domain.plan

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.domain.model.PlanBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 计划展开测试。
 *
 * 锁住的都是「4:00 边界 + 跨夜 + 每周重复」交织出来的规则，
 * 这些规则错了在界面上表现得非常隐蔽：课表少一条、或者某条目整体偏一天。
 *
 * 日期基准：2026-09-12 是**周六**，因此
 * sept11 = 周五、sept13 = 周日、sept14 = 周一、sept5 / sept19 也是周六。
 */
class PlanBlockExpanderTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private val fixedNow = Instant.parse("2026-09-12T06:00:00Z").toEpochMilli()
    private val dayBoundary = DayBoundary(zoneId = shanghai, nowProvider = { fixedNow })
    private val expander = PlanBlockExpander(dayBoundary)

    private val sept5 = LocalDate.of(2026, 9, 5).toEpochDay()
    private val sept7 = LocalDate.of(2026, 9, 7).toEpochDay()
    private val sept11 = LocalDate.of(2026, 9, 11).toEpochDay()
    private val sept12 = LocalDate.of(2026, 9, 12).toEpochDay()
    private val sept13 = LocalDate.of(2026, 9, 13).toEpochDay()
    private val sept14 = LocalDate.of(2026, 9, 14).toEpochDay()

    private val saturday = 6
    private val sunday = 7
    private val monday = 1
    private val friday = 5

    /** 距该逻辑日 04:00 的第 N 小时；负值表示当天 04:00 之前（属于前一个逻辑日）。 */
    private fun at(day: Long, hoursFrom4am: Int, minutes: Int = 0): Long =
        dayBoundary.dayRange(day).startMillis + hoursFrom4am * 3600_000L + minutes * 60_000L

    private fun minutes(hour: Int, minute: Int = 0): Int = hour * 60 + minute

    private fun weekly(
        id: Long,
        title: String,
        dayOfWeek: Int,
        startMinuteOfDay: Int,
        endMinuteOfDay: Int,
        categoryId: Long? = null,
        activityId: Long? = null,
        location: String? = null,
        validFromEpochDay: Long? = null,
        validToEpochDay: Long? = null,
        isActive: Boolean = true,
    ) = PlanBlock(
        id = id,
        title = title,
        dateEpochDay = null,
        dayOfWeek = dayOfWeek,
        startMinuteOfDay = startMinuteOfDay,
        endMinuteOfDay = endMinuteOfDay,
        categoryId = categoryId,
        activityId = activityId,
        location = location,
        validFromEpochDay = validFromEpochDay,
        validToEpochDay = validToEpochDay,
        isActive = isActive,
    )

    private fun once(
        id: Long,
        title: String,
        dateEpochDay: Long,
        startMinuteOfDay: Int,
        endMinuteOfDay: Int,
    ) = PlanBlock(
        id = id,
        title = title,
        dateEpochDay = dateEpochDay,
        dayOfWeek = null,
        startMinuteOfDay = startMinuteOfDay,
        endMinuteOfDay = endMinuteOfDay,
    )

    // ---------------- 需求五节的示例课表 ----------------

    @Test
    fun `weekly timetable expands into ordered slots with correct durations`() {
        val timetable = listOf(
            weekly(1, "高等数学", monday, minutes(9), minutes(10, 40), location = "文一 203"),
            weekly(2, "Java", monday, minutes(11), minutes(12)),
            weekly(3, "软件工程", monday, minutes(14), minutes(15, 40)),
            weekly(4, "自习", monday, minutes(19), minutes(22)),
        )

        val slots = expander.expandForDay(timetable, sept14)

        assertEquals(4, slots.size)
        assertEquals(
            listOf("高等数学", "Java", "软件工程", "自习"),
            slots.map { it.title },
        )
        assertEquals(listOf(100, 60, 100, 180), slots.map { it.durationMinutes })
        // 第一条：周一 09:00–10:40
        assertEquals(at(sept14, 5), slots[0].startMillis)
        assertEquals(at(sept14, 6, 40), slots[0].endMillisExclusive)
        assertEquals("文一 203", slots[0].location)
        assertEquals(sept14, slots[0].logicalDay)
        assertFalse(slots[0].crossesMidnight)
    }

    @Test
    fun `weekly block does not appear on other weekdays`() {
        val timetable = listOf(weekly(1, "高等数学", monday, minutes(9), minutes(10, 40)))
        assertTrue(expander.expandForDay(timetable, sept12).isEmpty())
        assertEquals(1, expander.expandForDay(timetable, sept14).size)
    }

    // ---------------- 4:00 边界 ----------------

    @Test
    fun `early morning block belongs to the previous logical day`() {
        // 周日 01:00–02:00：挂钟上是周日，但凌晨 4 点才翻篇，所以属于周六那一格
        val blocks = listOf(weekly(1, "早读", sunday, minutes(1), minutes(2)))

        val onSaturday = expander.expandForDay(blocks, sept12)
        assertEquals(1, onSaturday.size)
        assertEquals(sept12, onSaturday.single().logicalDay)
        assertEquals(at(sept13, -3), onSaturday.single().startMillis) // 周日 01:00
        assertEquals(at(sept13, -2), onSaturday.single().endMillisExclusive)

        // 展开周日时它不该再出现，否则同一条计划会被两天各显示一次
        assertTrue(expander.expandForDay(blocks, sept13).isEmpty())
    }

    @Test
    fun `block starting exactly at four am belongs to its own calendar day`() {
        val blocks = listOf(weekly(1, "晨跑", sunday, minutes(4), minutes(5)))

        val onSunday = expander.expandForDay(blocks, sept13)
        assertEquals(1, onSunday.size)
        assertEquals(sept13, onSunday.single().logicalDay)
        assertTrue(expander.expandForDay(blocks, sept12).isEmpty())
    }

    @Test
    fun `block starting one minute before four am belongs to the previous day`() {
        val blocks = listOf(weekly(1, "夜读", saturday, minutes(3, 59), minutes(4, 30)))

        // 开始于周六 03:59 → 属于周五那一格
        val onFriday = expander.expandForDay(blocks, sept11)
        assertEquals(1, onFriday.size)
        assertEquals(sept11, onFriday.single().logicalDay)
        // 结束时刻落在周六 04:30，但这不影响归属（归属只看开始时刻）
        assertEquals(at(sept12, 0, 30), onFriday.single().endMillisExclusive)

        assertTrue(expander.expandForDay(blocks, sept12).isEmpty())
    }

    // ---------------- 跨夜 ----------------

    @Test
    fun `cross midnight block is owned by its start day`() {
        // 周六 23:30 – 周日 01:00
        val blocks = listOf(weekly(1, "夜间自习", saturday, minutes(23, 30), minutes(1)))

        val onSaturday = expander.expandForDay(blocks, sept12)
        assertEquals(1, onSaturday.size)
        val slot = onSaturday.single()
        assertTrue(slot.crossesMidnight)
        assertEquals(at(sept12, 19, 30), slot.startMillis)  // 周六 23:30
        assertEquals(at(sept13, -3), slot.endMillisExclusive) // 周日 01:00
        assertEquals(90, slot.durationMinutes)

        // 周日那一格不重复展示
        assertTrue(expander.expandForDay(blocks, sept13).isEmpty())
    }

    @Test
    fun `end minute of 1440 resolves to the next midnight`() {
        val blocks = listOf(weekly(1, "晚自习", saturday, minutes(22), 1440))

        val slot = expander.expandForDay(blocks, sept12).single()

        // 注意 at() 的基准是 04:00，所以「周日 00:00」要写成 -4 小时
        assertEquals(at(sept13, -4), slot.endMillisExclusive)
        assertEquals(120, slot.durationMinutes)
        // 到 24:00 结束不算「跨夜」（结束分钟数大于开始分钟数），但确实占到了次日零点
        assertFalse(slot.crossesMidnight)
    }

    // ---------------- 单次日程 ----------------

    @Test
    fun `single occurrence block only appears on its own date`() {
        val blocks = listOf(once(1, "补课", sept12, minutes(10), minutes(11)))

        assertEquals(1, expander.expandForDay(blocks, sept12).size)
        assertTrue(expander.expandForDay(blocks, sept11).isEmpty())
        assertTrue(expander.expandForDay(blocks, sept13).isEmpty())
    }

    @Test
    fun `single occurrence early morning block belongs to the previous logical day`() {
        val blocks = listOf(once(1, "赶飞机", sept13, minutes(1), minutes(2)))

        val onSaturday = expander.expandForDay(blocks, sept12)
        assertEquals(1, onSaturday.size)
        assertEquals(sept12, onSaturday.single().logicalDay)
        assertTrue(expander.expandForDay(blocks, sept13).isEmpty())
    }

    // ---------------- 生效区间与开关 ----------------

    @Test
    fun `valid from excludes earlier days`() {
        val blocks = listOf(
            weekly(1, "新学期课", saturday, minutes(9), minutes(10), validFromEpochDay = sept12)
        )

        assertTrue(expander.expandForDay(blocks, sept5).isEmpty()) // 上一个周六，学期还没开始
        assertEquals(1, expander.expandForDay(blocks, sept12).size)
    }

    @Test
    fun `valid to excludes later days`() {
        val blocks = listOf(
            weekly(1, "结课", saturday, minutes(9), minutes(10), validToEpochDay = sept5)
        )

        assertEquals(1, expander.expandForDay(blocks, sept5).size)
        assertTrue(expander.expandForDay(blocks, sept12).isEmpty())
    }

    @Test
    fun `inactive block is skipped`() {
        val blocks = listOf(
            weekly(1, "已停用", saturday, minutes(9), minutes(10), isActive = false)
        )
        assertTrue(expander.expandForDay(blocks, sept12).isEmpty())
    }

    // ---------------- 边界与不变式 ----------------

    @Test
    fun `empty input yields empty result`() {
        assertTrue(expander.expandForDay(emptyList(), sept12).isEmpty())
    }

    @Test
    fun `slots are sorted by start time regardless of input order`() {
        val blocks = listOf(
            weekly(3, "下午", saturday, minutes(14), minutes(15)),
            weekly(1, "早上", saturday, minutes(9), minutes(10)),
            weekly(2, "中午", saturday, minutes(12), minutes(13)),
        )

        val slots = expander.expandForDay(blocks, sept12)

        assertEquals(listOf("早上", "中午", "下午"), slots.map { it.title })
    }

    @Test
    fun `conflicting blocks are both returned and left unresolved`() {
        // 排课冲突是真实存在的，系统不该悄悄丢掉一个
        val blocks = listOf(
            weekly(1, "选修 A", saturday, minutes(9), minutes(10)),
            weekly(2, "选修 B", saturday, minutes(9, 30), minutes(10, 30)),
        )

        val slots = expander.expandForDay(blocks, sept12)

        assertEquals(2, slots.size)
        assertTrue(slots[0].range.overlaps(slots[1].range))
    }

    @Test
    fun `a weekly block appears exactly once per week over a two week span`() {
        // 这条不变式挡住的是「同一条计划在相邻两天各出现一次」这类重复展示 bug
        val blocks = listOf(weekly(1, "周六课", saturday, minutes(9), minutes(10)))

        val appearances = (sept7 until sept7 + 14)
            .map { day -> expander.expandForDay(blocks, day).size }
            .sum()

        assertEquals(2, appearances) // 两个周六
    }

    @Test
    fun `cross midnight block also appears exactly once per week`() {
        val blocks = listOf(weekly(1, "夜间自习", saturday, minutes(23, 30), minutes(1)))

        val appearances = (sept7 until sept7 + 14)
            .map { day -> expander.expandForDay(blocks, day).size }
            .sum()

        assertEquals(2, appearances)
    }

    @Test
    fun `slot carries the plan binding for later comparison against records`() {
        val blocks = listOf(
            weekly(
                id = 7,
                title = "Java",
                dayOfWeek = saturday,
                startMinuteOfDay = minutes(9),
                endMinuteOfDay = minutes(10),
                categoryId = 11L,
                activityId = 22L,
                location = "机房",
            )
        )

        val slot = expander.expandForDay(blocks, sept12).single()

        assertEquals(7L, slot.planBlockId)
        assertEquals(11L, slot.categoryId)
        assertEquals(22L, slot.activityId)
        assertEquals("机房", slot.location)
        assertEquals(60 * 60_000L, slot.durationMillis)
    }

    @Test
    fun `distinct weekly blocks on different weekdays do not shadow each other`() {
        val blocks = listOf(
            weekly(1, "周五课", friday, minutes(9), minutes(10)),
            weekly(2, "周六课", saturday, minutes(9), minutes(10)),
        )

        assertEquals(listOf("周五课"), expander.expandForDay(blocks, sept11).map { it.title })
        assertEquals(listOf("周六课"), expander.expandForDay(blocks, sept12).map { it.title })
        assertTrue(expander.expandForDay(blocks, sept13).isEmpty())
    }
}
