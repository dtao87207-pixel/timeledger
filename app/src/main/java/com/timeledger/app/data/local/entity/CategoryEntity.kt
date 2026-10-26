package com.timeledger.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一级分类表。
 *
 * 设计说明：
 * - 名称唯一索引：防止 UI 误操作建出两个「学习」导致统计被拆散。
 * - 不建外键（全库统一策略，见 TimeRecordEntity 的说明）。
 * - [isArchived] 代替硬删除：历史记录依赖分类归属，删掉分类会让往年统计失去口径。
 */
@Entity(
    tableName = "categories",
    indices = [Index(value = ["name"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

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
