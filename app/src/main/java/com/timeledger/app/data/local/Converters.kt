package com.timeledger.app.data.local

import androidx.room.TypeConverter
import com.timeledger.app.domain.model.GoalPeriod
import com.timeledger.app.domain.model.GoalType
import com.timeledger.app.domain.model.RecordSource

/**
 * Room 类型转换器。
 *
 * 枚举一律**按 name 存字符串**，不存 ordinal：
 * ordinal 一旦在枚举中间插入新值，历史数据就会整体错位，
 * 而字符串在数据库里可读，迁移时也容易写 SQL 修正。
 *
 * 解析失败时给出**兜底值而不是抛异常**：旧版本写入的枚举名在新版本被删除时，
 * 宁可降级成最保守的取值，也不要让整个查询崩溃（数据库迁移兼容性，需求十六节第 8 条）。
 */
class Converters {

    @TypeConverter
    fun recordSourceToString(value: RecordSource): String = value.name

    @TypeConverter
    fun stringToRecordSource(value: String?): RecordSource =
        RecordSource.fromNameOrNull(value) ?: RecordSource.SUGGESTED

    @TypeConverter
    fun goalTypeToString(value: GoalType): String = value.name

    @TypeConverter
    fun stringToGoalType(value: String?): GoalType =
        GoalType.fromNameOrNull(value) ?: GoalType.TIME

    @TypeConverter
    fun goalPeriodToString(value: GoalPeriod): String = value.name

    @TypeConverter
    fun stringToGoalPeriod(value: String?): GoalPeriod =
        GoalPeriod.fromNameOrNull(value) ?: GoalPeriod.DAY
}
