package com.timeledger.app.domain.model

/**
 * Android 系统原始使用事件（需求六节）—— **事实层**，不是行为层。
 *
 * 为什么必须自己存一份：系统 UsageStats 的历史保留时间有限（通常几天到几周，
 * 且随 ROM 而异），无法作为长期数据源。因此采集到就立刻落到自己的库里。
 *
 * [eventType] 直接保存 `android.app.usage.UsageEvents.Event` 的原始 int 值，
 * **不在数据层解释其含义**：
 * - 不同 Android 版本的常量语义有差异（`MOVE_TO_FOREGROUND` 已废弃，
 *   由 `ACTIVITY_RESUMED` 取代），解释逻辑集中放在系统感知模块里，
 *   配合版本判断处理（需求十六节第 11 条）。
 * - 保存原值可保证后续修正推断逻辑时，历史数据不需要重新采集。
 *
 * [eventKey] 是幂等键：`eventType|timestampMillis|packageName`。
 * 同步流程可能被重复触发，靠它去重，避免同一事件写入多条。
 */
data class UsageEvent(
    val id: Long = 0L,
    /** 包名。屏幕/锁屏类系统事件没有包名，因此可为空。 */
    val packageName: String? = null,
    /** Activity 类名，可空。 */
    val className: String? = null,
    val eventType: Int,
    val timestampMillis: Long,
    /** 幂等键，唯一索引。 */
    val eventKey: String,
    val createdAtMillis: Long = 0L,
) {

    companion object {
        /** 生成幂等键。所有写入路径必须使用它，保证口径一致。 */
        fun buildEventKey(
            eventType: Int,
            timestampMillis: Long,
            packageName: String?,
        ): String = "$eventType|$timestampMillis|${packageName.orEmpty()}"
    }
}
