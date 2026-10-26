package com.timeledger.app.ui.mapping

import com.timeledger.app.ui.day.PickerOption

/** 映射列表里的一行（一个包名一行）。 */
data class AppMappingRowUi(
    val packageName: String,
    /** 有应用名就用应用名，否则显示包名 —— 后者难看，但总比什么都不显示好。 */
    val displayName: String,
    /** `学习 / Java` 或 `未确定` 或 `已确认：不归类`。 */
    val bindingLabel: String,
    /** `最近 2 小时前用过 · 打开 8 次`；窗口内没出现过则为 null。 */
    val usageLabel: String?,
    val colorArgb: Int?,
    /** 系统猜的还是用户说的。界面上要能区分，否则用户不知道自己改过什么。 */
    val isUserConfirmed: Boolean,
    /** 当前归属，用于打开编辑框时预填。 */
    val categoryId: Long?,
    val activityId: Long?,
)

/** 编辑某个 App 归属时的表单状态。 */
data class AppMappingFormState(
    val packageName: String,
    val displayName: String,
    val categoryId: Long? = null,
    val activityId: Long? = null,
)

enum class AppMappingFormError {
    /** 仓库拒绝了写入（分类/活动已不可用等）。 */
    SAVE_FAILED,
}

/** 表单的界面状态。 */
data class AppMappingFormUi(
    val form: AppMappingFormState,
    val title: String,
    val categories: List<PickerOption>,
    /** 当前所选分类下的活动。 */
    val activities: List<PickerOption>,
    val hint: String,
    val errorMessage: String?,
)

/**
 * App 归类页的状态（需求九节）。
 *
 * 分成三组是有意的：
 * - **待归类**：用得够多、值得用户花一次时间归类的 App；
 * - **用时很少**：天气、信息、计算器这类 —— 照样记录，但不催用户（真机数据里
 *   47 个待归类有 24 个用时不到 1 分钟，逐个问就是骚扰）；
 * - **已处理**：改错了才动它。
 *
 * 全部混在一起排的话，用户每次进来都要重新找「还有哪些没处理」——
 * 而这个页面存在的意义恰恰是让那件事一眼可见、且越用越少。
 */
data class AppMappingUiState(
    val pendingRows: List<AppMappingRowUi>,
    /** 用过但用时很少，不打扰用户的那批。 */
    val minorRows: List<AppMappingRowUi>,
    val handledRows: List<AppMappingRowUi>,
    val summaryLabel: String,
    /** 系统还一个 App 都没见过（多半是没授权，或刚装上）。 */
    val isEmpty: Boolean,
    val emptyMessage: String,
    val form: AppMappingFormUi?,
) {
    val hasPending: Boolean get() = pendingRows.isNotEmpty()

    val isFormOpen: Boolean get() = form != null

    /** 「另有 N 个 App 用时很少」这一行只在确实有内容时显示。 */
    val hasMinor: Boolean get() = minorRows.isNotEmpty()
}
