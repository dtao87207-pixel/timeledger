package com.timeledger.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 计划 / 课程表条目表（需求五节）。
 *
 * V1 只支持「每周重复」与「单次日程」两种形态，没有 RRULE 之类的复杂重复规则：
 * 需求明确要求 V1 不做教务系统爬取与复杂导入，重复规则越简单越不容易出错。
 * 未来要支持单双周/调课，可以在此基础上加 `week_parity` 字段并做一次迁移，
 * 不需要推翻表结构。
 *
 * 索引：按 `day_of_week` 取某天的课表是最热路径；`date_epoch_day` 供单次日程查询。
 */
@Entity(
    tableName = "plan_blocks",
    indices = [
        Index(value = ["day_of_week"]),
        Index(value = ["date_epoch_day"]),
        Index(value = ["is_active"]),
    ],
)
data class PlanBlockEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "title")
    val title: String,

    /** 单次日程日期（epochDay）。null 表示按 day_of_week 每周重复。 */
    @ColumnInfo(name = "date_epoch_day")
    val dateEpochDay: Long? = null,

    /** 1=周一 … 7=周日，与 java.time.DayOfWeek.value 一致。 */
    @ColumnInfo(name = "day_of_week")
    val dayOfWeek: Int? = null,

    @ColumnInfo(name = "start_minute_of_day")
    val startMinuteOfDay: Int,

    /** 1~1440；<= start_minute_of_day 表示跨夜。 */
    @ColumnInfo(name = "end_minute_of_day")
    val endMinuteOfDay: Int,

    /**
     * 上哪些周（模块 28）：`1-3周,5-17周` / `单周` / `双周`。
     *
     * **空字符串 = 每周都上** —— 这正是加这一列之前所有计划块的语义，
     * 所以迁移不改动任何现有行为（零回归），也不需要回填数据。
     *
     * 为什么存字符串而不是位图或关系表：真实课表里写的就是这种文本
     * （`1周,4-6周,8-15周`）。原文存下来既能解析、也能**原样显示给用户核对**；
     * 换成位图会让「用户看到什么」和「库里存什么」对不上。
     */
    @ColumnInfo(name = "weeks_spec", defaultValue = "")
    val weeksSpec: String = "",

    @ColumnInfo(name = "category_id")
    val categoryId: Long? = null,

    @ColumnInfo(name = "activity_id")
    val activityId: Long? = null,

    @ColumnInfo(name = "location")
    val location: String? = null,

    @ColumnInfo(name = "note")
    val note: String? = null,

    @ColumnInfo(name = "valid_from_epoch_day")
    val validFromEpochDay: Long? = null,

    @ColumnInfo(name = "valid_to_epoch_day")
    val validToEpochDay: Long? = null,

    @ColumnInfo(name = "is_active", defaultValue = "1")
    val isActive: Boolean = true,

    @ColumnInfo(name = "created_at_millis")
    val createdAtMillis: Long = 0L,

    @ColumnInfo(name = "updated_at_millis")
    val updatedAtMillis: Long = 0L,
)
