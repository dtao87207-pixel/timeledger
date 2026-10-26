package com.timeledger.app.data.mapper

import com.timeledger.app.data.local.entity.ActivityEntity
import com.timeledger.app.data.local.entity.AppMappingEntity
import com.timeledger.app.data.local.entity.CategoryEntity
import com.timeledger.app.data.local.entity.GoalEntity
import com.timeledger.app.data.local.entity.PlanBlockEntity
import com.timeledger.app.data.local.entity.TimeRecordEntity
import com.timeledger.app.data.local.entity.UsageEventEntity
import com.timeledger.app.domain.model.Activity
import com.timeledger.app.domain.model.AppMapping
import com.timeledger.app.domain.model.Category
import com.timeledger.app.domain.model.Goal
import com.timeledger.app.domain.model.GoalPeriod
import com.timeledger.app.domain.model.GoalType
import com.timeledger.app.domain.model.PlanBlock
import com.timeledger.app.domain.model.RecordSource
import com.timeledger.app.domain.model.TimeRecord
import com.timeledger.app.domain.model.UsageEvent

/**
 * 数据库实体 ←→ 领域模型的映射。
 *
 * ## 为什么分两层，而不是直接用 Room 实体当模型
 * 1. 领域模型带 `init` 不变式（名称不能为空等），实体不带 —— 让实体进领域层，
 *    等于把「数据一定合法」这个假设丢掉；
 * 2. 实体把枚举存成 `String`（`type` / `period` / `source`），领域层用真正的枚举，
 *    编译期就能穷尽分支；
 * 3. 将来改表结构（加列、拆表）只要改这一层，业务代码不用动。
 *
 * ## 一条安全规则：反序列化未知枚举时降级，不抛异常
 * 库里出现新版本写入的枚举名（或脏数据）时，`valueOf` 会直接抛异常，
 * 于是**整个查询都失败**。所以来源这类字段一律走 [RecordSource.fromNameOrNull]
 * 并降级到最保守的值。
 *
 * > 这个文件在一次编码事故中被截断（PowerShell 按 ANSI 读写了中文注释），
 * > 之后按实体与领域模型的字段逐条重写。原来的类注释较长，
 * > 这里只保留上面两条**真正影响正确性**的约定；其余说明见 README 决策 116。
 */

fun CategoryEntity.toDomain(): Category = Category(
    id = id,
    name = name,
    colorArgb = colorArgb,
    iconKey = iconKey,
    sortOrder = sortOrder,
    isBuiltIn = isBuiltIn,
    isArchived = isArchived,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun Category.toEntity(): CategoryEntity = CategoryEntity(
    id = id,
    name = name,
    colorArgb = colorArgb,
    iconKey = iconKey,
    sortOrder = sortOrder,
    isBuiltIn = isBuiltIn,
    isArchived = isArchived,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun ActivityEntity.toDomain(): Activity = Activity(
    id = id,
    categoryId = categoryId,
    name = name,
    colorArgb = colorArgb,
    iconKey = iconKey,
    sortOrder = sortOrder,
    isBuiltIn = isBuiltIn,
    isArchived = isArchived,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun Activity.toEntity(): ActivityEntity = ActivityEntity(
    id = id,
    categoryId = categoryId,
    name = name,
    colorArgb = colorArgb,
    iconKey = iconKey,
    sortOrder = sortOrder,
    isBuiltIn = isBuiltIn,
    isArchived = isArchived,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun TimeRecordEntity.toDomain(): TimeRecord = TimeRecord(
    id = id,
    startMillis = startMillis,
    endMillis = endMillis,
    categoryId = categoryId,
    activityId = activityId,
    source = RecordSource.fromNameOrNull(source) ?: RecordSource.SUGGESTED,
    confidence = confidence,
    countValue = countValue,
    note = note,
    externalKey = externalKey,
    isDeleted = isDeleted,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun TimeRecord.toEntity(): TimeRecordEntity = TimeRecordEntity(
    id = id,
    startMillis = startMillis,
    endMillis = endMillis,
    categoryId = categoryId,
    activityId = activityId,
    source = source.name,
    confidence = confidence,
    countValue = countValue,
    note = note,
    externalKey = externalKey,
    isDeleted = isDeleted,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun GoalEntity.toDomain(): Goal = Goal(
    id = id,
    title = title,
    type = GoalType.valueOf(type),
    period = GoalPeriod.valueOf(period),
    targetValue = targetValue,
    categoryId = categoryId,
    activityId = activityId,
    startEpochDay = startEpochDay,
    endEpochDay = endEpochDay,
    isActive = isActive,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun Goal.toEntity(): GoalEntity = GoalEntity(
    id = id,
    title = title,
    type = type.name,
    period = period.name,
    targetValue = targetValue,
    categoryId = categoryId,
    activityId = activityId,
    startEpochDay = startEpochDay,
    endEpochDay = endEpochDay,
    isActive = isActive,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun PlanBlockEntity.toDomain(): PlanBlock = PlanBlock(
    id = id,
    title = title,
    dateEpochDay = dateEpochDay,
    dayOfWeek = dayOfWeek,
    startMinuteOfDay = startMinuteOfDay,
    endMinuteOfDay = endMinuteOfDay,
    categoryId = categoryId,
    activityId = activityId,
    location = location,
    note = note,
    validFromEpochDay = validFromEpochDay,
    validToEpochDay = validToEpochDay,
    // 库里存空串 = 「每周都上」（模块 28 加的列，迁移默认值就是这个），
    // 领域层用 null 表达同一件事。
    weeksSpec = weeksSpec.ifBlank { null },
    isActive = isActive,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun PlanBlock.toEntity(): PlanBlockEntity = PlanBlockEntity(
    id = id,
    title = title,
    dateEpochDay = dateEpochDay,
    dayOfWeek = dayOfWeek,
    startMinuteOfDay = startMinuteOfDay,
    endMinuteOfDay = endMinuteOfDay,
    categoryId = categoryId,
    activityId = activityId,
    location = location,
    note = note,
    validFromEpochDay = validFromEpochDay,
    validToEpochDay = validToEpochDay,
    weeksSpec = weeksSpec.orEmpty(),
    isActive = isActive,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun AppMappingEntity.toDomain(): AppMapping = AppMapping(
    id = id,
    packageName = packageName,
    appLabel = appLabel,
    categoryId = categoryId,
    activityId = activityId,
    confidence = confidence,
    isUserConfirmed = isUserConfirmed,
    updatedAtMillis = updatedAtMillis,
)

fun AppMapping.toEntity(): AppMappingEntity = AppMappingEntity(
    id = id,
    packageName = packageName,
    appLabel = appLabel,
    categoryId = categoryId,
    activityId = activityId,
    confidence = confidence,
    isUserConfirmed = isUserConfirmed,
    updatedAtMillis = updatedAtMillis,
)

fun UsageEventEntity.toDomain(): UsageEvent = UsageEvent(
    id = id,
    packageName = packageName,
    className = className,
    eventType = eventType,
    timestampMillis = timestampMillis,
    eventKey = eventKey,
    createdAtMillis = createdAtMillis,
)

fun UsageEvent.toEntity(): UsageEventEntity = UsageEventEntity(
    id = id,
    packageName = packageName,
    className = className,
    eventType = eventType,
    timestampMillis = timestampMillis,
    eventKey = eventKey,
    createdAtMillis = createdAtMillis,
)
