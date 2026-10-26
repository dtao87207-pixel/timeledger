package com.timeledger.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Android 系统原始使用事件表（需求六节）—— 事实层。
 *
 * 为什么不能只依赖系统：`UsageStatsManager` 的历史数据保留时间由 ROM 决定，
 * 通常只有几天且会被清理，无法支撑周/月/年统计。
 * 所以采集到就立刻写入本表，本表才是长期数据源。
 *
 * `event_key` 唯一索引承担幂等职责（见 UsageEvent.buildEventKey）：
 * 补采、重启、WorkManager 重试都可能让同一批事件被写入两次，
 * 靠 `INSERT OR IGNORE` 天然去重，不需要先查后写（避免并发下的竞态）。
 *
 * 只做一件事：忠实保存事实。**推断「用户在做什么」的逻辑不在这里**，
 * 因为同一个 App 在不同上下文可能对应不同行为（需求七节）。
 */
@Entity(
    tableName = "usage_events",
    indices = [
        Index(value = ["event_key"], unique = true),
        Index(value = ["timestamp_millis"]),
        Index(value = ["package_name", "timestamp_millis"]),
    ],
)
data class UsageEventEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    /** 屏幕/锁屏等系统事件没有包名，故可为空。 */
    @ColumnInfo(name = "package_name")
    val packageName: String? = null,

    @ColumnInfo(name = "class_name")
    val className: String? = null,

    /** 保存 UsageEvents.Event 的原始 int 值，含义由系统感知模块按版本解释。 */
    @ColumnInfo(name = "event_type")
    val eventType: Int,

    @ColumnInfo(name = "timestamp_millis")
    val timestampMillis: Long,

    @ColumnInfo(name = "event_key")
    val eventKey: String,

    @ColumnInfo(name = "created_at_millis")
    val createdAtMillis: Long = 0L,
)
