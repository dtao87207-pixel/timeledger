package com.timeledger.app.domain.model

import com.timeledger.app.core.time.MillisRange
import java.time.Instant

/**
 * 目标进度手动覆盖记录（需求扩展）。
 *
 * 允许用户手动调整目标进度，解决自动计算不准确的问题。
 * 例如：系统统计显示目标已完成，但实际未完成，用户可手动降低进度。
 */
data class GoalProgressOverride(
    val id: Long = 0L,
    /** 对应的目标ID */
    val goalId: Long,
    /** 生效的日期（逻辑日） */
    val epochDay: Long,
    /** 手动设置的进度值 */
    val achievedValue: Double,
    /** 创建时间 */
    val createdAt: Long = Instant.now().toEpochMilli(),
    /** 更新时间 */
    val updatedAt: Long = Instant.now().toEpochMilli(),
    /** 是否已删除（软删除） */
    val isDeleted: Boolean = false,
)