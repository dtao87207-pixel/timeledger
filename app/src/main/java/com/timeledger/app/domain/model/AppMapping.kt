package com.timeledger.app.domain.model

/**
 * App → 活动映射（需求九节）。
 *
 * 这是「系统事实」走向「候选行为」的关键一环，设计上刻意**不**做死映射：
 *
 * 1. [categoryId] 为 null 表示**未确定**，例如 Chrome —— 它可能是查 Java 文档，
 *    也可能是看小说，绝不能固定判成娱乐。未确定的 App 由上下文规则推断，
 *    或直接丢给用户确认。
 * 2. [confidence] 表示这条映射有多可信：内置默认映射给中等值，用户亲手确认过的给 1.0。
 * 3. [isUserConfirmed] 区分「系统猜的」和「用户说的」。
 *    用户确认过的映射在后续推断中权重最高，并且不会被默认规则静默改回。
 *
 * 每个包名只允许一条映射记录（数据库唯一索引），保证查询结果唯一。
 */
data class AppMapping(
    val id: Long = 0L,
    /** Android 包名，例如 `com.tencent.mm`。 */
    val packageName: String,
    /** App 显示名，仅用于展示，采集时可能还没拿到（则为 null）。 */
    val appLabel: String? = null,
    /** 默认一级分类；null 表示未确定。 */
    val categoryId: Long? = null,
    /** 默认二级活动；null 表示未确定。 */
    val activityId: Long? = null,
    /** 置信度 0.0~1.0。 */
    val confidence: Double = 0.0,
    /** 是否由用户确认/修改过。 */
    val isUserConfirmed: Boolean = false,
    val updatedAtMillis: Long = 0L,
) {

    /** 是否仍未确定归属（需要用户确认或上下文推断）。 */
    val isUnresolved: Boolean get() = categoryId == null && activityId == null

    init {
        require(packageName.isNotBlank()) { "包名不能为空" }
        require(confidence in 0.0..1.0) {
            "置信度必须在 0.0~1.0 之间，实际为 $confidence"
        }
    }
}
