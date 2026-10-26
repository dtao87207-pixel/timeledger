package com.timeledger.app.data.repository

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.data.local.dao.UsageEventDao
import com.timeledger.app.data.mapper.toDomain
import com.timeledger.app.data.mapper.toEntity
import com.timeledger.app.domain.model.UsageEvent
import com.timeledger.app.domain.repository.UsageEventRepository

/**
 * 系统原始事件仓库实现。
 *
 * 刻意做得很薄：不解释事件类型、不推断行为、不做聚合。
 * 唯一需要动脑的地方是幂等 —— 依靠 event_key 唯一索引 + `INSERT OR IGNORE`，
 * 而不是「先查有没有再插」：后者在补采与并发重试下有竞态窗口，
 * 会出现两条一模一样的事件。
 */
class UsageEventRepositoryImpl(
    private val usageEventDao: UsageEventDao,
    private val dayBoundary: DayBoundary,
) : UsageEventRepository {

    override suspend fun appendEvents(events: List<UsageEvent>): Int {
        if (events.isEmpty()) return 0
        val now = dayBoundary.nowMillis()
        val rows = events.map { event -> event.copy(createdAtMillis = now).toEntity() }
        // Room 的 IGNORE 策略对冲突行返回 -1，正好用来统计「本次实际新增多少条」。
        return usageEventDao.insertAllIgnore(rows).count { it != -1L }
    }

    override suspend fun getEventsIn(range: MillisRange): List<UsageEvent> =
        usageEventDao.getInRange(range.startMillis, range.endMillisExclusive)
            .map { it.toDomain() }

    override suspend fun getLatestEventMillis(): Long? = usageEventDao.getMaxTimestampMillis()

    override suspend fun pruneOlderThan(beforeMillis: Long): Int =
        usageEventDao.deleteOlderThan(beforeMillis)

    override suspend fun count(): Int = usageEventDao.count()
}
