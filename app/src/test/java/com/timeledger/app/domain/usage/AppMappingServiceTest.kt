package com.timeledger.app.domain.usage

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.domain.model.AppMapping
import com.timeledger.app.domain.model.RecordSource
import com.timeledger.app.domain.model.UsageEvent
import com.timeledger.app.domain.repository.SaveOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * App 归类服务测试（需求九节）。
 *
 * 「最近用过哪个 App、打开了几次」看着简单，但有几个地方很容易写错：
 * 只该算**前台**事件（熄屏、锁屏也发事件，且没有包名）、只该算窗口内、
 * 排序要让用户先看到最该处理的。这些都在这里钉死。
 */
class AppMappingServiceTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    /** 2026-09-12 22:00（+08:00）。 */
    private val fixedNow: Long = Instant.parse("2026-09-12T14:00:00Z").toEpochMilli()

    private val dayBoundary = DayBoundary(zoneId = shanghai, nowProvider = { fixedNow })

    private lateinit var appMappingHost: FakeAppMappingHost
    private lateinit var usageEventHost: FakeUsageEventHost
    private lateinit var service: AppMappingService

    private val wechat = "com.tencent.mm"
    private val chrome = "com.android.chrome"

    private fun ts(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun foreground(pkg: String, atMillis: Long) = UsageEvent(
        packageName = pkg,
        eventType = UsageEventTypes.ACTIVITY_RESUMED,
        timestampMillis = atMillis,
        eventKey = UsageEvent.buildEventKey(UsageEventTypes.ACTIVITY_RESUMED, atMillis, pkg),
    )

    private fun paused(pkg: String, atMillis: Long) = UsageEvent(
        packageName = pkg,
        eventType = UsageEventTypes.ACTIVITY_PAUSED,
        timestampMillis = atMillis,
        eventKey = UsageEvent.buildEventKey(UsageEventTypes.ACTIVITY_PAUSED, atMillis, pkg),
    )

    private fun screenOff(atMillis: Long) = UsageEvent(
        packageName = null,
        eventType = UsageEventTypes.SCREEN_NON_INTERACTIVE,
        timestampMillis = atMillis,
        eventKey = UsageEvent.buildEventKey(UsageEventTypes.SCREEN_NON_INTERACTIVE, atMillis, null),
    )

    @Before
    fun setUp() {
        appMappingHost = FakeAppMappingHost()
        usageEventHost = FakeUsageEventHost()
        service = AppMappingService(
            appMappingRepository = appMappingHost,
            usageEventRepository = usageEventHost,
            dayBoundary = dayBoundary,
        )
    }

    // ---------------- 最近使用 ----------------

    @Test
    fun `the latest foreground moment and open count are reported per package`() = runTest {
        // 微信两次独立访问（都正常结束），Chrome 一次
        usageEventHost.appendEvents(
            listOf(
                foreground(wechat, ts("2026-09-12T06:00:00Z")), // 14:00
                paused(wechat, ts("2026-09-12T06:20:00Z")),
                foreground(wechat, ts("2026-09-12T08:00:00Z")), // 16:00
                paused(wechat, ts("2026-09-12T08:40:00Z")),
                foreground(chrome, ts("2026-09-12T07:00:00Z")), // 15:00
                paused(chrome, ts("2026-09-12T07:30:00Z")),
            )
        )

        val hints = service.usageHints()

        // 「最近用过」指的是**用到什么时候**，所以取会话结束时刻：
        // 一次 20:00~21:30 的使用，说「最近 20:00 用过」是错的
        assertEquals(ts("2026-09-12T08:40:00Z"), hints.getValue(wechat).lastForegroundMillis)
        assertEquals(2, hints.getValue(wechat).sessionCount)
        assertEquals(60 * 60_000L, hints.getValue(wechat).foregroundMillis)
        assertEquals(1, hints.getValue(chrome).sessionCount)
    }

    @Test
    fun `an app still in use counts up to now`() = runTest {
        // 只有 RESUMED、没有退场：用户此刻还在用，最近用过就是现在
        usageEventHost.appendEvents(listOf(foreground(wechat, ts("2026-09-12T06:00:00Z"))))

        val hint = service.usageHints().getValue(wechat)

        assertEquals(fixedNow, hint.lastForegroundMillis)
        assertEquals(1, hint.sessionCount)
    }

    @Test
    fun `a quick switch back counts as one visit and reports its duration`() = runTest {
        // 切出去 5 秒又回来：算一次访问，时长连着算（与首页时间轴同一口径）。
        // 5 秒低于 10 秒门槛 —— 这种插曲不会成为记录，所以可以被安全地并掉。
        usageEventHost.appendEvents(
            listOf(
                foreground(wechat, ts("2026-09-12T06:00:00Z")),
                foreground(chrome, ts("2026-09-12T06:10:00Z")),
                UsageEvent(
                    packageName = chrome,
                    eventType = UsageEventTypes.ACTIVITY_PAUSED,
                    timestampMillis = ts("2026-09-12T06:10:05Z"),
                    eventKey = UsageEvent.buildEventKey(
                        UsageEventTypes.ACTIVITY_PAUSED, ts("2026-09-12T06:10:05Z"), chrome,
                    ),
                ),
                foreground(wechat, ts("2026-09-12T06:10:05Z")),
                UsageEvent(
                    packageName = wechat,
                    eventType = UsageEventTypes.ACTIVITY_PAUSED,
                    timestampMillis = ts("2026-09-12T06:30:00Z"),
                    eventKey = UsageEvent.buildEventKey(
                        UsageEventTypes.ACTIVITY_PAUSED, ts("2026-09-12T06:30:00Z"), wechat,
                    ),
                ),
            )
        )

        val hints = service.usageHints()
        val wechatHint = hints.getValue(wechat)

        assertEquals(1, wechatHint.sessionCount)
        // 06:00 → 06:30 = 30 分钟（中间那 5 秒的插曲被合并进这一段）
        assertEquals(30 * 60_000L, wechatHint.foregroundMillis)
        assertTrue(wechatHint.isWorthAsking())
        // Chrome 只闪了 5 秒，低于阈值，会被丢掉
        assertTrue(hints[chrome] == null)
    }

    @Test
    fun `a half-minute detour becomes its own visit instead of being absorbed`() = runTest {
        // 30 秒 ≥ 10 秒门槛 —— 它自己是一条记录，所以不能被并进微信：
        // 那等于把「在看 Chrome」的 30 秒算成微信时长（模块 24 把门槛降到 10 秒之后的行为）
        usageEventHost.appendEvents(
            listOf(
                foreground(wechat, ts("2026-09-12T06:00:00Z")),
                foreground(chrome, ts("2026-09-12T06:10:00Z")),
                UsageEvent(
                    packageName = chrome,
                    eventType = UsageEventTypes.ACTIVITY_PAUSED,
                    timestampMillis = ts("2026-09-12T06:10:30Z"),
                    eventKey = UsageEvent.buildEventKey(
                        UsageEventTypes.ACTIVITY_PAUSED, ts("2026-09-12T06:10:30Z"), chrome,
                    ),
                ),
                foreground(wechat, ts("2026-09-12T06:10:30Z")),
                UsageEvent(
                    packageName = wechat,
                    eventType = UsageEventTypes.ACTIVITY_PAUSED,
                    timestampMillis = ts("2026-09-12T06:30:00Z"),
                    eventKey = UsageEvent.buildEventKey(
                        UsageEventTypes.ACTIVITY_PAUSED, ts("2026-09-12T06:30:00Z"), wechat,
                    ),
                ),
            )
        )

        val hints = service.usageHints()

        assertEquals(2, hints.getValue(wechat).sessionCount)
        // 微信：06:00–06:10 + 06:10:30–06:30 = 29.5 分钟（那 30 秒不再算在它头上）
        assertEquals(29 * 60_000L + 30_000L, hints.getValue(wechat).foregroundMillis)
        assertEquals(30 * 1000L, hints.getValue(chrome).foregroundMillis)
    }

    @Test
    fun `tiny usage is not worth asking about`() = runTest {
        usageEventHost.appendEvents(
            listOf(
                foreground(wechat, ts("2026-09-12T06:00:00Z")),
                UsageEvent(
                    packageName = wechat,
                    eventType = UsageEventTypes.ACTIVITY_PAUSED,
                    timestampMillis = ts("2026-09-12T06:02:00Z"),
                    eventKey = UsageEvent.buildEventKey(
                        UsageEventTypes.ACTIVITY_PAUSED, ts("2026-09-12T06:02:00Z"), wechat,
                    ),
                ),
            )
        )

        val hint = service.usageHints().getValue(wechat)

        assertEquals(2 * 60_000L, hint.foregroundMillis)
        // 2 分钟 < 10 分钟：照样记录，但不值得请用户归类
        assertTrue(!hint.isWorthAsking())
    }

    @Test
    fun `the asking threshold is ten minutes`() {
        assertEquals(10 * 60 * 1000L, AppUsageHint.WORTH_ASKING_MILLIS)
    }

    @Test
    fun `only foreground events count`() = runTest {
        // 熄屏、锁屏这类事件没有包名，也不代表「用户打开了某个 App」。
        // 混进来会让「打开 12 次」这句话不成立。
        usageEventHost.appendEvents(
            listOf(
                foreground(wechat, ts("2026-09-12T06:00:00Z")),
                screenOff(ts("2026-09-12T06:05:00Z")),
                UsageEvent(
                    packageName = wechat,
                    eventType = UsageEventTypes.ACTIVITY_PAUSED,
                    timestampMillis = ts("2026-09-12T06:10:00Z"),
                    eventKey = UsageEvent.buildEventKey(
                        UsageEventTypes.ACTIVITY_PAUSED, ts("2026-09-12T06:10:00Z"), wechat,
                    ),
                ),
            )
        )

        val hints = service.usageHints()

        assertEquals(1, hints.getValue(wechat).sessionCount)
        // 模块 26 之后熄屏不再替应用收尾（这台 ROM 在视频播放时也会发熄屏事件），
        // 所以这一段一直开到应用自己的 PAUSED（06:10）
        assertEquals(ts("2026-09-12T06:10:00Z"), hints.getValue(wechat).lastForegroundMillis)
    }

    @Test
    fun `packages unseen in the window are simply absent`() = runTest {
        // 七天前的使用不该出现在「最近用过」里
        usageEventHost.appendEvents(listOf(foreground(wechat, ts("2026-09-01T06:00:00Z"))))

        assertTrue(service.usageHints().isEmpty())
    }

    @Test
    fun `the window is configurable`() = runTest {
        usageEventHost.appendEvents(listOf(foreground(wechat, ts("2026-09-01T06:00:00Z"))))

        val patient = AppMappingService(
            appMappingRepository = appMappingHost,
            usageEventRepository = usageEventHost,
            dayBoundary = dayBoundary,
            lookbackDays = 30,
        )

        assertEquals(1, patient.usageHints().getValue(wechat).sessionCount)
    }

    @Test
    fun `no events means no hints`() = runTest {
        assertTrue(service.usageHints().isEmpty())
    }

    // ---------------- 指定归属 ----------------

    @Test
    fun `assigning a binding is remembered as a user decision`() = runTest {
        appMappingHost.put(AppMapping(packageName = wechat))

        val outcome = service.assign(wechat, categoryId = 1L, activityId = 2L)

        assertTrue(outcome is SaveOutcome.Saved)
        val mapping = appMappingHost.getMapping(wechat)!!
        assertEquals(1L, mapping.categoryId)
        assertEquals(2L, mapping.activityId)
        assertTrue("用户说过的归属必须被标成用户确认，否则推断层还会继续问他", mapping.isUserConfirmed)
    }

    @Test
    fun `saying not to classify is also remembered`() = runTest {
        // 「这个 App 不用归类」是一个明确的决定，值得被记住：
        // 之后推断层会直接写成 SYSTEM，不再拿它来问用户
        appMappingHost.put(AppMapping(packageName = chrome))

        val outcome = service.assign(chrome, categoryId = null, activityId = null)

        assertTrue(outcome is SaveOutcome.Saved)
        val mapping = appMappingHost.getMapping(chrome)!!
        assertTrue(mapping.isUnresolved)
        assertTrue(mapping.isUserConfirmed)
    }

    @Test
    fun `the inference layer then stops asking about a confirmed app`() {
        // 这条把「归类一次、之后自动」这个承诺钉在两层之间：
        // 已确认的映射 → SYSTEM 记录 → 不进待确认列表
        val mapping = AppMapping(
            packageName = wechat,
            categoryId = 1L,
            activityId = 2L,
            confidence = 1.0,
            isUserConfirmed = true,
        )
        val session = AppSession(wechat, 1_000L, 2_000L, SessionEndReason.APP_BACKGROUNDED)

        val inferred = UsageRecordInferrer().infer(listOf(session), mapOf(wechat to mapping), 20_000L).single()

        assertEquals(RecordSource.SYSTEM, inferred.source)
        assertTrue(!inferred.source.needsConfirmation)
    }

    @Test
    fun `assigning to a package without a mapping row fails instead of silently succeeding`() = runTest {
        val outcome = service.assign("com.never.seen", categoryId = 1L, activityId = null)

        assertTrue(outcome is SaveOutcome.Rejected)
    }

    // ---------------- 订阅 ----------------

    @Test
    fun `mappings are observed so the list updates right after a change`() = runBlocking {
        appMappingHost.put(AppMapping(packageName = wechat))
        assertEquals(1, service.observeMappings().first().size)

        appMappingHost.put(AppMapping(packageName = chrome))

        assertEquals(2, service.observeMappings().first().size)
        assertNull(appMappingHost.getMapping("com.absent"))
    }

    @Test
    fun `the default lookback is a week`() {
        assertEquals(7L, AppMappingService.DEFAULT_LOOKBACK_DAYS)
    }
}
