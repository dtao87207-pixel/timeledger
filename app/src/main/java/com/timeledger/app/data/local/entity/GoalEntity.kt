package com.timeledger.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 目标表（需求四节）。
 *
 * 刻意**不存任何进度字段**：完成度一律从 time_records 现算（需求十三节）。
 * 存进度就会有「用户改了历史记录、进度没跟着变」的经典 bug，
 * 而手机端的数据量（一年几千条记录）现算完全够快。
 *
 * [targetValue] 单位随类型变化：TIME → 秒，COUNT → 个数，FREQUENCY → 次数。
 */
@Entity(
    tableName = "goals",
    indices = [
        Index(value = ["is_active"]),
        Index(value = ["category_id"]),
        Index(value = ["activity_id"]),
    ],
)
data class GoalEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "title")
    val title: String,

    /** 存枚举 name 字符串：TIME / COUNT / FREQUENCY。 */
    @ColumnInfo(name = "type")
    val type: String,

    /** 存枚举 name 字符串：DAY / WEEK / MONTH。 */
    @ColumnInfo(name = "period")
    val period: String,

    @ColumnInfo(name = "target_value")
    val targetValue: Double,

    @ColumnInfo(name = "category_id")
    val categoryId: Long? = null,

    @ColumnInfo(name = "activity_id")
    val activityId: Long? = null,

    /** 生效起始逻辑日（epochDay，含）。null 表示不限。 */
    @ColumnInfo(name = "start_epoch_day")
    val startEpochDay: Long? = null,

    /** 生效结束逻辑日（epochDay，含）。null 表示不限。 */
    @ColumnInfo(name = "end_epoch_day")
    val endEpochDay: Long? = null,

    @ColumnInfo(name = "is_active", defaultValue = "1")
    val isActive: Boolean = true,

    @ColumnInfo(name = "created_at_millis")
    val createdAtMillis: Long = 0L,

    @ColumnInfo(name = "updated_at_millis")
    val updatedAtMillis: Long = 0L,
)
