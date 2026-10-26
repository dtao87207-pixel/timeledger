package com.timeledger.app.domain.model

/**
 * 目标（需求四节）。
 *
 * 绑定规则：必须至少绑定 [categoryId] 或 [activityId] 之一。
 * - 只绑分类：统计该分类下所有活动；
 * - 绑到活动：只统计该活动。
 *
 * [targetValue] 的单位随 [type] 变化：
 * - [GoalType.TIME]      → **秒**（不用毫秒，避免数值过大；更不用小时，避免浮点误差累积）
 * - [GoalType.COUNT]     → 个数
 * - [GoalType.FREQUENCY] → 次数
 *
 * 完成度**永远从 TimeRecord 动态计算**（需求十三节），本模型不保存任何进度缓存字段，
 * 这样用户修改/删除历史记录后统计不会过期。
 */
data class Goal(
    val id: Long = 0L,
    /** 目标标题，例如「Java 每天 3 小时」。 */
    val title: String,
    val type: GoalType,
    val period: GoalPeriod,
    /** 目标值，单位见类注释。必须 > 0。 */
    val targetValue: Double,
    val categoryId: Long? = null,
    val activityId: Long? = null,
    /** 生效起始逻辑日（epochDay，含）。为空表示不限。 */
    val startEpochDay: Long? = null,
    /** 生效结束逻辑日（epochDay，含）。为空表示不限（例如「每天」长期目标）。 */
    val endEpochDay: Long? = null,
    val isActive: Boolean = true,
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
) {

    init {
        require(targetValue > 0.0) { "目标值必须大于 0，实际为 $targetValue" }
        require(categoryId != null || activityId != null) {
            "目标必须绑定分类或活动之一，否则无法计算完成度"
        }
    }

    /** 目标是否覆盖给定逻辑日。 */
    fun isEffectiveOn(epochDay: Long): Boolean =
        (startEpochDay == null || epochDay >= startEpochDay) &&
            (endEpochDay == null || epochDay <= endEpochDay)
}
