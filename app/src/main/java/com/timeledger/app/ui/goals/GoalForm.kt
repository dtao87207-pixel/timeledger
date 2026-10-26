package com.timeledger.app.ui.goals

import com.timeledger.app.domain.model.Goal
import com.timeledger.app.domain.model.GoalPeriod
import com.timeledger.app.domain.model.GoalType
import kotlin.math.roundToInt

/**
 * 目标表单的原始输入。
 *
 * 目标值用**文本**保存而不是 Double：用户正在输入时可能是不完整的
 * （刚敲了一个「1.」），这时直接转数字会失败或丢掉输入内容。
 * 文本原样保留，只在保存时解析。
 */
data class GoalFormState(
    /** null 表示新建；非空表示正在编辑该目标。 */
    val goalId: Long? = null,
    val title: String = "",
    val type: GoalType = GoalType.TIME,
    val period: GoalPeriod = GoalPeriod.DAY,
    /** 目标值的输入文本。**TIME 类型这里填的是小时**，不是秒。 */
    val targetText: String = "",
    val categoryId: Long? = null,
    val activityId: Long? = null,
) {
    val isEditing: Boolean get() = goalId != null
}

/** 表单校验失败的原因。 */
enum class GoalFormError {
    EMPTY_TITLE,
    INVALID_TARGET,
    MISSING_BINDING,

    /** 仓库拒绝了这次写入（引用不存在、记录已被删等），表单无法给出更具体的原因。 */
    SAVE_FAILED,
}

sealed interface GoalFormResult {
    data class Valid(val goal: Goal) : GoalFormResult
    data class Invalid(val error: GoalFormError) : GoalFormResult
}

/**
 * 目标表单的解析与校验 —— 纯逻辑，可完整用单元测试覆盖。
 *
 * ## 为什么 TIME 类型让用户填「小时」
 * 需求里的写法是「Java 每天 ≥ 3 小时」，用户脑子里也是这个单位。
 * 领域模型的 `targetValue` 对 TIME 存的是**秒**（避免小时的小数在累加中丢精度），
 * 两者之间的换算是本类的职责：**界面输入小时，领域存秒**。
 * 把这个换算放在这里而不是界面里，是因为它必须被测试覆盖 ——
 * 「3 小时变成 10800 秒」这种事算错了，用户看到的进度会整体差 3600 倍。
 */
object GoalForm {

    private const val SECONDS_PER_HOUR = 3600.0

    fun empty(): GoalFormState = GoalFormState()

    /** 编辑已有目标时回填表单（TIME 由秒换回小时）。 */
    fun from(goal: Goal): GoalFormState = GoalFormState(
        goalId = goal.id,
        title = goal.title,
        type = goal.type,
        period = goal.period,
        targetText = targetTextOf(goal.targetValue, goal.type),
        categoryId = goal.categoryId,
        activityId = goal.activityId,
    )

    fun validate(state: GoalFormState): GoalFormResult {
        val title = state.title.trim()
        if (title.isEmpty()) return GoalFormResult.Invalid(GoalFormError.EMPTY_TITLE)

        // 必须绑定分类或活动之一，否则无法计算完成度（Goal 的构造函数也要求这一点）
        if (state.categoryId == null && state.activityId == null) {
            return GoalFormResult.Invalid(GoalFormError.MISSING_BINDING)
        }

        val target = parseTarget(state.targetText, state.type)
        // 注意要显式排除 NaN / 无穷：Kotlin 的 "NaN".toDoubleOrNull() 会成功返回 NaN，
        // 而 NaN 参与的比较全是 false，只写 `target <= 0` 会把它放进领域模型
        if (target == null || !target.isFinite() || target <= 0.0) {
            return GoalFormResult.Invalid(GoalFormError.INVALID_TARGET)
        }

        return GoalFormResult.Valid(
            Goal(
                id = state.goalId ?: 0L,
                title = title,
                type = state.type,
                period = state.period,
                targetValue = target,
                categoryId = state.categoryId,
                activityId = state.activityId,
            )
        )
    }

    /** 输入框文本 → 领域值。TIME 输入的是小时。解析失败返回 null。 */
    fun parseTarget(text: String, type: GoalType): Double? {
        val value = text.trim().toDoubleOrNull() ?: return null
        return when (type) {
            GoalType.TIME -> value * SECONDS_PER_HOUR
            GoalType.COUNT, GoalType.FREQUENCY -> value
        }
    }

    /** 领域值 → 输入框文本。TIME 换回小时。 */
    fun targetTextOf(targetValue: Double, type: GoalType): String = when (type) {
        GoalType.TIME -> trimNumber(targetValue / SECONDS_PER_HOUR)
        GoalType.COUNT, GoalType.FREQUENCY -> trimNumber(targetValue)
    }

    /** 输入框旁边的单位标签。 */
    fun targetUnitLabel(type: GoalType): String = when (type) {
        GoalType.TIME -> "小时"
        GoalType.COUNT -> "个"
        GoalType.FREQUENCY -> "次"
    }

    /** 去掉多余的小数位：3.0 → `3`，1.5 → `1.5`（避免回填时显示成 3.0）。 */
    private fun trimNumber(value: Double): String {
        val rounded = (value * 100).roundToInt() / 100.0
        val asLong = rounded.toLong()
        return if (rounded == asLong.toDouble()) asLong.toString() else rounded.toString()
    }
}
