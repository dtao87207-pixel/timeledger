package com.timeledger.app.data.repository

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.core.time.TimeBoundary
import com.timeledger.app.data.local.entity.ActivityEntity
import com.timeledger.app.data.local.entity.CategoryEntity
import com.timeledger.app.domain.model.RecordSource
import com.timeledger.app.domain.model.TimeRecordDraft
import com.timeledger.app.domain.repository.RejectReason
import com.timeledger.app.domain.repository.SaveOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 时间记录仓库测试 —— 模块1 里最需要被验证的一块。
 *
 * 时间基准刻意写死：时区固定上海、`now` 固定为 2026-09-12 14:00（+08:00）。
 * 这样「跨 4:00 边界」「时间戳精确相等」这类断言才是可复现的，
 * 而不是靠「跑的时候恰好不是半夜」。
 */
class TimeRecordRepositoryImplTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private val fixedNow: Long = Instant.parse("2026-09-12T06:00:00Z").toEpochMilli()
    private val sept12: Long = LocalDate.of(2026, 9, 12).toEpochDay()
    private val sept13: Long = LocalDate.of(2026, 9, 13).toEpochDay()
    private val sept11: Long = LocalDate.of(2026, 9, 11).toEpochDay()

    private val dayBoundary = DayBoundary(zoneId = shanghai, nowProvider = { fixedNow })

    private lateinit var categoryDao: FakeCategoryDao
    private lateinit var activityDao: FakeActivityDao
    private lateinit var timeRecordDao: FakeTimeRecordDao
    private lateinit var repository: TimeRecordRepositoryImpl

    private var studyCategoryId: Long = 0L
    private var javaActivityId: Long = 0L
    private var funCategoryId: Long = 0L
    private var bilibiliActivityId: Long = 0L

    // 时间戳统一写成 UTC 字面量再换算，避免心算 +08:00 偏移出错。
    private fun ts(iso: String): Long = Instant.parse(iso).toEpochMilli()
    private val at1400 = ts("2026-09-12T06:00:00Z") // 9/12 14:00 +08:00
    private val at1430 = ts("2026-09-12T06:30:00Z")
    private val at1435 = ts("2026-09-12T06:35:00Z")
    private val at1440 = ts("2026-09-12T06:40:00Z")
    private val at1450 = ts("2026-09-12T06:50:00Z")
    private val at1500 = ts("2026-09-12T07:00:00Z")
    private val at1530 = ts("2026-09-12T07:30:00Z")
    private val at1600 = ts("2026-09-12T08:00:00Z")
    private val at1630 = ts("2026-09-12T08:30:00Z")
    private val at1700 = ts("2026-09-12T09:00:00Z")
    private val at1730 = ts("2026-09-12T09:30:00Z")
    private val at1800 = ts("2026-09-12T10:00:00Z")

    @Before
    fun setUp() {
        categoryDao = FakeCategoryDao()
        activityDao = FakeActivityDao()
        timeRecordDao = FakeTimeRecordDao()
        repository = TimeRecordRepositoryImpl(
            timeRecordDao = timeRecordDao,
            categoryDao = categoryDao,
            activityDao = activityDao,
            dayBoundary = dayBoundary,
        )
        runBlocking {
            studyCategoryId = categoryDao.insert(CategoryEntity(name = "学习", sortOrder = 0))
            javaActivityId = activityDao.insert(
                ActivityEntity(categoryId = studyCategoryId, name = "Java")
            )
            funCategoryId = categoryDao.insert(CategoryEntity(name = "娱乐", sortOrder = 1))
            bilibiliActivityId = activityDao.insert(
                ActivityEntity(categoryId = funCategoryId, name = "B站")
            )
        }
    }

    private fun draft(
        start: Long = at1400,
        end: Long = at1500,
        categoryId: Long? = studyCategoryId,
        activityId: Long? = javaActivityId,
        countValue: Double? = null,
        note: String? = null,
        confidence: Double = 1.0,
        externalKey: String? = null,
    ) = TimeRecordDraft(
        startMillis = start,
        endMillis = end,
        categoryId = categoryId,
        activityId = activityId,
        countValue = countValue,
        note = note,
        confidence = confidence,
        externalKey = externalKey,
    )

    private fun assertSaved(outcome: SaveOutcome): SaveOutcome.Saved {
        // 注意：org.junit.Assert.fail(String) 在 Java 里返回 void，Kotlin 只把它看作 Unit
        // 而不是 Nothing，所以「调用 fail 之后编译器就知道类型」这种写法不成立，
        // 必须显式断言 + 显式转换。
        assertTrue("期望 Saved，实际是 $outcome", outcome is SaveOutcome.Saved)
        return outcome as SaveOutcome.Saved
    }

    private fun assertRejected(outcome: SaveOutcome, reason: RejectReason) {
        assertEquals("拒绝原因不符", SaveOutcome.Rejected(reason), outcome)
    }

    // ---------------- 手动记录 ----------------

    @Test
    fun `manual record is stored as MANUAL with full confidence`() = runTest {
        val saved = assertSaved(
            repository.createManualRecord(
                draft(confidence = 0.2, externalKey = "不应被采用", note = "  看完 Java 并发  ")
            )
        )
        val stored = timeRecordDao.all().single()
        assertEquals(saved.id, stored.id)
        assertEquals(RecordSource.MANUAL.name, stored.source)
        assertEquals(1.0, stored.confidence, 0.0)
        // 手动记录不允许带幂等键，否则会污染系统采集的去重口径
        assertNull(stored.externalKey)
        assertEquals("看完 Java 并发", stored.note)
        assertEquals(fixedNow, stored.createdAtMillis)
        assertEquals(fixedNow, stored.updatedAtMillis)
    }

    @Test
    fun `manual record auto fills category from its activity`() = runTest {
        assertSaved(repository.createManualRecord(draft(categoryId = null, activityId = javaActivityId)))
        assertEquals(studyCategoryId, timeRecordDao.all().single().categoryId)
    }

    @Test
    fun `manual record rejects mismatched category and activity`() = runTest {
        val outcome = repository.createManualRecord(
            draft(categoryId = funCategoryId, activityId = javaActivityId)
        )
        assertRejected(outcome, RejectReason.ACTIVITY_CATEGORY_MISMATCH)
        assertTrue(timeRecordDao.all().isEmpty())
    }

    @Test
    fun `manual record rejects reversed time range`() = runTest {
        val outcome = repository.createManualRecord(draft(start = at1500, end = at1400))
        assertRejected(outcome, RejectReason.INVALID_TIME_RANGE)
        assertTrue(timeRecordDao.all().isEmpty())
    }

    @Test
    fun `manual record rejects archived activity`() = runTest {
        activityDao.archive(javaActivityId, fixedNow)
        val outcome = repository.createManualRecord(draft())
        assertRejected(outcome, RejectReason.UNKNOWN_CATEGORY_OR_ACTIVITY)
    }

    @Test
    fun `manual record rejects unknown category`() = runTest {
        val outcome = repository.createManualRecord(draft(categoryId = 9999L, activityId = null))
        assertRejected(outcome, RejectReason.UNKNOWN_CATEGORY_OR_ACTIVITY)
    }

    @Test
    fun `instant manual record is allowed and counts zero duration`() = runTest {
        // 「今天做了 2 题 LeetCode，不关心耗时」就是零长度记录
        assertSaved(
            repository.createManualRecord(
                draft(start = at1400, end = at1400, countValue = 2.0, note = null)
            )
        )
        val stored = timeRecordDao.all().single()
        assertEquals(0L, stored.endMillis - stored.startMillis)
        assertEquals(2.0, stored.countValue!!, 0.0)
    }

    @Test
    fun `manual record rejects negative count`() = runTest {
        val outcome = repository.createManualRecord(
            draft(start = at1400, end = at1400, countValue = -1.0)
        )
        assertRejected(outcome, RejectReason.INVALID_COUNT_VALUE)
    }

    // ---------------- 系统记录 ----------------

    @Test
    fun `system record rejects manual source`() = runTest {
        val error = runCatching {
            repository.saveSystemRecord(draft(externalKey = "sys-1"), RecordSource.MANUAL)
        }.exceptionOrNull()
        assertTrue("非系统来源应当被 require 挡下", error is IllegalArgumentException)
    }

    @Test
    fun `system record requires an external key`() = runTest {
        val outcome = repository.saveSystemRecord(draft(externalKey = null), RecordSource.SYSTEM)
        assertRejected(outcome, RejectReason.MISSING_EXTERNAL_KEY)
        assertTrue(timeRecordDao.all().isEmpty())
    }

    @Test
    fun `system record is idempotent by external key`() = runTest {
        assertSaved(repository.saveSystemRecord(draft(externalKey = "sys-1"), RecordSource.SYSTEM))
        val second = repository.saveSystemRecord(draft(externalKey = "sys-1"), RecordSource.SYSTEM)
        assertEquals(SaveOutcome.Skipped, second)
        assertEquals(1, timeRecordDao.all().size)
    }

    @Test
    fun `system record must not overwrite a manual record`() = runTest {
        // 用户手动记了 14:00-15:00 Java
        assertSaved(repository.createManualRecord(draft()))

        // 系统检测到 14:30-15:30 使用 B站，与手动记录重叠 —— 必须被拒绝
        val outcome = repository.saveSystemRecord(
            draft(
                start = at1430,
                end = at1530,
                // 注意分类要跟着活动走：只改 activityId 而沿用默认的「学习」分类，
                // 会被判成「活动与分类不一致」，先于重叠检查被拒绝，测的就不是同一件事了。
                categoryId = funCategoryId,
                activityId = bilibiliActivityId,
                externalKey = "evt-1",
            ),
            RecordSource.SYSTEM,
        )
        assertRejected(outcome, RejectReason.OVERLAPS_MANUAL_RECORD)

        // 库里仍然只有用户那一条，且内容未被改动
        val stored = timeRecordDao.all().single()
        assertEquals(RecordSource.MANUAL.name, stored.source)
        assertEquals(studyCategoryId, stored.categoryId)
        assertEquals(at1500, stored.endMillis)
    }

    @Test
    fun `system record is rejected even when only touching the tail of a manual record`() = runTest {
        assertSaved(repository.createManualRecord(draft(start = at1400, end = at1500)))
        val outcome = repository.saveSystemRecord(
            draft(start = at1450, end = at1600, externalKey = "evt-2"),
            RecordSource.SYSTEM,
        )
        assertRejected(outcome, RejectReason.OVERLAPS_MANUAL_RECORD)
    }

    @Test
    fun `system records may overlap each other`() = runTest {
        assertSaved(
            repository.saveSystemRecord(
                draft(start = at1400, end = at1500, externalKey = "evt-a"), RecordSource.SYSTEM
            )
        )
        assertSaved(
            repository.saveSystemRecord(
                draft(start = at1430, end = at1530, externalKey = "evt-b"), RecordSource.SYSTEM
            )
        )
        assertEquals(2, timeRecordDao.all().size)
    }

    @Test
    fun `system record is allowed right after a manual record ends`() = runTest {
        // 手写 14:00-15:00，系统记录从 15:00 开始 —— 端点相接不算重叠（左闭右开）
        assertSaved(repository.createManualRecord(draft(start = at1400, end = at1500)))
        assertSaved(
            repository.saveSystemRecord(
                draft(start = at1500, end = at1600, externalKey = "evt-after"), RecordSource.SYSTEM
            )
        )
        assertEquals(2, timeRecordDao.all().size)
    }

    @Test
    fun `re-analysis revives the soft deleted row instead of being silently skipped`() = runTest {
        // 第一次分析：14:00-14:35 Java，来源 SUGGESTED
        val first = assertSaved(
            repository.saveSystemRecord(
                draft(start = at1400, end = at1435, externalKey = "evt-1"), RecordSource.SUGGESTED
            )
        )

        // 模拟「重新分析这一天」：先把这条软删除
        timeRecordDao.softDelete(first.id, fixedNow)
        assertTrue(repository.getRecordsIn(dayBoundary.dayRange(sept12)).isEmpty())

        // 再用**同一个幂等键**写入修正后的结果（时长改成 14:00-14:50）
        //
        // 这是最容易踩的坑：软删除只是把 is_deleted 置 1，external_key 仍然占着唯一索引。
        // 如果实现里走的是 insertIgnore，这里会静默返回 -1，
        // 新结果一条都写不进去，而界面上看起来「什么都没发生」。
        val replaced = assertSaved(
            repository.saveSystemRecord(
                draft(start = at1400, end = at1450, externalKey = "evt-1"), RecordSource.SUGGESTED
            )
        )

        // 复用同一行，并且真的活着、内容已更新
        assertEquals(first.id, replaced.id)
        val live = repository.getRecordsIn(dayBoundary.dayRange(sept12))
        assertEquals(1, live.size)
        assertEquals(at1450, live.single().endMillis)
        assertFalse(live.single().isDeleted)
    }

    // ---------------- 重新分析整段区间 ----------------

    @Test
    fun `replaceSystemGeneratedIn keeps manual and merged records while rewriting suggestions`() = runTest {
        // 用户手动记录 14:00-15:00
        assertSaved(repository.createManualRecord(draft(start = at1400, end = at1500)))

        // 用户确认过的一条记录（SYSTEM 写入后被用户改成 MERGED）16:00-16:30
        val toConfirm = assertSaved(
            repository.saveSystemRecord(
                draft(start = at1600, end = at1630, externalKey = "evt-confirmed"), RecordSource.SYSTEM
            )
        )
        assertSaved(repository.updateRecord(toConfirm.id, draft(start = at1600, end = at1630)))

        // 一条待确认的推测记录 17:00-18:00
        assertSaved(
            repository.saveSystemRecord(
                draft(start = at1700, end = at1800, externalKey = "evt-old"), RecordSource.SUGGESTED
            )
        )

        val savedCount = repository.replaceSystemGeneratedIn(
            range = dayBoundary.dayRange(sept12),
            drafts = listOf(
                draft(
                    start = at1700,
                    end = at1730,
                    // 分类必须与活动一致，理由同上一条测试
                    categoryId = funCategoryId,
                    activityId = bilibiliActivityId,
                    externalKey = "evt-new",
                )
            ),
            source = RecordSource.SUGGESTED,
        )
        assertEquals(1, savedCount)

        val live = repository.getRecordsIn(dayBoundary.dayRange(sept12))
        // 剩下：手动 1 条 + MERGED 1 条 + 新的推测 1 条
        assertEquals(3, live.size)
        assertEquals(1, live.count { it.source == RecordSource.MANUAL })
        assertEquals(1, live.count { it.source == RecordSource.MERGED })
        assertEquals(1, live.count { it.source == RecordSource.SUGGESTED })
        // 旧的推测记录被换成新的内容
        assertEquals(at1730, live.first { it.source == RecordSource.SUGGESTED }.endMillis)
    }

    @Test
    fun `clearing a day leaves the previous day record that ends exactly on the boundary`() = runTest {
        // 真机复现出来的场景：一夜的熄屏 9/12 00:43 → 08:20 被 4:00 的日志边界切成两段。
        //
        // 前一段 [00:43, 04:00] 按「开始时刻归属」属于 9/11 那一天，
        // 但它的**结束时刻正好等于 9/12 窗口的起点** ——
        // 用「时间段重叠」判据清理 9/12 时它会一起被删掉，
        // 界面上表现为「前一天夜里那段熄屏，每分析一次次日就消失一次」。
        val boundary = dayBoundary.dayRange(sept12).startMillis // 9/12 04:00 +08:00
        val nightStart = ts("2026-09-11T16:43:00Z")            // 9/12 00:43 +08:00
        val morningEnd = ts("2026-09-12T00:20:00Z")            // 9/12 08:20 +08:00

        // 前一天的那一段（属于 9/11）
        assertSaved(
            repository.saveSystemRecord(
                draft(start = nightStart, end = boundary, externalKey = "screen-off|night"),
                RecordSource.SYSTEM,
            )
        )
        // 当天的那一段（属于 9/12），重新分析 9/12 时它**应该**被清掉
        assertSaved(
            repository.saveSystemRecord(
                draft(start = boundary, end = morningEnd, externalKey = "screen-off|morning"),
                RecordSource.SYSTEM,
            )
        )

        val cleared = repository.clearSystemGeneratedIn(dayBoundary.dayRange(sept12))
        assertEquals(1, cleared)

        // 前一天那段必须活着，而且内容一点没变
        val previousDay = repository.getRecordsIn(dayBoundary.dayRange(sept11))
        assertEquals(1, previousDay.size)
        assertEquals(nightStart, previousDay.single().startMillis)
        assertEquals(boundary, previousDay.single().endMillis)

        // 当天那段被清掉了，等重新分析把它写回来
        assertTrue(repository.getRecordsIn(dayBoundary.dayRange(sept12)).isEmpty())
    }

    // ---------------- 修改与删除 ----------------

    @Test
    fun `editing a system record turns it into merged`() = runTest {
        val saved = assertSaved(
            repository.saveSystemRecord(
                draft(start = at1400, end = at1435, externalKey = "evt-1"), RecordSource.SYSTEM
            )
        )
        assertSaved(repository.updateRecord(saved.id, draft(start = at1400, end = at1440)))

        val stored = timeRecordDao.all().single()
        assertEquals(RecordSource.MERGED.name, stored.source)
        assertEquals(1.0, stored.confidence, 0.0)
        assertEquals(at1440, stored.endMillis)
    }

    @Test
    fun `editing a manual record keeps it manual`() = runTest {
        val saved = assertSaved(repository.createManualRecord(draft(start = at1400, end = at1435)))
        assertSaved(repository.updateRecord(saved.id, draft(start = at1400, end = at1440)))
        // 手动记录不能因为一次编辑被降级成 MERGED
        assertEquals(RecordSource.MANUAL.name, timeRecordDao.all().single().source)
    }

    @Test
    fun `editing rejects unknown record`() = runTest {
        val outcome = repository.updateRecord(4242L, draft())
        assertRejected(outcome, RejectReason.RECORD_NOT_FOUND)
    }

    @Test
    fun `deleted record cannot be edited`() = runTest {
        val saved = assertSaved(repository.createManualRecord(draft()))
        assertTrue(repository.deleteRecord(saved.id))
        assertRejected(repository.updateRecord(saved.id, draft()), RejectReason.RECORD_NOT_FOUND)
    }

    @Test
    fun `deleting soft deletes and hides the record from queries`() = runTest {
        val saved = assertSaved(repository.createManualRecord(draft()))
        assertTrue(repository.deleteRecord(saved.id))

        assertTrue(repository.getRecordsIn(dayBoundary.dayRange(sept12)).isEmpty())
        // 行还在（软删除），只是被查询过滤掉了 —— 便于撤销与排查
        assertEquals(1, timeRecordDao.all().size)
        assertTrue(timeRecordDao.all().single().isDeleted)
    }

    @Test
    fun `deleting twice is idempotent and unknown id reports false`() = runTest {
        val saved = assertSaved(repository.createManualRecord(draft()))
        assertTrue(repository.deleteRecord(saved.id))
        assertTrue(repository.deleteRecord(saved.id))
        assertFalse(repository.deleteRecord(4242L))
    }

    // ---------------- 跨 4:00 边界 ----------------

    @Test
    fun `record after midnight belongs to the previous logical day`() = runTest {
        // 需求原文的例子：9月13日 01:00 的活动算作 9月12日 这一天
        val oneAmNextDay = ts("2026-09-12T17:00:00Z") // 9/13 01:00 +08:00
        val twoAmNextDay = ts("2026-09-12T18:00:00Z") // 9/13 02:00 +08:00
        assertSaved(
            repository.createManualRecord(draft(start = oneAmNextDay, end = twoAmNextDay))
        )

        assertEquals(1, repository.getRecordsIn(dayBoundary.dayRange(sept12)).size)
        assertTrue(repository.getRecordsIn(dayBoundary.dayRange(sept13)).isEmpty())
    }

    @Test
    fun `record that crosses the boundary is stored once and clipped per day`() = runTest {
        // 01:00-08:00 的睡眠，跨过了 9/13 04:00 这条逻辑日分界线
        val start = ts("2026-09-12T17:00:00Z") // 9/13 01:00 +08:00
        val end = ts("2026-09-13T00:00:00Z")   // 9/13 08:00 +08:00
        assertSaved(repository.createManualRecord(draft(start = start, end = end)))

        // 1) 存储不拆分：库里就一条，不能因为跨日被切成两条
        assertEquals(1, timeRecordDao.all().size)

        // 2) 归属按 startMillis：这条记录属于 9月12日
        assertEquals(sept12, dayBoundary.dayKeyOf(start))

        // 3) 区间查询按「有交集」返回，所以两天的查询都能看到它 —— 这是刻意的，
        //    代价是调用方必须自己裁剪到当天窗口再统计，不能直接相加。
        val sept12Window = dayBoundary.dayRange(sept12)
        val sept13Window = dayBoundary.dayRange(sept13)
        val record = repository.getRecordsIn(sept12Window).single()
        assertEquals(1, repository.getRecordsIn(sept13Window).size)

        // 4) 裁剪后分摊：9/12 得 01:00-04:00 共 3 小时，9/13 得 04:00-08:00 共 4 小时，
        //    相加正好等于总时长 7 小时 —— 不重复也不遗漏。
        val range = MillisRange(record.startMillis, record.endMillis)
        assertEquals(3L * 3600 * 1000, range.intersect(sept12Window)!!.durationMillis)
        assertEquals(4L * 3600 * 1000, range.intersect(sept13Window)!!.durationMillis)
        assertEquals(7L * 3600 * 1000, range.durationMillis)

        // 5) 反过来说：如果不裁剪直接相加，两天加起来会有 14 小时 —— 这正是要避免的错误。
        assertEquals(24L * 3600 * 1000, sept12Window.durationMillis)
        assertTrue(range.durationMillis > sept12Window.durationMillis / 4)
    }

    @Test
    fun `instant record exactly at the day start is included`() = runTest {
        // 零长度记录落在区间起点上，用「重叠」判定会被漏掉，因此 SQL 里补了特例。
        // 这条测试同时锁住替身与 SQL 的口径一致。
        val dayStart = dayBoundary.dayRange(sept12).startMillis
        assertSaved(repository.createManualRecord(draft(start = dayStart, end = dayStart)))
        assertEquals(1, repository.getRecordsIn(dayBoundary.dayRange(sept12)).size)
    }

    @Test
    fun `observeRecordsOfDay follows the injected day boundary`() = runTest {
        assertSaved(repository.createManualRecord(draft(start = at1400, end = at1500)))
        val records = repository.observeRecordsOfDay(sept12).first()
        assertEquals(1, records.size)
        assertEquals(at1400, records.single().startMillis)
        // 同一条记录不会同时出现在相邻的逻辑日里
        assertTrue(repository.observeRecordsOfDay(sept13).first().isEmpty())
    }

    @Test
    fun `overlapping manual records are reported for the explain UI`() = runTest {
        assertSaved(repository.createManualRecord(draft(start = at1400, end = at1500)))
        assertSaved(repository.createManualRecord(draft(start = at1430, end = at1530)))

        val overlapping = repository.getOverlappingManualRecords(MillisRange(at1430, at1440))
        // 用户自己允许重复记录（例如两件事同时在发生），两条都要能被找出来解释给用户
        assertEquals(2, overlapping.size)
    }

    @Test
    fun `day range used by the repository matches TimeBoundary`() = runTest {
        val expected = TimeBoundary.dayRange(sept12, shanghai)
        assertEquals(expected, dayBoundary.dayRange(sept12))
    }
}
