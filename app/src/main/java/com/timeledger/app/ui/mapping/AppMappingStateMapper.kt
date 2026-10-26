package com.timeledger.app.ui.mapping

import com.timeledger.app.domain.model.Activity
import com.timeledger.app.domain.model.AppMapping
import com.timeledger.app.domain.model.Category
import com.timeledger.app.domain.usage.AppUsageHint
import com.timeledger.app.ui.day.CatalogLookup
import com.timeledger.app.ui.day.PickerOption
import com.timeledger.app.ui.format.DisplayFormat

/**
 * App 归类数据 → 界面状态的**纯函数**映射。
 *
 * 与其他界面映射器同样的理由：本机无法目视验证界面，
 * 所以「哪些算待处理、怎么排序、显示什么名字」这些判断都在这里被测试钉死。
 *
 * ## 一条容易搞错的分组规则
 * 「待归类」不等于 `isUnresolved`：用户可能已经明确说过「这个 App 不用归类」
 * （那时 `isUserConfirmed` 为 true、归属仍为空）。那种情况必须算**已处理** ——
 * 否则用户刚做完决定，那个 App 还赖在待办列表里，他会以为没保存成功。
 */
class AppMappingStateMapper(
    private val nowMillis: () -> Long,
) {

    fun map(
        mappings: List<AppMapping>,
        hints: Map<String, AppUsageHint>,
        categories: List<Category>,
        activities: List<Activity>,
        form: AppMappingFormState?,
        formError: AppMappingFormError?,
    ): AppMappingUiState {
        val catalog = CatalogLookup.from(categories, activities)

        val unresolved = mappings
            .filter { it.isUnresolved && !it.isUserConfirmed }
            .map { mapping -> mapping to row(mapping, hints[mapping.packageName], catalog) }

        // 用得够多的才请用户归类；用时很少的收进「另有 N 个」，不打扰
        val pending = unresolved
            .filter { (mapping, _) -> isWorthAsking(hints[mapping.packageName]) }
            .map { (_, ui) -> ui }
            .sortedWith(pendingOrder(mappings, hints))

        val minor = unresolved
            .filterNot { (mapping, _) -> isWorthAsking(hints[mapping.packageName]) }
            .map { (_, ui) -> ui }
            .sortedWith(pendingOrder(mappings, hints))

        val handled = mappings
            .filterNot { it.isUnresolved && !it.isUserConfirmed }
            .map { row(it, hints[it.packageName], catalog) }
            .sortedWith(pendingOrder(mappings, hints))

        return AppMappingUiState(
            pendingRows = pending,
            minorRows = minor,
            handledRows = handled,
            summaryLabel = summaryLabel(pending, minor),
            isEmpty = mappings.isEmpty(),
            emptyMessage = "系统还没有记录到任何 App。给「使用情况访问」授权之后，" +
                "用过的 App 会出现在这里，你只需要给它们指定归属。",
            form = form?.let { formUi(it, categories, activities, formError) },
        )
    }

    /**
     * 值不值得请用户归类。
     *
     * 没有使用记录（窗口内没出现过）的一律不打扰：那说明它是很久以前用过的，
     * 现在翻出来问「这是干什么用的」，用户还得先回忆半天。
     */
    private fun isWorthAsking(hint: AppUsageHint?): Boolean =
        hint != null && hint.isWorthAsking()

    /**
     * 排序：最近用过的排前面，没用过的排最后。
     *
     * 用户的时间和注意力有限，先处理「昨天刚用了两小时」的那个 App，
     * 比按包名字母序有意义得多。
     */
    private fun pendingOrder(
        mappings: List<AppMapping>,
        hints: Map<String, AppUsageHint>,
    ): Comparator<AppMappingRowUi> {
        val lastSeen = mappings.associate { it.packageName to hints[it.packageName]?.lastForegroundMillis }
        return compareByDescending<AppMappingRowUi> { lastSeen[it.packageName] ?: Long.MIN_VALUE }
            .thenBy { it.packageName }
    }

    private fun row(mapping: AppMapping, hint: AppUsageHint?, catalog: CatalogLookup): AppMappingRowUi {
        val activityName = catalog.activityName(mapping.activityId)
        val categoryName = catalog.categoryName(mapping.categoryId)
        val hasBinding = activityName != null || categoryName != null

        val binding = when {
            activityName != null && categoryName != null -> "$categoryName / $activityName"
            activityName != null -> activityName
            categoryName != null -> categoryName
            // 归属为空 + 用户确认过 = 用户说过「这个 App 不用归类」
            mapping.isUserConfirmed -> "已确认：不归类"
            else -> "未确定"
        }

        // 有归属但用户没确认过 = 系统猜的。必须标出来：
        // 否则用户会以为「娱乐」这个归类是自己定的，而实际上那条记录还在待确认列表里等他点头。
        val bindingLabel = if (hasBinding && !mapping.isUserConfirmed) "$binding（系统推测）" else binding

        return AppMappingRowUi(
            packageName = mapping.packageName,
            displayName = mapping.appLabel?.takeIf { it.isNotBlank() } ?: mapping.packageName,
            bindingLabel = bindingLabel,
            usageLabel = usageLabel(hint),
            // 颜色优先取活动自己的，其次分类的：与时间轴、统计页的取色顺序一致
            colorArgb = catalog.activityColor(mapping.activityId)
                ?: catalog.categoryColor(mapping.categoryId),
            isUserConfirmed = mapping.isUserConfirmed,
            categoryId = mapping.categoryId,
            activityId = mapping.activityId,
        )
    }

    private fun usageLabel(hint: AppUsageHint?): String? {
        val lastSeen = hint?.lastForegroundMillis ?: return null
        val relative = DisplayFormat.relativeTimeLabel(lastSeen, nowMillis())
        val duration = DisplayFormat.compactDuration(hint.foregroundMillis)
        // 注意 ${relative} 的花括号不能省：中文也是合法的标识符字符，
        // "$relative用过" 会被当成一个叫「relative用过」的变量
        return "${relative}用过 · 打开 ${hint.sessionCount} 次 · 共 $duration"
    }

    /**
     * 一句话说清「还剩多少要你做」，并且**把打扰量说在明面上**。
     *
     * 用时很少的那批单独说明：用户看到「另有 24 个用时很少的 App」时，
     * 应该能立刻明白系统没有把它们当成待办 —— 否则他会以为还得处理 47 个。
     */
    private fun summaryLabel(pending: List<AppMappingRowUi>, minor: List<AppMappingRowUi>): String {
        val base = when {
            pending.isEmpty() -> "用得多的 App 都已经归类了"
            pending.size == 1 -> "还有 1 个 App 等着归类 —— 归类之后它的记录就会自动出现在时间轴里"
            else -> "还有 ${pending.size} 个 App 等着归类 —— 归类之后它们的记录就会自动出现在时间轴里"
        }
        if (minor.isEmpty()) return base
        return "$base（另有 ${minor.size} 个用时很少的 App，不催你处理）"
    }

    private fun formUi(
        form: AppMappingFormState,
        categories: List<Category>,
        activities: List<Activity>,
        error: AppMappingFormError?,
    ): AppMappingFormUi {
        val activityOptions = activities
            .filterNot { it.isArchived }
            // 只列所选分类下的活动：不可能选出「活动与分类不一致」的组合（会被仓库拒绝）
            .filter { it.categoryId == form.categoryId }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
            .map { activity ->
                PickerOption(
                    id = activity.id,
                    label = activity.name,
                    colorArgb = activity.colorArgb
                        ?: categories.firstOrNull { it.id == activity.categoryId }?.colorArgb,
                )
            }

        return AppMappingFormUi(
            form = form,
            title = "给「${form.displayName}」指定归属",
            categories = categories
                .filterNot { it.isArchived }
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))
                .map { PickerOption(it.id, it.name, it.colorArgb) },
            activities = activityOptions,
            // 这条提示是必须的：什么都不选就保存，含义是「明确不归类」，
            // 之后系统不会再拿这个 App 来问 —— 不说清楚，用户会以为是没填完
            hint = "两者都不选也可以保存，表示「这个 App 不用归类」，之后不会再问你",
            errorMessage = error?.let(::errorMessage),
        )
    }

    private fun errorMessage(error: AppMappingFormError): String = when (error) {
        AppMappingFormError.SAVE_FAILED -> "保存失败：所选的分类或活动已不可用，请重新选择"
    }
}
