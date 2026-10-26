package com.timeledger.app.domain.usage

import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.model.UsageEvent

/**
 * 原始事件 → 前台会话（模块 26：改用 Android 自己的归属口径）。
 *
 * ## 为什么推翻了第一版
 * 第一版的模型是「同一时刻只有一个 App 在前台」：谁最新 RESUMED 谁接上，
 * 别人一 PAUSED 这一段就结束。这个假设在纸面上成立，在真机上不成立 ——
 * 用户拿手机内置的「健康使用设备 → 使用统计」一比，两边的数字差了一大截
 * （09-13：抖音内置 1h54m、我们 41m；王者内置 38m、我们 1m），
 * 而**内置那套就是 Android 自己算的**（`UsageStatsManager.queryUsageStats`）。
 *
 * 读 AOSP 的 `UsageStatsService` 得到它的真实规则：
 * **它维护的是「当前 resumed 的 Activity 集合」，时间算给所有还有 Activity 在前台的包。**
 * 这条规则解释了真机上的两种现象（都是从一个 App 的事件流里直接看出来的）：
 *
 * 1. **同一个 App 内部换 Activity 不是「切走」**。抖音的事件流是
 *    `PAUSED / RESUMED / STOPPED` 一秒内成对出现几十次 —— 那是它的视频流、个人页、
 *    评论页在互相切换。Android 口径下这个 App 一直算前台；第一版却在这里把它切碎，
 *    短于门槛的碎片还被丢掉：1h54m 只剩 41m。
 * 2. **别的 App 冒出后台 RESUMED 也不是「切走」**。用户打王者那 38 分钟里，
 *    QQ / 微信 / 桌面不断冒出 RESUMED 事件；第一版的单会话模型一看到别人 RESUMED
 *    就把王者结束掉 —— 23 分钟整段没有记录。
 *
 * ## 现在的规则（四步）
 * 1. **按 (包名, Activity) 记账**：每个仍开着的 Activity 都算它所属的包在前台；
 *    一个包的 Activity 全部关掉，这一段才结束。熄屏 / 锁屏 / 关机结束**所有**会话。
 * 2. **丢掉短于 [minSessionMillis] 的段**（通知把 App 拉起来闪一下、ROM 抖动）。
 * 3. **同一 App 离开一下又回来**：间隔不超过 [mergeGapMillis]、且中间没有别的 App
 *    真的在前台、也没有跨过熄屏，就接回上一段。
 * 4. **切出互不重叠的段落**：Android 的口径允许两个 App 同时算（切换瞬间、多窗口），
 *    而时间轴要求一段只属于一个 App —— 所以按「**最后到前台的那个占这一段**」切。
 *    这是唯一引入的取舍，写在 [resolveOverlaps] 里。
 *
 * ## 边界约定
 * - 调用方传入的事件必须已经落库（软删除过滤由仓库负责）；
 * - 事件先按时间、再按下标稳定排序，因此重复分析同一批事件结果完全一致；
 * - **[range] 的结束时刻必须由调用方限制到「现在」**：否则此刻还在用的 App
 *   会被记成一直用到今天 24:00。
 */
class ForegroundSessionBuilder(
    /**
     * 短于这个时长的段直接丢掉。
     *
     * 十秒。取值依据见 [DEFAULT_MIN_SESSION_MILLIS] 的注释（真机实测的四档对比）。
     * 注意它在**合并之后**再判一次：一段 5 秒的访问，如果 10 秒后用户又回来了，
     * 它已经接进那一段长的，不会在这里被丢掉。
     */
    private val minSessionMillis: Long = DEFAULT_MIN_SESSION_MILLIS,
    /** 离开多久之内回来算同一段（见第 3 步）。 */
    private val mergeGapMillis: Long = DEFAULT_MERGE_GAP_MILLIS,
) {

    /**
     * 供上层校验不变式用：熄屏阈值必须大于这个值，否则同一段时间会被算两次
     * （既被并进上一个会话，又写成一条熄屏记录）。见 `UsageAnalysisService` 的 init。
     */
    val mergeGapMillisForCheck: Long get() = mergeGapMillis

    fun build(
        events: List<UsageEvent>,
        range: MillisRange,
        /** 不属于「用户在使用」的包名：桌面、系统界面、输入法。由平台层解析后传入。 */
        ignoredPackages: Set<String> = emptySet(),
    ): List<AppSession> {
        if (events.isEmpty()) return emptyList()

        val sorted = events.withIndex()
            .sortedWith(compareBy({ it.value.timestampMillis }, { it.index }))
            .map { it.value }

        /** 开着的 Activity：键是 (包名, Activity)，值是它开始前台的时刻。 */
        val openActivities = mutableMapOf<ActivityKey, Long>()

        /** 每个包名当前开着的 Activity 数量。 */
        val openCount = mutableMapOf<String, Int>()

        /** 每个包名当前这一段的开始时刻。 */
        val spanStart = mutableMapOf<String, Long>()

        val spans = mutableListOf<Span>()

        fun openActivity(key: ActivityKey, at: Long) {
            if (key in openActivities) return
            openActivities[key] = at
            val count = (openCount[key.packageName] ?: 0) + 1
            openCount[key.packageName] = count
            if (count == 1) spanStart[key.packageName] = at
        }

        fun closeSpan(packageName: String, at: Long, reason: SessionEndReason) {
            val start = spanStart.remove(packageName) ?: return
            openCount.remove(packageName)
            if (at > start) {
                spans += Span(packageName, start, at, reason)
            }
        }

        fun closeActivity(key: ActivityKey, at: Long) {
            if (key !in openActivities) return
            openActivities.remove(key)
            val remaining = (openCount[key.packageName] ?: 1) - 1
            if (remaining <= 0) {
                closeSpan(key.packageName, at, SessionEndReason.APP_BACKGROUNDED)
            } else {
                openCount[key.packageName] = remaining
            }
        }

        fun closeEverything(at: Long, reason: SessionEndReason) {
            openActivities.clear()
            // 用 spanStart 的快照，因为 closeSpan 会边改边删
            spanStart.keys.toList().forEach { packageName -> closeSpan(packageName, at, reason) }
            openCount.clear()
        }

        for (event in sorted) {
            val at = event.timestampMillis
            when (UsageEventTypes.kindOf(event.eventType)) {
                UsageEventKind.FOREGROUND -> {
                    val packageName = event.packageName
                    if (packageName.isNullOrBlank()) continue
                    // 桌面 / 系统界面 / 输入法不是「用户在做什么」，不参与记账
                    if (packageName in ignoredPackages) continue
                    openActivity(ActivityKey(packageName, event.className.orEmpty()), at)
                }

                UsageEventKind.BACKGROUND,
                UsageEventKind.ACTIVITY_STOPPED,
                -> {
                    val packageName = event.packageName
                    if (packageName.isNullOrBlank()) continue
                    if (packageName in ignoredPackages) continue
                    // **只关掉「我们看着它开起来」的那个 Activity**，与 Android 完全一致：
                    // 收到一个从没见过的 Activity 的 PAUSED（它的 RESUMED 在窗口之前、
                    // 或者这个 ROM 报的类名前后不一致）时，什么都不做。
                    //
                    // 这里曾经有一条「兜底」：类名对不上就关掉这个包里最早开着的那个
                    // Activity。它的代价是量出来的 —— 抖音的原始段从 **1h35m 掉到 31m**
                    // （每来一个对不上的 PAUSED 就把正在用的 Activity 关掉一次），
                    // 而手机内置显示 1h54m。删掉兜底之后，重建结果与 Android 口径一致。
                    closeActivity(ActivityKey(packageName, event.className.orEmpty()), at)
                }

                UsageEventKind.DEVICE_SHUTDOWN -> {
                    // 关机是硬边界：所有 Activity 都会被系统收掉，事件也未必来得及发
                    closeEverything(at, SessionEndReason.DEVICE_SHUTDOWN)
                }

                // **熄屏与锁屏不再结束会话**（模块 26 的第二处修正）。
                //
                // 旧模型在这里收尾，理由是「熄屏了就是没在用了」。真机数据否掉了它：
                // 这台 ROM 在**视频播放、屏幕闪烁**时也会发 SCREEN_NON_INTERACTIVE /
                // KEYGUARD_SHOWN（09-12 有一段 32 分钟里发了 136 次熄屏事件），
                // 而应用自己的 PAUSED 并没有到 —— Android 的口径下这个 App 一直算在前台
                // （手机内置给抖音 1h54m，我们按「熄屏就收尾」只能算出 36m）。
                //
                // 所以收尾交给**应用自己的 PAUSED/STOPPED**：它到了就结束，
                // 没到就说明系统也认为它还在前台 —— 与 Android 完全一致。
                UsageEventKind.SCREEN_OFF,
                UsageEventKind.KEYGUARD_SHOWN,

                // 亮屏 / 解锁 / 开机都不开启会话：此时还不知道用户在用哪个 App
                UsageEventKind.SCREEN_ON,
                UsageEventKind.KEYGUARD_HIDDEN,
                UsageEventKind.DEVICE_STARTUP,
                UsageEventKind.OTHER,
                -> Unit
            }
        }

        closeEverything(range.endMillisExclusive, SessionEndReason.RANGE_END)

        val clipped = spans.mapNotNull { span ->
            val start = maxOf(span.startMillis, range.startMillis)
            val end = minOf(span.endMillisExclusive, range.endMillisExclusive)
            if (end <= start) null else span.copy(startMillis = start, endMillisExclusive = end)
        }

        // 顺序很要紧，弄反过一次：
        // 抖音那种「换 Activity」的抖动会把一个包切成一堆 0.x 秒的小段，
        // **先按门槛筛掉它们就再也合不回来了**（09-13 实测：1h35m 只剩 30m）。
        // 所以先合并、再筛；而合并时判断「中间有没有别人真的在用」，
        // 用的又是**过了门槛的那些段**（路过一下的碎片没有资格挡住合并）。
        val significant = clipped.filter { it.durationMillis >= minSessionMillis }
        val merged = mergeReopens(clipped, significant)

        val resolved = resolveOverlaps(
            spans = merged.filter { it.durationMillis >= minSessionMillis },
            range = range,
        )

        return resolved
            // 切分之后可能留下很短的边角料，同样不值得记
            .filter { it.durationMillis >= minSessionMillis }
            .map { span ->
                AppSession(
                    packageName = span.packageName,
                    startMillis = span.startMillis,
                    endMillisExclusive = span.endMillisExclusive,
                    endReason = span.endReason,
                )
            }
            .sortedWith(compareBy({ it.startMillis }, { it.packageName }))
    }

    /**
     * 第 2 步：同一个 App 离开一下又回来，接成一段。
     *
     * 唯一的限制：中间**没有别的 App 真的在前台**。
     * 「真的在前台」= 那一段自己过了门槛（[significant]）—— 通知把别的 App
     * 拉起来闪一下不算，否则那种一闪而过会把明明连续的使用切断。
     */
    private fun mergeReopens(spans: List<Span>, significant: List<Span>): List<Span> {
        if (spans.size < 2) return spans
        val ordered = spans.sortedWith(compareBy({ it.startMillis }, { it.packageName }))
        val result = mutableListOf<Span>()

        ordered.forEach { span ->
            // 找**同一个包名的上一段**，而不是「列表里的最后一段」——
            // 中间可能夹着别的 App 一闪而过的碎片（它们不值得记，也不该挡住合并）。
            val index = result.indexOfLast { it.packageName == span.packageName }
            val previous = result.getOrNull(index)
            val joinable = previous != null &&
                span.startMillis - previous.endMillisExclusive in 0..mergeGapMillis &&
                significant.none { other ->
                    other.packageName != span.packageName &&
                        other.startMillis < span.startMillis &&
                        other.endMillisExclusive > previous.endMillisExclusive
                }

            if (joinable) {
                // 中间那些「一闪而过」的段丢掉，然后把上一段延伸到这里
                while (result.size > index + 1) result.removeAt(result.lastIndex)
                result[index] = previous!!.copy(
                    endMillisExclusive = span.endMillisExclusive,
                    endReason = span.endReason,
                )
            } else {
                result += span
            }
        }
        return result
    }

    /**
     * 第 4 步：切出互不重叠的段落。
     *
     * Android 的口径允许两个 App 同时算前台（切换的那一瞬、以及多窗口），
     * 而时间轴要求「一段只属于一个 App」—— 所以必须有人让出这段重叠。
     *
     * 规则：**最后到前台的那个占这一段**（与直觉一致：屏幕上最后出现的就是它）。
     * 于是「A 玩着 → B 冒出来 → 又回到 A」会切成 A / B / A 三段，
     * 而 A 的总时长只少了 B 占的那部分 —— 这正是我们要的：
     * 不把 B 的时间算给 A，也不把 A 的时间算给 B。
     */
    private fun resolveOverlaps(spans: List<Span>, range: MillisRange): List<Span> {
        if (spans.size < 2) return spans

        val points = sortedSetOf<Long>()
        spans.forEach { points += it.startMillis; points += it.endMillisExclusive }

        val active = mutableListOf<Span>()
        val segments = mutableListOf<Span>()
        var owner: Span? = null
        var segmentStart = 0L

        points.forEach { at ->
            active.removeAll { it.endMillisExclusive <= at }
            active += spans.filter { it.startMillis == at }

            val next = active.maxWithOrNull(
                compareBy({ it.startMillis }, { it.endMillisExclusive }, { it.packageName })
            )
            if (next != owner) {
                val current = owner
                if (current != null && at > segmentStart) {
                    segments += current.copy(
                        startMillis = segmentStart,
                        endMillisExclusive = at,
                        // 被后来者顶掉的那一段，结束原因写成「切走了」
                        endReason = if (next != null) SessionEndReason.APP_SWITCHED else current.endReason,
                    )
                }
                owner = next
                segmentStart = at
            }
        }

        return segments
    }

    /** 一个包名前台的一段（切分之前，可能互相重叠）。 */
    private data class Span(
        val packageName: String,
        val startMillis: Long,
        val endMillisExclusive: Long,
        val endReason: SessionEndReason,
    ) {
        val durationMillis: Long get() = endMillisExclusive - startMillis
    }

    /** Activity 的标识：Android 自己的聚合就是按 (包名, Activity, 实例) 记的，实例号我们没有存。 */
    private data class ActivityKey(val packageName: String, val className: String)

    companion object {
        /**
         * 十秒。**这个值决定「多少真实使用被记下来」。**
         *
         * 取值经过实测（`tools/threshold_probe.py`，09-12 那天的真机数据）：
         *
         * | 门槛 | 一天几段会话 | 前台时长 |
         * |---|---|---|
         * | 60 秒（最初的版本） | 47 | 4h00m |
         * | 30 秒 | 83 | 4h26m |
         * | 20 秒 | 123 | 4h42m |
         * | **10 秒（现在）** | **203** | **5h00m** |
         *
         * 用户的原话是「那些零碎的时间也没有进行分类，而是直接断开」——
         * 60 秒门槛把一小时的真实使用整段丢掉了。10 秒把其中一小时捞了回来。
         *
         * **为什么不更低**：10 秒以下基本是通知把 App 拉起来闪一下、
         * 以及 ROM 重复上报事件造成的假会话，记下来只会让轴变成噪点。
         */
        const val DEFAULT_MIN_SESSION_MILLIS = 10_000L

        /**
         * 三十秒。**这个是"时长"的口径，不是"好不好看"的口径，所以必须紧。**
         *
         * 试过放宽到 5 分钟（用户的原话是「有些间隔一分钟然后是同一个应用的，
         * 可以直接并一起，就设定五分钟左右」），结果是灾难性的：
         * 合并会把空档**算进 App 时长**，而一天有几百个「离开一下」的空档。
         * 真机上量出来的数字：09-12 的真实前台时间 5h18m，记录里变成了 **9h58m**。
         *
         * 时间记账的时长必须是真的，所以这里保持 30 秒：
         * 只吃掉「秒级误触」这种明显不是使用的噪音。
         *
         * 「时间轴太碎」是**显示问题**，应该由界面聚合解决（模块 24 的块 + 展开），
         * 而不是靠在数据层虚报时长。两者不要混。
         */
        const val DEFAULT_MERGE_GAP_MILLIS = 30_000L
    }
}
