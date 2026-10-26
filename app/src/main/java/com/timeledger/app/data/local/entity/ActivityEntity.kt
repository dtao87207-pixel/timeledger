package com.timeledger.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 二级活动表。
 *
 * - `(category_id, name)` 联合唯一：同一分类下不能出现两个同名活动，
 *   但不同分类可以有同名活动（例如「阅读」既可能在「学习」也可能在「个人」）。
 * - [categoryId] 建索引：按分类列活动、按分类聚合统计都会用到。
 */
@Entity(
    tableName = "activities",
    indices = [
        Index(value = ["category_id", "name"], unique = true),
        Index(value = ["category_id"]),
    ],
)
data class ActivityEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "category_id")
    val categoryId: Long,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "color_argb")
    val colorArgb: Int? = null,

    @ColumnInfo(name = "icon_key")
    val iconKey: String? = null,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    @ColumnInfo(name = "is_built_in", defaultValue = "0")
    val isBuiltIn: Boolean = false,

    @ColumnInfo(name = "is_archived", defaultValue = "0")
    val isArchived: Boolean = false,

    @ColumnInfo(name = "created_at_millis")
    val createdAtMillis: Long = 0L,

    @ColumnInfo(name = "updated_at_millis")
    val updatedAtMillis: Long = 0L,
)
