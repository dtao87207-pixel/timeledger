package com.timeledger.app.domain.usage

import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.model.UsageEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 熄屏时段提取测试（模块 23）。
 *
 * 判定规则看着简单，但这几条都真的会遇到：
 * - 熄屏与锁屏谁先谁后**各家 ROM 不一样**（按电源键通常 keyguard 先、自动息屏相反）；
 * - 关机与开机也是一对；
 * - 到窗口结束还熄着的情况必须有答案，否则那一段会凭空消失。
 */
class ScreenOffPeriodsTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    /** 逻辑日窗口：0 点起算 24 小时，方便心算（提取逻辑不关心 4:00 边界）。 */
    private val window = MillisRange(0L, 24 * hour)

    private var nextKey = 0L

    private fun event(type: Int, atMillis: Long) = UsageEvent(
        packageName = null,
        eventType = type,
        timestampMillis = atMillis,
        eventKey = "k${nextKey++}",
    )

    private fun off(at: Long) = event(UsageEventTypes.SCREEN_NON_INTERACTIVE, at)
    private fun on(at: Long) = event(UsageEventTypes.SCREEN_INTERACTIVE, at)
    private fun keyguardShown(at: Long) = event(UsageEventTypes.KEYGUARD_SHOWN, at)
    private fun keyguardHidden(at: Long) = event(UsageEventTypes.KEYGUARD_HIDDEN, at)
    private fun shutdown(at: Long) = event(UsageEventTypes.DEVICE_SHUTDOWN, at)
    private fun startup(at: Long) = event(UsageEventTypes.DEVICE_STARTUP, at)

    private fun extract(vararg events: UsageEvent, min: Long = 5 * minute) =
        ScreenOffPeriods.extract(events.toList(), window, min)

    @Test
    fun `a simple screen off and on becomes one period`() {
        val periods = extract(off(1 * hour), on(3 * hour))

        assertEquals(1, periods.size)
        assertEquals(1 * hour, periods.single().startMillis)
        assertEquals(3 * hour, periods.single().endMillisExclusive)
    }

    @Test
    fun `keyguard first then screen off still counts as one period`() {
        // 按电源键：keyguard 先到、screen off 后到
        val periods = extract(keyguardShown(1 * hour), off(1 * hour + 200), on(3 * hour))

        assertEquals(1, periods.size)
        assertEquals(1 * hour, periods.single().startMillis)
        assertEquals(3 * hour, periods.single().endMillisExclusive)
    }

    @Test
    fun `screen off first then keyguard still counts as one period`() {
        // 自动息屏：screen off 先到、keyguard 后到
        val periods = extract(off(1 * hour), keyguardShown(1 * hour + 200), keyguardHidden(3 * hour), on(3 * hour + 200))

        assertEquals(1, periods.size)
        assertEquals(1 * hour, periods.single().startMillis)
        assertEquals(3 * hour, periods.single().endMillisExclusive)
    }

    @Test
    fun `shutdown and startup form a period too`() {
        val periods = extract(shutdown(1 * hour), startup(5 * hour))

        assertEquals(1, periods.size)
        assertEquals(4 * hour, periods.single().durationMillis)
    }

    @Test
    fun `brief screen-offs are filtered out`() {
        // 掏口袋、看时间：几秒钟的熄屏不值得记一条
        val periods = extract(off(1 * hour), on(1 * hour + 3 * minute))

        assertTrue(periods.isEmpty())
    }

    @Test
    fun `the threshold is inclusive`() {
        assertEquals(1, extract(off(0L), on(5 * minute)).size)
        assertTrue(extract(off(0L), on(5 * minute - 1)).isEmpty())
    }

    @Test
    fun `several periods come out in order`() {
        val periods = extract(
            off(1 * hour), on(3 * hour),
            off(5 * hour), on(9 * hour),
        )

        assertEquals(2, periods.size)
        assertEquals(listOf(1 * hour, 5 * hour), periods.map { it.startMillis })
        assertTrue(periods[0].endMillisExclusive <= periods[1].startMillis)
    }

    @Test
    fun `a period still open at the end is closed at the window end`() {
        // 到窗口结束还熄着：按「一直熄着」算，这是目前能知道的最诚实答案。
        // 不这么处理的话，那一段会凭空消失。
        val periods = extract(off(22 * hour))

        assertEquals(1, periods.size)
        assertEquals(22 * hour, periods.single().startMillis)
        assertEquals(window.endMillisExclusive, periods.single().endMillisExclusive)
    }

    @Test
    fun `a period crossing the window start is clipped`() {
        // 昨天 23:00 熄到今天 02:00：本窗口只留 00:00–02:00
        val periods = extract(off(-1 * hour), on(2 * hour))

        assertEquals(1, periods.size)
        assertEquals(0L, periods.single().startMillis)
        assertEquals(2 * hour, periods.single().endMillisExclusive)
    }

    @Test
    fun `an on event without an off event is ignored`() {
        // 熄屏事件在窗口之前（原始事件被保留策略清理过）：不能凭空造一段熄屏
        val periods = extract(on(2 * hour))

        assertTrue(periods.isEmpty())
    }

    @Test
    fun `unrelated events are ignored`() {
        val periods = extract(
            event(UsageEventTypes.ACTIVITY_RESUMED, 30 * minute),
            off(1 * hour),
            event(UsageEventTypes.ACTIVITY_PAUSED, 2 * hour),
            on(3 * hour),
        )

        assertEquals(1, periods.size)
        assertEquals(1 * hour, periods.single().startMillis)
    }

    @Test
    fun `events are sorted before extraction`() {
        val periods = extract(on(3 * hour), off(1 * hour))

        assertEquals(1, periods.size)
        assertEquals(1 * hour, periods.single().startMillis)
    }

    @Test
    fun `no events means no periods`() {
        assertTrue(ScreenOffPeriods.extract(emptyList(), window).isEmpty())
    }

    @Test
    fun `the default threshold is one minute`() {
        // 5 分钟太高：真机上 09-12「黑屏 ≥30 秒」是 10h14m，而「≥5 分钟」只有 6h55m ——
        // 中间那三个多小时因为被亮屏打断而整段消失，时间轴上就成了用户问的「空」。
        assertEquals(60 * 1000L, ScreenOffPeriods.DEFAULT_MIN_MILLIS)
    }
}
