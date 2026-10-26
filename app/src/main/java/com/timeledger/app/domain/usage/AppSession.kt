package com.timeledger.app.domain.usage

import com.timeledger.app.core.time.MillisRange

/**
 * 一次**前台会话**：某个 App 从到前台到离开前台的这一段时间（需求六节）。
 *
 * 这是「系统事实」到「候选行为」之间的中间层。为什么不直接从事件生成时间记录：
 * 事件是零散的（切一下 App 就是两条），而用户关心的是「我在这上面待了多久」。
 * 中间隔一层的好处是它可以被单独测试，也能被单独改进（例如将来加上「同一 App
 * 短暂离开又回来算一段」的规则），而不触碰记录层。
 */
data class AppSession(
    val packageName: String,
    val startMillis: Long,
    /** 结束时刻（不含），与 [MillisRange] 一致。 */
    val endMillisExclusive: Long,
    /** 会话为什么结束。出问题时这是唯一能解释「为什么这段只有 30 秒」的线索。 */
    val endReason: SessionEndReason,
) {
    val range: MillisRange get() = MillisRange(startMillis, endMillisExclusive)

    val durationMillis: Long get() = endMillisExclusive - startMillis

    /** 会话一直开着，直到分析窗口结束（用户此刻还在用）。 */
    val endsAtRangeEnd: Boolean get() = endReason == SessionEndReason.RANGE_END
}

/** 会话结束的原因。 */
enum class SessionEndReason {
    /** App 自己退到后台（按了 Home、切到别的 App）。 */
    APP_BACKGROUNDED,

    /** 直接切到了另一个 App（只有新 App 的 RESUMED，没等到旧 App 的 PAUSED）。 */
    APP_SWITCHED,

    SCREEN_OFF,
    KEYGUARD,
    DEVICE_SHUTDOWN,

    /** 分析窗口结束了，会话还没结束。 */
    RANGE_END,
}

/**
 * 系统记录的幂等键格式。
 *
 * 为什么键里要有**哪一天**：一条跨 4:00 的会话会被相邻两天各分析一次，
 * 各自只写自己窗口内的那一段。若键里只有会话开始时刻，两天算出的键相同，
 * 第二天的写入会被当成重复采集而跳过 —— 那半天就凭空消失了。
 *
 * 包名里可能有 `.`，但不会有 `|`，所以用它做分隔符是安全的。
 */
object UsageRecordKeys {

    const val PREFIX = "usage"

    /**
     * 熄屏记录用的前缀。**故意和 `usage` 不同**。
     *
     * 应用排行是按「有 `usage|` 前缀的记录」聚合包名的 —— 熄屏不是 App，
     * 用同一个前缀会被算成某个名叫 `screen-off` 的应用，排行里冒出一行莫名其妙的东西。
     */
    const val SCREEN_OFF_PREFIX = "screen-off"

    fun build(dayKey: Long, startMillis: Long, packageName: String): String =
        "$PREFIX|$dayKey|$startMillis|$packageName"

    /** 熄屏记录的幂等键。 */
    fun buildScreenOff(dayKey: Long, startMillis: Long): String =
        "$SCREEN_OFF_PREFIX|$dayKey|$startMillis"

    fun isScreenOffKey(externalKey: String?): Boolean =
        externalKey?.startsWith("$SCREEN_OFF_PREFIX|") == true

    fun isUsageKey(externalKey: String?): Boolean =
        externalKey?.startsWith("$PREFIX|") == true

    /** 反解出包名；不是系统使用记录、或格式不对时返回 null。 */
    fun packageNameOf(externalKey: String?): String? {
        if (!isUsageKey(externalKey)) return null
        val parts = externalKey!!.split('|')
        return parts.getOrNull(3)?.takeIf { it.isNotEmpty() }
    }
}
