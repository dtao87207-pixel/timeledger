package com.timeledger.app.domain.repository

/**
 * 写入结果。
 *
 * 为什么不用异常表达失败：写时间记录失败大多是**业务规则**而非程序错误
 * （例如「这一段时间用户已经手动记过了」），属于正常分支，
 * 需要把原因回传给 UI 做提示。用密封类型可以让调用方在编译期被强制处理每种情况，
 * 也避免 UI 层到处 try/catch。
 */
sealed interface SaveOutcome {

    /** 写入成功。[id] 为自增主键；更新操作返回被更新的记录 id。 */
    data class Saved(val id: Long) : SaveOutcome

    /** 被业务规则拒绝，附具体原因。 */
    data class Rejected(val reason: RejectReason) : SaveOutcome

    /**
     * 幂等跳过：该数据已经写入过（外部幂等键命中），本次不重复写入。
     * 这不是错误，调用方通常无需提示用户。
     */
    data object Skipped : SaveOutcome

    val isSuccess: Boolean get() = this is Saved
}

/**
 * 写入被拒绝的原因。
 *
 * 只保留**真的会出现**的分支。判断依据是「谁负责校验」这条分工：
 *
 * - **领域模型自己**负责「值本身是否成立」：例如 [com.timeledger.app.domain.model.Goal]
 *   要求目标值为正、必须绑定分类或活动，[com.timeledger.app.domain.model.TimeRecord]
 *   要求结束不早于开始。这些用 `require` 挡在构造函数里，因此仓库层不会再出现
 *   「目标值非正」这种永远触发不了的原因。
 *   用户填表时的具体报错由表单校验负责（在目标模块实现），构造出领域模型时它已经是合法的。
 * - **仓库层**负责「引用是否存在、名称是否重名、是否与已有数据冲突」——
 *   这些只有查了数据库才知道，领域模型无法判断，因此用本枚举回报。
 */
enum class RejectReason {
    /** 名称/标题为空或只有空白字符。 */
    INVALID_NAME,

    /** 名称重复（分类、同一分类下的活动都有唯一约束）。 */
    DUPLICATE_NAME,

    /** 结束时间早于开始时间。 */
    INVALID_TIME_RANGE,

    /** 计数值为负数。 */
    INVALID_COUNT_VALUE,

    /** 引用的分类/活动不存在，或已归档（不允许挂到不可用的归属上）。 */
    UNKNOWN_CATEGORY_OR_ACTIVITY,

    /** 活动与其所属分类不一致（activity.categoryId != categoryId）。 */
    ACTIVITY_CATEGORY_MISMATCH,

    /** 系统记录未提供幂等键，会导致重复采集写入重复数据，直接拒绝。 */
    MISSING_EXTERNAL_KEY,

    /** 该时段已经存在用户手动记录，系统记录不得覆盖（需求三节优先级原则）。 */
    OVERLAPS_MANUAL_RECORD,

    /** 起止日期颠倒。 */
    INVALID_DATE_RANGE,

    /** 计划时长非法：结束分钟数等于开始分钟数，等于把条目定义成 24 小时。 */
    INVALID_PLAN_DURATION,

    /** 记录不存在（更新/删除时）。 */
    RECORD_NOT_FOUND;
}
