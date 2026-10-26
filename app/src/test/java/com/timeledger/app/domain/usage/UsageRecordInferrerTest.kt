package com.timeledger.app.domain.usage

import com.timeledger.app.domain.model.AppMapping
import com.timeledger.app.domain.model.RecordSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记录推断测试 —— 「少记录」这件事成立与否，就取决于这张表：
 *
 * | 情况 | 来源 | 需要确认吗 |
 * |---|---|---|
 * | 用户归类过的 App | SYSTEM | 不需要 |
 * | 有归属但没确认过（系统猜的） | SUGGESTED | 需要 |
 * | 没定归属 / 没见过 | SYSTEM | 不需要（去归类页问一次） |
 */
class UsageRecordInferrerTest {

    private val inferrer = UsageRecordInferrer()
    private val day = 20_000L

    private val at1400 = 1_700_000_000_000L
    private val at1500 = at1400 + 3_600_000L

    private fun session(
        pkg: String = "com.a",
        start: Long = at1400,
        end: Long = at1500,
    ) = AppSession(pkg, start, end, SessionEndReason.APP_BACKGROUNDED)

    private fun mapping(
        pkg: String = "com.a",
        categoryId: Long? = 1L,
        activityId: Long? = 10L,
        confidence: Double = 0.5,
        isUserConfirmed: Boolean = false,
        appLabel: String? = "某应用",
    ) = AppMapping(
        id = 1L,
        packageName = pkg,
        appLabel = appLabel,
        categoryId = categoryId,
        activityId = activityId,
        confidence = confidence,
        isUserConfirmed = isUserConfirmed,
    )

    // ---------------- 四种情况 ----------------

    @Test
    fun `a user confirmed mapping becomes a system record needing no confirmation`() {
        val result = inferrer.infer(
            listOf(session()),
            mapOf("com.a" to mapping(isUserConfirmed = true)),
            day,
        ).single()

        assertEquals(RecordSource.SYSTEM, result.source)
        assertEquals(InferenceReason.CONFIRMED_MAPPING, result.reason)
        assertEquals(1.0, result.confidence, 0.0)
        assertEquals(1L, result.draft.categoryId)
        assertEquals(10L, result.draft.activityId)
        // 已经归类过的记录不需要用户再确认
        assertTrue(!result.source.needsConfirmation)
    }

    @Test
    fun `a default mapping becomes a suggestion`() {
        val result = inferrer.infer(
            listOf(session()),
            mapOf("com.a" to mapping(isUserConfirmed = false)),
            day,
        ).single()

        assertEquals(RecordSource.SUGGESTED, result.source)
        assertEquals(InferenceReason.DEFAULT_MAPPING, result.reason)
        assertEquals(UsageRecordInferrer.DEFAULT_MAPPING_CONFIDENCE, result.confidence, 0.0)
        assertTrue(result.source.needsConfirmation)
        assertEquals(1L, result.draft.categoryId)
    }

    @Test
    fun `a mapping without a target is written as a system record and not asked about`() {
        // Chrome 这类 App：可能是查资料，也可能是看小说 —— 需求明确要求不能固定判成娱乐。
        // 但「你用了 Chrome 40 分钟」是可观测的事实，不该因此让用户逐条确认（模块 20：
        // 真机上这样会产生 47 条待确认）。归属留空，把 App 名写进备注，
        // 让用户去**归类页**说一次「Chrome 归到哪」。
        val result = inferrer.infer(
            listOf(session("com.android.chrome")),
            mapOf("com.android.chrome" to mapping(pkg = "com.android.chrome", categoryId = null, activityId = null)),
            day,
        ).single()

        assertEquals(RecordSource.SYSTEM, result.source)
        assertEquals(InferenceReason.UNRESOLVED_MAPPING, result.reason)
        assertNull(result.draft.categoryId)
        assertNull(result.draft.activityId)
        assertEquals(UsageRecordInferrer.UNKNOWN_CONFIDENCE, result.confidence, 0.0)
        assertTrue("不该再逐条问用户", !result.source.needsConfirmation)
    }

    @Test
    fun `an unknown app is written as a system record and not asked about`() {
        // 不写的话用户会看到一段空白，而空白在这款 App 里意味着「没记录」，
        // 他只会以为系统坏了。写，但不问 —— 问 47 次同样的问题没有意义。
        val result = inferrer.infer(listOf(session()), emptyMap(), day).single()

        assertEquals(RecordSource.SYSTEM, result.source)
        assertEquals(InferenceReason.UNKNOWN_APP, result.reason)
        assertNull(result.draft.categoryId)
        assertTrue("不该再逐条问用户", !result.source.needsConfirmation)
    }

    @Test
    fun `only a guessed category still asks the user`() {
        // 唯一值得问的情况：系统猜了归属、但用户没确认过。
        // 这条规则保证「待确认」不会变成一堆噪音 —— 它只装真正有判断价值的问题。
        val guessed = inferrer.infer(
            listOf(session()),
            mapOf("com.a" to mapping(isUserConfirmed = false)),
            day,
        ).single()
        val unknown = inferrer.infer(listOf(session()), emptyMap(), day).single()

        assertTrue(guessed.source.needsConfirmation)
        assertTrue(!unknown.source.needsConfirmation)
    }

    @Test
    fun `a user confirmed app with no target becomes a system record`() {
        // 用户明确说过「这个 App 不归类」（confirmMapping 允许空归属），
        // 那就别再问第二遍：时间本身是事实，写下来即可
        val result = inferrer.infer(
            listOf(session()),
            mapOf("com.a" to mapping(categoryId = null, activityId = null, isUserConfirmed = true)),
            day,
        ).single()

        assertEquals(RecordSource.SYSTEM, result.source)
        assertEquals(InferenceReason.CONFIRMED_MAPPING, result.reason)
        assertNull(result.draft.categoryId)
    }

    // ---------------- 备注与幂等键 ----------------

    @Test
    fun `an unresolved record carries the app name so the timeline can say something`() {
        // 时间轴上「微信」远比「未归类」有用：用户一眼就能说「对，那是社交」
        val result = inferrer.infer(
            listOf(session("com.tencent.mm")),
            mapOf("com.tencent.mm" to mapping(pkg = "com.tencent.mm", appLabel = "微信", categoryId = null, activityId = null)),
            day,
        ).single()

        assertEquals("微信", result.draft.note)
    }

    @Test
    fun `an unknown app falls back to the package name`() {
        val result = inferrer.infer(listOf(session("com.unknown.app")), emptyMap(), day).single()
        assertEquals("com.unknown.app", result.draft.note)
    }

    @Test
    fun `a resolved record still carries the app name`() {
        // 第一版这里是「已归类就不用写 App 名了」。真机上用了一天之后，用户的原话是：
        // 「时间轴只有分类，没有具体某个应用，不知道我这段时间具体做了什么。」
        // 归类解决的是**统计口径**（娱乐两小时），时间轴要回答的是
        // **这段时间具体在干什么**（刷抖音还是打王者）—— 两者不是一回事，
        // 所以 App 名一律写进备注，不管有没有归类。
        val result = inferrer.infer(
            listOf(session()),
            mapOf("com.a" to mapping(isUserConfirmed = true)),
            day,
        ).single()

        assertEquals("某应用", result.draft.note)
        assertEquals(1L, result.draft.categoryId)
    }

    @Test
    fun `the record keeps both the app name and the category`() {
        // 两个都要：备注给「具体在干什么」，分类给统计
        val result = inferrer.infer(
            listOf(session("com.tencent.mm")),
            mapOf("com.tencent.mm" to mapping(pkg = "com.tencent.mm", appLabel = "微信", isUserConfirmed = true)),
            day,
        ).single()

        assertEquals("微信", result.draft.note)
        assertEquals(10L, result.draft.activityId)
    }

    @Test
    fun `the idempotency key includes the day so a session across 4am is not lost`() {
        // 跨 4:00 的会话会被相邻两天各分析一次。键里没有日期的话，
        // 第二天算出的键与第一天相同，写入会被当成重复而跳过 —— 那半天就消失了。
        val one = session(start = at1400)
        val first = inferrer.infer(listOf(one), emptyMap(), dayKey = 20_000L).single()
        val second = inferrer.infer(listOf(one), emptyMap(), dayKey = 20_001L).single()

        assertTrue(first.draft.externalKey != second.draft.externalKey)
        assertEquals("com.a", UsageRecordKeys.packageNameOf(first.draft.externalKey))
        assertTrue(UsageRecordKeys.isUsageKey(first.draft.externalKey))
    }

    @Test
    fun `the idempotency key is stable for the same input`() {
        val first = inferrer.infer(listOf(session()), emptyMap(), day).single()
        val second = inferrer.infer(listOf(session()), emptyMap(), day).single()

        assertEquals(first.draft.externalKey, second.draft.externalKey)
    }

    @Test
    fun `keys from other sources are not mistaken for usage records`() {
        assertTrue(!UsageRecordKeys.isUsageKey(null))
        assertTrue(!UsageRecordKeys.isUsageKey("something-else"))
        assertNull(UsageRecordKeys.packageNameOf("something-else"))
        assertNull(UsageRecordKeys.packageNameOf("usage|1|2"))
    }

    // ---------------- 时间字段直接来自会话 ----------------

    @Test
    fun `time range comes straight from the session`() {
        val result = inferrer.infer(listOf(session(start = at1400, end = at1500)), emptyMap(), day).single()

        assertEquals(at1400, result.draft.startMillis)
        assertEquals(at1500, result.draft.endMillis)
    }

    @Test
    fun `every session produces exactly one record`() {
        val sessions = listOf(
            session("com.a", at1400, at1400 + 600_000L),
            session("com.b", at1400 + 600_000L, at1400 + 900_000L),
        )

        val result = inferrer.infer(sessions, emptyMap(), day)

        assertEquals(2, result.size)
        assertEquals(listOf("com.a", "com.b"), result.map { it.packageName })
    }

    @Test
    fun `no sessions produce no records`() {
        assertTrue(inferrer.infer(emptyList(), emptyMap(), day).isEmpty())
    }
}
