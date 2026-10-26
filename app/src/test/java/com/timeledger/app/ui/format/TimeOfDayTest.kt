package com.timeledger.app.ui.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「一天中的第几分钟」的解析与格式化测试。
 *
 * 这些用例原先长在 PlanFormTest 里，实现搬到 [TimeOfDay] 后跟着一起搬过来 ——
 * 课程表和记录编辑共用同一份实现，测试也必须跟着实现走，
 * 否则会出现「测试还在测旧入口」的假绿灯。
 */
class TimeOfDayTest {

    // ---------------- 解析 ----------------

    @Test
    fun `parse accepts the formats people actually type`() {
        assertEquals(540, TimeOfDay.parseMinuteOfDay("09:00"))
        assertEquals(540, TimeOfDay.parseMinuteOfDay("9:00"))
        assertEquals(540, TimeOfDay.parseMinuteOfDay("9"))
        // 中文输入法下很容易打成全角冒号
        assertEquals(540, TimeOfDay.parseMinuteOfDay("9：00"))
        assertEquals(540, TimeOfDay.parseMinuteOfDay("  09:00  "))
        assertEquals(1439, TimeOfDay.parseMinuteOfDay("23:59"))
        // 24:00 表示「到当天 24 点结束」
        assertEquals(1440, TimeOfDay.parseMinuteOfDay("24:00"))
        assertEquals(0, TimeOfDay.parseMinuteOfDay("0:00"))
    }

    @Test
    fun `parse rejects invalid formats and ranges`() {
        val invalid = listOf("", "   ", "abc", "25:00", "9:60", "-1:00", "24:30", "9:0:0", "9:", ":30")
        invalid.forEach { text ->
            assertNull("「$text」应当解析失败", TimeOfDay.parseMinuteOfDay(text))
        }
    }

    // ---------------- 格式化 ----------------

    @Test
    fun `minute text round trips with zero padding`() {
        assertEquals("09:00", TimeOfDay.minuteOfDayText(540))
        assertEquals("00:00", TimeOfDay.minuteOfDayText(0))
        assertEquals("10:40", TimeOfDay.minuteOfDayText(640))
        assertEquals("24:00", TimeOfDay.minuteOfDayText(1440))
        // 与解析互为逆运算
        listOf(0, 540, 640, 1439, 1440).forEach { minute ->
            assertEquals(minute, TimeOfDay.parseMinuteOfDay(TimeOfDay.minuteOfDayText(minute)))
        }
    }

    // ---------------- 时长 ----------------

    @Test
    fun `duration text formats hours and minutes`() {
        assertEquals("1 小时 40 分", TimeOfDay.durationText("09:00", "10:40"))
        assertEquals("1 小时", TimeOfDay.durationText("09:00", "10:00"))
        assertEquals("30 分钟", TimeOfDay.durationText("09:00", "09:30"))
        assertEquals("45 分钟", TimeOfDay.durationText(45))
        assertEquals("2 小时", TimeOfDay.durationText(120))
    }

    @Test
    fun `duration text treats end before start as crossing midnight`() {
        assertEquals("1 小时 30 分", TimeOfDay.durationText("23:30", "01:00"))
        assertEquals("2 小时", TimeOfDay.durationText("23:00", "01:00"))
    }

    @Test
    fun `duration text is null when unparsable or zero length`() {
        assertNull(TimeOfDay.durationText("", "10:00"))
        assertNull(TimeOfDay.durationText("09:00", "abc"))
        // 两端相同：既不是跨夜也不是有效时长，交给调用方各自决定
        assertNull(TimeOfDay.durationText("09:00", "09:00"))
    }
}
