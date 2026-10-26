package com.timeledger.app.domain.usage

import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.model.UsageEvent

/**
 * 从原始事件里挑出**熄屏时段**（模块 23）。
 *
 * ## 它解决什么问题
 * 在这之前，手机没在用的那段时间在时间轴上是**空白** —— 空白是「没有记录」（未知），
 * 而用户的原话点出了它的问题：「或许可以设定一个熄屏的分类，这样就可以避免某个
 * 统计时间够长的风险」。把那段时间写成一条记录，一次解决两件事：
 *
 * 1. **堵住合并的洞**：空档一旦有归属，就不会被当成「还在用上一个 App」；
 * 2. **一天的账变成平的**：空白是未知，熄屏是已知，对记账产品来说这两者完全不同。
 *
 * ## 判定规则
 * - **开始**：`SCREEN_NON_INTERACTIVE` / `KEYGUARD_SHOWN` / `DEVICE_SHUTDOWN` 里第一个到的事件；
 * - **结束**：`SCREEN_INTERACTIVE` / `KEYGUARD_HIDDEN` / `DEVICE_STARTUP` 里第一个到的事件。
 *
 * 为什么两个方向都收三种事件：熄屏与锁屏谁先谁后各家 ROM 不一样
 * （按电源键通常是 keyguard 先、screen off 后；自动息屏则相反），
 * 只看一种会漏掉另一条路径。同一方向上的重复事件自然被忽略（时段已经开着/关着）。
 *
 * ## 短熄屏也记（阈值 1 分钟，不是 5 分钟）
 * 掏口袋、误触锁屏这种几秒钟的熄屏不记 —— 一天几十次，全记下来只是一堆碎片。
 * 但阈值**不能定得太高**：真机上 09-12 这一天，「黑屏 ≥30 秒」共 10h14m，
 * 而「黑屏 ≥5 分钟」只有 6h55m：**三个多小时的黑屏因为中间被亮屏打断而整段消失了**，
 * 在时间轴上就表现为用户问的那句「间隔中的时间也没有进行分类，而是直接断开」。
 *
 * 所以阈值取 1 分钟：手机上一次拿起来看一眼通常不到 1 分钟，而手机放下的间隔一般超过它。
 * 代价是记录条数变多（09-12：22 条 → 109 条），这一点交给**显示分组**解决
 * （见 `DayViewStateMapper` 把相邻的熄屏段并成一块显示），数据层仍然是诚实的。
 *
 * 这个类是纯函数：不碰 Android API、不碰数据库，因此能在 JVM 上完整测。
 */
object ScreenOffPeriods {

    /** 默认阈值：1 分钟。取值依据见类注释（真机数据：5 分钟会漏掉 3 小时）。 */
    const val DEFAULT_MIN_MILLIS = 60 * 1000L

    private val offEventTypes = setOf(
        UsageEventTypes.SCREEN_NON_INTERACTIVE,
        UsageEventTypes.KEYGUARD_SHOWN,
        UsageEventTypes.DEVICE_SHUTDOWN,
    )

    private val onEventTypes = setOf(
        UsageEventTypes.SCREEN_INTERACTIVE,
        UsageEventTypes.KEYGUARD_HIDDEN,
        UsageEventTypes.DEVICE_STARTUP,
    )

    fun extract(
        events: List<UsageEvent>,
        range: MillisRange,
        minMillis: Long = DEFAULT_MIN_MILLIS,
    ): List<MillisRange> {
        if (events.isEmpty()) return emptyList()

        val sorted = events.sortedBy { it.timestampMillis }
        val periods = mutableListOf<MillisRange>()

        var openedAt: Long? = null

        sorted.forEach { event ->
            when (event.eventType) {
                in offEventTypes -> if (openedAt == null) openedAt = event.timestampMillis
                in onEventTypes -> {
                    val start = openedAt
                        ?: return@forEach // 没有开始就来的「亮屏」，忽略
                    periods += MillisRange(start, event.timestampMillis)
                    openedAt = null
                }
            }
        }

        // 到窗口结束还开着的时段：按「一直熄着」算 —— 这是目前能知道的最诚实答案。
        // （真正熄屏时 App 不会在跑，所以下一次分析通常已经能看到那个亮屏事件了。）
        openedAt?.let { periods += MillisRange(it, range.endMillisExclusive) }

        return periods
            .asSequence()
            // 裁剪到分析窗口：跨 4:00 的熄屏在两天各留自己那一段，与其它记录口径一致
            .mapNotNull { it.intersect(range) }
            .filter { it.durationMillis >= minMillis }
            .sortedBy { it.startMillis }
            .toList()
    }
}
