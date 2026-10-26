package com.timeledger.app.domain.repository

import com.timeledger.app.domain.model.PlanBlock
import kotlinx.coroutines.flow.Flow

/**
 * 计划 / 课程表仓库（需求五节）。
 *
 * V1 范围：手动创建、编辑、删除、每周重复。
 * 未来导入 CSV/Excel/图片的扩展点在这里预留：只要能生成 [PlanBlock] 列表，
 * 走同一个 [createPlans] 入口即可，不需要改动上层。
 */
interface PlanBlockRepository {

    fun observePlans(activeOnly: Boolean = true): Flow<List<PlanBlock>>

    suspend fun getPlan(id: Long): PlanBlock?

    // 「取某个逻辑日的计划」刻意不在这里提供。
    //
    // 逻辑日横跨两个日历日（当天 04:00 ~ 次日 03:59），因此「今天有哪些计划」
    // 不是一次 SQL 过滤能回答的：见 PlanBlockDao 的说明。
    // 调用方应当用 observePlans() 取出全部计划，交给
    // com.timeledger.app.domain.plan.PlanBlockExpander 展开 —— 那里是归属规则唯一的落点。

    suspend fun createPlan(plan: PlanBlock): SaveOutcome

    /** 批量创建，供将来的课程表导入使用；返回实际写入条数。 */
    suspend fun createPlans(plans: List<PlanBlock>): Int

    suspend fun updatePlan(plan: PlanBlock): SaveOutcome

    /** 计划条目允许真删除：没有任何历史记录引用它。 */
    suspend fun deletePlan(id: Long): Boolean
}
