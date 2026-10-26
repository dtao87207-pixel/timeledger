package com.timeledger.app.ui.day

import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.model.RecordSource
import com.timeledger.app.domain.model.TimeRecord
import com.timeledger.app.domain.review.DayReview
import com.timeledger.app.domain.usage.GapActivity
import com.timeledger.app.domain.usage.UsageRecordKeys
import com.timeledger.app.ui.format.DisplayFormat
import java.time.Instant
import java.time.ZoneId

/**
 * 「某一天」的复盘数据 → 界面状态的**纯函数**映射，首页与日历共用。
 *
 * 这是界面模块里唯一能被真正验证的部分：本机没有模拟器、没有设备、也没有 Studio Preview，
 * 界面长什么样无法目视确认。因此所有「显示什么文字、取哪几段、怎么排序」的判断
 * 都集中到这个类里，用单元测试钉死；Composable 只负责把结果画出来，
 * 里面不含条件判断之外的业务逻辑。
 *
 * 它不依赖任何 Android / Compose 类型（只用 ZoneId 做时间文案），所以测试跑在纯 JVM 上。
 */
class DayViewStateMapper(
    private val zone: ZoneId,
    /** 最多列几段「没记录的时间」。 */
    private val maxGaps: Int = 3,
    /** 长于这个时长的空档才画成空白段；它同时也是「块在哪里断开」的间隔。 */
    private val gapThresholdMillis: Long = DEFAULT_GAP_THRESHOLD_MILLIS,
    /**
     * 一个块里**至少**要攒够多少记录时间才收手（模块 24）。
     *
     * 十五分钟。会话门槛降到 10 秒之后，一天有 369 条记录，一条一块地画要十几屏；
     * 攒到 15 分钟一块，一天大约七八十块，仍然读得懂。块里写了 `N 段`，
     * 点一下能展开成每一条 —— 可读与不丢信息两头都要。
     */
    private val minChunkMillis: Long = DEFAULT_MIN_CHUNK_MILLIS,
) {

    fun map(
        review: DayReview,
        catalog: CatalogLookup,
        quickAddOptions: List<QuickAddOption> = emptyList(),
    ): DayViewUiState {
        return DayViewUiState(
            dateLabel = DisplayFormat.dateLabel(review.epochDay),
            recordedLabel = DisplayFormat.compactDuration(review.durations.confirmedMillis),
            pendingLabel = pendingLabel(review),
            unrecordedLabel = DisplayFormat.compactDuration(review.durations.unrecordedMillis),
            goals = review.goalProgress.toGoalRows(),
            currentMoment = currentMoment(review),
            planRows = planRows(review, catalog),
            dayShares = dayShares(review, catalog),
            appShares = appShares(review, catalog),
            elapsedLabel = DisplayFormat.compactDuration(review.observedWindow.durationMillis),
            elapsedNote = if (review.observedWindow.durationMillis < review.window.durationMillis) {
                ELAPSED_TODAY
            } else {
                ELAPSED_WHOLE_DAY
            },
            // 轴裁到此刻时，末端画一道「此刻 HH:mm」—— 告诉用户数据读到哪儿了
            nowLabel = if (review.observedWindow.durationMillis < review.window.durationMillis) {
                "此刻 ${DisplayFormat.clockLabel(review.observedWindow.endMillisExclusive, zone)}"
            } else {
                null
            },
            timeline = dayTimeline(
                rows = timelineRows(review, catalog),
                window = review.observedWindow,
                gapActivities = review.gapActivities,
            ),
            gaps = gapRows(review),
            planNote = planNote(review),
            isEmptyDay = review.durations.isEntirelyEmpty,
            quickAddOptions = quickAddOptions,
            hasAnyPlan = review.hasAnyActivePlan,
            todayInsights = buildTodayInsights(review, catalog),
        )
    }

    /**
     * 把时间轴搭成一条**竖轴**。
     *
     * 用户的原话：「我设想的时间轴是一条竖立的轴，从上到下，明细在用什么，
     * 哪些是空白，用户想看的不会是单纯的罗列使用时间段，而是将应用与时间轴一同打包」。
     *
     * 三步：
     * 1. **显示分组**：[groupForDisplay] 把同一个 App 在 5 分钟内重新出现的相邻段落合成一块 ——
     *    块的高按**跨度**画（轴才准），标注写**真实总时长**（不虚报）；
     * 2. **空白占位**：段落之间、当天开头、以及最后一段之后，按真实时长留出空的块
     *    （就是用户要的「留白」）；
     * 3. **算高度**：[heightDp] 按分钟换算（1 小时 = 30dp），矮的给一个下限，
     *    否则一分钟的记录只占 0.5dp，等于看不见。
     *
     * 高度、下限、刻度都在这里算，界面只负责画 —— 本机看不到界面，
     * 这类判断必须放在能被测试覆盖的地方。
     *
     * [window] 传的是 `review.observedWindow`（今天裁到此刻）：
     * 所以今天不会在轴底画出一大块「空 13 小时」—— 那是还没发生的时间。
     */
    private fun dayTimeline(
        rows: List<TimelineRow>,
        window: MillisRange,
        gapActivities: List<GapActivity>,
    ): DayTimeline {
        if (rows.isEmpty()) return DayTimeline.EMPTY

        // 1. 聚合成块（见 chunkForDisplay）
        val chunks = chunkForDisplay(rows)

        // 2. 记录块与空白块按时间顺序交替
        val blocks = interleaveGaps(chunks, window, gapActivities)

        return DayTimeline(
            blocks = blocks,
            ticks = ticksFor(blocks, window),
            totalHeightDp = blocks.sumOf { it.heightDp },
        )
    }

    /**
     * 把一个块里的若干行合成一行（标题取**占得最多的那一个**）。
     *
     * 块的跨度是 [TimelineRow.startMillis]..[TimelineRow.endMillisExclusive]（真实时间），
     * 时长标注写的是**这一块里记下来的真实总和**（不是跨度，更不是估算）——
     * 这条纪律从决策 102 起就没变过。
     */
    private fun mergeChunk(chunk: List<TimelineRow>): TimelineRow {
        val first = chunk.first()
        val last = chunk.last()
        if (chunk.size == 1) return first

        val totals = chunk.groupBy { it.keyForGrouping() }
            .mapValues { (_, group) -> group.sumOf { it.durationMillis } }
        val dominantKey = totals.maxByOrNull { it.value }!!.key
        val dominant = chunk.filter { it.keyForGrouping() == dominantKey }
            .maxByOrNull { it.durationMillis } ?: first

        return dominant.copy(
            rangeLabel = rangeLabel(first.startMillis, last.endMillisExclusive),
            startLabel = DisplayFormat.clockLabel(first.startMillis, zone),
            countLabel = "${chunk.size} 段",
            durationLabel = DisplayFormat.compactDuration(chunk.sumOf { it.durationMillis }),
            durationMillis = chunk.sumOf { it.durationMillis },
            startMillis = first.startMillis,
            endMillisExclusive = last.endMillisExclusive,
            spilloverLabel = null,
        )
    }

    /**
     * 显示聚合的**块**。
     *
     * ## 为什么需要它
     * 会话门槛降到 10 秒之后（用户定的：那些零碎时间也要记下来），
     * 09-12 那一天有 **369 条记录**。一条一块地画：轴高 = 369 × 36dp 下限 ≈ 一万 dp，
     * 十几屏滚不完，而且每块都矮到点不准 —— 用户之前抱怨「太挤」就是这个症状。
     *
     * ## 规则（一次遍历，全部可测）
     * 1. 间隔超过 [chunkGapMillis] 的，是真的空档：块在这里断开（那段留白照画）；
     * 2. **用户自己写的记录永远单独一块**，绝不并进别的块 ——
     *    他亲手记的每一笔都要能直接点到；
     * 3. 同一个 key 再次出现 → 直接接着收（这就是原来的「显示分组」）；
     * 4. 否则继续收，直到这一块里**记下来的时间**累计到 [minChunkMillis] 才收手。
     *
     * ## 块里混了别的 App 怎么看
     * 标题取**占得最多的那一个**，并标上 `N 段`；点一下可以展开成每一条。
     * 这是「可读」与「不丢信息」之间的取舍：一块 15 分钟里可能切了七八次 App，
     * 全画出来等于没画；而段数把「这里不止一件事」明确写了出来，展开就能看到全部。
     */
    private fun chunkForDisplay(rows: List<TimelineRow>): List<TimelineBlock> {
        val blocks = mutableListOf<TimelineBlock>()
        var open = mutableListOf<TimelineRow>()
        var openMillis = 0L

        fun flush() {
            if (open.isEmpty()) return
            val span = open.last().endMillisExclusive - open.first().startMillis
            val height = heightDp(span)
            blocks += TimelineBlock.of(
                row = mergeChunk(open),
                heightDp = height,
                // 聚合块里放的是标题 + 段数 + 时长，比单条记录多一行字
                compact = height < COMPACT_BLOCK_DP && open.size == 1,
                children = open.map { child ->
                    val childHeight = heightDp(child.endMillisExclusive - child.startMillis)
                    TimelineBlock.of(
                        row = child,
                        heightDp = childHeight,
                        compact = childHeight < COMPACT_BLOCK_DP,
                    )
                },
            )
            open = mutableListOf()
            openMillis = 0L
        }

        rows.forEach { row ->
            val previous = open.lastOrNull()
            val gap = if (previous == null) 0L else row.startMillis - previous.endMillisExclusive
            val sameThing = previous != null && row.keyForGrouping() == previous.keyForGrouping()
            // 相同 App 且非手动记录的，给予更宽松的聚合条件
            val sameApp = previous != null && 
                !row.isUserRecorded && 
                !previous.isUserRecorded &&
                row.packageName != null && 
                row.packageName == previous.packageName
            val canJoin = previous != null &&
                gap in 0..gapThresholdMillis &&
                !row.isUserRecorded &&
                !previous.isUserRecorded &&
                (sameThing || (sameApp && gap <= 5 * 60 * 1000L) || openMillis < minChunkMillis)

            if (!canJoin) flush()
            open += row
            openMillis += row.durationMillis
        }
        flush()
        return blocks
    }

    /**
     * 把「记录块」与「它们之间的空白块」按时间顺序交替排好。
     *
     * 单独一个函数而不是塞在上面的循环里：空白块有三种来源（开头、相邻之间、收尾），
     * 混在一起写最容易漏掉其中一种 —— 之前就漏掉了「收尾」那种。
     */
    private fun interleaveGaps(
        chunks: List<TimelineBlock>,
        window: MillisRange,
        gapActivities: List<GapActivity>,
    ): List<TimelineBlock> {
        val blocks = mutableListOf<TimelineBlock>()

        val leading = gapBlock(window.startMillis, chunks.first().row!!.startMillis, gapActivities)
        leading?.let { blocks += it }

        chunks.forEachIndexed { index, chunk ->
            blocks += chunk

            val next = chunks.getOrNull(index + 1)
            val chunkEnd = chunk.row!!.endMillisExclusive
            val gapEnd = next?.row?.startMillis ?: window.endMillisExclusive
            gapBlock(chunkEnd, gapEnd, gapActivities)?.let { blocks += it }
        }

        return blocks
    }

    /**
     * 分组键：**只有同一个 App 才算同一个东西**；熄屏是同一个东西。
     *
     * 手动记录用记录 id 当键 —— 也就是说**它们永远不分组**。
     * 一开始按「分类+活动」给手动记录分组，结果是两条不同的手记被并成一块
     * （测试里 `a record split into several rows` 直接炸了）：
     * 用户亲手写的两笔，哪怕归到同一个活动，也是两件事，不该合并。
     *
     * 熄屏是唯一按「不是 App」也能分组的：它没有包名，但所有熄屏段说的是同一件事
     * ——「手机没在用」。
     */
    private fun TimelineRow.keyForGrouping(): String = when {
        isScreenOff -> SCREEN_OFF_GROUP_KEY
        else -> packageName ?: "record:$recordId"
    }

    /**
     * 造一个空白块；太短（或区间为空/反向）时返回 null，不画。
     *
     * 短的空白（< 15 分钟）不画成块：否则每两条碎片之间都会插一段，
     * 轴上全是「空 2m」，反而看不清真正的大段留白。
     */
    private fun gapBlock(
        from: Long,
        to: Long,
        gapActivities: List<GapActivity>,
    ): TimelineBlock? {
        val millis = to - from
        if (millis < gapThresholdMillis) return null

        val height = heightDp(millis)
        val range = MillisRange(from, to)

        return TimelineBlock.gap(
            gap = TimelineGap(
                rangeLabel = rangeLabel(from, to),
                durationLabel = "空 ${DisplayFormat.compactDuration(millis)}",
                startLabel = DisplayFormat.clockLabel(from, zone),
                startMillis = from,
                endMillisExclusive = to,
                heightDp = height,
                hintLabel = gapHint(range, gapActivities),
            ),
            heightDp = height,
        )
    }

    /**
     * 这段空白里到底发生了什么（模块 24）。
     *
     * 措辞写在这里而不是领域层：领域层给的是**事实**（事件数、黑屏时长、没有数据的时长），
     * 「怎么说给用户听」是显示决定，必须和其它文案一样能被 JVM 测试钉住。
     *
     * 一条硬规则：**只说这段时间里成立的事**。
     * 一开始的写法是「有事件就报事件」，结果真机上最典型的那块空白
     * （凌晨 04:00–07:43，前 3h40m 一条事件都没有，最后 3 分钟有几条）
     * 被写成「零碎操作 6 次」—— 数字没错，但把三个多小时的「没有数据」藏掉了。
     * 所以现在按**谁占得更多**来决定先说哪一件，最多说两件。
     */
    private fun gapHint(range: MillisRange, gapActivities: List<GapActivity>): String? {
        val activity = gapActivities.firstOrNull { it.range.overlaps(range) } ?: return null

        val parts = mutableListOf<String>()
        if (activity.noDataMillis >= MIN_MEANINGFUL_MILLIS) {
            parts += "没有系统记录 ${DisplayFormat.compactDuration(activity.noDataMillis)}"
        }
        if (activity.appOpenCount > 0) {
            parts += "零碎操作 ${activity.appOpenCount} 次"
        }
        if (activity.idleMillis >= MIN_MEANINGFUL_MILLIS) {
            parts += "手机没在用 ${DisplayFormat.compactDuration(activity.idleMillis)}"
        }

        return when {
            parts.isEmpty() -> PHONE_IDLE_HINT
            // 最多两条：块本身常常只有几十 dp 高，第三行就溢出了
            else -> parts.take(2).joinToString(" · ")
        }
    }

    /**
     * 整点刻度。
     *
     * 位置按**块的累加高度**算，而不是按线性时间 —— 因为矮块有高度下限，
     * 轴本身是「分段线性」的。这样算出来的刻度才会和它旁边的块对得上；
     * 按线性时间去摆，刻度会越走越偏。
     */
    private fun ticksFor(blocks: List<TimelineBlock>, window: MillisRange): List<TimelineTick> {
        val ticks = mutableListOf<TimelineTick>()
        var offset = 0
        var cursor = window.startMillis

        blocks.forEach { block ->
            val blockEnd = block.row?.endMillisExclusive ?: block.gap!!.endMillisExclusive
            // 这一块里跨过的每个整点，都按比例映射到它的高度上
            var hour = nextHourBoundary(cursor)
            while (hour <= blockEnd && blockEnd > cursor) {
                val fraction = (hour - cursor).toDouble() / (blockEnd - cursor).toDouble()
                val y = offset + (block.heightDp * fraction).toInt()
                if (ticks.isEmpty() || y - ticks.last().offsetDp >= MIN_TICK_SPACING_DP) {
                    ticks += TimelineTick(DisplayFormat.clockLabel(hour - 1, zone), y)
                }
                hour += 3_600_000L
            }
            offset += block.heightDp
            cursor = blockEnd
        }
        return ticks
    }

    private fun nextHourBoundary(millis: Long): Long {
        val remainder = millis % 3_600_000L
        return if (remainder == 0L) millis else millis + (3_600_000L - remainder)
    }

    /** 时长 → 视觉高度：1 小时 = [DP_PER_HOUR]，矮的抬到下限（否则一分钟的记录看不见）。 */
    private fun heightDp(millis: Long): Int {
        val minutes = millis / 60_000f
        val raw = minutes * (DP_PER_HOUR / 60f)
        return raw.coerceAtLeast(MIN_BLOCK_HEIGHT_DP.toFloat()).toInt()
    }

    /**
     * 时间轴逐段列出。
     *
     * 两处刻意处理：
     * 1. 每段都**裁剪到当天窗口**：跨 4:00 的记录会同时出现在相邻两天的查询结果里，
     *    不裁剪的话昨天 23:00–01:00 的记录会在今天头上多显示一小时；
     * 2. 零长度记录（只记了次数）也保留：它不占时间，但确实是用户主动记的一笔，
     *    从时间轴上抹掉等于吞掉用户数据。
     */
    private fun timelineRows(review: DayReview, catalog: CatalogLookup): List<TimelineRow> =
        review.timeline.segments.mapNotNull { segment ->
            val display: MillisRange = if (segment.isInstant) {
                segment.range.takeIf { it.startMillis in review.window }
            } else {
                segment.range.intersect(review.window)
            } ?: return@mapNotNull null

            TimelineRow(
                recordId = segment.record.id,
                rangeLabel = if (segment.isInstant) {
                    DisplayFormat.clockLabel(display.startMillis, zone)
                } else {
                    rangeLabel(display.startMillis, display.endMillisExclusive)
                },
                startLabel = DisplayFormat.clockLabel(display.startMillis, zone),
                title = titleFor(segment.categoryId, segment.activityId, segment.record.note, catalog, appFirst = segment.record.externalKey != null),
                subtitle = subtitleFor(segment.categoryId, segment.activityId, segment.record.note, catalog),
                durationLabel = if (segment.isInstant) {
                    "记了一笔"
                } else {
                    DisplayFormat.compactDuration(display.durationMillis)
                },
                colorArgb = catalog.activityColor(segment.activityId)
                    ?: catalog.categoryColor(segment.categoryId),
                isPending = segment.source == RecordSource.SUGGESTED,
                isInstant = segment.isInstant,
                durationMillis = if (segment.isInstant) 0L else display.durationMillis,
                // 用户自己记的与确认过的永远单独显示，不折叠
                isUserRecorded = segment.source == RecordSource.MANUAL ||
                    segment.source == RecordSource.MERGED,
                packageName = UsageRecordKeys.packageNameOf(segment.record.externalKey),
                isScreenOff = UsageRecordKeys.isScreenOffKey(segment.record.externalKey),
                startMillis = display.startMillis,
                endMillisExclusive = display.endMillisExclusive,
                spilloverLabel = spilloverLabel(segment.record, review.window),
                countLabel = null,
            )
        }

    /**
     * 记录超出了当天窗口时，把「另一半在哪」写清楚。
     *
     * 用户的疑问很具体：「我们睡眠被切割了从 4 点到早上 9 点，那 4 点之前呢」。
     * 答案是：**4 点之前那一段在昨天那一天里**（逻辑日 9/12 覆盖 9/12 04:00 ~ 9/13 03:59）。
     * 记录本身没有切开 —— 数据库里就一行，切开的是显示。
     *
     * 所以这一行既要写出全天有多长，也要指出另一半接在哪：
     * - 从昨天延续过来：`延续自昨日 23:00 · 全天 10h`
     * - 延伸到明天：`延续到次日 01:00 · 全天 3h`
     */
    private fun spilloverLabel(record: TimeRecord, window: MillisRange): String? {
        if (record.isInstant) return null

        val fromYesterday = record.startMillis < window.startMillis
        val toTomorrow = record.endMillis > window.endMillisExclusive
        if (!fromYesterday && !toTomorrow) return null

        val whole = DisplayFormat.compactDuration(record.durationMillis)
        val edge = when {
            fromYesterday -> "延续自昨日 ${DisplayFormat.clockLabel(record.startMillis, zone)}"
            else -> "延续到次日 ${DisplayFormat.clockLabel(record.endMillis, zone)}"
        }
        return "$edge · 全天 $whole"
    }

    /**
     * 一天的时间去向（饼图）。
     *
     * 分母是**已经过去的时间**：各分类 + 待确认 + 没有记录 = 全部，加起来正好 100%。
     * 这样「今天是怎么过的」有一个平的账，而不是只在有记录的那部分里分蛋糕
     * （那样的话「摸鱼三小时」会被藏起来看不见）。
     *
     * 用户要的就是这个：「需要一天的时间饼图」。
     */
    private fun dayShares(review: DayReview, catalog: CatalogLookup): List<DayShareRow> {
        val elapsed = review.observedWindow.durationMillis
        if (elapsed <= 0L) return emptyList()

        fun share(name: String, millis: Long, colorArgb: Int?, unrecorded: Boolean = false) =
            DayShareRow(
                name = name,
                durationLabel = DisplayFormat.compactDuration(millis),
                fraction = millis.toFloat() / elapsed,
                colorArgb = colorArgb,
                isUnrecorded = unrecorded,
            )

        val rows = mutableListOf<DayShareRow>()
        review.durations.byCategory.forEach { bucket ->
            rows += share(
                name = catalog.categoryName(bucket.categoryId) ?: UNCLASSIFIED,
                millis = bucket.durationMillis,
                colorArgb = catalog.categoryColor(bucket.categoryId),
            )
        }

        // 待确认：系统猜的还没被认可，单独一片（与分类分布的口径一致）
        review.durations.pendingMillis.takeIf { it > 0L }?.let { pending ->
            rows += share(PENDING_SHARE, pending, PENDING_COLOR)
        }

        // 没有记录：灰色那一片，就是用户问的「有些空的没有分类」
        review.durations.unrecordedMillis.takeIf { it > 0L }?.let { unrecorded ->
            rows += share(UNRECORDED_SHARE, unrecorded, UNRECORDED_COLOR, unrecorded = true)
        }

        return rows
    }

    /**
     * 一天的时间去向 —— **按具体应用**拆（模块 25）。
     *
     * 用户的原话：「我觉得可以再做一个具体应用的饼图，单看分类感觉不是很直观」。
     * 分母与分类饼图**完全相同**（今天已经过去的时间），所以两张图能直接对着看：
     * 分类那张说「娱乐占了多少」，这张说「娱乐里到底是谁」。
     *
     * ## 三件事值得说明
     * 1. **标签取记录里的 App 名**（`note`，由推断层写入），与时间轴上的标题是同一份来源 ——
     *    两处不会出现「轴上是抖音、饼图里是 com.ss.android.ugc.aweme」这种对不上；
     * 2. **熄屏与「没有记录」也各占一片**：它们是这一天的一部分，
     *    少了这两片，各应用的比例就会按「用手机的时间」重新归一，与分类图对不上；
     * 3. **颜色不能沿用分类色**：抖音、B 站、小红书同属「娱乐」，用分类色画出来
     *    会是一整块同色 —— 那张图就白画了。所以应用用一套固定色板按排名取色。
     */
    private fun appShares(review: DayReview, catalog: CatalogLookup): List<DayShareRow> {
        val elapsed = review.observedWindow.durationMillis
        if (elapsed <= 0L) return emptyList()

        val byPackage = mutableMapOf<String, Pair<String, Long>>()
        var screenOffMillis = 0L
        var screenOffCategoryId: Long? = null
        var manualMillis = 0L

        review.timeline.segments.forEach { segment ->
            if (segment.isInstant) return@forEach
            // 待确认的推测不算进分类，这里同样不算（单独一片）
            if (segment.source == RecordSource.SUGGESTED) return@forEach
            val clipped = segment.range.intersect(review.observedWindow) ?: return@forEach
            if (clipped.durationMillis <= 0L) return@forEach

            val key = segment.record.externalKey
            if (UsageRecordKeys.isScreenOffKey(key)) {
                screenOffMillis += clipped.durationMillis
                // 分类 id 从记录自己身上取：分类饼图里的「熄屏」用哪个颜色，这里就用哪个
                screenOffCategoryId = segment.categoryId
                return@forEach
            }
            val packageName = UsageRecordKeys.packageNameOf(key)
            if (packageName == null) {
                // 用户手写的记录：它回答的是「在干什么」（睡眠、复习），不是「在用哪个 App」。
                // 但它必须在这张图里占一片 —— 否则各片加起来不到 100%，
                // 圆环上会缺一个口子，看起来像画错了。
                manualMillis += clipped.durationMillis
                return@forEach
            }
            val label = segment.record.note?.takeIf { it.isNotBlank() } ?: packageName
            val previous = byPackage[packageName]
            byPackage[packageName] = (previous?.first ?: label) to
                ((previous?.second ?: 0L) + clipped.durationMillis)
        }

        fun share(name: String, millis: Long, colorArgb: Int?, unrecorded: Boolean = false) =
            DayShareRow(
                name = name,
                durationLabel = DisplayFormat.compactDuration(millis),
                fraction = millis.toFloat() / elapsed,
                colorArgb = colorArgb,
                isUnrecorded = unrecorded,
            )

        val ranked = byPackage.entries
            .sortedByDescending { it.value.second }
        val rows = mutableListOf<DayShareRow>()
        ranked.take(MAX_APP_SLICES).forEachIndexed { index, entry ->
            rows += share(
                name = entry.value.first,
                millis = entry.value.second,
                colorArgb = APP_PALETTE[index % APP_PALETTE.size],
            )
        }
        // 其余的应用合成一片：一片「其它 12 个」比十二片看不清的细条有用得多
        val rest = ranked.drop(MAX_APP_SLICES)
        if (rest.isNotEmpty()) {
            rows += share(
                name = "$OTHER_APPS_PREFIX${rest.size} 个",
                millis = rest.sumOf { it.value.second },
                colorArgb = OTHER_APPS_COLOR,
            )
        }

        // 熄屏与「没有记录」：与分类饼图用同一套颜色与措辞，两张图才像在说同一件事
        if (screenOffMillis > 0L) {
            rows += share(
                name = catalog.categoryName(screenOffCategoryId) ?: SCREEN_OFF_NAME,
                millis = screenOffMillis,
                colorArgb = catalog.categoryColor(screenOffCategoryId) ?: SCREEN_OFF_COLOR,
            )
        }
        review.durations.pendingMillis.takeIf { it > 0L }?.let { pending ->
            rows += share(PENDING_SHARE, pending, PENDING_COLOR)
        }
        // 用户自己记的那部分：它不属于任何 App，但必须占一片，
        // 否则各片加起来不到 100%，圆环上会缺一个口子
        manualMillis.takeIf { it > 0L }?.let { manual ->
            rows += share(MANUAL_SHARE, manual, MANUAL_SHARE_COLOR)
        }
        review.durations.unrecordedMillis.takeIf { it > 0L }?.let { unrecorded ->
            rows += share(UNRECORDED_SHARE, unrecorded, UNRECORDED_COLOR, unrecorded = true)
        }

        return rows
    }

    /**
     * 「计划 vs 实际」的明细（V1.1 P0-5）。
     *
     * 用户要的不是完成率，是**哪一段偏了**。所以每个计划时段给三样东西：
     * 计划是什么、实际发生了什么（逐段列出来）、以及一句偏差点睛。
     *
     * 三种偏差分开说，因为处理办法完全不同：
     * - **没记录**：那段时间什么都没记（`差 15m 没记录`）→ 该补记或该确认；
     * - **记了别的**：记了，但不是计划里那件事（`有 20m 记了别的事`）→ 计划被现实挤掉了；
     * - **待确认**：那段时间还只是系统推测（`有 10m 待确认`）→ 先去确认。
     *
     * **完全照做时不输出任何偏差文案** —— 没有偏差就不啰嗦，这是这类界面的基本礼貌。
     */
    private fun planRows(review: DayReview, catalog: CatalogLookup): List<PlanActualRow> =
        review.planComparisons.map { comparison ->
            val slot = comparison.slot
            val categoryName = catalog.categoryName(slot.categoryId)
            val activityName = catalog.activityName(slot.activityId)
            val binding = activityName ?: categoryName

            val deviation = when {
                !comparison.hasBinding -> NO_BINDING_HINT
                comparison.pendingMillis > 0L ->
                    "有 ${DisplayFormat.compactDuration(comparison.pendingMillis)} 待确认"
                comparison.deviatedMillis > 0L ->
                    "有 ${DisplayFormat.compactDuration(comparison.deviatedMillis)} 记了别的事"
                comparison.unrecordedMillis > 0L ->
                    "差 ${DisplayFormat.compactDuration(comparison.unrecordedMillis)} 没记录"
                else -> null
            }

            PlanActualRow(
                planBlockId = slot.planBlockId,
                rangeLabel = rangeLabel(slot.startMillis, slot.endMillisExclusive),
                title = if (binding != null) "${slot.title} · $binding" else slot.title,
                plannedLabel = DisplayFormat.compactDuration(comparison.plannedMillis),
                matchedLabel = DisplayFormat.compactDuration(comparison.matchedMillis),
                deviationLabel = deviation,
                matchedFraction = comparison.adherenceRatio.toFloat(),
                actualLines = comparison.segments.take(MAX_ACTUAL_LINES).map { segment ->
                    val name = catalog.activityName(segment.activityId)
                        ?: catalog.categoryName(segment.categoryId)
                        ?: UNCLASSIFIED
                    val mark = if (segment.source == RecordSource.SUGGESTED) "（待确认）" else ""
                    "${rangeLabel(segment.range.startMillis, segment.range.endMillisExclusive)} " +
                        "$name$mark"
                } + if (comparison.segments.size > MAX_ACTUAL_LINES) {
                    listOf("另有 ${comparison.segments.size - MAX_ACTUAL_LINES} 段")
                } else {
                    emptyList()
                },
                hasBinding = comparison.hasBinding,
            )
        }

    /**
     * 「此刻」状态（V1.1 P0）：**正在发生什么**。
     *
     * ## 「现在」从哪里来（不需要给映射器塞一个时钟）
     * 复盘的观察窗口对「今天」是裁到此刻的（模块 24：`observedWindow` 的终点就是 now），
     * 对过去的日子就是完整的一天。所以：
     * - 窗口终点 < 整天长度 → 这是今天，终点**就是现在**；
     * - 等于整天长度 → 过去的日子，不显示「此刻」（那时「现在」早已不在窗口里）。
     *
     * 这样一个纯函数就够了，不用注入时钟，测试也能写死。
     *
     * ## 三种状态
     * - 现在落在**最后一段**记录里（而且那段一直开到现在）→ 「正在进行」；
     * - 落在空档里 → 说「刚刚」那一段（今天最近发生的事），没有就如实说尚未确认。
     *
     * **绝不编造活动**：没有记录就说没有记录 —— 系统事实 ≠ 用户行为（需求第七节）。
     */
    private fun currentMoment(review: DayReview): CurrentMoment? {
        val window = review.observedWindow
        if (window.durationMillis >= review.window.durationMillis) return null

        val now = window.endMillisExclusive
        val rows = timelineRows(review, CatalogLookup.EMPTY)

        // 正在开着的那一段：它结束在「此刻」（分析层把仍在用的会话裁到现在）
        val running = rows.lastOrNull { it.endMillisExclusive >= now && it.startMillis < now }
        if (running != null) {
            return CurrentMoment(
                state = CurrentMoment.State.RUNNING,
                title = running.title,
                subtitle = running.subtitle,
                detailLabel = "已持续 ${DisplayFormat.compactDuration(now - running.startMillis)}",
                colorArgb = running.colorArgb,
            )
        }

        val recent = rows.lastOrNull { it.endMillisExclusive <= now }
            ?: return CurrentMoment(
                state = CurrentMoment.State.UNKNOWN,
                title = CURRENT_UNKNOWN_TITLE,
                subtitle = null,
                detailLabel = "已过去 ${DisplayFormat.compactDuration(window.durationMillis)}",
                colorArgb = null,
            )

        return CurrentMoment(
            state = CurrentMoment.State.RECENT,
            title = recent.title,
            subtitle = recent.subtitle,
            // 空档有多长，就是「多久之前结束的」
            detailLabel = "${DisplayFormat.compactDuration(now - recent.endMillisExclusive)}前结束",
            colorArgb = recent.colorArgb,
        )
    }

    /**
     * 缺口按**时长降序**取前几段，而不是按时间顺序。
     *
     * 界面空间有限，最该回答的是「这一天最大的空白在哪」；
     * 按时间顺序排的话，用户得自己扫完一遍才知道哪段最长。
     */
    private fun gapRows(review: DayReview): List<GapRow> {
        // 整天没有任何记录时不列缺口：那会变成一整段 24 小时，既没信息量又吓人，
        // 界面会改用「这一天还没有记录」的空状态来提示。
        if (review.durations.isEntirelyEmpty) return emptyList()

        return review.durations.unrecordedSegments
            .sortedByDescending { it.durationMillis }
            .take(maxGaps)
            .map { gap ->
                GapRow(
                    rangeLabel = rangeLabel(gap.startMillis, gap.endMillisExclusive),
                    durationLabel = DisplayFormat.compactDuration(gap.durationMillis),
                )
            }
    }

    private fun pendingLabel(review: DayReview): String? {
        if (review.durations.pendingMillis <= 0L) return null
        val duration = DisplayFormat.compactDuration(review.durations.pendingMillis)
        return if (review.pendingRecordCount > 0) {
            "$duration 待确认（${review.pendingRecordCount} 条）"
        } else {
            "$duration 待确认"
        }
    }

    private fun planNote(review: DayReview): String? {
        if (review.planComparisons.isEmpty()) return null
        return buildString {
            append("计划 ")
            append(DisplayFormat.compactDuration(review.plannedMillis))
            append("，照计划做了 ")
            append(DisplayFormat.compactDuration(review.plannedMatchedMillis))
            append("（")
            append(DisplayFormat.percentLabel(review.planAdherenceRatio))
            append("）")
        }
    }

    /**
     * 区间文案：`14:00–15:30`；跨到**下一个日历日**时写成 `22:00–次日 01:00`。
     *
     * 为什么必须区分：逻辑日横跨午夜（04:00 ~ 次日 03:59），
     * 所以「结束时刻在第二天」是常态而不是例外。
     * 只写 `22:00–01:00` 的话，用户根本分不清 01:00 是今天凌晨还是明天凌晨 ——
     * 而这个 App 恰恰以「凌晨 4 点才翻篇」为核心设定，把这一点显示错会直接误导人。
     */
    private fun rangeLabel(startMillis: Long, endMillisExclusive: Long): String {
        val startLabel = DisplayFormat.clockLabel(startMillis, zone)
        val endLabel = DisplayFormat.clockLabel(endMillisExclusive, zone)
        return if (crossesCalendarDay(startMillis, endMillisExclusive)) {
            "$startLabel–次日 $endLabel"
        } else {
            "$startLabel–$endLabel"
        }
    }

    private fun crossesCalendarDay(startMillis: Long, endMillisExclusive: Long): Boolean {
        val startDate = Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate()
        val endDate = Instant.ofEpochMilli(endMillisExclusive).atZone(zone).toLocalDate()
        return startDate != endDate
    }

    /**
     * 时间轴的主标题：**这段时间具体在干什么**。
     *
     * ## 系统记录一律显示 App 名（模块 27 定的）
     * 用户的原话：「把时间轴上的信息统一为具体的应用名称，不要用分类的，
     * 分类只在分析时使用」。
     *
     * 改造前这里优先取**活动名**，于是归过类的 App 会显示成「影视」「游戏」——
     * 那不是「在用什么」，而是**分析口径**。分类与活动回答的是「这一周娱乐多少」，
     * 时间轴要回答的是「刚才那 40 分钟到底在刷抖音还是在打王者」。两者不能混。
     *
     * 所以：系统记录（幂等键非空）**先取备注里的 App 名**，
     * 分类/活动退到副标题去；用户手写的记录没有 App 名，仍然按 活动 → 分类 显示。
     * 熄屏记录没有 App，落到分类名「熄屏」—— 那是它唯一诚实的名字。
     */
    private fun titleFor(
        categoryId: Long?,
        activityId: Long?,
        note: String?,
        catalog: CatalogLookup,
        /** 是否是系统采集来的记录（手记没有幂等键）。 */
        appFirst: Boolean = false,
    ): String {
        val noteText = note?.trim()?.takeIf { it.isNotEmpty() }
        if (appFirst && noteText != null) return noteText

        return catalog.activityName(activityId)
            ?: noteText
            ?: catalog.categoryName(categoryId)
            ?: UNCLASSIFIED
    }

    /**
     * 副标题：归属（`娱乐` 或 `学习 / Java`）。
     *
     * 只有当主标题已经不是它时才给 —— 否则屏幕上会出现两遍同样的字。
     */
    private fun subtitleFor(
        categoryId: Long?,
        activityId: Long?,
        note: String?,
        catalog: CatalogLookup,
    ): String? {
        val activityName = catalog.activityName(activityId)
        val categoryName = catalog.categoryName(categoryId)
        val title = titleFor(categoryId, activityId, note, catalog)

        val subtitle = when {
            activityName != null && categoryName != null -> "$categoryName / $activityName"
            activityName != null -> activityName
            categoryName != null -> categoryName
            else -> null
        }
        return subtitle?.takeIf { it != title }
    }

    private companion object {
        const val UNCLASSIFIED = "未归类"

        /** 饼图上「没有记录」那一片。 */
        const val UNRECORDED_SHARE = "没有记录"

        /** 饼图上「待确认」那一片。 */
        const val PENDING_SHARE = "待确认"

        /**
         * 「没有记录」用的是**淡灰**而不是主题色：它不是一个分类，而是一段未知。
         * 淡到一眼能看出「这里什么都没有」，又不会喧宾夺主。
         */
        const val UNRECORDED_COLOR = 0xFFE0E0E0.toInt()

        /** 「待确认」用中灰：比未知更实在一点（确实有系统猜的内容），但还没被认可。 */
        const val PENDING_COLOR = 0xFFBDBDBD.toInt()

        /** 空白块的几句解释（见 [gapHint]）。 */
        const val PHONE_IDLE_HINT = "空白时间"

        /** 「此刻」什么都没有时的标题：如实说不知道，不编活动。 */
        const val CURRENT_UNKNOWN_TITLE = "尚未确认"

        /** 计划没绑定分类/活动时的说明：那种情况下根本判断不了偏离（见 PlanActualComparison）。 */
        const val NO_BINDING_HINT = "未绑定分类，无法判断是否偏离"

        /** 计划时段内最多列几条实际明细，其余收成一句「另有 N 段」。 */
        const val MAX_ACTUAL_LINES = 3

        /**
         * 值得写进解释的最短时长：一分钟。
         *
         * 比它更短的（比如 40 秒的黑屏）写出来只是噪音，
         * 而且 `compactDuration` 会渲染成 `<1m`，读起来更奇怪。
         */
        const val MIN_MEANINGFUL_MILLIS = 60_000L

        /** 饼图中心的说明文字。 */
        const val ELAPSED_TODAY = "今天已过去"
        const val ELAPSED_WHOLE_DAY = "一整天"

        /** 十五分钟：短于这个时长的空档不画成空白块，否则每两条碎片之间都会插一段。 */
        const val DEFAULT_GAP_THRESHOLD_MILLIS = 15 * 60 * 1000L

        /**
         * 一个块里至少攒够这么多**记录时间**才收手（模块 24）。
         *
         * 依据是真机数据：会话门槛降到 10 秒之后 09-12 有 369 条记录，
         * 一条一块画出来是 369 × 36dp ≈ 一万 dp，十几屏。
         */
        const val DEFAULT_MIN_CHUNK_MILLIS = 15 * 60 * 1000L

        /** 显示分组用的键：所有熄屏段共用它。 */
        const val SCREEN_OFF_GROUP_KEY = "screen-off"

        /** 熄屏分类没在目录里时的兜底名字（正常情况下目录里一定有它，见 DefaultCatalog）。 */
        const val SCREEN_OFF_NAME = "熄屏"
        const val SCREEN_OFF_COLOR = 0xFF546E7A.toInt()

        /** 应用饼图最多画几片，其余合成「其它 N 个」。 */
        const val MAX_APP_SLICES = 8

        /** 用户手记的那部分在应用饼图里的名字（它不是 App，但必须占一片）。 */
        const val MANUAL_SHARE = "自己记的"
        const val MANUAL_SHARE_COLOR = 0xFF78909C.toInt()

        const val OTHER_APPS_PREFIX = "其它 "
        const val OTHER_APPS_COLOR = 0xFF9E9E9E.toInt()

        /**
         * 应用饼图的色板。
         *
         * **为什么不沿用分类色**：抖音、B 站、小红书同属「娱乐」，用分类色画出来
         * 是一整块同色 —— 那张图就白画了。用户要的是「具体是哪个应用」，
         * 所以每一片必须是能分开的颜色。按排名取色（第一名永远第一个颜色），
         * 这样同一份数据每次画出来一样，不会「刷新一下颜色全变了」。
         *
         * 选的是中等明度的实色：深浅两种主题下都还能分辨。
         */
        val APP_PALETTE = intArrayOf(
            0xFF3F6BFF.toInt(), // 蓝
            0xFFE53935.toInt(), // 红
            0xFF43A047.toInt(), // 绿
            0xFFFB8C00.toInt(), // 橙
            0xFF8E24AA.toInt(), // 紫
            0xFF00ACC1.toInt(), // 青
            0xFFD81B60.toInt(), // 玫红
            0xFF6D4C41.toInt(), // 棕
        )

        /**
         * 比例尺：1 小时 = 30dp，一整天约 720dp，加上矮块下限后约 3 屏。
         *
         * 取值来自真机数据（09-12：60 个记录块 + 10 个空白块）：
         * - `20dp/小时 + 下限 18dp` → 1.6 屏，但最小的块只有 18dp，**用户点不准**；
         * - `30dp/小时 + 下限 36dp` → 3.1 屏，最小的块 36dp，好点；
         * - `40dp/小时 + 下限 44dp` → 3.9 屏，更好点但太长。
         *
         * 选中间那档。用户的原话是「你这个轴太挤了吧，想要修改时间都不太好操作，
         * 我的建议是放大」——块高翻倍正是他要的。
         */
        const val DP_PER_HOUR = 30f

        /**
         * 高度下限：**这是"能不能点得到"的下限，不是"好不好看"的下限**。
         *
         * 一分钟的记录按比例只有 0.5dp，抬到 36dp 才是一个能稳定点中的目标
         * （块是整行宽的，所以 36dp 高已经很好点）。18dp 时用户明确反馈过点不准。
         */
        const val MIN_BLOCK_HEIGHT_DP = 36

        /** 矮于这个只放一行字（映射器决定，界面照做）。 */
        const val COMPACT_BLOCK_DP = 56

        /** 两个刻度靠得太近就不画第二个，免得文字叠在一起。 */
        const val MIN_TICK_SPACING_DP = 22
    }
    
    /**
     * 构建今日洞察列表，提供关键信息的快速概览。
     */
    private fun buildTodayInsights(review: DayReview, catalog: CatalogLookup): List<String> {
        val insights = mutableListOf<String>()
        
        // 添加已记录时间信息
        if (review.durations.confirmedMillis > 0) {
            insights.add("已记录 ${DisplayFormat.compactDuration(review.durations.confirmedMillis)}")
        }
        
        // 添加待确认时间信息
        if (review.durations.pendingMillis > 0) {
            insights.add("${DisplayFormat.compactDuration(review.durations.pendingMillis)} 待确认")
        }
        
        // 添加主要活动信息
        val topCategory = review.durations.byCategory.maxByOrNull { it.durationMillis }
        if (topCategory != null && topCategory.durationMillis > 0) {
            val categoryName = catalog.categoryName(topCategory.categoryId) ?: "未归类"
            insights.add("主要时间花在 $categoryName (${DisplayFormat.compactDuration(topCategory.durationMillis)})")
        }
        
        // 添加未完成目标信息
        val unfinishedGoals = review.goalProgress.filter { !it.isAchieved }
        if (unfinishedGoals.isNotEmpty()) {
            unfinishedGoals.take(1).forEach { progress -> // 最多显示1个未完成目标
                val remaining = progress.target - progress.achieved
                val remainingLabel = DisplayFormat.goalAchievedLabel(remaining, progress.goal.type)
                insights.add("${progress.goal.title} 还差 $remainingLabel")
            }
        }
        
        return insights
    }
}
