package com.timeledger.app.domain.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周次解析测试（模块 28）。
 *
 * 用例里的字符串**全部来自用户真实课表的原文**（`作者(2026-2027-1)课表.pdf` 的文字层，
 * 逐字取出）：`第18周`、`1周,4-6周,8-15周`、`1-3周,5-17周`、`3周,15-17周`……
 * 用真实数据写测试，是因为这类解析最容易在「我没想到的写法」上出错。
 */
class WeekSpecTest {

    @Test
    fun `empty means every week`() {
        // 加这一列之前所有计划块都是这个语义 —— 迁移的默认值也是它
        assertTrue(WeekSpec.parse(null).isEveryWeek)
        assertTrue(WeekSpec.parse("").isEveryWeek)
        assertTrue(WeekSpec.parse("   ").isEveryWeek)
        assertTrue(WeekSpec.parse("").matches(1))
        assertTrue(WeekSpec.parse("").matches(30))
    }

    @Test
    fun `a full term range`() {
        val spec = WeekSpec.parse("1-17周")

        assertTrue(spec.isUnderstood)
        assertTrue(spec.matches(1))
        assertTrue(spec.matches(17))
        assertFalse(spec.matches(18))
        assertFalse(spec.matches(0))
    }

    @Test
    fun `a single week`() {
        val spec = WeekSpec.parse("第18周")

        assertTrue(spec.matches(18))
        assertFalse(spec.matches(17))
    }

    @Test
    fun `several ranges separated by commas`() {
        // 用户课表里最常见的形态：1周,4-6周,8-15周
        val spec = WeekSpec.parse("1周,4-6周,8-15周")

        assertTrue(spec.matches(1))
        assertFalse("第 2、3 周没有这门课", spec.matches(2))
        assertTrue(spec.matches(4))
        assertTrue(spec.matches(6))
        assertFalse("第 7 周没课", spec.matches(7))
        assertTrue(spec.matches(8))
        assertTrue(spec.matches(15))
        assertFalse(spec.matches(16))
    }

    @Test
    fun `the gap week is the whole point of this class`() {
        // 1-3周,5-17周：第 4 周不上。以前硬导成「每周」时，
        // 第 4 周会显示「计划有课但没记录」——那正是要避免的谎报。
        val spec = WeekSpec.parse("1-3周,5-17周")

        assertTrue(spec.matches(3))
        assertFalse(spec.matches(4))
        assertTrue(spec.matches(5))
    }

    @Test
    fun `odd and even weeks`() {
        val odd = WeekSpec.parse("单周")
        val even = WeekSpec.parse("双周")

        assertTrue(odd.matches(1))
        assertFalse(odd.matches(2))
        assertTrue(even.matches(2))
        assertFalse(even.matches(1))
    }

    @Test
    fun `parity combined with a range`() {
        val spec = WeekSpec.parse("1-17单周")

        assertTrue(spec.matches(1))
        assertFalse(spec.matches(2))
        assertTrue(spec.matches(15))
        assertFalse("超出范围", spec.matches(19))
    }

    @Test
    fun `full width punctuation and spacing are tolerated`() {
        assertTrue(WeekSpec.parse("1-3周，5-17周").matches(10))
        assertTrue(WeekSpec.parse("1、4-6").matches(4))
        assertTrue(WeekSpec.parse(" 1 - 3 ").matches(2))
    }

    @Test
    fun `an unparsable spec never hides the course`() {
        // 诚实优先：看不懂就不阻断（宁可多显示一次计划，也不要悄悄抹掉一门课）
        val spec = WeekSpec.parse("等着通知")

        assertFalse(spec.isUnderstood)
        assertTrue(spec.matches(1))
        assertTrue(spec.matches(18))
        assertEquals("等着通知", spec.source)
    }

    @Test
    fun `the source text is kept verbatim for the user to check`() {
        // 界面上要能原样显示，用户才核对得了
        assertEquals("1周,4-6周,8-15周", WeekSpec.parse("1周,4-6周,8-15周").source)
        assertEquals("第18周", WeekSpec.parse(" 第18周 ").source)
    }
}
