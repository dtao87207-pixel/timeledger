package com.timeledger.app.ui.plan

import com.timeledger.app.domain.model.PlanBlock
import com.timeledger.app.ui.format.TimeOfDay

/** 课程表条目的表单输入。 */
data class PlanFormState(
    /** null 表示新建；非空表示正在编辑该条目。 */
    val planBlockId: Long? = null,
    val title: String = "",
    /** 1=周一 … 7=周日（与 `DayOfWeek.value` 一致）。 */
    val dayOfWeek: Int = 1,
    /** 开始时间文本，形如 `09:00`。 */
    val startText: String = "",
    /** 结束时间文本，形如 `10:40`；`24:00` 表示到当天 24 点。 */
    val endText: String = "",
    val categoryId: Long? = null,
    /** 可以不指定活动：一门课绑到「学习」这一级就够了。 */
    val activityId: Long? = null,
    val location: String = "",
    /** 下面两个字段界面上不编辑，但要**原样带回**仓库（见 CatalogForm 的同类说明）。 */
    val validFromEpochDay: Long? = null,
    val validToEpochDay: Long? = null,
    val isActive: Boolean = true,
) {
    val isEditing: Boolean get() = planBlockId != null
}

/** 表单校验失败的原因。 */
enum class PlanFormError {
    EMPTY_TITLE,
    TITLE_TOO_LONG,

    /** 时间文本解析失败，或超出允许范围（开始不允许 24:00、结束不允许 00:00）。 */
    INVALID_TIME,

    /** 开始与结束是同一时刻 —— 那等于把条目定义成 0 分钟或 24 小时，多半是误操作。 */
    ZERO_DURATION,

    /** 仓库拒绝了写入（例如活动与分类不一致）。 */
    SAVE_FAILED,
}

sealed interface PlanFormResult {
    data class Valid(val planBlock: PlanBlock) : PlanFormResult
    data class Invalid(val error: PlanFormError) : PlanFormResult
}

/**
 * 课程表表单的解析与校验 —— 纯逻辑，可完整用单元测试覆盖。
 *
 * ## 时间为什么用文本输入而不是时间选择器
 * 课表里的时间是**挂钟模式**（「每周一 09:00」），用户打字五个字符就能填完，
 * 而 Material3 的时间选择器在当前版本仍属实验性 API，引入它要承担版本风险；
 * 更关键的是：解析逻辑放进这个纯函数后，「9:00」「09:00」「9：00」（全角冒号）
 * 这些实际会被敲出来的写法都能被测试覆盖，而不是靠肉眼在界面上试。
 *
 * ## 跨夜是合法的
 * `结束 <= 开始` 表示跨过午夜（例如 23:30–01:00），这是需求五节明确支持的形态，
 * 所以校验里**不禁止**它；但 `结束 == 开始` 会被拒绝 ——
 * 那等于把条目定义成 24 小时，几乎一定是误操作。
 */
object PlanForm {

    /** 课程名长度上限。比分类名宽松一些：课程名本来就长（「马克思主义基本原理」）。 */
    const val MAX_TITLE_LENGTH = 20

    const val MINUTES_PER_DAY = TimeOfDay.MINUTES_PER_DAY

    fun empty(): PlanFormState = PlanFormState()

    fun from(planBlock: PlanBlock): PlanFormState = PlanFormState(
        planBlockId = planBlock.id,
        title = planBlock.title,
        dayOfWeek = planBlock.dayOfWeek ?: 1,
        startText = TimeOfDay.minuteOfDayText(planBlock.startMinuteOfDay),
        endText = TimeOfDay.minuteOfDayText(planBlock.endMinuteOfDay),
        categoryId = planBlock.categoryId,
        activityId = planBlock.activityId,
        location = planBlock.location.orEmpty(),
        validFromEpochDay = planBlock.validFromEpochDay,
        validToEpochDay = planBlock.validToEpochDay,
        isActive = planBlock.isActive,
    )

    fun validate(state: PlanFormState): PlanFormResult {
        val title = state.title.trim()
        if (title.isEmpty()) return PlanFormResult.Invalid(PlanFormError.EMPTY_TITLE)
        if (title.length > MAX_TITLE_LENGTH) return PlanFormResult.Invalid(PlanFormError.TITLE_TOO_LONG)

        val start = TimeOfDay.parseMinuteOfDay(state.startText)
            ?: return PlanFormResult.Invalid(PlanFormError.INVALID_TIME)
        val end = TimeOfDay.parseMinuteOfDay(state.endText)
            ?: return PlanFormResult.Invalid(PlanFormError.INVALID_TIME)

        // 领域模型要求 开始 0..1439、结束 1..1440：开始不能是 24:00，结束不能是 00:00
        if (start > MINUTES_PER_DAY - 1) return PlanFormResult.Invalid(PlanFormError.INVALID_TIME)
        if (end < 1) return PlanFormResult.Invalid(PlanFormError.INVALID_TIME)

        if (start == end) return PlanFormResult.Invalid(PlanFormError.ZERO_DURATION)

        return PlanFormResult.Valid(
            PlanBlock(
                id = state.planBlockId ?: 0L,
                title = title,
                // V1 的表单只支持每周重复；单次日程数据层已支持，界面留待后续
                dateEpochDay = null,
                dayOfWeek = state.dayOfWeek,
                startMinuteOfDay = start,
                endMinuteOfDay = end,
                categoryId = state.categoryId,
                activityId = state.activityId,
                location = state.location.trim().ifEmpty { null },
                validFromEpochDay = state.validFromEpochDay,
                validToEpochDay = state.validToEpochDay,
                isActive = state.isActive,
            )
        )
    }

    /**
     * 时间的解析、格式化与时长计算统一放在 [TimeOfDay]（记录编辑表单也要用）。
     * 这里**不保留**同名转发方法：多一个入口，就多一处将来会分叉的地方。
     */
}
