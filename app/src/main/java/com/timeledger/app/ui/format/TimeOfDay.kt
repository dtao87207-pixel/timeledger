package com.timeledger.app.ui.format

/**
 * 「一天中的第几分钟」的解析与格式化 —— 课程表与记录编辑共用。
 *
 * 抽成独立一处，是因为两个表单都要把「用户敲进去的时间文本」转成分钟数、
 * 再转回文本；两份实现迟早会在细节上分叉（一个支持全角冒号、另一个不支持），
 * 而这类差异用户只会觉得「有时候能填有时候不能」。
 */
object TimeOfDay {

    const val MINUTES_PER_DAY = 1440

    /**
     * `09:00` / `9:00` / `9：00`（全角冒号）/ `9` → 距零点的分钟数；解析失败返回 null。
     *
     * `24:00` 解析为 1440（表示到当天 24 点结束），而 `24:30` 是非法的。
     */
    fun parseMinuteOfDay(text: String): Int? {
        val normalized = text.trim().replace('：', ':')
        if (normalized.isEmpty()) return null

        val parts = normalized.split(':')
        if (parts.size > 2) return null

        val hours = parts[0].toIntOrNull() ?: return null
        val minutes = if (parts.size == 2) parts[1].toIntOrNull() ?: return null else 0

        if (hours !in 0..24) return null
        if (minutes !in 0..59) return null
        if (hours == 24 && minutes != 0) return null

        return hours * 60 + minutes
    }

    /** 分钟数 → `09:00`；1440 → `24:00`。补零保证与解析互为逆运算。 */
    fun minuteOfDayText(minuteOfDay: Int): String {
        val hours = minuteOfDay / 60
        val minutes = minuteOfDay % 60
        return "${twoDigits(hours)}:${twoDigits(minutes)}"
    }

    /**
     * 时长文案：`1 小时 40 分` / `30 分钟`。
     *
     * 结束不晚于开始时**按跨夜处理**，自动补上跨过 24 点的部分；
     * 两端相同时返回 null（既不是跨夜也不是有效时长，双方各自处理）。
     */
    fun durationText(startText: String, endText: String): String? {
        val start = parseMinuteOfDay(startText) ?: return null
        val end = parseMinuteOfDay(endText) ?: return null
        if (end == start) return null

        val minutes = if (end > start) end - start else end + MINUTES_PER_DAY - start
        return durationText(minutes)
    }

    /** 分钟数 → 时长文案。 */
    fun durationText(totalMinutes: Int): String {
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "$hours 小时 $minutes 分"
            hours > 0 -> "$hours 小时"
            else -> "$minutes 分钟"
        }
    }

    private fun twoDigits(value: Int): String = if (value < 10) "0$value" else value.toString()
}
