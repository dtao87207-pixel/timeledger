package com.timeledger.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.timeledger.app.domain.model.GoalProgressOverride

@Entity(tableName = "goal_progress_overrides")
data class GoalProgressOverrideEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    
    @ColumnInfo(name = "goal_id")
    val goalId: Long,
    
    @ColumnInfo(name = "epoch_day")
    val epochDay: Long,
    
    @ColumnInfo(name = "achieved_value")
    val achievedValue: Double,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    
    @ColumnInfo(name = "is_deleted")
    val isDeleted: Boolean = false,
)

fun GoalProgressOverrideEntity.toDomain(): GoalProgressOverride = GoalProgressOverride(
    id = this.id,
    goalId = this.goalId,
    epochDay = this.epochDay,
    achievedValue = this.achievedValue,
    createdAt = this.createdAt,
    updatedAt = this.updatedAt,
    isDeleted = this.isDeleted
)

fun GoalProgressOverride.toEntity(): GoalProgressOverrideEntity = GoalProgressOverrideEntity(
    id = this.id,
    goalId = this.goalId,
    epochDay = this.epochDay,
    achievedValue = this.achievedValue,
    createdAt = this.createdAt,
    updatedAt = this.updatedAt,
    isDeleted = this.isDeleted
)