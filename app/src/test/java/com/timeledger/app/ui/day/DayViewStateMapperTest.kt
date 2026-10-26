package com.timeledger.app.ui.day

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.goal.GoalProgress
import com.timeledger.app.domain.model.Goal
import com.timeledger.app.domain.model.GoalPeriod
import com.timeledger.app.domain.model.GoalType
import com.timeledger.app.domain.model.RecordSource
import com.timeledger.app.domain.model.TimeRecord
import com.timeledger.app.domain.plan.PlannedSlot
import com.timeledger.app.domain.review.DayReview
import com.timeledger.app.domain.review.PlanActualComparator
import com.timeledger.app.domain.review.PlanActualComparison
import com.timeledger.app.domain.review.PlanActualSegment
import com.timeledger.app.domain.review.TimelineAggregator
import com.timeledger.app.domain.timeline.TimelineResolver
import com.timeledger.app.domain.usage.GapActivity
import com.timeledger.app.domain.usage.UsageRecordKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 日视图状态映射测试 —— 首页与日历共用这一份映射，因此它是界面层唯一能被真正验证的部分。
 *
 * 本机没有模拟器、没有设备、也没有 Studio Preview，界面长什么样无法目视确认。
 * 所以「显示什么文字、时间轴取哪几段、缺口怎么排序、跨夜怎么写」这些判断
 * 全部集中在 [DayViewStateMapper] 里，由这个测试逐条钉死；
 * Composable 只负责把结果画出来，不含业务判断。
 */
class DayViewStateMapperTest {

    private companion object {
        /** 内置分类「熄屏」的 id（测试里的目录表自己指定一个）。 */
        const val SCREEN_OFF_CATEGORY_ID = 3L
    }

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private val fixedNow = Instant.parse("2026-09-12T06:00:00Z").toEpochMilli()
    private val dayBoundary = DayBoundary(zoneId = shanghai, nowProvider = { fixedNow })

    /** 2026-09-12 是周六。 */
    private val sept12: Long = LocalDate.of(2026, 9, 12).toEpochDay()
    private val window: MillisRange = dayBoundary.dayRange(sept12)
    private val hour = 3600_000L

    private val mapper = DayViewStateMapper(zone = shanghai, maxGaps = 3)

    private val catalog = CatalogLookup(
        categoryNames = mapOf(1L to "学习", 2L to "娱乐", SCREEN_OFF_CATEGORY_ID to "熄屏"),
        // 熄屏的颜色照 DefaultCatalog 给（真实种子里它就是 0xFF546E7A）
        categoryColors = mapOf(1L to 0xFF3F6BFF.toInt(), SCREEN_OFF_CATEGORY_ID to 0xFF546E7A.toInt()),
        activityNames = mapOf(100L to "Java"),
        activityColors = emptyMap(),
    )

    /** 逻辑日窗口内、距 04:00 的第 N 小时。 */
    private fun at(hoursFrom4am: Double): Long =
        window.startMillis + (hoursFrom4am * hour).toLong()

    private fun record(
        fromHours: Double,
        toHours: Double,
        categoryId: Long? = 1L,
        activityId: Long? = 100L,
        source: RecordSource = RecordSource.MANUAL,
        id: Long = 0L,
        note: String? = null,
        /**
         * 系统记录的来源包名。
         *
         * 传了它就会生成一条真正的 `usage|…` 幂等键 —— 时间轴的**显示分组按包名**进行，
         * 所以「同一个 App 的两段」必须带上相同的包名才算数（真实数据也是这样）。
         */
        packageName: String? = null,
    ) = TimeRecord(
        id = id,
        startMillis = at(fromHours),
        endMillis = at(toHours),
        categoryId = categoryId,
        activityId = activityId,
        source = source,
        note = note,
        externalKey = packageName?.let { UsageRecordKeys.build(sept12, at(fromHours), it) },
    )

    /** 一条系统采集到的 App 段：包名进幂等键，App 名放备注（与推断层的写法一致）。 */
    private fun app(
        fromHours: Double,
        toHours: Double,
        packageName: String,
        label: String?,
        source: RecordSource = RecordSource.SYSTEM,
        categoryId: Long? = 2L,
        id: Long = 0L,
    ) = TimeRecord(
        id = id,
        startMillis = at(fromHours),
        endMillis = at(toHours),
        categoryId = categoryId,
        activityId = null,
        source = source,
        note = label,
        externalKey = UsageRecordKeys.build(sept12, at(fromHours), packageName),
    )

    /** 一条熄屏记录（幂等键用 `screen-off|` 前缀，与真实写入完全一致）。 */
    private fun screenOff(
        fromHours: Double,
        toHours: Double,
        id: Long = 0L,
    ) = TimeRecord(
        id = id,
        startMillis = at(fromHours),
        endMillis = at(toHours),
        categoryId = SCREEN_OFF_CATEGORY_ID,
        activityId = null,
        source = RecordSource.SYSTEM,
        note = null,
        externalKey = UsageRecordKeys.buildScreenOff(sept12, at(fromHours)),
    )

    private fun review(
        records: List<TimeRecord>,
        plans: List<PlanActualComparison> = emptyList(),
        goals: List<GoalProgress> = emptyList(),
        epochDay: Long = sept12,
        /**
         * 已经过去多少小时（默认整天）。
         *
         * 「今天」的窗口会被裁到此刻，复盘层就是这么算的（[DayReview.observedWindow]）；
         * 测试传一个小于 24 的值来模拟「今天下午」。
         */
        elapsedHours: Double = 24.0,
        gapActivities: List<GapActivity> = emptyList(),
    ): DayReview {
        val timeline = TimelineResolver.resolve(records)
        val dayWindow = dayBoundary.dayRange(epochDay)
        val observed = MillisRange(
            dayWindow.startMillis,
            dayWindow.startMillis + (elapsedHours * hour).toLong(),
        )
        return DayReview(
            epochDay = epochDay,
            window = dayWindow,
            timeline = timeline,
            durations = TimelineAggregator().aggregate(timeline, observed),
            planComparisons = plans,
            goalProgress = goals,
            pendingRecordCount = records.count { it.needsConfirmation },
            gapActivities = gapActivities,
        )
    }

    /** 造一个计划时段：从第 [fromHours] 小时到第 [toHours] 小时，其中照计划做了 [matchedHours]。 */
    private fun planComparison(
        fromHours: Double,
        toHours: Double,
        matchedHours: Double,
        /** 时段内的实际明细（默认给一段：这正是真实数据的样子）。 */
        segments: List<PlanActualSegment>? = null,
    ) = PlanActualComparison(
        slot = PlannedSlot(
            planBlockId = 1L,
            title = "Java",
            logicalDay = sept12,
            range = MillisRange(at(fromHours), at(toHours)),
            categoryId = 2L,
            activityId = null,
            location = null,
            note = null,
            crossesMidnight = false,
        ),
        recordedMillis = (matchedHours * hour).toLong(),
        matchedMillis = (matchedHours * hour).toLong(),
        segments = segments ?: if (matchedHours > 0.0) {
            listOf(
                PlanActualSegment(
                    range = MillisRange(at(fromHours), at(fromHours + matchedHours)),
                    recordIndex = 0,
                    categoryId = 2L,
                    activityId = null,
                    source = RecordSource.SYSTEM,
                )
            )
        } else {
            emptyList()
        },
    )

    private fun goalProgress(
        id: Long,
        period: GoalPeriod,
        achievedSeconds: Double = 0.0,
        targetSeconds: Double = 3 * 3600.0,
    ) = GoalProgress(
        goal = Goal(
            id = id,
            title = "目标$id",
            type = GoalType.TIME,
            period = period,
            targetValue = targetSeconds,
            categoryId = 1L,
        ),
        window = window,
        achieved = achievedSeconds,
        target = targetSeconds,
        contributingRecordCount = 1,
    )

    // ---------------- 顶部汇总 ----------------

    @Test
    fun `header shows date recorded and unrecorded time`() {
        val state = mapper.map(review(listOf(record(0.0, 2.0))), catalog)

        assertEquals("9月12日 周六", state.dateLabel)
        assertEquals("2h", state.recordedLabel)
        assertEquals("22h", state.unrecordedLabel)
        assertNull(state.pendingLabel)
        assertFalse(state.isEmptyDay)
    }

    @Test
    fun `pending time is reported with its record count`() {
        val state = mapper.map(
            review(
                listOf(
                    record(0.0, 2.0),
                    record(10.0, 11.0, categoryId = 2L, activityId = null, source = RecordSource.SUGGESTED),
                )
            ),
            catalog,
        )

        // 待确认不计入「有记录」的总时长
        assertEquals("2h", state.recordedLabel)
        assertEquals("1h 待确认（1 条）", state.pendingLabel)
    }

    // ---------------- 时间轴 ----------------

    @Test
    fun `timeline rows carry names durations and colors`() {
        val state = mapper.map(
            review(listOf(record(0.0, 2.0), record(4.0, 4.5, categoryId = 2L, activityId = null))),
            catalog,
        )

        assertEquals(2, state.timelineRows.size)
        val first = state.timelineRows[0]
        assertEquals("04:00–06:00", first.rangeLabel)
        assertEquals("Java", first.title)
        assertEquals("2h", first.durationLabel)
        assertEquals(0xFF3F6BFF.toInt(), first.colorArgb)
        assertFalse(first.isPending)
        assertFalse(first.isInstant)

        val second = state.timelineRows[1]
        assertEquals("08:00–08:30", second.rangeLabel)
        // 没有活动时退回分类名
        assertEquals("娱乐", second.title)
        assertEquals("30m", second.durationLabel)
    }

    @Test
    fun `timeline falls back to unclassified when neither id is known`() {
        val state = mapper.map(
            review(listOf(record(0.0, 1.0, categoryId = 99L, activityId = null))),
            catalog,
        )

        assertEquals("未归类", state.timelineRows.single().title)
        assertNull(state.timelineRows.single().colorArgb)
    }

    @Test
    fun `cross boundary record is clipped to this day in the timeline`() {
        // 周六 03:00–05:00：属于前一个逻辑日的 1 小时不该出现在今天
        val state = mapper.map(review(listOf(record(-1.0, 1.0))), catalog)

        assertEquals("1h", state.recordedLabel)
        assertEquals(1, state.timelineRows.size)
        assertEquals("04:00–05:00", state.timelineRows.single().rangeLabel)
        assertEquals("1h", state.timelineRows.single().durationLabel)
    }

    @Test
    fun `instant records are kept with a clock only label`() {
        val state = mapper.map(
            review(
                listOf(
                    record(2.0, 2.0, categoryId = 1L, activityId = 100L), // 只记了一笔
                    record(4.0, 5.0),
                )
            ),
            catalog,
        )

        // 零长度记录不占时间，但不能从时间轴上抹掉 —— 那是用户主动记的一笔
        assertEquals(2, state.timelineRows.size)
        val instant = state.timelineRows[0]
        assertEquals("06:00", instant.rangeLabel)
        assertEquals("记了一笔", instant.durationLabel)
        assertTrue(instant.isInstant)
        // 但它不贡献时长
        assertEquals("1h", state.recordedLabel)
    }

    @Test
    fun `range label marks the next calendar day`() {
        // 22:00 – 次日 01:00：跨过午夜，必须写清楚，否则分不清 01:00 是哪天
        val state = mapper.map(review(listOf(record(18.0, 21.0))), catalog)

        assertEquals("22:00–次日 01:00", state.timelineRows.single().rangeLabel)
        assertEquals("3h", state.timelineRows.single().durationLabel)
    }

    // ---------------- 熄屏的显示分组（模块 24） ----------------

    @Test
    fun `consecutive screen-off records are grouped into one block`() {
        // 熄屏门槛降到 1 分钟之后一天有上百条（真机：22 → 109）。
        // 「熄屏 → 亮屏看一眼 → 又熄屏」本来就是一件事，显示上要并成一块，
        // 否则整条轴都是「熄屏 · 熄屏 · 熄屏」。
        val state = mapper.map(
            review(
                listOf(
                    screenOff(fromHours = 0.0, toHours = 0.5),   // 04:00–04:30
                    screenOff(fromHours = 0.58, toHours = 1.0),  // 04:35–05:00
                )
            ),
            catalog,
        )

        val block = state.timelineRows.single()
        assertEquals("2 段", block.countLabel)
        // 块的跨度是 04:00–05:00，但时长写的是真实之和（30m + 25m）
        assertEquals("04:00–05:00", block.rangeLabel)
        assertEquals("55m", block.durationLabel)
        assertTrue(block.isScreenOff)
    }

    @Test
    fun `a screen-off record interrupted by real usage is not grouped across it`() {
        // 中间有一条真正的手动记录 → 分组链在这里断掉：熄屏不能被并到「用手机」的两边
        val state = mapper.map(
            review(
                listOf(
                    screenOff(fromHours = 0.0, toHours = 0.5),   // 04:00–04:30
                    record(0.58, 1.0),                            // 04:35–05:00 手记
                    screenOff(fromHours = 1.08, toHours = 1.5),  // 05:05–05:30
                )
            ),
            catalog,
        )

        assertEquals(3, state.timelineRows.size)
        assertNull(state.timelineRows.first().countLabel)
        assertNull(state.timelineRows.last().countLabel)
    }

    // ---------------- 按应用的时间去向（模块 25） ----------------

    @Test
    fun `app shares are ranked, use distinct colours and keep the same denominator`() {
        // 用户的原话：「可以再做一个具体应用的饼图，单看分类感觉不是很直观」
        val state = mapper.map(
            review(
                listOf(
                    app(0.0, 1.0, "com.douyin", "抖音"),   // 1h
                    app(1.0, 1.5, "com.wechat", "微信"),   // 30m
                    app(1.5, 1.75, "com.bili", "B站"),     // 15m
                )
            ),
            catalog,
        )

        val apps = state.appShares
        assertEquals(listOf("抖音", "微信", "B站"), apps.dropLast(1).map { it.name })
        // 分母是**今天已经过去的时间**（与分类饼图同一把尺子），不是「用手机的时间」
        assertEquals(1f / 24f, apps[0].fraction, 0.0001f)
        assertEquals(0.5f / 24f, apps[1].fraction, 0.0001f)
        // 每一片颜色都不一样：同属「娱乐」的 App 用分类色会画成一整块同色
        val colors = apps.map { it.colorArgb }
        assertEquals(colors.size, colors.distinct().size)
        // 最后一片永远是「没有记录」
        assertTrue(apps.last().isUnrecorded)
    }

    @Test
    fun `app shares keep screen-off and unrecorded so both pies add up the same`() {
        val state = mapper.map(
            review(
                listOf(
                    app(0.0, 1.0, "com.douyin", "抖音"),
                    screenOff(fromHours = 1.0, toHours = 2.0),
                ),
                elapsedHours = 4.0,
            ),
            catalog,
        )

        // 抖音 1h + 熄屏 1h + 没有记录 2h = 已经过去的 4h
        assertEquals(listOf("抖音", "熄屏", "没有记录"), state.appShares.map { it.name })
        assertEquals(1f, state.appShares.sumOf { it.fraction.toDouble() }.toFloat(), 0.0001f)
        // 熄屏用分类色，与分类饼图里那一块同色
        assertEquals(
            state.dayShares.first { it.name == "熄屏" }.colorArgb,
            state.appShares.first { it.name == "熄屏" }.colorArgb,
        )
    }

    @Test
    fun `app shares add up to the elapsed day even with hand-written records`() {
        // 这一条是圆环能不能画满的保证：应用 + 熄屏 + 自己记的 + 没有记录 = 已经过去的时间
        val state = mapper.map(
            review(
                listOf(
                    app(0.0, 1.0, "com.douyin", "抖音"),
                    record(1.0, 1.5),                 // 手记 30m
                    screenOff(fromHours = 2.0, toHours = 3.0),
                ),
                elapsedHours = 6.0,
            ),
            catalog,
        )

        assertEquals(listOf("抖音", "熄屏", "自己记的", "没有记录"), state.appShares.map { it.name })
        assertEquals(1f, state.appShares.sumOf { it.fraction.toDouble() }.toFloat(), 0.0001f)
    }

    @Test
    fun `apps beyond the top slices are grouped into one readable slice`() {
        val records = (0 until 12).map { index ->
            app(
                fromHours = index * 0.1,
                toHours = index * 0.1 + 0.05,
                packageName = "com.app$index",
                label = "App$index",
            )
        }
        val state = mapper.map(review(records), catalog)

        // 前 8 个各占一片，其余 4 个合成「其它 4 个」——十二片细条谁也看不清
        assertEquals(10, state.appShares.size) // 8 个应用 + 「其它 4 个」 + 「没有记录」
        assertEquals(listOf("App0", "App7"), listOf(state.appShares.first().name, state.appShares[7].name))
        assertEquals("其它 4 个", state.appShares[8].name)
        assertTrue(state.appShares.last().isUnrecorded)
    }

    @Test
    fun `app shares fall back to the package name when the record has no label`() {
        val state = mapper.map(
            review(listOf(app(0.0, 1.0, "com.example.mystery", label = null))),
            catalog,
        )

        assertEquals("com.example.mystery", state.appShares.first().name)
    }

    @Test
    fun `manual records are not apps but still take a slice`() {
        // 手记回答的是「在干什么」（睡眠、复习），不是「在用哪个 App」。
        // 但它必须占一片 —— 否则各片加起来不到 100%，圆环上会缺一个口子。
        val state = mapper.map(review(listOf(record(0.0, 2.0))), catalog)

        assertTrue(state.appShares.none { it.name == "Java" })
        assertEquals(listOf("自己记的", "没有记录"), state.appShares.map { it.name })
        assertEquals("2h", state.appShares.first().durationLabel)
    }

    @Test
    fun `unconfirmed suggestions stay out of the app ranking`() {
        val state = mapper.map(
            review(
                listOf(
                    app(0.0, 1.0, "com.douyin", "抖音"),
                    app(2.0, 3.0, "com.wechat", "微信", source = RecordSource.SUGGESTED),
                )
            ),
            catalog,
        )

        // 猜测的归属不混进应用排行，单独一片「待确认」（与分类口径一致）
        assertEquals(listOf("抖音", "待确认", "没有记录"), state.appShares.map { it.name })
    }

    // ---------------- 计划 vs 实际明细（V1.1 P0-5） ----------------

    @Test
    fun `a plan slot with nothing recorded says how much is missing`() {
        val state = mapper.map(
            review(
                records = listOf(app(0.0, 1.0, "com.douyin", "抖音")),
                plans = listOf(planComparison(fromHours = 2.0, toHours = 3.0, matchedHours = 0.0)),
            ),
            catalog,
        )

        val row = state.planRows.single()
        assertEquals("06:00–07:00", row.rangeLabel)
        // 完全没记录 → 偏差说的是「差 1h 没记录」，而不是笼统的完成率
        assertEquals("差 1h 没记录", row.deviationLabel)
        assertEquals(0f, row.matchedFraction, 0.0001f)
    }

    @Test
    fun `a plan slot that went exactly as planned has no deviation line`() {
        // 没有偏差就不啰嗦 —— 这类界面最基本的礼貌
        val state = mapper.map(
            review(
                records = listOf(app(0.0, 1.0, "com.douyin", "抖音")),
                plans = listOf(planComparison(fromHours = 0.0, toHours = 1.0, matchedHours = 1.0)),
            ),
            catalog,
        )

        assertNull(state.planRows.single().deviationLabel)
        assertEquals(1f, state.planRows.single().matchedFraction, 0.0001f)
    }

    @Test
    fun `actual time inside the slot is listed segment by segment`() {
        // 用户要的是「到底是哪一段偏了」，所以实际要逐段列出来
        val state = mapper.map(
            review(
                records = listOf(app(0.5, 1.0, "com.douyin", "抖音")),
                plans = listOf(planComparison(fromHours = 0.0, toHours = 1.0, matchedHours = 0.5)),
            ),
            catalog,
        )

        val row = state.planRows.single()
        // 这一栏列的是**分类/活动**而不是 App 名：计划绑定的就是分类/活动，
        // 两边同口径才比得出来（时间轴那边才显示 App 名，两者分工不同）。
        assertTrue("实际明细要把时段内的记录列出来", row.actualLines.any { it.contains("娱乐") })
    }

    // ---------------- 此刻（V1.1 P0） ----------------

    @Test
    fun `a record still running says what is happening right now`() {
        // 观察窗口对「今天」是裁到此刻的，所以窗口终点就是 now。
        // 一段记录一直开到此刻 → 「正在进行」，时长是**已经持续了多久**。
        val state = mapper.map(
            review(records = listOf(app(0.0, 6.0, "com.douyin", "抖音")), elapsedHours = 6.0),
            catalog,
        )

        val moment = state.currentMoment!!
        assertEquals(CurrentMoment.State.RUNNING, moment.state)
        assertEquals("抖音", moment.title)
        assertEquals("已持续 6h", moment.detailLabel)
    }

    @Test
    fun `when nothing is running it says what just ended`() {
        val state = mapper.map(
            review(records = listOf(app(0.0, 2.0, "com.bili", "哔哩哔哩")), elapsedHours = 5.0),
            catalog,
        )

        val moment = state.currentMoment!!
        assertEquals(CurrentMoment.State.RECENT, moment.state)
        assertEquals("哔哩哔哩", moment.title)
        assertEquals("3h前结束", moment.detailLabel)
    }

    @Test
    fun `with no record at all it says unknown instead of inventing an activity`() {
        // 系统事实 ≠ 用户行为：没有记录就说没有记录
        val state = mapper.map(review(emptyList(), elapsedHours = 5.0), catalog)

        val moment = state.currentMoment!!
        assertEquals(CurrentMoment.State.UNKNOWN, moment.state)
        assertEquals("尚未确认", moment.title)
    }

    @Test
    fun `a past day has no current moment`() {
        // 过去的日子「现在」早已不在窗口里 —— 不显示比显示一个假的强
        assertNull(mapper.map(review(listOf(record(0.0, 1.0))), catalog).currentMoment)
    }

    // ---------------- 一天的时间去向（饼图，模块 24） ----------------

    @Test
    fun `day shares take the elapsed day as the denominator and end with unrecorded`() {
        val state = mapper.map(
            review(
                listOf(
                    record(0.0, 2.0),                                     // 学习 2h
                    record(2.0, 2.5, categoryId = 2L, activityId = null), // 娱乐 0.5h
                )
            ),
            catalog,
        )

        // 三片：学习、娱乐、没有记录。分母是「今天已经过去的时间」= 24h
        assertEquals(3, state.dayShares.size)
        assertEquals("学习", state.dayShares[0].name)
        assertEquals("2h", state.dayShares[0].durationLabel)
        assertEquals(2f / 24f, state.dayShares[0].fraction, 0.0001f)
        assertEquals(0xFF3F6BFF.toInt(), state.dayShares[0].colorArgb)

        assertEquals("娱乐", state.dayShares[1].name)
        assertEquals("30m", state.dayShares[1].durationLabel)
        assertEquals(0.5f / 24f, state.dayShares[1].fraction, 0.0001f)
        // 没有配色的分类返回 null，界面自己用默认色
        assertNull(state.dayShares[1].colorArgb)

        // 最后一片永远是「没有记录」：一天的账必须是平的
        val last = state.dayShares.last()
        assertEquals("没有记录", last.name)
        assertEquals("21h30m", last.durationLabel)
        assertTrue(last.isUnrecorded)
        assertTrue(state.hasUnrecordedShare)
    }

    @Test
    fun `day shares add up to the whole elapsed day`() {
        val state = mapper.map(
            review(
                listOf(
                    record(0.0, 2.0),
                    record(5.0, 6.0, source = RecordSource.SUGGESTED), // 待确认 1h
                ),
                elapsedHours = 10.0,
            ),
            catalog,
        )

        // 学习 2h + 待确认 1h + 没有记录 7h = 已经过去的 10 小时
        assertEquals(listOf("学习", "待确认", "没有记录"), state.dayShares.map { it.name })
        assertEquals(1f, state.dayShares.sumOf { it.fraction.toDouble() }.toFloat(), 0.0001f)
        assertEquals("10h", state.elapsedLabel)
        assertEquals("今天已过去", state.elapsedNote)
    }

    @Test
    fun `day shares keep unconfirmed time out of the category slice`() {
        // 分母与分子都不把推测混进分类：否则「娱乐占了 70%」这种结论
        // 会建立在系统猜测之上（与分类分布的老口径一致）
        val state = mapper.map(
            review(
                listOf(
                    record(0.0, 2.0),
                    record(10.0, 12.0, categoryId = 2L, activityId = null, source = RecordSource.SUGGESTED),
                )
            ),
            catalog,
        )

        val study = state.dayShares.first { it.name == "学习" }
        assertEquals("2h", study.durationLabel)
        assertEquals(2f / 24f, study.fraction, 0.0001f)
        // 猜测的那 2 小时单独一片，没有被算进「娱乐」
        assertNull(state.dayShares.firstOrNull { it.name == "娱乐" })
        assertEquals("2h", state.dayShares.first { it.name == "待确认" }.durationLabel)
    }

    @Test
    fun `a day with no records shows one grey slice instead of nothing`() {
        val state = mapper.map(review(emptyList()), catalog)

        // 一条记录都没有时，饼图只有「没有记录」一片 ——
        // 那比「什么都没有」诚实：这一天确实还没被记录。
        assertEquals(1, state.dayShares.size)
        assertEquals("没有记录", state.dayShares.single().name)
        assertEquals(1f, state.dayShares.single().fraction, 0.0001f)
        assertTrue(state.hasShares)
    }

    @Test
    fun `unclassified category falls back to a readable name`() {
        val state = mapper.map(
            review(listOf(record(0.0, 1.0, categoryId = null, activityId = null))),
            catalog,
        )

        assertEquals("未归类", state.dayShares.first().name)
        assertEquals(1f / 24f, state.dayShares.first().fraction, 0.0001f)
    }

    // ---------------- 缺口 ----------------

    @Test
    fun `gaps are sorted by duration and limited`() {
        val limited = DayViewStateMapper(zone = shanghai, maxGaps = 2)
        val state = limited.map(
            review(
                listOf(
                    record(0.0, 1.0),   // 04:00–05:00
                    record(2.0, 6.0),   // 06:00–10:00
                    record(7.0, 8.0),   // 11:00–12:00
                )
            ),
            catalog,
        )

        // 缺口：05–06（1h）、10–11（1h）、12–次日04（16h）
        assertEquals(2, state.gaps.size)
        assertEquals("16h", state.gaps[0].durationLabel)
        assertEquals("12:00–次日 04:00", state.gaps[0].rangeLabel)
        assertEquals("1h", state.gaps[1].durationLabel)
    }

    @Test
    fun `empty day hides gaps and flags the empty state`() {
        val state = mapper.map(review(emptyList()), catalog)

        assertTrue(state.isEmptyDay)
        // 整天没记录时不列一段 24 小时的缺口：没有信息量，界面用空状态提示
        assertTrue(state.gaps.isEmpty())
        assertEquals("0m", state.recordedLabel)
        assertEquals("24h", state.unrecordedLabel)
        assertFalse(state.hasTimeline)
    }

    // ---------------- 空白块为什么是空白（模块 24） ----------------

    @Test
    fun `the axis ends with a blank block from the last record to now`() {
        // 以前这里什么都不画：晚上 21:00 之后没记录的那一段在轴上凭空消失，
        // 轴看起来「到 21:00 就结束了」—— 那段时间其实是「没有记录」，应该留白。
        val state = mapper.map(
            review(listOf(record(0.0, 1.0)), elapsedHours = 10.0),
            catalog,
        )

        val gaps = state.timelineGaps
        // 只有收尾那一段：开头没有空白（记录从 04:00 就开始）
        assertEquals(1, gaps.size)
        assertEquals("空 9h", gaps.single().durationLabel)
        assertEquals("05:00", gaps.single().startLabel)
    }

    @Test
    fun `a blank inside fragmented usage is explained by the fragments`() {
        val state = mapper.map(
            review(
                records = listOf(record(0.0, 1.0), record(3.0, 4.0)),
                gapActivities = listOf(
                    GapActivity(
                        range = MillisRange(at(1.0), at(3.0)),
                        hasEvents = true,
                        appOpenCount = 46,
                        idleMillis = 0L,
                        noDataMillis = 0L,
                    )
                ),
            ),
            catalog,
        )

        val gap = state.timelineGaps.single { it.startLabel == "05:00" }
        assertEquals("零碎操作 46 次", gap.hintLabel)
    }

    @Test
    fun `a blank with no events says the system has no data`() {
        // 「不知道」必须说成不知道，而不是含糊成「手机没在用」
        val state = mapper.map(
            review(
                records = listOf(record(0.0, 1.0), record(3.0, 4.0)),
                gapActivities = listOf(
                    GapActivity(
                        range = MillisRange(at(1.0), at(3.0)),
                        hasEvents = false,
                        appOpenCount = 0,
                        idleMillis = 0L,
                        noDataMillis = 2 * hour,
                    )
                ),
            ),
            catalog,
        )

        assertEquals("没有系统记录 2h", state.timelineGaps.first { it.startLabel == "05:00" }.hintLabel)
    }

    @Test
    fun `a mostly quiet blank leads with how long the system had no data`() {
        // 真机上最典型的一块：凌晨 04:00–07:43，前面 3h40m 一条事件都没有，
        // 最后 3 分钟才有几条零碎事件。只说「零碎操作 6 次」会把三个多小时的
        // 「没有数据」藏掉 —— 而用户问的恰恰是「这段为什么是空的」。
        val state = mapper.map(
            review(
                records = listOf(record(0.0, 1.0), record(3.0, 4.0)),
                gapActivities = listOf(
                    GapActivity(
                        range = MillisRange(at(1.0), at(3.0)),
                        hasEvents = true,
                        appOpenCount = 6,
                        idleMillis = 0L,
                        noDataMillis = 110 * 60_000L,
                    )
                ),
            ),
            catalog,
        )

        assertEquals(
            "没有系统记录 1h50m · 零碎操作 6 次",
            state.timelineGaps.first { it.startLabel == "05:00" }.hintLabel,
        )
    }

    @Test
    fun `a blank that is flickering screen off says the phone was not in use`() {
        // 手机确实没在用，只是每次熄屏都不到 5 分钟，所以没被记成熄屏
        val state = mapper.map(
            review(
                records = listOf(record(0.0, 1.0), record(3.0, 4.0)),
                gapActivities = listOf(
                    GapActivity(
                        range = MillisRange(at(1.0), at(3.0)),
                        hasEvents = true,
                        appOpenCount = 0,
                        idleMillis = 95 * 60_000L,
                        noDataMillis = 25 * 60_000L,
                    )
                ),
            ),
            catalog,
        )

        val gap = state.timelineGaps.first { it.startLabel == "05:00" }
        assertEquals("没有系统记录 25m · 手机没在用 1h35m", gap.hintLabel)
    }

    @Test
    fun `a blank with both fragments and idle time reports both`() {
        val state = mapper.map(
            review(
                records = listOf(record(0.0, 1.0), record(3.0, 4.0)),
                gapActivities = listOf(
                    GapActivity(
                        range = MillisRange(at(1.0), at(3.0)),
                        hasEvents = true,
                        appOpenCount = 12,
                        idleMillis = 40 * 60_000L,
                        noDataMillis = 0L,
                    )
                ),
            ),
            catalog,
        )

        val gap = state.timelineGaps.first { it.startLabel == "05:00" }
        assertEquals("零碎操作 12 次 · 手机没在用 40m", gap.hintLabel)
    }

    @Test
    fun `sub-minute facts are not worth mentioning`() {
        // 40 秒的黑屏写出来只是噪音（`compactDuration` 会渲染成 `<1m`，更奇怪）
        val state = mapper.map(
            review(
                records = listOf(record(0.0, 1.0), record(3.0, 4.0)),
                gapActivities = listOf(
                    GapActivity(
                        range = MillisRange(at(1.0), at(3.0)),
                        hasEvents = true,
                        appOpenCount = 0,
                        idleMillis = 40_000L,
                        noDataMillis = 20_000L,
                    )
                ),
            ),
            catalog,
        )

        assertEquals("空白时间", state.timelineGaps.first { it.startLabel == "05:00" }.hintLabel)
    }

    @Test
    fun `a blank without an explanation keeps its plain wording`() {
        // 算不出来时不能瞎猜：界面就只显示「空 Xh」（与加这个模块之前一样）
        val state = mapper.map(
            review(records = listOf(record(0.0, 1.0), record(3.0, 4.0))),
            catalog,
        )

        assertNull(state.timelineGaps.first { it.startLabel == "05:00" }.hintLabel)
    }

    // ---------------- 目标 ----------------

    @Test
    fun `goals are ordered by period then id`() {
        val state = mapper.map(
            review(
                emptyList(),
                goals = listOf(
                    goalProgress(id = 3L, period = GoalPeriod.MONTH),
                    goalProgress(id = 2L, period = GoalPeriod.WEEK),
                    goalProgress(id = 1L, period = GoalPeriod.DAY, achievedSeconds = 3 * 3600.0),
                ),
            ),
            catalog,
        )

        assertEquals(listOf("今日", "本周", "本月"), state.goals.map { it.periodLabel })
        assertEquals(listOf(1L, 2L, 3L), state.goals.map { it.goalId })
        assertTrue(state.goals[0].isAchieved)
        assertEquals("3h", state.goals[0].achievedLabel)
        assertEquals("3h", state.goals[0].targetLabel)
        assertEquals(1f, state.goals[0].progressFraction, 0.0001f)
        assertFalse(state.goals[1].isAchieved)
        assertEquals(0f, state.goals[1].progressFraction, 0.0001f)
        assertNull(state.planNote)
    }

    // ---------------- 计划 vs 实际 ----------------

    @Test
    fun `plan note summarises planned versus matched`() {
        val records = listOf(record(5.0, 7.0)) // 09:00–11:00
        val timeline = TimelineResolver.resolve(records)
        val slot = PlannedSlot(
            planBlockId = 1L,
            title = "Java",
            logicalDay = sept12,
            // 计划 09:00–10:40
            range = MillisRange(at(5.0), at(6.0 + 40.0 / 60.0)),
            categoryId = 1L,
            activityId = 100L,
            location = null,
            note = null,
            crossesMidnight = false,
        )
        val comparison = PlanActualComparator().compare(slot, timeline)

        val state = mapper.map(review(records, plans = listOf(comparison)), catalog)

        assertEquals("计划 1h40m，照计划做了 1h40m（100%）", state.planNote)
    }

    @Test
    fun `plan note is absent when there is no plan today`() {
        val state = mapper.map(review(listOf(record(0.0, 1.0))), catalog)
        assertNull(state.planNote)
    }

    // ---------------- 快速添加选项 ----------------

    @Test
    fun `quick add options are passed through unchanged`() {
        val options = listOf(QuickAddOption(activityId = 100L, label = "学习 / Java"))
        val state = mapper.map(review(emptyList()), catalog, options)

        assertEquals(options, state.quickAddOptions)
        assertTrue(state.canQuickAdd)
    }

    @Test
    fun `quick add is unavailable when no options are supplied`() {
        // 日历里的历史日期不给快速添加：它以「此刻」为基准，会把记录写到错误的时间
        val state = mapper.map(review(emptyList()), catalog)
        assertFalse(state.canQuickAdd)
    }

    // ---------------- 空白段与跨日标注（模块 21） ----------------

    @Test
    fun `the empty stretch before the first record is shown as a band`() {
        // 逻辑日从 04:00 开始，第一段记录在 10:00 —— 中间那 6 小时要看得见。
        // 用户的原话：「时间线轴可以留出没有记录的时间，直接留白，
        // 因为肯定会有没使用手机的时间」
        val state = mapper.map(
            review(listOf(record(6.0, 8.0, source = RecordSource.SYSTEM, id = 1L))),
            catalog,
        )

        val leading = state.timelineGaps.first()
        assertEquals("空 6h", leading.durationLabel)
        assertEquals("04:00–10:00", leading.rangeLabel)
        assertTrue("长空白要画得更高", leading.heightDp > 6)
    }

    @Test
    fun `short empty stretches between records are not drawn`() {
        // 5 分钟的空档画成一条带子只会把时间轴切得更碎
        val state = mapper.map(
            review(
                listOf(
                    record(2.0, 3.0, source = RecordSource.SYSTEM, id = 1L),
                    record(3.083, 4.0, source = RecordSource.SYSTEM, id = 2L), // 相隔 5 分钟
                )
            ),
            catalog,
        )

        assertEquals(2, state.timelineRows.size)
        // 5 分钟那一段不画；剩下的两段是开头（04:00–06:00）与收尾（08:00–次日 04:00）
        assertTrue(state.timelineGaps.none { it.durationLabel == "空 5m" })
        assertEquals(listOf("空 2h", "空 20h"), state.timelineGaps.map { it.durationLabel })
    }

    @Test
    fun `a record crossing the day boundary shows where the other half is`() {
        // 睡眠 23:00 → 次日 09:00：记录没被切开（库里就一行），切开的是显示。
        // 用户的原话是「我们睡眠被切割了从 4 点到早上 9 点，那 4 点之前呢」——
        // 答案是它在**前一天**那一天里。所以这一行要写出另一半接在哪儿。
        val sleep = TimeRecord(
            id = 5L,
            startMillis = at(19.0), // 9/12 23:00
            endMillis = window.endMillisExclusive + 5 * hour, // 9/13 09:00
            categoryId = 2L,
            source = RecordSource.SYSTEM,
            note = "睡眠",
        )

        // 从 9/12 看：入睡在今天，清醒跑到明天 → 写「延续到次日」
        val today = mapper.map(review(listOf(sleep)), catalog)
        val todayRow = today.timelineRows.single()
        assertEquals("延续到次日 09:00 · 全天 10h", todayRow.spilloverLabel)
        assertEquals("5h", todayRow.durationLabel) // 本日只有 23:00–次日 04:00

        // 从 9/13 看：同一行记录，这一次写「延续自昨日」——
        // 这就是「4 点之前那一段去哪儿了」的答案：它在昨天，而且昨天也写着全天 10h
        val tomorrow = mapper.map(review(listOf(sleep), epochDay = sept12 + 1), catalog)
        val tomorrowRow = tomorrow.timelineRows.single()
        assertEquals("延续自昨日 23:00 · 全天 10h", tomorrowRow.spilloverLabel)
        assertEquals("5h", tomorrowRow.durationLabel) // 本日只有 04:00–09:00
    }

    @Test
    fun `a record inside the day carries no spillover label`() {
        val state = mapper.map(
            review(listOf(record(2.0, 4.0, source = RecordSource.SYSTEM, id = 1L))),
            catalog,
        )

        assertNull(state.timelineRows.single().spilloverLabel)
    }

    // ---------------- 应用名与分类（模块 21） ----------------

    @Test
    fun `a system record shows the app name as the title and the category as the subtitle`() {
        // 用户的原话：「时间轴只有分类，没有具体的应用，不知道我这段时间具体做了什么」。
        // 归类解决统计口径，时间轴要回答「具体在干什么」—— 所以主标题给 App 名，
        // 分类退到副标题，两个信息都不丢
        val state = mapper.map(
            review(
                listOf(
                    record(
                        0.0, 2.0,
                        categoryId = 2L, // 娱乐
                        activityId = null,
                        source = RecordSource.SYSTEM,
                        note = "抖音极速版",
                    )
                )
            ),
            catalog,
        )

        val row = state.timelineRows.single()
        assertEquals("抖音极速版", row.title)
        assertEquals("娱乐", row.subtitle)
    }

    @Test
    fun `a manual record with an activity shows the activity and its category`() {
        // 手动记录里没有 App 名，最具体的信息是活动名（Java），分类作为副标题
        val state = mapper.map(
            review(listOf(record(0.0, 2.0, categoryId = 1L, activityId = 100L))),
            catalog,
        )

        val row = state.timelineRows.single()
        assertEquals("Java", row.title)
        assertEquals("学习 / Java", row.subtitle)
    }

    @Test
    fun `a record with only a category does not repeat it twice`() {
        // 只有分类时，主标题就是分类，副标题必须为 null —— 否则屏幕上出现两遍同样的字
        val state = mapper.map(
            review(listOf(record(0.0, 2.0, categoryId = 1L, activityId = null))),
            catalog,
        )

        val row = state.timelineRows.single()
        assertEquals("学习", row.title)
        assertNull(row.subtitle)
    }

    @Test
    fun `an unclassified app still shows its name`() {
        val state = mapper.map(
            review(
                listOf(
                    record(
                        0.0, 2.0,
                        categoryId = null,
                        activityId = null,
                        source = RecordSource.SYSTEM,
                        note = "com.example.unknown",
                    )
                )
            ),
            catalog,
        )

        val row = state.timelineRows.single()
        assertEquals("com.example.unknown", row.title)
        assertNull(row.subtitle)
    }

    @Test
    fun `a record with nothing at all falls back to unclassified`() {
        val state = mapper.map(
            review(listOf(record(0.0, 2.0, categoryId = null, activityId = null))),
            catalog,
        )

        assertEquals("未归类", state.timelineRows.single().title)
    }

    // ---------------- 记录 id（编辑入口要用） ----------------

    @Test
    fun `timeline rows carry the record id so a row can be edited`() {
        val state = mapper.map(
            review(listOf(record(0.0, 1.0, id = 7L), record(2.0, 3.0, id = 8L))),
            catalog,
        )

        assertEquals(listOf(7L, 8L), state.timelineRows.map { it.recordId })
    }

    @Test
    fun `a record split into several rows is identified by the same id`() {
        // 系统记录被一条手动记录从中间穿过 → 时间轴上是三行，但首尾两行是同一条记录。
        // 编辑其中任意一行都应该落到这条记录上，所以 id 必须相同。
        val state = mapper.map(
            review(
                listOf(
                    record(0.0, 3.0, source = RecordSource.SYSTEM, id = 7L),
                    record(1.0, 2.0, source = RecordSource.MANUAL, id = 9L),
                )
            ),
            catalog,
        )

        assertEquals(3, state.timelineRows.size)
        assertEquals(listOf(7L, 9L, 7L), state.timelineRows.map { it.recordId })
    }

    // ---------------- 时间轴折叠（模块 20） ----------------

    // ---------------- 竖轴：显示分组与高度（模块 22） ----------------

    @Test
    fun `the same app coming back within five minutes is shown as one block`() {
        // 用户的原话：「有些间隔一分钟然后是同一个应用的，可以直接并一起，就设定五分钟左右」。
        // 注意这只发生在**显示**上：块的标注写真实总时长（12m + 12m = 24m），不是跨度
        val state = mapper.map(
            review(
                listOf(
                    record(0.0, 0.2, categoryId = 2L, activityId = null, source = RecordSource.SYSTEM, note = "抖音", packageName = "com.douyin", id = 1L), // 12 分钟
                    record(0.22, 0.42, categoryId = 2L, activityId = null, source = RecordSource.SYSTEM, note = "抖音", packageName = "com.douyin", id = 2L), // 12 分钟
                )
            ),
            catalog,
        )

        val row = state.timelineRows.single()
        assertEquals("抖音", row.title)
        assertEquals("2 段", row.countLabel)
        assertEquals("24m", row.durationLabel)
    }

    @Test
    fun `a stretch of fiddling becomes one block named after the app used most`() {
        // 门槛降到 10 秒之后（模块 24），一天有几百条记录，一条一块地画会到十几屏。
        // 所以把「一段连续在动的时间」聚成一块：标题写**占得最多的那一个**，
        // 并标出 `N 段`；点一下能展开成每一条 —— 可读与不丢信息两头都要。
        val state = mapper.map(
            review(
                listOf(
                    record(0.0, 0.2, categoryId = 2L, activityId = null, source = RecordSource.SYSTEM, note = "抖音", packageName = "com.douyin", id = 1L), // 12 分钟
                    record(0.21, 0.35, categoryId = 2L, activityId = null, source = RecordSource.SYSTEM, note = "微信", packageName = "com.wechat", id = 2L), // 8.4 分钟
                )
            ),
            catalog,
        )

        val row = state.timelineRows.single()
        assertEquals("抖音", row.title)
        assertEquals("2 段", row.countLabel)
        assertEquals("20m", row.durationLabel)
        // 每一条都还在块里：展开就能逐条点到
        assertEquals(2, state.timeline.blocks.single { it.row != null }.children.size)
        assertTrue(state.timeline.blocks.single { it.row != null }.isExpandable)
    }

    @Test
    fun `a long visit is never swallowed into a neighbouring app`() {
        // 第一段自己就超过 15 分钟：块在这里收手，抖音与微信各自成块 ——
        // 「看了 20 分钟抖音」不该被贴上一个「抖音 2 段」的混合块
        val state = mapper.map(
            review(
                listOf(
                    record(0.0, 0.334, categoryId = 2L, activityId = null, source = RecordSource.SYSTEM, note = "抖音", packageName = "com.douyin", id = 1L), // 20 分钟
                    record(0.34, 0.5, categoryId = 2L, activityId = null, source = RecordSource.SYSTEM, note = "微信", packageName = "com.wechat", id = 2L), // 9.6 分钟
                )
            ),
            catalog,
        )

        assertEquals(listOf("抖音", "微信"), state.timelineRows.map { it.title })
        assertNull(state.timelineRows[0].countLabel)
        assertNull(state.timelineRows[1].countLabel)
    }

    @Test
    fun `expanding a block is what makes its records tappable`() {
        // 展开状态在界面上（用户点开的那些块），但**高度怎么算在映射器里**：
        // 本机看不到界面，这类算术必须能被测试钉住。
        val state = mapper.map(
            review(
                listOf(
                    record(0.0, 0.2, categoryId = 2L, activityId = null, source = RecordSource.SYSTEM, note = "抖音", packageName = "com.douyin", id = 1L),
                    record(0.21, 0.35, categoryId = 2L, activityId = null, source = RecordSource.SYSTEM, note = "微信", packageName = "com.wechat", id = 2L),
                )
            ),
            catalog,
        )

        val chunkIndex = state.timeline.blocks.indexOfFirst { it.row != null }
        val collapsed = state.timeline.heightDp(emptySet())
        val expandedHeight = state.timeline.heightDp(setOf(chunkIndex))

        assertEquals(state.timeline.totalHeightDp, collapsed)
        // 展开之后 = 收起高度 - 这个块自己的高度 + 里面每条各自的高度（各自也有下限）
        val chunk = state.timeline.blocks[chunkIndex]
        assertEquals(
            collapsed - chunk.heightDp + chunk.children.sumOf { it.heightDp },
            expandedHeight,
        )
        assertTrue("展开一定比收起高：每条记录都有 36dp 的下限", expandedHeight > collapsed)
    }

    @Test
    fun `a long absence between visits of the same app is not grouped`() {
        // 隔了 30 分钟还并成一块，就等于说「你连续用了半小时」—— 那是虚报
        val state = mapper.map(
            review(
                listOf(
                    record(0.0, 0.2, categoryId = 2L, activityId = null, source = RecordSource.SYSTEM, note = "抖音", packageName = "com.douyin", id = 1L),
                    record(0.7, 0.9, categoryId = 2L, activityId = null, source = RecordSource.SYSTEM, note = "抖音", id = 2L),
                )
            ),
            catalog,
        )

        assertEquals(2, state.timelineRows.size)
        assertNull(state.timelineRows[0].countLabel)
    }

    @Test
    fun `block height follows duration so a long stretch takes more room`() {
        // 竖轴的核心：块高 = 时长。1 小时 = 20dp
        val state = mapper.map(
            review(
                listOf(
                    record(0.0, 2.0, categoryId = 2L, activityId = null, source = RecordSource.SYSTEM, note = "长", id = 1L), // 2 小时
                    record(3.0, 3.25, categoryId = 2L, activityId = null, source = RecordSource.SYSTEM, note = "短", id = 2L), // 15 分钟
                )
            ),
            catalog,
        )

        val long = state.timeline.blocks.first { it.row?.title == "长" }
        val short = state.timeline.blocks.first { it.row?.title == "短" }
        assertEquals(60, long.heightDp)
        // 15 分钟只有 7.5dp，点不准 → 抬到下限 36dp
        assertEquals(36, short.heightDp)
        assertTrue("长的必须占更多地方", long.heightDp > short.heightDp)
        // 5dp 放不下图标和文字 → 标记为紧凑
        assertTrue(short.compact)
        assertTrue(!long.compact)
    }

    @Test
    fun `a very short block is bumped to the minimum height`() {
        // 一分钟的记录只占 0.5dp，不抬到下限等于点不到
        val state = mapper.map(
            review(listOf(record(0.0, 1.0 / 60.0, categoryId = 2L, activityId = null, source = RecordSource.SYSTEM, note = "一闪", id = 1L))),
            catalog,
        )

        assertEquals(36, state.timeline.blocks.first { it.row != null }.heightDp)
    }

    @Test
    fun `blank time takes room in proportion to how long it was`() {
        // 「上午三小时没碰手机」必须真的占三小时的地方 —— 这就是用户要的留白
        val state = mapper.map(
            review(listOf(record(6.0, 8.0, categoryId = 2L, activityId = null, source = RecordSource.SYSTEM, note = "抖音", id = 1L))),
            catalog,
        )

        val leadingGap = state.timelineGaps.first()
        assertEquals("空 6h", leadingGap.durationLabel)
        assertEquals("04:00", leadingGap.startLabel)
        assertEquals(180, leadingGap.heightDp) // 6 小时 × 30dp
    }

    @Test
    fun `short blanks are not drawn as blocks`() {
        // 5 分钟的空档单独占一块只会把轴切得更碎
        val state = mapper.map(
            review(
                listOf(
                    record(2.0, 3.0, source = RecordSource.SYSTEM, note = "a", id = 1L),
                    record(3.083, 4.0, source = RecordSource.SYSTEM, note = "b", id = 2L),
                )
            ),
            catalog,
        )

        // 5 分钟的空档不画；画出来的是开头（04:00–06:00）和收尾（08:00–次日 04:00）
        assertTrue(state.timelineGaps.none { it.durationLabel == "空 5m" })
        assertEquals(2, state.timelineGaps.size)
    }

    @Test
    fun `the total height is the sum of the blocks`() {
        val state = mapper.map(
            review(listOf(record(6.0, 8.0, categoryId = 2L, activityId = null, source = RecordSource.SYSTEM, note = "抖音", id = 1L))),
            catalog,
        )

        assertEquals(
            state.timeline.blocks.sumOf { it.heightDp },
            state.timeline.totalHeightDp,
        )
        assertTrue(state.timeline.totalHeightDp > 0)
    }

    @Test
    fun `every block carries the clock label the axis shows`() {
        val state = mapper.map(
            review(listOf(record(6.0, 8.0, categoryId = 2L, activityId = null, source = RecordSource.SYSTEM, note = "抖音", id = 1L))),
            catalog,
        )

        assertEquals("04:00", state.timelineGaps.first().startLabel)
        assertEquals("10:00", state.timelineRows.single().startLabel)
    }
}