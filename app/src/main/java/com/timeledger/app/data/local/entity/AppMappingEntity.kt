package com.timeledger.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * App → 活动映射表（需求九节）。
 *
 * `package_name` 唯一：一个包名只允许一条映射，保证「这个 App 属于什么」有唯一答案。
 * 用户修改映射时更新这一行并把 is_user_confirmed 置 1，而不是插入新行 ——
 * 避免出现「默认映射」和「用户映射」并存、查询结果不确定的情况。
 */
@Entity(
    tableName = "app_mappings",
    indices = [
        Index(value = ["package_name"], unique = true),
        Index(value = ["category_id"]),
    ],
)
data class AppMappingEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "app_label")
    val appLabel: String? = null,

    /** null 表示「未确定」，例如 Chrome（需求九节明确要求不能固定映射）。 */
    @ColumnInfo(name = "category_id")
    val categoryId: Long? = null,

    @ColumnInfo(name = "activity_id")
    val activityId: Long? = null,

    @ColumnInfo(name = "confidence", defaultValue = "0.0")
    val confidence: Double = 0.0,

    @ColumnInfo(name = "is_user_confirmed", defaultValue = "0")
    val isUserConfirmed: Boolean = false,

    @ColumnInfo(name = "updated_at_millis")
    val updatedAtMillis: Long = 0L,
)
