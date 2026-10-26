package com.timeledger.app.domain.usage

import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.model.UsageEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 前台会话重建测试。
 *
 * 事件流的形状比想象中乱：切 App 时新 App 的 RESUMED 常先于旧 App 的 PAUSED，
 * 同一 App 内换 Activity 会重复发 RESUMED，锁屏与熄屏可能先后各来一次。
 * 这些情况在真机上不容易复现，但在这里可以逐条摆出来。
 */
class ForegroundSessionBuilderTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private val sept12: Long = LocalDate.of(2026, 9, 12).toEpochDay()

    /** 逻辑日窗口：9/12 04:00 ~ 9/13 04:00。 */
    private val window: MillisRange = MillisRange(
        startMillis = millis(sept12, 4),
        endMillisExclusive = millis(sept13(), 4),
    )

    private fun sept13(): Long = sept12 + 1

    private fun millis(epochDay: Long, hour: Int, minute: Int = 0): Long =
        LocalDate.ofEpochDay(epochDay).atTime(hour, minute).atZone(shanghai).toInstant().toEpochMilli()

    private fun ts(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun event(
        type: Int,
        atMillis: Long,
        packageName: String? = null,
        className: String? = null,
    ) = UsageEvent(
        packageName = packageName,
        className = className,
        eventType = type,
        timestampMillis = atMillis,
        eventKey = UsageEvent.buildEventKey(type, atMillis, packageName),
    )

    private fun foreground(atMillis: Long, pkg: String, className: String = "MainActivity") =
        event(UsageEventTypes.ACTIVITY_RESUMED, atMillis, pkg, className)

    private fun background(atMillis: Long, pkg: String, className: String = "MainActivity") =
        event(UsageEventTypes.ACTIVITY_PAUSED, atMillis, pkg, className)

    private val builder = ForegroundSessionBuilder()

    // 9/12 14:00 之类，用 UTC 字面量写清楚
    private val at1400 = ts("2026-09-12T06:00:00Z")
    private val at1405 = ts("2026-09-12T06:05:00Z")
    private val at1430 = ts("2026-09-12T06:30:00Z")
    private val at1500 = ts("2026-09-12T07:00:00Z")

    // ---------------- 基本形状 ----------------

    @Test
    fun `a simple foreground then background becomes one session`() {
        val sessions = builder.build(
            listOf(foreground(at1400, "com.a"), background(at1500, "com.a")),
            window,
        )

        assertEquals(1, sessions.size)
        assertEquals("com.a", sessions[0].packageName)
        assertEquals(at1400, sessions[0].startMillis)
        assertEquals(at1500, sessions[0].endMillisExclusive)
        assertEquals(SessionEndReason.APP_BACKGROUNDED, sessions[0].endReason)
    }

    @Test
    fun `switching apps ends the previous session when its own activity pauses`() {
        // 模块 26 之后，结束 A 的是 **A 自己的 PAUSED**，不是 B 的 RESUMED。
        // 真机上这两件事相差几百毫秒（切 App 时 A 立刻被 pause），所以结果看起来一样；
        // 但规则变了很重要 —— 打王者那 38 分钟里 QQ 不断冒出 RESUMED，
        // 旧规则会把王者切成碎片（23 分钟整段消失），新规则不会。
        val sessions = builder.build(
            listOf(
                foreground(at1400, "com.a"),
                foreground(at1430, "com.b"),
                background(ts("2026-09-12T06:30:01Z"), "com.a"),
                background(at1500, "com.b"),
            ),
            window,
        )

        assertEquals(listOf("com.a", "com.b"), sessions.map { it.packageName })
        assertEquals(at1400, sessions[0].startMillis)
        assertEquals(at1430, sessions[0].endMillisExclusive)
        assertEquals(SessionEndReason.APP_SWITCHED, sessions[0].endReason)
        assertEquals("com.b", sessions[1].packageName)
    }

    @Test
    fun `without a pause event the app still counts as being in the foreground`() {
        // 与 Android 同口径：决定「这一段结束」的是它自己的 PAUSED。
        // B 到前台并不会替 A 收尾，所以 A 在 B 走后继续 —— 这是同一条规则的另一面。
        // 真机上 PAUSED 总会到（切走时相差几百毫秒），这一条只是把规则写死，
        // 免得以后有人以为「别人 RESUMED 就该把当前这段截断」。
        val sessions = builder.build(
            listOf(
                foreground(at1400, "com.a"),
                foreground(at1430, "com.b"),
                background(at1500, "com.b"),
            ),
            window,
        )

        assertEquals(listOf("com.a", "com.b", "com.a"), sessions.map { it.packageName })
        assertEquals(at1430, sessions[0].endMillisExclusive)
        assertEquals(at1500, sessions[1].endMillisExclusive)
    }

    @Test
    fun `an out of order pause from the previous app does not truncate the current one`() {
        // 真实事件流常见顺序：新 App RESUMED 先到，旧 App PAUSED 后到。
        // 如果按包名各自记会话，这里会得到两段重叠的会话 —— 那等于说用户同时在用两个 App。
        val sessions = builder.build(
            listOf(
                foreground(at1400, "com.a"),
                foreground(at1430, "com.b"),
                background(at1430, "com.a"), // 迟到的旧事件
                background(at1500, "com.b"),
            ),
            window,
        )

        assertEquals(2, sessions.size)
        assertEquals(at1430, sessions[0].endMillisExclusive)
        assertEquals(at1430, sessions[1].startMillis)
        assertEquals(at1500, sessions[1].endMillisExclusive)
    }

    @Test
    fun `repeated resumes for the same app do not split a session`() {
        // 同一个 App 内换 Activity 会再发一次 RESUMED。切碎的话，
        // 一次连续使用会变成好几条记录，时间轴看起来像被谁乱切过。
        val sessions = builder.build(
            listOf(
                foreground(at1400, "com.a"),
                foreground(at1405, "com.a"),
                background(at1500, "com.a"),
            ),
            window,
        )

        assertEquals(1, sessions.size)
        assertEquals(at1400, sessions[0].startMillis)
        assertEquals(at1500, sessions[0].endMillisExclusive)
    }

    @Test
    fun `events are sorted before rebuilding so input order does not matter`() {
        val shuffled = listOf(
            background(at1500, "com.a"),
            foreground(at1400, "com.a"),
        )

        val sessions = builder.build(shuffled, window)

        assertEquals(1, sessions.size)
        assertEquals(at1400, sessions[0].startMillis)
        assertEquals(at1500, sessions[0].endMillisExclusive)
    }

    @Test
    fun `a background event without a matching foreground is ignored`() {
        // 会话的 RESUMED 可能在窗口之前（例如昨天 23:00 打开、今天 00:10 关掉）
        val sessions = builder.build(listOf(background(at1400, "com.a")), window)

        assertTrue(sessions.isEmpty())
    }

    // ---------------- 结束原因 ----------------

    @Test
    fun `only a shutdown ends the session behind the app's back`() {
        // 模块 26 之后：**熄屏与锁屏不再替应用收尾**（这台 ROM 在视频播放、屏幕闪烁时
        // 也会发熄屏事件，而应用自己的 PAUSED 并不到 —— Android 口径下它一直算前台）。
        // 关机仍然收尾：所有 Activity 都会被系统收掉，事件也未必来得及发。
        listOf(
            UsageEventTypes.SCREEN_NON_INTERACTIVE,
            UsageEventTypes.KEYGUARD_SHOWN,
        ).forEach { type ->
            val sessions = builder.build(
                listOf(foreground(at1400, "com.a"), event(type, at1500)),
                window,
            )
            assertEquals("type=$type", 1, sessions.size)
            assertEquals(
                "熄屏/锁屏不该替应用收尾：会话应一直开到窗口结束",
                window.endMillisExclusive,
                sessions[0].endMillisExclusive,
            )
        }

        val shutdown = builder.build(
            listOf(
                foreground(at1400, "com.a"),
                event(UsageEventTypes.DEVICE_SHUTDOWN, at1500),
            ),
            window,
        )
        assertEquals(1, shutdown.size)
        assertEquals(SessionEndReason.DEVICE_SHUTDOWN, shutdown[0].endReason)
        assertEquals(at1500, shutdown[0].endMillisExclusive)
    }

    @Test
    fun `an app that pauses right after the screen goes off still ends there`() {
        // 真机上的常态：熄屏的同时应用自己也会 PAUSED（相差几百毫秒），
        // 所以「熄屏就结束」这个结论在结果上仍然成立 —— 只是收尾的人换成了应用自己。
        val sessions = builder.build(
            listOf(
                foreground(at1400, "com.a"),
                event(UsageEventTypes.SCREEN_NON_INTERACTIVE, at1500),
                background(ts("2026-09-12T07:00:01Z"), "com.a"),
                event(UsageEventTypes.KEYGUARD_SHOWN, ts("2026-09-12T07:00:01Z")),
            ),
            window,
        )

        assertEquals(1, sessions.size)
        assertEquals(ts("2026-09-12T07:00:01Z"), sessions[0].endMillisExclusive)
        assertEquals(SessionEndReason.APP_BACKGROUNDED, sessions[0].endReason)
    }

    @Test
    fun `screen on and unlock do not start a session by themselves`() {
        // 亮屏时还不知道用户要打开什么，会话必须从下一个 RESUMED 开始。
        // 把亮屏当成会话开始，会把「拿起手机看一眼时间」记成一段使用。
        val sessions = builder.build(
            listOf(
                event(UsageEventTypes.SCREEN_INTERACTIVE, at1400),
                event(UsageEventTypes.KEYGUARD_HIDDEN, at1405),
                foreground(at1430, "com.a"),
                background(at1500, "com.a"),
            ),
            window,
        )

        assertEquals(1, sessions.size)
        assertEquals(at1430, sessions[0].startMillis)
    }

    @Test
    fun `an open session ends at the range end`() {
        // 用户此刻还在用：会话算到分析窗口结束（调用方负责把窗口结束限制在「现在」）
        val sessions = builder.build(listOf(foreground(at1400, "com.a")), window)

        assertEquals(1, sessions.size)
        assertEquals(window.endMillisExclusive, sessions[0].endMillisExclusive)
        assertEquals(SessionEndReason.RANGE_END, sessions[0].endReason)
        assertTrue(sessions[0].endsAtRangeEnd)
    }

    // ---------------- 规则二：离开一下又回来（模块 20） ----------------

    @Test
    fun `a quick switch away and back becomes one session`() {
        // 真机数据：2674 段碎片里 2407 段短于 5 秒 —— 用户切出去看一眼又马上回来。
        // 这些不该把一次连续使用切成两段，更不该因为短于阈值而整段消失。
        // 注意插曲必须短于 10 秒：长到能成为一条记录的插曲是不许被吞掉的（见下一条测试）。
        val sessions = builder.build(
            listOf(
                foreground(at1400, "com.a"),
                foreground(at1430, "com.b"), // 14:30 切到 b
                background(ts("2026-09-12T06:30:03Z"), "com.b"), // 3 秒后 b 退场
                foreground(ts("2026-09-12T06:30:03Z"), "com.a"), // 同时回到 a
                background(at1500, "com.a"),
            ),
            window,
        )

        assertEquals(1, sessions.size)
        assertEquals("com.a", sessions.single().packageName)
        assertEquals(at1400, sessions.single().startMillis)
        assertEquals(at1500, sessions.single().endMillisExclusive)
        assertTrue(
            "中间那段 3 秒的插曲应该被吃掉，而不是留在时间轴上",
            sessions.none { it.packageName == "com.b" },
        )
    }

    @Test
    fun `a longer absence is not merged`() {
        // 超过合并间隔就不接回去：那段时间用户是真的在用别的东西，
        // 接回去等于把那段算进前一个 App —— 那是捏造时间。
        // 间隔 6 分钟 > 默认的 5 分钟，所以分成两段
        val sessions = builder.build(
            listOf(
                foreground(at1400, "com.a"),
                background(ts("2026-09-12T06:02:00Z"), "com.a"),
                foreground(ts("2026-09-12T06:08:00Z"), "com.a"),
                background(ts("2026-09-12T07:00:00Z"), "com.a"),
            ),
            window,
        )

        assertEquals(2, sessions.size)
        assertEquals(at1400, sessions[0].startMillis)
        assertEquals(ts("2026-09-12T06:02:00Z"), sessions[0].endMillisExclusive)
        assertEquals(ts("2026-09-12T06:08:00Z"), sessions[1].startMillis)
        assertEquals(ts("2026-09-12T07:00:00Z"), sessions[1].endMillisExclusive)
    }

    @Test
    fun `a brief return to the same app is merged`() {
        // 用户的反馈：「有些间隔一分钟然后是同一个应用的，可以直接并一起，就设定五分钟左右」。
        // 代价是中间那两分钟被算进这个 App —— 这是他要的可读性
        val sessions = builder.build(
            listOf(
                foreground(at1400, "com.a"),
                background(ts("2026-09-12T06:02:00Z"), "com.a"),
                foreground(ts("2026-09-12T06:02:30Z"), "com.a"),
                background(ts("2026-09-12T07:00:00Z"), "com.a"),
            ),
            window,
        )

        assertEquals(1, sessions.size)
        assertEquals(at1400, sessions.single().startMillis)
        assertEquals(ts("2026-09-12T07:00:00Z"), sessions.single().endMillisExclusive)
    }

    @Test
    fun `the merge gap is configurable`() {
        // 离开 6 分钟：超过默认的 5 分钟，不合并；把间隔放宽到 10 分钟就会合并
        val events = listOf(
            foreground(at1400, "com.a"),
            background(ts("2026-09-12T06:02:00Z"), "com.a"),
            foreground(ts("2026-09-12T06:08:00Z"), "com.a"),
            background(ts("2026-09-12T07:00:00Z"), "com.a"),
        )

        assertEquals(2, builder.build(events, window).size)
        assertEquals(1, ForegroundSessionBuilder(mergeGapMillis = 10 * 60_000L).build(events, window).size)
    }

    @Test
    fun `exactly the merge gap still merges`() {
        // 边界是闭区间：正好 30 秒也算「马上回来」。写清楚是因为
        // 这种 ±1 的地方最容易在改动时悄悄变味
        val events = listOf(
            foreground(at1400, "com.a"),
            background(ts("2026-09-12T06:02:00Z"), "com.a"),
            foreground(ts("2026-09-12T06:02:30Z"), "com.a"),
            background(ts("2026-09-12T07:00:00Z"), "com.a"),
        )

        assertEquals(1, builder.build(events, window).size)
    }

    @Test
    fun `the merge never swallows a session where something else was really used`() {
        // 放宽合并窗口的真实后果（真机上量出来的）：09-12 的真实前台时间 5h18m
        // 变成了记录里的 9h58m。抖音 5m → 微信 3m → 抖音 5m：
        // 微信那 3 分钟是真的在用，绝不能被并进抖音
        val sessions = builder.build(
            listOf(
                foreground(at1400, "douyin"),
                background(ts("2026-09-12T06:05:00Z"), "douyin"), // 抖音 14:00–14:05
                foreground(ts("2026-09-12T06:06:00Z"), "wechat"), // 微信 14:06–14:09
                background(ts("2026-09-12T06:09:00Z"), "wechat"),
                foreground(ts("2026-09-12T06:10:00Z"), "douyin"), // 抖音 14:10–14:15
                background(ts("2026-09-12T06:15:00Z"), "douyin"),
            ),
            window,
        )

        assertEquals(3, sessions.size)
        assertEquals(listOf("douyin", "wechat", "douyin"), sessions.map { it.packageName })
        assertEquals(
            "微信那 3 分钟不能被算进抖音",
            3 * 60_000L,
            sessions[1].durationMillis,
        )
    }

    @Test
    fun `a short interruption that would be dropped anyway is still merged`() {
        // 中间那段只有 5 秒（低于 10 秒门槛，本来就会被丢掉）：这种接回去是安全的
        val sessions = builder.build(
            listOf(
                foreground(at1400, "douyin"),
                background(ts("2026-09-12T06:05:00Z"), "douyin"),
                foreground(ts("2026-09-12T06:05:05Z"), "wechat"),
                background(ts("2026-09-12T06:05:10Z"), "wechat"),
                foreground(ts("2026-09-12T06:05:20Z"), "douyin"),
                background(ts("2026-09-12T06:15:00Z"), "douyin"),
            ),
            window,
        )

        assertEquals(1, sessions.size)
        assertEquals("douyin", sessions.single().packageName)
        assertEquals(at1400, sessions.single().startMillis)
        assertEquals(ts("2026-09-12T06:15:00Z"), sessions.single().endMillisExclusive)
    }

    @Test
    fun `an interruption long enough to be a record blocks the merge`() {
        // 中间那段 30 秒 ≥ 10 秒门槛 —— **它自己会变成一条记录**，
        // 所以绝不能被并进抖音：那等于把「在看微信」的 30 秒算成刷抖音。
        // 这正是「两个角色共用一个门槛」的原因（见 minSessionMillis 的注释）。
        val sessions = builder.build(
            listOf(
                foreground(at1400, "douyin"),
                background(ts("2026-09-12T06:05:00Z"), "douyin"),
                foreground(ts("2026-09-12T06:05:05Z"), "wechat"),
                background(ts("2026-09-12T06:05:35Z"), "wechat"),
                foreground(ts("2026-09-12T06:05:40Z"), "douyin"),
                background(ts("2026-09-12T06:15:00Z"), "douyin"),
            ),
            window,
        )

        assertEquals(
            listOf("douyin", "wechat", "douyin"),
            sessions.map { it.packageName },
        )
        assertEquals(30 * 1000L, sessions[1].durationMillis)
    }

    @Test
    fun `the default merge gap stays tight because it decides duration`() {
        assertEquals(30 * 1000L, ForegroundSessionBuilder.DEFAULT_MERGE_GAP_MILLIS)
    }

    @Test
    fun `the default session threshold is ten seconds`() {
        // 这是「多少真实使用被记下来」的口径，取值依据见常量注释（真机实测的四档对比）
        assertEquals(10 * 1000L, ForegroundSessionBuilder.DEFAULT_MIN_SESSION_MILLIS)
    }

    // ---------------- 规则一：不是「用户在使用」的包名（模块 20） ----------------

    @Test
    fun `the launcher never becomes a session but does end the previous one`() {
        // 真机上桌面 30 小时出现 559 次：按 Home、切 App 都会路过它。
        // 它不是「用户在做什么」，所以永远不成一段；
        // 而离开 App 到桌面时，**那个 App 自己的 PAUSED 会到** —— 收尾的是它，
        // 不是桌面（模块 26 之后不再让「别人 RESUMED」替当前这段收尾）。
        val launcher = "com.example.launcher"
        val sessions = builder.build(
            listOf(
                foreground(at1400, "com.a"),
                foreground(at1430, launcher),
                background(ts("2026-09-12T06:30:02Z"), "com.a"),
                // 很久之后才回到 a（远超合并间隔）
                foreground(ts("2026-09-12T10:00:00Z"), "com.a"),
                background(ts("2026-09-12T11:00:00Z"), "com.a"),
            ),
            window,
            ignoredPackages = setOf(launcher),
        )

        assertEquals(2, sessions.size)
        assertTrue(sessions.none { it.packageName == launcher })
        assertEquals(ts("2026-09-12T06:30:02Z"), sessions[0].endMillisExclusive)
    }

    @Test
    fun `a trip through the launcher and straight back stays one session`() {
        val launcher = "com.example.launcher"
        val sessions = builder.build(
            listOf(
                foreground(at1400, "com.a"),
                foreground(ts("2026-09-12T06:10:00Z"), launcher),
                foreground(ts("2026-09-12T06:10:05Z"), "com.a"), // 5 秒后回到 a
                background(at1500, "com.a"),
            ),
            window,
            ignoredPackages = setOf(launcher),
        )

        assertEquals(1, sessions.size)
        assertEquals(at1400, sessions.single().startMillis)
        assertEquals(at1500, sessions.single().endMillisExclusive)
    }

    @Test
    fun `an ignored package alone produces nothing`() {
        val launcher = "com.example.launcher"
        val sessions = builder.build(
            listOf(
                foreground(at1400, launcher),
                background(at1500, launcher),
            ),
            window,
            ignoredPackages = setOf(launcher),
        )

        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `without an ignore list everything is treated as usage`() {
        // 默认什么都不排除：测试与没有平台信息的场景下行为不变
        val launcher = "com.example.launcher"
        val sessions = builder.build(
            listOf(
                foreground(at1400, launcher),
                background(at1500, launcher),
            ),
            window,
        )

        assertEquals(1, sessions.size)
        assertEquals(launcher, sessions.single().packageName)
    }

    // ---------------- 短会话与窗口裁剪 ----------------

    @Test
    fun `the threshold can be configured`() {
        val permissive = ForegroundSessionBuilder(minSessionMillis = 1_000L)
        val events = listOf(
            foreground(at1400, "com.a"),
            background(ts("2026-09-12T06:00:05Z"), "com.a"), // 五秒
        )

        assertTrue(builder.build(events, window).isEmpty())
        assertEquals(1, permissive.build(events, window).size)
    }

    @Test
    fun `a session crossing midnight stays whole because the logical day is 4am to 4am`() {
        // 23:00 打开、次日 00:10 关掉：两者属于**同一个逻辑日**（04:00~次日 04:00），
        // 所以这一段必须完整保留。若按自然日切，同样的两小时会被拆成两天各一段。
        val sessions = builder.build(
            listOf(
                foreground(ts("2026-09-12T15:00:00Z"), "com.a"), // 9/12 23:00
                background(ts("2026-09-12T16:10:00Z"), "com.a"), // 9/13 00:10
            ),
            window,
        )

        assertEquals(1, sessions.size)
        assertEquals(ts("2026-09-12T15:00:00Z"), sessions[0].startMillis)
        assertEquals(ts("2026-09-12T16:10:00Z"), sessions[0].endMillisExclusive)
    }

    @Test
    fun `a session running past the window end is clipped at the boundary`() {
        // 03:00 打开、一直用到 05:00：本窗口只到 04:00，
        // 所以留下来的必须是 03:00–04:00；05:00 之后那一段属于下一个逻辑日，
        // 会由那一天的重新分析负责（原始事件两天都在库里）。
        val sessions = builder.build(
            listOf(
                foreground(ts("2026-09-12T19:00:00Z"), "com.a"), // 9/13 03:00
                background(ts("2026-09-12T21:00:00Z"), "com.a"), // 9/13 05:00
            ),
            window,
        )

        assertEquals(1, sessions.size)
        assertEquals(millis(sept13(), 3), sessions[0].startMillis)
        assertEquals(millis(sept13(), 4), sessions[0].endMillisExclusive)
    }

    @Test
    fun `a session outside the window is dropped`() {
        val sessions = builder.build(
            listOf(
                foreground(ts("2026-09-13T02:00:00Z"), "com.a"), // 9/13 10:00，已过窗口
                background(ts("2026-09-13T03:00:00Z"), "com.a"),
            ),
            window,
        )

        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `a session clipped to less than the threshold is dropped`() {
        // 03:59:55 打开、04:00:05 关闭：本窗口内只剩 5 秒（裁剪之后判断阈值，
        // 否则它会被当成一段完整的 10 秒会话留下）
        val sessions = builder.build(
            listOf(
                foreground(ts("2026-09-12T19:59:55Z"), "com.a"),
                background(ts("2026-09-12T20:00:05Z"), "com.a"),
            ),
            window,
        )

        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `sessions with no package name are ignored`() {
        val sessions = builder.build(
            listOf(
                event(UsageEventTypes.ACTIVITY_RESUMED, at1400, null),
                event(UsageEventTypes.ACTIVITY_RESUMED, at1430, "  "),
                foreground(at1500, "com.a"),
                background(ts("2026-09-12T07:30:00Z"), "com.a"),
            ),
            window,
        )

        assertEquals(1, sessions.size)
        assertEquals("com.a", sessions[0].packageName)
    }

    @Test
    fun `no events produce no sessions`() {
        assertTrue(builder.build(emptyList(), window).isEmpty())
    }

    // ---------------- 模块 26：两个真机现象（用户拿手机内置一比就露出来的） ----------------

    @Test
    fun `activity churn inside one app does not split the session`() {
        // 抖音的真机事件流：一秒内 PAUSED/RESUMED/STOPPED 成对出现几十次
        // （视频流、个人页、评论页在互相切换）。
        // 旧模型把这当成「切走」，1h54m 被切成几十段、短于门槛的碎片还被丢掉 —— 只剩 41m；
        // 手机内置显示 1h54m，因为它算的是「只要还有 Activity 在前台」。
        val sessions = builder.build(
            listOf(
                foreground(at1400, "com.douyin", "MainActivity"),
                background(ts("2026-09-12T06:00:30Z"), "com.douyin", "MainActivity"),
                foreground(ts("2026-09-12T06:00:30Z"), "com.douyin", "ProfileActivity"),
                background(ts("2026-09-12T06:01:00Z"), "com.douyin", "ProfileActivity"),
                foreground(ts("2026-09-12T06:01:00Z"), "com.douyin", "MainActivity"),
                background(ts("2026-09-12T06:30:00Z"), "com.douyin", "MainActivity"),
            ),
            window,
        )

        assertEquals(1, sessions.size)
        assertEquals("com.douyin", sessions.single().packageName)
        assertEquals(at1400, sessions.single().startMillis)
        assertEquals(ts("2026-09-12T06:30:00Z"), sessions.single().endMillisExclusive)
    }

    @Test
    fun `a brief blip of another app does not chop the session`() {
        // 用户打王者那 38 分钟里 QQ / 微信 / 桌面不断冒出 RESUMED，每次十几秒 ——
        // 那是通知和后台活动在动，不是「用户切走了」。
        // 短于门槛的段先被丢掉，所以它没有资格抢前台，王者那一段保持完整。
        val sessions = builder.build(
            listOf(
                foreground(at1400, "com.sgame", "GameActivity"),
                foreground(ts("2026-09-12T06:10:00Z"), "com.qq", "SplashActivity"),
                background(ts("2026-09-12T06:10:08Z"), "com.qq", "SplashActivity"),
                background(ts("2026-09-12T06:38:00Z"), "com.sgame", "GameActivity"),
            ),
            window,
        )

        assertEquals(1, sessions.size)
        assertEquals("com.sgame", sessions.single().packageName)
        assertEquals(38 * 60_000L, sessions.single().durationMillis)
    }

    @Test
    fun `sessions come out sorted and never overlap`() {
        val sessions = builder.build(
            listOf(
                foreground(at1400, "com.a"),
                background(ts("2026-09-12T06:20:00Z"), "com.a"),
                foreground(at1430, "com.b"),
                background(ts("2026-09-12T06:50:00Z"), "com.b"),
                foreground(at1500, "com.c"),
                background(ts("2026-09-12T07:30:00Z"), "com.c"),
            ),
            window,
        )

        assertEquals(listOf("com.a", "com.b", "com.c"), sessions.map { it.packageName })
        sessions.zipWithNext().forEach { (first, second) ->
            assertTrue(
                "会话不能重叠：${first.packageName} 结束于 ${first.endMillisExclusive}，"
                    + "${second.packageName} 开始于 ${second.startMillis}",
                first.endMillisExclusive <= second.startMillis,
            )
        }
    }
}
