package com.timeledger.app.domain.plan

/**
 * 课表里的「上哪些周」（模块 28）。
 *
 * ## 为什么需要它
 * 真实课表里几乎每门课都带周次 —— 从用户的课表里逐字取出来的原文就是这些形态：
 *
 * ```
 * 1-17周          第18周          1周,4-6周,8-15周
 * 1-3周,5-17周    3周,15-17周      单周 / 双周
 * ```
 *
 * 而计划块原本只有「每周重复 + 生效区间（一段日期）」。硬把 `1-3周,5-17周`
 * 当成「每周都上」，App 会在没课的那些周里显示「计划有课但没记录」——
 * **等于让 App 对用户说谎**，这个项目里最不能接受的一类错误。
 *
 * ## 为什么存原文、解析放在这里
 * 课表给的文本原样存进 `weeks_spec`（用户能在界面上核对），解析成可判定的形式放在这个纯函数里。
 * 换成位图存储会让「用户看到的」和「库里存的」对不上，也丢掉原文。
 *
 * ## 一条诚实优先的规则：解析不了就不阻断
 * 遇到看不懂的写法时 [isUnderstood] 为 false，[matches] 一律返回 true ——
 * **宁可多显示一次计划，也不要因为解析失败把课从计划里悄悄抹掉**。
 * 界面可以据此提示「周次没看懂」，但不能替用户做删除决定。
 */
data class WeekSpec private constructor(
    /** 原始文本（界面上原样显示）。 */
    val source: String,
    private val ranges: List<IntRange>,
    /** 单双周：1 = 单周，2 = 双周，null = 不限。 */
    private val parity: Int?,
    /** 是否解析成功；false 时 [matches] 恒为 true。 */
    val isUnderstood: Boolean,
) {

    /** 第 [weekNumber] 周上不上这门课。 */
    fun matches(weekNumber: Int): Boolean {
        if (!isUnderstood) return true
        if (weekNumber <= 0) return false
        if (parity != null && weekNumber % 2 != parity % 2) return false
        if (ranges.isEmpty()) return true
        return ranges.any { weekNumber in it }
    }

    /** 没有任何限制（每周都上）—— 加这一列之前所有计划块的语义。 */
    val isEveryWeek: Boolean
        get() = isUnderstood && parity == null && ranges.isEmpty()

    companion object {

        /** 每周都上。 */
        val EVERY_WEEK = WeekSpec(source = "", ranges = emptyList(), parity = null, isUnderstood = true)

        private val RANGE = Regex("^(\\d{1,2})\\s*[-~—]\\s*(\\d{1,2})$")
        private val SINGLE = Regex("^(\\d{1,2})$")

        /**
         * 解析课表文本。空/空白 = 每周都上；看不懂的写法会被标记为未解析（见类注释）。
         */
        fun parse(text: String?): WeekSpec {
            val raw = text?.trim().orEmpty()
            if (raw.isEmpty()) return EVERY_WEEK

            // 归一：去掉「第」「周」，全角逗号与顿号统一成半角
            val normalized = raw
                .replace("第", "")
                .replace("周", "")
                .replace("，", ",")
                .replace("、", ",")
                .replace(" ", "")

            var parity: Int? = null
            val ranges = mutableListOf<IntRange>()

            normalized.split(',').filter { it.isNotEmpty() }.forEach { token ->
                when {
                    token == "单" -> parity = 1
                    token == "双" -> parity = 2
                    token.endsWith("单") -> {
                        parity = 1
                        addRange(token.removeSuffix("单"), ranges)
                    }
                    token.endsWith("双") -> {
                        parity = 2
                        addRange(token.removeSuffix("双"), ranges)
                    }
                    else -> addRange(token, ranges)
                }
            }

            val understood = ranges.isNotEmpty() || parity != null
            return WeekSpec(
                source = raw,
                ranges = ranges.sortedBy { it.first },
                parity = parity,
                isUnderstood = understood,
            )
        }

        private fun addRange(token: String, into: MutableList<IntRange>) {
            RANGE.matchEntire(token)?.let { match ->
                val from = match.groupValues[1].toIntOrNull() ?: return
                val to = match.groupValues[2].toIntOrNull() ?: return
                if (from in 1..60 && to in from..60) into += from..to
                return
            }
            SINGLE.matchEntire(token)?.let { match ->
                val week = match.groupValues[1].toIntOrNull() ?: return
                if (week in 1..60) into += week..week
            }
        }
    }
}
