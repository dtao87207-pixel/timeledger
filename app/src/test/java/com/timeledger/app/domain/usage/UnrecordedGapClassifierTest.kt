package com.timeledger.app.domain.usage

import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.model.UsageEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「空白段里发生了什么」的分类测试（模块 24）。
 *
 * 用户的原话是「有些空的没有分类是怎么回事」。真机数据显示：
 * 竖轴上画出来的 8 块空白一共 6h58m，**每一块里面都有几百条真实事件** ——
 * 「空」不是「什么都没发生」，而是「发生了，但都不够长」。
 * 这个分类器就是把这些事件翻译成三个事实，好让界面把三种空白区分开。
 */
class UnrecordedGapClassifierTest {

    private val classifier = UnrecordedGapClassifier()

    /** 2026-09-12 04:00 +08:00 作为基准时刻。 */
    private val base = 1789171200000L
    private val minute = 60_000L
    private val hour = 3600_000L

    /** 基准时刻之后的第 N 分钟（收 Int 与 Double：整分钟写 10，带小数写 1.05，读起来都自然）。 */
    private fun at(minutesFromBase: Number): Long =
        base + (minutesFromBase.toDouble() * minute).toLong()

    private fun event(type: Int, minutesFromBase: Number, packageName: String? = null) = UsageEvent(
        packageName = packageName,
        eventType = type,
        timestampMillis = at(minutesFromBase),
        eventKey = UsageEvent.buildEventKey(type, at(minutesFromBase), packageName),
    )

    private val gap = MillisRange(at(0), at(60))

    // ---------------- 三种空白的区别 ----------------

    @Test
    fun `a gap with no events at all is reported as having no data`() {
        val result = classifier.classify(emptyList(), listOf(gap)).single()

        assertFalse(result.hasEvents)
        assertEquals(0, result.appOpenCount)
        assertEquals(0L, result.idleMillis)
        // 一条事件都没有 → 整段都是「没有数据」
        assertEquals(hour, result.noDataMillis)
    }

    @Test
    fun `an event cluster only covers the time it spans`() {
        // 真机上最典型的一块空白：前面一条事件都没有，最后两分钟才有几条零碎事件。
        // 「没有数据」的时长必须是前面那 58 分钟，而不是 0。
        val events = listOf(
            event(UsageEventTypes.ACTIVITY_RESUMED, 57, "com.tencent.mm"),
            event(UsageEventTypes.ACTIVITY_RESUMED, 58, "com.taobao.taobao"),
            event(UsageEventTypes.ACTIVITY_RESUMED, 59, "com.tencent.mm"),
        )

        val result = classifier.classify(events, listOf(gap)).single()

        assertEquals(58 * minute, result.noDataMillis)
        assertEquals(3, result.appOpenCount)
    }

    @Test
    fun `clusters separated by more than five minutes are counted separately`() {
        // 两簇事件（各跨 2 分钟），中间隔了 28 分钟：中间那段系统是没有数据的
        val events = listOf(
            event(UsageEventTypes.ACTIVITY_RESUMED, 0, "com.tencent.mm"),
            event(UsageEventTypes.ACTIVITY_RESUMED, 1, "com.tencent.mm"),
            event(UsageEventTypes.ACTIVITY_RESUMED, 2, "com.tencent.mm"),
            event(UsageEventTypes.ACTIVITY_RESUMED, 30, "com.tencent.mm"),
            event(UsageEventTypes.ACTIVITY_RESUMED, 31, "com.tencent.mm"),
            event(UsageEventTypes.ACTIVITY_RESUMED, 32, "com.tencent.mm"),
        )

        // 60 分钟里只有 2 + 2 = 4 分钟有动静
        assertEquals(56 * minute, classifier.classify(events, listOf(gap)).single().noDataMillis)
    }

    @Test
    fun `flickering screen off is counted as idle time`() {
        // 手机在口袋里：熄屏 → 亮屏 → 熄屏 …… 每段都不到 5 分钟
        val events = listOf(
            event(UsageEventTypes.SCREEN_NON_INTERACTIVE, 0),
            event(UsageEventTypes.SCREEN_INTERACTIVE, 20),
            event(UsageEventTypes.SCREEN_NON_INTERACTIVE, 25),
            event(UsageEventTypes.SCREEN_INTERACTIVE, 55),
        )

        val result = classifier.classify(events, listOf(gap)).single()

        assertTrue(result.hasEvents)
        // 20 分钟 + 30 分钟
        assertEquals(50 * minute, result.idleMillis)
        assertEquals(0, result.appOpenCount)
    }

    @Test
    fun `app opens are counted once per opening, not once per event`() {
        // 真机上同一个事件会被 ROM 重复上报（间隔 2497ms），
        // 而且切 App 时 PAUSED/RESUMED 会成对出现 —— 都不能把「打开了几次」算多
        val events = listOf(
            event(UsageEventTypes.ACTIVITY_RESUMED, 1, "com.tencent.mm"),
            event(UsageEventTypes.ACTIVITY_RESUMED, 1.05, "com.tencent.mm"), // 重复上报
            event(UsageEventTypes.ACTIVITY_PAUSED, 3, "com.tencent.mm"),
            event(UsageEventTypes.ACTIVITY_RESUMED, 4, "com.taobao.taobao"),
            event(UsageEventTypes.ACTIVITY_RESUMED, 4.05, "com.taobao.taobao"),
            event(UsageEventTypes.ACTIVITY_STOPPED, 6, "com.taobao.taobao"),
            event(UsageEventTypes.ACTIVITY_RESUMED, 8, "com.tencent.mm"),
        )

        val result = classifier.classify(events, listOf(gap)).single()

        assertEquals(3, result.appOpenCount)
    }

    @Test
    fun `going through the launcher counts as a new opening`() {
        // 「抖音 → 桌面 → 抖音」是两次打开：用户确实回了一次桌面。
        // 桌面本身不算一次「打开 App」，但它必须打断「还是同一个 App」的判断。
        val events = listOf(
            event(UsageEventTypes.ACTIVITY_RESUMED, 1, "com.ss.android.ugc.aweme.lite"),
            event(UsageEventTypes.ACTIVITY_RESUMED, 2, "com.bbk.launcher2"),
            event(UsageEventTypes.ACTIVITY_RESUMED, 3, "com.ss.android.ugc.aweme.lite"),
        )

        val result = classifier.classify(
            events = events,
            gaps = listOf(gap),
            ignoredPackages = setOf("com.bbk.launcher2"),
        ).single()

        assertEquals(2, result.appOpenCount)
    }

    // ---------------- 边界的处理 ----------------

    @Test
    fun `very short black screens do not count as idle`() {
        // 电源键抖动、以及 ROM 重复上报造成的 2.5 秒假熄屏，都不该被算成「手机没在用」
        val events = listOf(
            event(UsageEventTypes.SCREEN_NON_INTERACTIVE, 0),
            event(UsageEventTypes.SCREEN_INTERACTIVE, 0.05), // 3 秒
            event(UsageEventTypes.SCREEN_NON_INTERACTIVE, 10),
            event(UsageEventTypes.SCREEN_INTERACTIVE, 10.05), // 3 秒
        )

        assertEquals(0L, classifier.classify(events, listOf(gap)).single().idleMillis)
    }

    @Test
    fun `events outside the gap are ignored`() {
        val events = listOf(
            event(UsageEventTypes.ACTIVITY_RESUMED, -30, "com.tencent.mm"), // 空白之前
            event(UsageEventTypes.ACTIVITY_RESUMED, 90, "com.tencent.mm"),  // 空白之后
        )

        val result = classifier.classify(events, listOf(gap)).single()

        assertFalse(result.hasEvents)
        assertEquals(0, result.appOpenCount)
    }

    @Test
    fun `each gap is classified separately in one pass`() {
        val first = MillisRange(at(0), at(60))
        val second = MillisRange(at(120), at(180))
        val events = listOf(
            event(UsageEventTypes.ACTIVITY_RESUMED, 10, "com.tencent.mm"),
            event(UsageEventTypes.SCREEN_NON_INTERACTIVE, 130),
            event(UsageEventTypes.SCREEN_INTERACTIVE, 175),
        )

        val results = classifier.classify(events, listOf(first, second))

        assertEquals(2, results.size)
        assertEquals(1, results[0].appOpenCount)
        assertEquals(0L, results[0].idleMillis)
        assertEquals(0, results[1].appOpenCount)
        assertEquals(45 * minute, results[1].idleMillis)
    }

    @Test
    fun `no gaps means no work`() {
        assertTrue(classifier.classify(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `a screen off period is clipped to the gap`() {
        // 熄屏从空白开始之前就开始了：只算落在空白里的那一部分
        // （前面那一段已经被别的记录占了，不该在这里重复计时）
        val events = listOf(
            event(UsageEventTypes.SCREEN_NON_INTERACTIVE, -30),
            event(UsageEventTypes.SCREEN_INTERACTIVE, 20),
        )

        assertEquals(20 * minute, classifier.classify(events, listOf(gap)).single().idleMillis)
    }

    @Test
    fun `long idle stretches stay a single period`() {
        val events = listOf(
            event(UsageEventTypes.SCREEN_NON_INTERACTIVE, 0),
            event(UsageEventTypes.KEYGUARD_SHOWN, 0.5),
            event(UsageEventTypes.SCREEN_INTERACTIVE, 60),
        )

        assertEquals(hour, classifier.classify(events, listOf(gap)).single().idleMillis)
    }
}
