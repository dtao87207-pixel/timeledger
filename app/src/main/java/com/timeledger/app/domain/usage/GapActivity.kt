package com.timeledger.app.domain.usage

import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.model.UsageEvent

/**
 * 一段「没有记录的时间」里，原始事件还能说明什么（模块 24）。
 *
 * ## 为什么要这个东西
 * 用户看着竖轴问：「有些空的没有分类是怎么回事」。这个问题问到点子上了 ——
 * 加进熄屏分类之后，一天里仍然有大块空白，而空白**把所有情况混成了一种**：
 *
 * - 手机真的没在用（但每次熄屏都不到 5 分钟，所以没被记成熄屏）；
 * - 用户在**零碎地**用手机（每次打开 App 都不到 1 分钟，会话被丢掉）；
 * - 系统压根没有那一段的数据（关机、或 ROM 没保留那么久的历史）。
 *
 * 真机数据（09-12 那一天）：竖轴上画出来的 8 块空白一共 **6h58m**，
 * 每一块里面都有几百条真实事件 —— 也就是说「空」不是「什么都没发生」，
 * 而是「发生了，但都不够长，所以两边都没记」。
 *
 * 这个类把每一块空白翻译成三个事实，措辞交给界面层的映射器
 * （与「界面文案集中在能被测试的纯函数里」这条既有做法一致）。
 *
 * ## 纯函数
 * 不碰 Android API、不碰数据库，因此能在 JVM 上完整测。
 */
data class GapActivity(
    /** 说的是哪一段空白。 */
    val range: MillisRange,
    /**
     * 这一段里**有没有任何原始事件**。
     *
     * false 表示系统没有这段数据，App 无法推断任何东西 —— 必须如实说出来，
     * 而不是含糊地显示「手机没在用」（那是把「不知道」说成了「知道」）。
     */
    val hasEvents: Boolean,
    /**
     * 用户打开 App 的次数。
     *
     * 连续同一个 App 的重复事件只算一次（真机上同一事件会被 ROM 重复上报，
     * 间隔正好 2497ms），所以这个数字更接近「打开了几次」而不是「事件几条」。
     */
    val appOpenCount: Int,
    /**
     * 这段里手机黑屏的总时长（只算 ≥30 秒的黑屏）。
     *
     * 30 秒的下限是为了滤掉电源键抖动、以及 ROM 重复上报造成的 2.5 秒假熄屏。
     */
    val idleMillis: Long,
    /**
     * 这段里**系统完全没有事件**的时长。
     *
     * 为什么不能只看 [hasEvents]：真机上最典型的一块空白是「凌晨 04:00–07:43」——
     * 前面 3 小时 40 分一条事件都没有（手机没在动），最后 3 分钟才有几条零碎事件。
     * 只看 hasEvents 的话，这一块会被解释成「零碎操作 6 次」，
     * 而它真正的样子是「三个多小时没有系统记录」。
     *
     * 算法：把这段里的事件按「相邻不超过 5 分钟」聚成簇，簇的跨度算「有活动的部分」，
     * 其余的就是没有数据的部分。没有事件 → 整段都是没有数据。
     */
    val noDataMillis: Long,
)

/**
 * 把原始事件按空白段分桶，逐段算出 [GapActivity]。
 *
 * 一次线性扫描完成（事件与空白段各自有序、空白段互不重叠），
 * 一天的规模（几千条事件、几十个空白）瞬时算完。
 */
class UnrecordedGapClassifier(
    /** 多长的黑屏才算「手机没在用」而不是抖动。 */
    private val idleFloorMillis: Long = DEFAULT_IDLE_FLOOR_MILLIS,
) {

    fun classify(
        events: List<UsageEvent>,
        gaps: List<MillisRange>,
        /**
         * 不算「打开 App」的包名（桌面、系统界面、输入法……）。
         *
         * 由平台层判定后传进来，而不是在这里硬编码包名 —— 不同 ROM 的桌面包名不一样。
         */
        ignoredPackages: Set<String> = emptySet(),
    ): List<GapActivity> {
        if (gaps.isEmpty()) return emptyList()

        val ordered = gaps.sortedBy { it.startMillis }
        val buckets = List(ordered.size) { mutableListOf<UsageEvent>() }

        var index = 0
        events.sortedBy { it.timestampMillis }.forEach { event ->
            // 空白段有序且互不重叠：游标只往前走，不会退回去
            while (index < ordered.size && event.timestampMillis >= ordered[index].endMillisExclusive) {
                index++
            }
            if (index >= ordered.size) return@forEach
            if (event.timestampMillis >= ordered[index].startMillis) {
                buckets[index] += event
            }
        }

        // 熄屏时段**要在整段范围上算一次**，而不是逐段算：
        // 一段熄屏可能横跨「一个空白 + 中间被手动记录盖住的一段 + 另一个空白」
        // （最典型的是「熄屏过夜，用户手记了睡眠 23:00–06:00」）。
        // 逐段算的话，第二段空白只看得到一个孤零零的「亮屏」事件，
        // 于是被解释成「系统没有这段数据」——而其实手机一直黑着。
        val span = MillisRange(ordered.first().startMillis, ordered.last().endMillisExclusive)
        val idlePeriods = ScreenOffPeriods.extract(events, span, idleFloorMillis)

        return ordered.mapIndexed { position, gap ->
            summarize(gap, buckets[position], ignoredPackages, idlePeriods)
        }
    }

    private fun summarize(
        gap: MillisRange,
        events: List<UsageEvent>,
        ignoredPackages: Set<String>,
        idlePeriods: List<MillisRange>,
    ): GapActivity {
        var appOpenCount = 0
        var previousPackage: String? = null

        events.forEach { event ->
            if (UsageEventTypes.kindOf(event.eventType) != UsageEventKind.FOREGROUND) return@forEach
            val packageName = event.packageName ?: return@forEach
            // 桌面/系统界面也参与「上一次是谁」的判断：
            // 「抖音 → 桌面 → 抖音」是两次打开，而不是一次 —— 用户确实回了一次桌面。
            if (packageName != previousPackage && packageName !in ignoredPackages) appOpenCount++
            previousPackage = packageName
        }

        return GapActivity(
            range = gap,
            hasEvents = events.isNotEmpty(),
            appOpenCount = appOpenCount,
            idleMillis = idlePeriods.sumOf { period ->
                period.intersect(gap)?.durationMillis ?: 0L
            },
            noDataMillis = gap.durationMillis - activeSpanMillis(events),
        )
    }

    /**
     * 事件覆盖到的时长：把事件按「相邻不超过 [ACTIVITY_CLUSTER_GAP_MILLIS]」聚成簇，
     * 每簇从第一条到最后一条的跨度就是「这段时间里有动静」。
     *
     * 用「跨度」而不是「事件条数 × 某个系数」：跨度是可解释的 ——
     * 一簇事件的跨度就是「这几分钟系统有记录」，剩下的一律算没有数据。
     */
    private fun activeSpanMillis(events: List<UsageEvent>): Long {
        if (events.isEmpty()) return 0L

        val sorted = events.map { it.timestampMillis }.sorted()
        var total = 0L
        var clusterStart = sorted.first()
        var previous = sorted.first()

        sorted.drop(1).forEach { timestamp ->
            if (timestamp - previous > ACTIVITY_CLUSTER_GAP_MILLIS) {
                total += previous - clusterStart
                clusterStart = timestamp
            }
            previous = timestamp
        }
        return total + (previous - clusterStart)
    }

    companion object {
        /** 三十秒：短于它的黑屏当抖动处理。 */
        const val DEFAULT_IDLE_FLOOR_MILLIS = 30_000L

        /** 相邻事件间隔超过它就算「中间没有数据」，不再算作同一簇活动。 */
        const val ACTIVITY_CLUSTER_GAP_MILLIS = 5 * 60 * 1000L
    }
}
