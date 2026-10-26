package com.timeledger.app.domain.repository

import com.timeledger.app.domain.model.Activity
import kotlinx.coroutines.flow.Flow

/**
 * 二级活动仓库。
 *
 * 活动与分类是**独立概念**（需求八节），因此单独建模、单独统计，
 * 但归属关系是强制的：活动必须属于一个存在的分类。
 */
interface ActivityRepository {

    /**
     * 观察活动列表。
     * [categoryId] 非空时只返回该分类下的活动。
     */
    fun observeActivities(
        categoryId: Long? = null,
        includeArchived: Boolean = false,
    ): Flow<List<Activity>>

    suspend fun getActivity(id: Long): Activity?

    suspend fun createActivity(
        categoryId: Long,
        name: String,
        colorArgb: Int? = null,
        iconKey: String? = null,
        sortOrder: Int = 0,
    ): SaveOutcome

    suspend fun updateActivity(activity: Activity): SaveOutcome

    suspend fun archiveActivity(id: Long): Boolean
}
