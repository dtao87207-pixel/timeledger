package com.timeledger.app.domain.plan

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.model.PlanBlock

/**
 * 计划展开器 —— 把「每周重复的课表」落到**某一天的具体时段**上。
 *
 * ## 为什么这件事不简单
 * 逻辑日是 `当天 04:00 ~ 次日 03:59`（需求十一节），它**横跨两个日历日**。
 * 于是「今天有哪些计划」不能靠一次 SQL 过滤回答：
 *
 * - 周二 09:00 的课 → 属于周二那一格；
 * - **周三 01:00 的日程 → 其实属于周二那一格**（因为凌晨 4 点才翻篇）；
 * - 周二 23:30–01:00 的跨夜时段 → 整条属于周二，结束时刻落在周三。
 *
 * 所以本类的做法是：同时看日历日 `D` 与 `D+1` 两天的候选条目，
 * 换算成绝对时刻后，用 `DayBoundary.dayKeyOf` **统一判定归属**，
 * 只保留真正属于 `D` 的那些。规则只有一处实现（`TimeBoundary`），
 * 本类里不另写一套「几点算今天」的判断 —— 那正是最容易长出第二个错误答案的地方。
 *
 * ## 结束时刻为什么不用 start + 时长
 * 因为夏令时。美东 2026-03-08 那天「01:30 + 2 小时」并不是 03:30 的绝对时间。
 * 这里改成用**挂钟时间**推算结束时刻（当天/次日 00:00 起算的第 N 分钟），
 * 与课表「09:00 就是挂钟 9 点」的语义一致，跨夏令时也不会偏移一小时。
 * 注意 04:00 这条分界线本身不需要特殊处理：它只影响**归属**，不影响时刻换算。
 *
 * ## 允许重叠
 * 两门课时间冲突时会返回两个时段，本类不做消解也不报错 ——
 * 排课冲突是真实存在的，应该让用户看见，而不是被系统悄悄丢掉一个。
 */
class PlanBlockExpander(
    private val dayBoundary: DayBoundary,
    /**
     * 学期起始日（模块 28）：判定「第几周」的参照点。
     *
     * 用**函数**而不是值，是因为它来自设置、随时可能被用户改；
     * 默认返回 null 表示「用户还没设过」——这时所有计划按「每周都上」处理，
     * 与加周次这一列之前的行为完全一致，所以构造它的老代码一行都不用改。
     */
    private val termStartProvider: () -> java.time.LocalDate? = { null },
) {

    /**
     * 展开 [epochDay] 这一逻辑日的全部计划时段，按开始时间升序。
     *
     * 传入的是**全部**计划条目（通常几十条，直接全取即可），
     * 过滤与展开都在这里完成，调用方不需要自己筛「今天有哪些课」。
     */
    fun expandForDay(blocks: List<PlanBlock>, epochDay: Long): List<PlannedSlot> {
        if (blocks.isEmpty()) return emptyList()

        val termStart = termStartProvider()
        val slots = mutableListOf<PlannedSlot>()
        // D 与 D+1：深夜 04:00 之前的条目属于「前一天」那一格，所以必须看次日。
        for (calendarDay in epochDay..(epochDay + 1)) {
            val dayOfWeek = dayBoundary.dayOfWeek(calendarDay)
            for (block in blocks) {
                if (!block.isActive) continue
                if (!block.occursOn(calendarDay, dayOfWeek)) continue
                // 周次过滤（模块 28）：课表写的是「1-3周,5-17周」这种，
                // 不判周次就会在没课的周里说「计划有课但没记录」——那是谎报。
                // 没设学期起点时 WeekSpec 一律放行（见构造函数）。
                if (!occursInWeek(block, calendarDay, termStart)) continue

                val slot = toSlot(block, calendarDay) ?: continue
                // 归属判定是唯一的过滤依据：D+1 的条目只有落在 04:00 之前才属于 D。
                if (slot.logicalDay != epochDay) continue
                slots += slot
            }
        }

        // 同一时刻的两门课谁先显示？按 id 兜底，保证渲染顺序稳定。
        return slots.sortedWith(compareBy({ it.startMillis }, { it.planBlockId }))
    }

    /** 该条目在 [calendarDay] 这个**日历日**上是否发生。 */
    /**
     * 这一条计划在 [calendarDay] 那天上不上（周次判定，模块 28）。
     *
     * 三件事决定它「放行」：
     * - 计划没写周次（`weeksSpec` 为空）→ 每周都上，这是加这一列之前的语义；
     * - 用户没设学期起点 → 无法判定第几周，**一律放行**（宁可多显示一次计划，
     *   也不要因为缺一个设置就把用户的课从计划里抹掉）；
     * - 其余情况交给 [WeekSpec] × [TermCalendar] 判定。
     */
    private fun occursInWeek(
        block: PlanBlock,
        calendarDay: Long,
        termStart: java.time.LocalDate?,
    ): Boolean {
        val spec = WeekSpec.parse(block.weeksSpec)
        if (spec.isEveryWeek) return true
        if (termStart == null) return true

        val week = TermCalendar.weekNumberOn(
            date = java.time.LocalDate.ofEpochDay(calendarDay),
            termStart = termStart,
        )
        return spec.matches(week)
    }

    private fun PlanBlock.occursOn(calendarDay: Long, dayOfWeek: Int): Boolean {
        val singleDate = dateEpochDay
        return if (singleDate != null) {
            singleDate == calendarDay
        } else {
            this.dayOfWeek == dayOfWeek
        }
    }

    private fun toSlot(block: PlanBlock, calendarDay: Long): PlannedSlot? {
        val startMillis = dayBoundary.millisAtMinuteOfDay(calendarDay, block.startMinuteOfDay)
        val endMillis = endMillisOf(block, calendarDay)
        // 正常构造下不会发生（PlanBlock 保证分钟数合法、仓库拒绝零时长），
        // 但这里是唯一把两个时刻凑到一起的地方，宁可跳过也不要产出一个负长度区间。
        if (endMillis < startMillis) return null

        val logicalDay = dayBoundary.dayKeyOf(startMillis)
        val from = block.validFromEpochDay
        val to = block.validToEpochDay
        // 有效期按**这条计划自己的逻辑日**判断，而不是按查询的那个日历日。
        if (from != null && logicalDay < from) return null
        if (to != null && logicalDay > to) return null

        return PlannedSlot(
            planBlockId = block.id,
            title = block.title,
            logicalDay = logicalDay,
            range = MillisRange(startMillis, endMillis),
            categoryId = block.categoryId,
            activityId = block.activityId,
            location = block.location,
            note = block.note,
            crossesMidnight = block.crossesMidnight,
        )
    }

    /**
     * 结束时刻（挂钟时间推算，见类注释）。
     *
     * [PlanBlock.endMinuteOfDay] 为 1440 表示「到当日 24:00 结束」，
     * 等价于次日 00:00，因此要落到次日那一格的第 0 分钟。
     */
    private fun endMillisOf(block: PlanBlock, calendarDay: Long): Long {
        val spillsIntoNextCalendarDay = block.crossesMidnight || block.endMinuteOfDay == 1440
        return if (spillsIntoNextCalendarDay) {
            dayBoundary.millisAtMinuteOfDay(calendarDay + 1, block.endMinuteOfDay % 1440)
        } else {
            dayBoundary.millisAtMinuteOfDay(calendarDay, block.endMinuteOfDay)
        }
    }
}
