package com.timeledger.app.domain.plan

import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * 学期起始日（模块 28）—— 课表按「第几周」判定所依赖的那个参照点。
 *
 * ## 为什么要单独抽一个接口
 * 上层（展开器、课表界面）只需要两件事：**读**当前学期起点、**写**一个新的。
 * 至于它存在 SharedPreferences 还是 DataStore，是不该被上层知道的细节 ——
 * 这也是需求十四节「UI 不直接操作存储、数据层与 UI 解耦」的同一条规矩。
 *
 * ## 实现放在 data 层
 * 见 `data/settings/TermSettingsStore.kt`。当前用 `SharedPreferences`（Android 自带、零依赖）；
 * 将来若引入 DataStore（需求十四节把它列进了技术栈，但一直没被用到），
 * **只换实现类，这个接口和所有调用方一行都不用动**。
 *
 * ## 没设过学期起点时返回 null
 * 这不是错误状态：`null` 表示「用户还没告诉 App 学期从哪天开始」，
 * 此时所有计划都按「每周都上」处理 —— 与加周次这一列之前的行为完全一致，
 * 所以老用户升级上来不会看到任何变化。
 */
interface TermSettings {

    /** 观察学期起始日；没设过时为 null。 */
    fun observeTermStart(): Flow<LocalDate?>

    /**
     * 同步读一次当前值（展开器是纯函数，不能挂起等 Flow）。
     *
     * 实现内部本来就缓存了一份，所以这是 O(1) 的取值，不是读盘。
     */
    fun currentTermStart(): LocalDate?

    /** 设置学期起始日（通常是第 1 周的周一）。 */
    suspend fun setTermStart(date: LocalDate)
}
