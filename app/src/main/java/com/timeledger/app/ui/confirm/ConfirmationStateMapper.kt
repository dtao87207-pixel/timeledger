package com.timeledger.app.ui.confirm

import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.model.Activity
import com.timeledger.app.domain.model.Category
import com.timeledger.app.domain.model.RecordSource
import com.timeledger.app.domain.review.DayReview
import com.timeledger.app.ui.day.CatalogLookup
import com.timeledger.app.ui.day.PickerOption
import com.timeledger.app.ui.format.DisplayFormat
import java.time.ZoneId

/**
 * 复盘数据 → 事后确认页界面状态的**纯函数**映射。
 *
 * 与其他界面映射器同样的理由：本机无法目视验证界面，
 * 所以「挑出哪些要问用户、缺口的门槛是多少、能不能提交」这些判断都在这里被测试钉死。
 */
class ConfirmationStateMapper(
    private val zone: ZoneId,
    /**
     * 多长的缺口才值得问用户。
     *
     * 需求十二节要求提醒「低打扰」。三分钟的空档问一次「你做了什么」，
     * 用户很快就会把这个 App 的通知关掉 —— 而通知一关，整个产品就废了。
     * 所以默认只问十分钟以上的空档（更短的仍然照常显示在首页的缺失时间里）。
     */
    private val minGapMinutes: Int = DEFAULT_MIN_GAP_MINUTES,
) {

    fun map(
        review: DayReview,
        categories: List<Category>,
        activities: List<Activity>,
        editor: ConfirmEditorState?,
        editorError: ConfirmEditorError?,
    ): ConfirmationUiState {
        val catalog = CatalogLookup.from(categories, activities)
        val pending = pendingRecords(review, catalog)
        val gaps = gaps(review)

        return ConfirmationUiState(
            dateLabel = DisplayFormat.dateLabel(review.epochDay),
            pendingRecords = pending,
            gaps = gaps,
            summaryLabel = summaryLabel(pending, gaps),
            isEmpty = pending.isEmpty() && gaps.isEmpty(),
            editor = editor?.let { editorUi(it, categories, activities, editorError) },
        )
    }

    /**
     * 待确认的记录。
     *
     * 从**时间轴**里取而不是从原始记录里取，是有意的：一条推测记录如果整段都被
     * 用户手动记录覆盖了，它在时间轴上就不存在了 —— 那种情况下没什么可确认的，
     * 不该再拿出来问用户。只有真正在时间轴上占着一段的推测记录才需要确认。
     *
     * 同一条记录可能占多段（中间被更高优先级记录穿过），按 recordIndex 合并回一条。
     */
    private fun pendingRecords(
        review: DayReview,
        catalog: CatalogLookup,
    ): List<PendingRecordUi> = review.timeline.segments
        .filter { it.source == RecordSource.SUGGESTED }
        .groupBy { it.recordIndex }
        .map { (_, segments) ->
            val first = segments.first()
            val record = first.record
            val start = segments.minOf { it.range.startMillis }
            val end = segments.maxOf { it.range.endMillisExclusive }
            PendingRecordUi(
                recordId = record.id,
                rangeLabel = rangeLabel(start, end),
                durationLabel = DisplayFormat.compactDuration(end - start),
                title = catalog.activityName(record.activityId)
                    ?: catalog.categoryName(record.categoryId)
                    // 没有归属时用备注兜底：系统采集到的 App 名存在那里，
                    // 显示「微信」比显示「未归类」更容易让用户判断
                    ?: record.note?.trim()?.takeIf { it.isNotEmpty() }
                    ?: UNCLASSIFIED,
                confidenceLabel = "${SUGGESTED_LABEL} · 置信度 ${DisplayFormat.percentLabel(record.confidence)}",
                colorArgb = catalog.activityColor(record.activityId)
                    ?: catalog.categoryColor(record.categoryId),
            ) to start
        }
        .sortedBy { (_, start) -> start }
        .map { (row, _) -> row }

    /** 只看够长的缺口：太短的问一次只会让用户关掉通知。 */
    private fun gaps(review: DayReview): List<GapUi> {
        val threshold = minGapMinutes * 60_000L
        return review.durations.unrecordedSegments
            .filter { it.durationMillis >= threshold }
            .map { gap ->
                GapUi(
                    startMillis = gap.startMillis,
                    endMillisExclusive = gap.endMillisExclusive,
                    rangeLabel = rangeLabel(gap.startMillis, gap.endMillisExclusive),
                    durationLabel = DisplayFormat.compactDuration(gap.durationMillis),
                )
            }
    }

    private fun summaryLabel(pending: List<PendingRecordUi>, gaps: List<GapUi>): String {
        if (pending.isEmpty() && gaps.isEmpty()) return "这一天的记录都已经确认过了"

        val parts = buildList {
            if (pending.isNotEmpty()) add("${pending.size} 条待确认")
            if (gaps.isNotEmpty()) {
                val total = gaps.sumOf { it.endMillisExclusive - it.startMillis }
                add("${gaps.size} 段缺失时间（${DisplayFormat.compactDuration(total)}）")
            }
        }
        return parts.joinToString("，")
    }

    private fun editorUi(
        editor: ConfirmEditorState,
        categories: List<Category>,
        activities: List<Activity>,
        error: ConfirmEditorError?,
    ): ConfirmEditorUiState {
        val activityOptions = activities
            .filterNot { it.isArchived }
            // 只列所选分类下的活动：不可能选出「活动与分类不一致」的组合
            .filter { it.categoryId == editor.categoryId }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
            .map { activity ->
                PickerOption(
                    id = activity.id,
                    label = activity.name,
                    colorArgb = activity.colorArgb
                        ?: categories.firstOrNull { it.id == activity.categoryId }?.colorArgb,
                )
            }

        val hasTarget = editor.categoryId != null || editor.activityId != null

        return ConfirmEditorUiState(
            title = if (editor.isGap) "补记这段时间" else "确认这段做了什么",
            rangeLabel = rangeLabel(editor.startMillis, editor.endMillisExclusive),
            durationLabel = DisplayFormat.compactDuration(
                editor.endMillisExclusive - editor.startMillis
            ),
            isGap = editor.isGap,
            categories = categories
                .filterNot { it.isArchived }
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))
                .map { PickerOption(it.id, it.name, it.colorArgb) },
            activities = activityOptions,
            selectedCategoryId = editor.categoryId,
            selectedActivityId = editor.activityId,
            note = editor.note,
            canSave = hasTarget,
            errorMessage = error?.let(::errorMessage),
        )
    }

    /** 与别处一致：跨到下一个日历日时写「次日」，否则用户分不清是哪天。 */
    private fun rangeLabel(startMillis: Long, endMillisExclusive: Long): String {
        val startLabel = DisplayFormat.clockLabel(startMillis, zone)
        val endLabel = DisplayFormat.clockLabel(endMillisExclusive, zone)
        val startDate = java.time.Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate()
        val endDate = java.time.Instant.ofEpochMilli(endMillisExclusive).atZone(zone).toLocalDate()
        return if (startDate == endDate) "$startLabel–$endLabel" else "$startLabel–次日 $endLabel"
    }

    private fun errorMessage(error: ConfirmEditorError): String = when (error) {
        ConfirmEditorError.MISSING_TARGET -> "请选择分类，或指定一个具体活动"
        ConfirmEditorError.SAVE_FAILED -> "保存失败：所选的分类或活动已不可用，请重新选择"
    }

    companion object {
        const val DEFAULT_MIN_GAP_MINUTES = 10
        private const val UNCLASSIFIED = "未归类"
        private const val SUGGESTED_LABEL = "系统推测"
    }
}

/** 表单的原始输入（补记与确认共用同一个形状）。 */
data class ConfirmEditorState(
    /** 非空 = 确认已有记录；null = 填补缺口。 */
    val recordId: Long? = null,
    val startMillis: Long,
    val endMillisExclusive: Long,
    val categoryId: Long? = null,
    val activityId: Long? = null,
    val note: String = "",
) {
    val isGap: Boolean get() = recordId == null

    val range: MillisRange get() = MillisRange(startMillis, endMillisExclusive)
}
