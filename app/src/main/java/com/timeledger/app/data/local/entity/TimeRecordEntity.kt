package com.timeledger.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 时间记录表 —— 全 App 的核心表。
 *
 * 索引设计（都由实际查询决定，不做无用索引）：
 * - `start_time_millis`：按时间范围取记录、判断重叠，是最主要的查询入口。
 * - `end_time_millis`：重叠判断需要 `start < X AND end > Y`，两端都要能走索引。
 * - `external_key` **唯一**：系统采集的幂等保证。SQLite 的唯一索引允许多个 NULL，
 *   所以手动记录（external_key 为 null）可以有很多条，互不冲突。
 * - `category_id` / `activity_id`：按分类/活动聚合时长时使用。
 *
 * 关于外键：全库统一**不使用 FOREIGN KEY 约束**，理由有两条：
 * 1. 系统采集与用户记录可能先落库、后补分类（例如 App 映射还没确定），
 *    外键会让这些正常写入失败；
 * 2. 分类/活动一律「只归档不硬删」，引用完整性由 Repository 层保证，
 *    比数据库约束更容易给出可读的错误信息。
 * 这个取舍在后续所有表上保持一致，避免一半表有外键一半没有。
 */
@Entity(
    tableName = "time_records",
    indices = [
        Index(value = ["start_time_millis"]),
        Index(value = ["end_time_millis"]),
        Index(value = ["external_key"], unique = true),
        Index(value = ["category_id"]),
        Index(value = ["activity_id"]),
    ],
)
data class TimeRecordEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "start_time_millis")
    val startMillis: Long,

    @ColumnInfo(name = "end_time_millis")
    val endMillis: Long,

    @ColumnInfo(name = "category_id")
    val categoryId: Long? = null,

    @ColumnInfo(name = "activity_id")
    val activityId: Long? = null,

    /** 存枚举 name 字符串，见 Converters。 */
    @ColumnInfo(name = "source")
    val source: String,

    @ColumnInfo(name = "confidence", defaultValue = "1.0")
    val confidence: Double = 1.0,

    @ColumnInfo(name = "count_value")
    val countValue: Double? = null,

    @ColumnInfo(name = "note")
    val note: String? = null,

    @ColumnInfo(name = "external_key")
    val externalKey: String? = null,

    @ColumnInfo(name = "is_deleted", defaultValue = "0")
    val isDeleted: Boolean = false,

    @ColumnInfo(name = "created_at_millis")
    val createdAtMillis: Long = 0L,

    @ColumnInfo(name = "updated_at_millis")
    val updatedAtMillis: Long = 0L,
)
