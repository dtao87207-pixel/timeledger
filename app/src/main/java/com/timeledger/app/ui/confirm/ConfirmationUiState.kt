package com.timeledger.app.ui.confirm

import com.timeledger.app.ui.day.PickerOption

/**
 * 一条等待确认的记录。
 *
 * 按**记录**而不是按时间段列出：用户脑子里是「我下午那半小时干了什么」，
 * 一条记录可能被更高优先级的记录切成两段时间轴，但对用户来说它仍然是一件事。
 */
data class PendingRecordUi(
    val recordId: Long,
    val rangeLabel: String,
    val durationLabel: String,
    /** 推断出的活动名 / 分类名，都没有时是「未归类」。 */
    val title: String,
    /** `系统推测 · 置信度 40%`。 */
    val confidenceLabel: String,
    val colorArgb: Int?,
)

/** 一段没有任何记录的时间。 */
data class GapUi(
    val startMillis: Long,
    val endMillisExclusive: Long,
    val rangeLabel: String,
    val durationLabel: String,
)

/** 补记 / 确认时用的表单状态。 */
data class ConfirmEditorUiState(
    /** `确认这段做了什么` 或 `补记这段时间`。 */
    val title: String,
    val rangeLabel: String,
    val durationLabel: String,
    /** true = 填补缺失时间（新建记录）；false = 确认已有记录。 */
    val isGap: Boolean,
    val categories: List<PickerOption>,
    /** 当前所选分类下的活动。 */
    val activities: List<PickerOption>,
    val selectedCategoryId: Long?,
    val selectedActivityId: Long?,
    val note: String,
    val canSave: Boolean,
    val errorMessage: String?,
)

/** 补记 / 确认表单的失败原因。 */
enum class ConfirmEditorError {
    /** 既没选分类也没选活动 —— 那等于「确认了什么也没说」。 */
    MISSING_TARGET,
    SAVE_FAILED,
}

/**
 * 事后确认页状态（需求三节 C）。
 *
 * ## 这一页解决的是什么
 * 系统能检测到「手机发生了什么」，但不知道「用户在做什么」。
 * 所以它只把**说不清的部分**挑出来问：待确认的推测记录、以及完全没有记录的时段。
 *
 * 需求原文的要求很明确：
 * > 只要求用户确认：15:30–16:00 做了什么？而不是让用户重新记录整个下午。
 *
 * 因此列表里待确认的每一条都提供**一键确认**（用系统推断的结果直接确认），
 * 想改才需要打开表单 —— 大多数情况下用户只需要点一下。
 */
data class ConfirmationUiState(
    val dateLabel: String,
    val pendingRecords: List<PendingRecordUi>,
    val gaps: List<GapUi>,
    /** 一句话汇总，例如 `3 条待确认，2 段缺失时间`。 */
    val summaryLabel: String,
    /** 今天既没有待确认也没有值得处理的缺口。 */
    val isEmpty: Boolean,
    val editor: ConfirmEditorUiState?,
) {
    val hasPending: Boolean get() = pendingRecords.isNotEmpty()

    val hasGaps: Boolean get() = gaps.isNotEmpty()

    val isEditorOpen: Boolean get() = editor != null

    /** 「全部确认」只在确实有多条时才出现：只有一条时逐个点反而更快。 */
    val canConfirmAll: Boolean get() = pendingRecords.size > 1
}
