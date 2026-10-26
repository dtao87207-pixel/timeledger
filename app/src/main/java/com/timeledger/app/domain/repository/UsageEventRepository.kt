package com.timeledger.app.domain.repository

import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.model.UsageEvent

/**
 * 系统原始事件仓库（需求六节）。
 *
 * 职责边界非常窄：**只做忠实落库与按时间读取**。
 * 不解释事件含义、不推断行为 —— 那是系统感知模块的事。
 * 这样即使将来推断逻辑整体重写，历史原始数据依然可用，不需要重新采集。
 */
interface UsageEventRepository {

    /**
     * 批量追加事件，返回**实际新增**条数。
     *
     * 幂等：靠 event_key 唯一索引去重，重复调用不会产生重复数据，
     * 也不会因为重复而报错。
     */
    suspend fun appendEvents(events: List<UsageEvent>): Int

    suspend fun getEventsIn(range: MillisRange): List<UsageEvent>

    /** 增量采集游标：已落库的最新事件时间。null 表示还没有任何事件。 */
    suspend fun getLatestEventMillis(): Long?

    /**
     * 按保留策略清理原始事件。
     *
     * 调用前必须确认这些事件已经被分析成 TimeRecord —— 原始事件被删掉就再也拿不回来了，
     * 而系统 UsageStats 的历史同样不可靠。清理策略由调用方决定，仓库只负责执行。
     */
    suspend fun pruneOlderThan(beforeMillis: Long): Int

    suspend fun count(): Int
}
