package com.timeledger.app.domain.repository

/**
 * 内置分类与活动的种子写入（需求八节）。
 *
 * 独立成一个接口，而不是挂在 CategoryRepository 上，原因：
 * 种子数据同时涉及 categories 与 activities 两张表，
 * 挂在分类仓库上会让它凭空依赖 ActivityDao，职责就混了。
 */
interface CatalogSeeder {

    /**
     * 确保内置分类与活动存在。
     *
     * **逐条幂等**，而不是「表为空才写」：
     * 如果上一次写入中途失败（进程被杀、磁盘满），整表判断会让数据永远残缺，
     * 而逐条按名称检查 + `INSERT OR IGNORE` 会在下次启动时自动补齐。
     *
     * 只补不删、不覆盖：用户改过的颜色、加过的活动都不会被动。
     *
     * @return true 表示本次确实写入了新数据。
     */
    suspend fun seedDefaults(): Boolean
}
