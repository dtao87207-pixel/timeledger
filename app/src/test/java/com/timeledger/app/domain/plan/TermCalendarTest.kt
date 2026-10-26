package com.timeledger.app.domain.plan

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * 「某天是第几周」测试（模块 28）。
 *
 * 学期起点用的是用户给的真实值：**2026-09-14（周一）= 第 1 周**。
 */
class TermCalendarTest {

    private val termStart = LocalDate.of(2026, 9, 14) // 周一

    @Test
    fun `the first day of the term is week one`() {
        assertEquals(1, TermCalendar.weekNumberOn(LocalDate.of(2026, 9, 14), termStart))
    }

    @Test
    fun `the whole first week is week one`() {
        // 周一对齐：9/14(一) ~ 9/20(日) 都是第 1 周
        assertEquals(1, TermCalendar.weekNumberOn(LocalDate.of(2026, 9, 16), termStart))
        assertEquals(1, TermCalendar.weekNumberOn(LocalDate.of(2026, 9, 20), termStart))
    }

    @Test
    fun `the next monday starts week two`() {
        assertEquals(2, TermCalendar.weekNumberOn(LocalDate.of(2026, 9, 21), termStart))
        assertEquals(3, TermCalendar.weekNumberOn(LocalDate.of(2026, 9, 28), termStart))
    }

    @Test
    fun `week numbers stay in step across a month boundary`() {
        // 第 4 周：10/5(一) ~ 10/11(日)
        assertEquals(4, TermCalendar.weekNumberOn(LocalDate.of(2026, 10, 5), termStart))
        assertEquals(4, TermCalendar.weekNumberOn(LocalDate.of(2026, 10, 11), termStart))
        assertEquals(5, TermCalendar.weekNumberOn(LocalDate.of(2026, 10, 12), termStart))
    }

    @Test
    fun `a term start that is not a monday snaps back to that monday`() {
        // 起点给成周三也按那一周的周一算，否则「第 2 周」会从周三开始，
        // 同一门课在不同星期几会落到不同周次
        val wednesday = LocalDate.of(2026, 9, 16)
        assertEquals(LocalDate.of(2026, 9, 14), TermCalendar.mondayOf(wednesday))
        assertEquals(1, TermCalendar.weekNumberOn(wednesday, wednesday))
        assertEquals(1, TermCalendar.weekNumberOn(LocalDate.of(2026, 9, 20), wednesday))
        assertEquals(2, TermCalendar.weekNumberOn(LocalDate.of(2026, 9, 21), wednesday))
    }

    @Test
    fun `days before the term are not week zero`() {
        // 学期还没开始 → 0 或负数（调用方能区分「没开始」和「第 0 周没课」）。
        // 边界贴着「周一开周」这条规则：9/7(一) ~ 9/13(日) 整整一周都是**第 0 周**，
        // 再往前一周才是 -1。
        assertEquals(0, TermCalendar.weekNumberOn(LocalDate.of(2026, 9, 13), termStart))
        assertEquals(0, TermCalendar.weekNumberOn(LocalDate.of(2026, 9, 7), termStart))
        assertEquals(-1, TermCalendar.weekNumberOn(LocalDate.of(2026, 9, 6), termStart))
    }

    @Test
    fun `the two pieces fit together`() {
        // 用户课表里的真实一条：1-3周,5-17周 —— 第 4 周不上，其余都上
        val spec = WeekSpec.parse("1-3周,5-17周")
        fun on(date: LocalDate) = spec.matches(TermCalendar.weekNumberOn(date, termStart))

        assertEquals(true, on(LocalDate.of(2026, 9, 14)))  // 第 1 周
        assertEquals(true, on(LocalDate.of(2026, 9, 28)))  // 第 3 周
        assertEquals(false, on(LocalDate.of(2026, 10, 5))) // 第 4 周：不上
        assertEquals(true, on(LocalDate.of(2026, 10, 12))) // 第 5 周
        assertEquals(true, on(LocalDate.of(2026, 12, 28))) // 第 16 周
        assertEquals(false, on(LocalDate.of(2027, 1, 18))) // 第 19 周：学期结束
    }
}
