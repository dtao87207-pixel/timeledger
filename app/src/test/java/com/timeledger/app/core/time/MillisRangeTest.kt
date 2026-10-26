package com.timeledger.app.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 区间语义测试。
 *
 * 左闭右开 + 「零长度区间不与任何区间重叠」这两条是时间轴、目标统计、
 * 冲突消解的共同地基，因此单独测。
 */
class MillisRangeTest {

    private fun range(start: Long, end: Long) = MillisRange(start, end)

    @Test
    fun `duration is end minus start`() {
        assertEquals(100L, range(10, 110).durationMillis)
        assertEquals(0L, range(10, 10).durationMillis)
    }

    @Test
    fun `end before start is rejected`() {
        val error = runCatching { range(100, 50) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `contains uses half open semantics`() {
        val r = range(100, 200)
        assertTrue(100L in r)
        assertTrue(199L in r)
        assertFalse(200L in r)
        assertFalse(99L in r)
    }

    @Test
    fun `adjacent ranges do not overlap`() {
        assertFalse(range(0, 100).overlaps(range(100, 200)))
        assertFalse(range(100, 200).overlaps(range(0, 100)))
    }

    @Test
    fun `overlapping ranges are detected`() {
        assertTrue(range(0, 150).overlaps(range(100, 200)))
        assertTrue(range(100, 200).overlaps(range(0, 150)))
        // 完全包含
        assertTrue(range(0, 1000).overlaps(range(100, 200)))
    }

    @Test
    fun `instant range never overlaps`() {
        val instant = range(100, 100)
        assertTrue(instant.isInstant)
        assertFalse(instant.overlaps(range(0, 200)))
        assertFalse(instant.overlaps(instant))
    }

    @Test
    fun `overlaps always agrees with intersect being non null`() {
        // 把「有重叠」与「交集非空」永久绑死。
        // 这两个判断各写一套公式时，最容易出现「一个说有、一个说没有」的隐性 bug，
        // 而时间轴的冲突消解正是建立在这个判断上。
        val ranges = listOf(
            range(0, 100), range(100, 100), range(100, 200),
            range(0, 200), range(50, 50), range(200, 300), range(150, 150),
        )
        for (a in ranges) {
            for (b in ranges) {
                assertEquals(
                    "overlaps 必须等价于 intersect 非空：$a vs $b",
                    a.intersect(b) != null,
                    a.overlaps(b),
                )
            }
        }
    }

    @Test
    fun `contains and overlaps answer different questions`() {
        // 瞬时事件落在区间内部：contains 为真，overlaps 为假。
        // 「查询某天有哪些记录」需要把这种瞬时事件算进来，
        // 那是 DAO 区间查询里另一套判定（专门为零长度记录补了特例），不要与 overlaps 混用。
        val span = range(0, 100)
        val instant = range(50, 50)
        assertTrue(instant.startMillis in span)
        assertFalse(instant.overlaps(span))
        assertNull(instant.intersect(span))
    }

    @Test
    fun `intersect returns the shared part`() {
        assertEquals(range(100, 150), range(0, 150).intersect(range(100, 200)))
    }

    @Test
    fun `intersect returns null when there is nothing shared`() {
        assertNull(range(0, 100).intersect(range(100, 200)))
        assertNull(range(0, 100).intersect(range(200, 300)))
        // 零长度交集也不算重叠
        assertNull(range(0, 100).intersect(range(100, 100)))
    }

    @Test
    fun `comparison orders by start then end`() {
        val sorted = listOf(range(200, 300), range(0, 500), range(0, 100)).sorted()
        assertEquals(listOf(range(0, 100), range(0, 500), range(200, 300)), sorted)
    }

    @Test
    fun `empty range constant is an instant at zero`() {
        assertEquals(0L, MillisRange.EMPTY.startMillis)
        assertEquals(0L, MillisRange.EMPTY.endMillisExclusive)
    }
}
