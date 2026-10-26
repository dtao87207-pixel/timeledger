package com.timeledger.app.data.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.model.UsageEvent
import com.timeledger.app.domain.usage.UsageEventReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `UsageStatsManager` 到领域事件的适配器 —— **这一层是整条系统感知链路上唯一需要真机验证的部分**。
 *
 * ## 一个必须写清楚的坑：`getNextEvent` 复用同一个对象
 * `UsageEvents.getNextEvent(event)` 是把下一条事件**填进你传进去的那个对象**里，
 * 而不是返回一个新对象。所以每次循环都必须立刻把字段拷出来，
 * 否则整个列表会是同一个对象、内容全是最后一条 ——
 * 这种错误编译通过、运行不崩，只是时间轴莫名其妙全变成同一个 App。
 *
 * ## 为什么不按 04:00 分界线拆查询
 * 查询区间由调用方给出（`UsageSyncService` 从窗口起点往前读），
 * 这里只管「要什么给什么」。会话怎么切成两天，是分析层的事。
 */
class AndroidUsageEventReader(
    private val context: Context,
) : UsageEventReader {

    override suspend fun isGranted(): Boolean = UsageAccess.isGranted(context)

    override suspend fun read(range: MillisRange): List<UsageEvent> = withContext(Dispatchers.IO) {
        if (!isGranted()) return@withContext emptyList()

        val manager = context.getSystemService(UsageStatsManager::class.java)
            ?: return@withContext emptyList()

        // 系统不让读时返回 null 或空集合，两种都按「没有数据」处理
        val events = try {
            manager.queryEvents(range.startMillis, range.endMillisExclusive)
        } catch (e: SecurityException) {
            return@withContext emptyList()
        } ?: return@withContext emptyList()

        val cursor = UsageEvents.Event()
        val result = ArrayList<UsageEvent>()

        while (events.hasNextEvent()) {
            events.getNextEvent(cursor)

            // 立刻取值：cursor 会在下一次 getNextEvent 时被覆盖
            val eventType = cursor.eventType
            val timestampMillis = cursor.timeStamp
            val packageName = cursor.packageName
            val className = cursor.className

            result += UsageEvent(
                packageName = packageName,
                className = className,
                eventType = eventType,
                timestampMillis = timestampMillis,
                eventKey = UsageEvent.buildEventKey(eventType, timestampMillis, packageName),
            )
        }

        result.sortedBy { it.timestampMillis }
    }
}
