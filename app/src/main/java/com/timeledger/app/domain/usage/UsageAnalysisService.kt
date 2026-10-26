package com.timeledger.app.domain.usage

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.model.RecordSource
import com.timeledger.app.domain.model.TimeRecordDraft
import com.timeledger.app.domain.model.UsageEvent
import com.timeledger.app.domain.repository.AppMappingRepository
import com.timeledger.app.domain.repository.CategoryRepository
import com.timeledger.app.domain.repository.RejectReason
import com.timeledger.app.domain.repository.SaveOutcome
import com.timeledger.app.domain.repository.TimeRecordRepository
import com.timeledger.app.domain.repository.UsageEventRepository
import kotlinx.coroutines.flow.first

/** 一次分析的结果。界面需要它来解释「系统这次做了什么」。 */
data class UsageAnalysisResult(
    val epochDay: Long,
    /** 实际分析的区间（结束时刻已经被限制到「现在」）。 */
    val analyzedRange: MillisRange,
    /** 重建出的前台会话数。 */
    val sessionCount: Int,
    /** 实际写入的记录数。 */
    val writtenCount: Int,
    /** 其中系统来源（用户已归类，不需要确认）的条数。 */
    val systemCount: Int,
    /** 其中待确认的条数。 */
    val suggestedCount: Int,
    /** 被用户手动记录挡住而没有写入的条数（手动记录优先，这是正常结果）。 */
    val blockedByManualCount: Int,
    /** 还没有归类的 App，按停留时长从多到少 —— 界面据此提示「去归类」。 */
    val unresolvedPackages: List<String>,
) {
    val hasAnyEvent: Boolean get() = sessionCount > 0

    companion object {
        /** 这一天没有任何原始事件（多半是还没授予「使用情况访问」权限）。 */
        fun empty(epochDay: Long, range: MillisRange): UsageAnalysisResult =
            UsageAnalysisResult(
                epochDay = epochDay,
                analyzedRange = range,
                sessionCount = 0,
                writtenCount = 0,
                systemCount = 0,
                suggestedCount = 0,
                blockedByManualCount = 0,
                unresolvedPackages = emptyList(),
            )
    }
}

/**
 * 系统感知的编排入口（需求六、九节）：**把原始事件变成时间轴上看得见的记录**。
 *
 * 三步：取原始事件 → 重建前台会话并推断归属 → 先清空这一天的系统记录再写入。
 *
 * ## 为什么是「先清空再写入」，而不是只做增量写入
 * 增量写入靠幂等键去重，看似更省事，但有个致命问题：**改了 App 映射之后不会生效**。
 * 幂等键命中时写入会被跳过，于是用户把「微信」归到社交之后，时间轴上那几段仍然是
 * 未归类 —— 用户会以为这个功能坏了。重新分析这一天的语义正好对应的就是
 * 「按现在的映射重新算一遍」，而用户确认过的记录（MERGED）不会被清掉，
 * 所以他自己改过的结果不会被打回。
 *
 * ## 平台无关
 * 这个类不碰任何 Android API：原始事件由平台层采集后落库（`usage_events`），
 * 这里只读库。因此它可以完整地跑在 JVM 测试里 —— 平台层要真机，
 * 而「怎么把事件解释成记录」这件事不必等真机才能验证。
 */
class UsageAnalysisService(
    private val usageEventRepository: UsageEventRepository,
    private val appMappingRepository: AppMappingRepository,
    private val timeRecordRepository: TimeRecordRepository,
    private val dayBoundary: DayBoundary,
    private val sessionBuilder: ForegroundSessionBuilder = ForegroundSessionBuilder(),
    private val inferrer: UsageRecordInferrer = UsageRecordInferrer(),
    /**
     * 分析某一天时，向窗口开始之前多取多久的原始事件。
     *
     * 用途只有一个：还原「跨过 4:00 分界线的那一次会话」。
     * 只取窗口内的事件，会导致凌晨 03:50 打开的 App 在 04:00 之后的那一段彻底消失
     * （窗口里只有它的 PAUSED，没有 RESUMED）。
     */
    private val lookbackMillis: Long = DEFAULT_LOOKBACK_MILLIS,
    /**
     * 只能分析最近这么多天。
     *
     * 比这个更旧的日子直接跳过（不清空、不重建）—— 因为它们的原始事件可能已经被
     * 保留策略清理掉了，而「清空 + 重建」在缺少原始事件时会变成「清空 + 什么都写不出来」。
     * 取值远大于实际会分析的范围（只分析今天与昨天），所以正常使用中不会碰到这条边界。
     */
    private val analyzableDays: Long = DEFAULT_ANALYZABLE_DAYS,
    /**
     * 挑出「不属于用户在使用」的包名（模块 20）：桌面、系统界面、输入法、
     * 以及所有没有桌面入口的系统组件（权限管理、安装器、锁屏画报……）。
     *
     * 由平台层判断后传进来，默认什么都不排除 —— 这样测试不需要 Android 环境，
     * 而真机上由 `AndroidNonUsageAppResolver` 给出真实结果。
     */
    private val nonUsageAppResolver: NonUsageAppResolver = NonUsageAppResolver { emptySet() },
    /** 熄屏记录要归到哪个分类（内置分类，见 DefaultCatalog）。 */
    private val categoryRepository: CategoryRepository,
    private val screenOffCategoryName: String = "熄屏",
    /**
     * 多长的熄屏才值得记一条。
     *
     * **必须严格大于 [ForegroundSessionBuilder] 的合并窗口**，否则会出现
     * 「一段 4 分钟的熄屏被并进了上一个 App 的会话，同时又写了一条熄屏记录」
     * ——同一段时间被算了两次。构造时会检查这个不变式。
     */
    private val screenOffMinMillis: Long = ScreenOffPeriods.DEFAULT_MIN_MILLIS,
) {

    init {
        require(screenOffMinMillis > sessionBuilder.mergeGapMillisForCheck) {
            "熄屏阈值必须大于会话合并窗口，否则同一段时间会被算两次：" +
                "screenOffMinMillis=$screenOffMinMillis, mergeGap=${sessionBuilder.mergeGapMillisForCheck}"
        }
    }

    suspend fun analyzeDay(epochDay: Long): UsageAnalysisResult {
        val window = dayBoundary.dayRange(epochDay)
        val now = dayBoundary.nowMillis()

        // 太旧的日子不再分析，**也不清空**。
        //
        // 这条守卫是为了让「原始事件保留策略」成立：事件超过保留期会被清理，
        // 而重新分析的第一步是「清空这一天的系统记录再重建」——
        // 两者撞在一起就是「清空了、但已经没有原始事件可重建」，
        // 那一天的记录会凭空消失。有了这条守卫，清理旧事件永远安全。
        if (epochDay < dayBoundary.todayEpochDay() - analyzableDays) {
            return UsageAnalysisResult.empty(epochDay, window)
        }

        // 未来的日子没有事件可分析；「今天」的结束时刻必须收在现在，
        // 否则此刻还在用的 App 会被记成一直用到明天 04:00。
        if (now <= window.startMillis) return UsageAnalysisResult.empty(epochDay, window)
        val effectiveEnd = minOf(window.endMillisExclusive, now)
        val range = MillisRange(window.startMillis, effectiveEnd)

        val events = usageEventRepository.getEventsIn(
            MillisRange(window.startMillis - lookbackMillis, effectiveEnd)
        )

        // 先问平台层：观察到这些包名里，哪些根本不是「用户打开的应用」
        // （桌面、系统界面、输入法、权限管理、安装器……）。
        // 必须在重建会话之前问，否则这些包会先变成会话、再变成记录。
        val ignoredPackages = nonUsageAppResolver.resolve(
            events.mapNotNullTo(mutableSetOf()) { it.packageName }
        )

        val sessions = sessionBuilder.build(events, range, ignoredPackages)

        // 熄屏时段也要写成记录（模块 23）。它和会话是两类东西，各自独立提取：
        // 会话回答「在用什么」，熄屏回答「手机没在用」。
        val screenOffDrafts = screenOffRecords(events, range, epochDay)

        if (sessions.isEmpty() && screenOffDrafts.isEmpty()) {
            // 没有会话也要清一遍：昨天分析过、今天原始事件被清理了的情况下，
            // 留着旧的系统记录会让时间轴显示已经不成立的内容
            timeRecordRepository.clearSystemGeneratedIn(window)
            return UsageAnalysisResult.empty(epochDay, range)
        }

        val mappings = appMappingRepository.observeMappings().first().associateBy { it.packageName }
        val inferred = inferrer.infer(sessions, mappings, epochDay)

        // 见过的包名登记成「未确定归属」的映射，用户才能在映射界面里给它归类。
        // ensureDefaultMapping 不会覆盖用户已确认的映射（模块 1 已验证）。
        inferred.map { it.packageName }.distinct().forEach { packageName ->
            appMappingRepository.ensureDefaultMapping(packageName)
        }

        // 清空这一天的系统/推测记录后重新写入；手动记录与已确认记录不受影响
        timeRecordRepository.clearSystemGeneratedIn(window)

        var written = 0
        var system = 0
        var suggested = 0
        var blocked = 0

        // 用户手写的记录：系统记录要**绕着它们写**，而不是整条放弃（模块 26 第二件）。
        //
        // 真机上踩到的：用户手记了 09:31–09:59，而抖音那一段是 09:18–10:30 ——
        // 旧实现「有重叠就整条拒绝」，于是连着没被覆盖的那 1 小时一起消失了
        // （手机内置给抖音 1h54m，我们只写出 34m）。
        // 现在把手动记录覆盖的那几段挖掉，剩下的照写：用户的手记仍然赢，
        // 但系统知道的那部分不会被它一笔勾销。
        val manualRanges = timeRecordRepository.getRecordsIn(window)
            .filter { it.source == RecordSource.MANUAL }
            .map { MillisRange(it.startMillis, it.endMillis) }

        suspend fun save(draft: TimeRecordDraft, source: RecordSource, countedAsSystem: Boolean) {
            val pieces = clipAroundManual(draft, manualRanges, epochDay)

            // 被用户记录挡下来的部分照样要报出来（这是他「手动记录救了系统数据」的证据）：
            // 挖掉了中间一段、或者整条都没了，都算被挡下 —— 只是现在只挡下它覆盖的那部分。
            val trimmed = pieces.isEmpty() ||
                pieces.size > 1 ||
                pieces.first().startMillis != draft.startMillis ||
                pieces.first().endMillis != draft.endMillis
            if (trimmed) blocked++

            pieces.forEach { piece ->
                when (val outcome = timeRecordRepository.saveSystemRecord(piece, source)) {
                    is SaveOutcome.Saved -> {
                        written++
                        // SYSTEM = 用户已经归类过的 App，不需要他再确认一次
                        if (countedAsSystem) system++ else suggested++
                    }

                    SaveOutcome.Skipped -> Unit

                    is SaveOutcome.Rejected -> if (outcome.reason == RejectReason.OVERLAPS_MANUAL_RECORD) {
                        blocked++
                    }
                }
            }
        }

        inferred.forEach { record ->
            save(record.draft, record.source, countedAsSystem = record.source == RecordSource.SYSTEM)
        }

        // 熄屏记录：来源固定 SYSTEM（「手机当时没在用」是事实，没什么要确认的）。
        // 它同样受手动记录保护 —— 用户若已经手记了「睡眠 23:00–07:00」，
        // 系统不会拿熄屏去覆盖他（同样的「挖掉重叠、其余照写」）。
        screenOffDrafts.forEach { draft ->
            save(draft, RecordSource.SYSTEM, countedAsSystem = true)
        }

        return UsageAnalysisResult(
            epochDay = epochDay,
            analyzedRange = range,
            sessionCount = sessions.size,
            writtenCount = written,
            systemCount = system,
            suggestedCount = suggested,
            blockedByManualCount = blocked,
            // 按停留时长排序：界面上最该先处理的是「花时间最多但还没归类」的那个
            unresolvedPackages = inferred
                .filter { it.reason == InferenceReason.UNKNOWN_APP || it.reason == InferenceReason.UNRESOLVED_MAPPING }
                .groupBy { it.packageName }
                .mapValues { (_, records) -> records.sumOf { it.draft.endMillis - it.draft.startMillis } }
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                .map { it.key },
        )
    }

    /**
     * 把熄屏时段变成待写入的记录（模块 23）。
     *
     * 三个细节：
     * 1. **分类要现查**：内置分类「熄屏」由种子数据写入，但用户可以改名、归档甚至删掉它。
     *    查不到就**什么都不写**（返回空列表）—— 而不是写一条指向不存在分类的记录，
     *    那会被仓库直接拒绝。功能降级，但不崩、不产生垃圾数据。
     * 2. **短于阈值的时段不写**：掏口袋、看时间的几秒钟熄屏写成记录只会变成碎片。
     * 3. **幂等键用 `screen-off|` 前缀**：与 `usage|` 分开，这样熄屏不会混进「应用排行」。
     */
    private suspend fun screenOffRecords(
        events: List<UsageEvent>,
        range: MillisRange,
        dayKey: Long,
    ): List<TimeRecordDraft> {
        if (events.isEmpty()) return emptyList()

        val periods = ScreenOffPeriods.extract(events, range, screenOffMinMillis)
        if (periods.isEmpty()) return emptyList()

        val categoryId = categoryRepository.observeCategories(includeArchived = true)
            .first()
            .firstOrNull { it.name == screenOffCategoryName && !it.isArchived }
            ?.id
            ?: return emptyList()

        return periods.map { period ->
            TimeRecordDraft(
                startMillis = period.startMillis,
                endMillis = period.endMillisExclusive,
                categoryId = categoryId,
                activityId = null,
                // 备注留空：时间轴的标题会落到分类名「熄屏」。
                // 对系统记录来说，唯一有意义的信息就是「手机没在用」，分类名已经说完了。
                note = null,
                confidence = 1.0,
                externalKey = UsageRecordKeys.buildScreenOff(dayKey, period.startMillis),
            )
        }
    }

    /**
     * 把手动记录覆盖的那几段从 [draft] 里挖掉，返回剩下的部分（模块 26）。
     *
     * 为什么要挖而不是整条放弃：用户的手记只应该**赢下它自己那一小段**，
     * 不该顺手把系统知道的其余时间也一起抹掉（真机上抖音因此少了 1 小时）。
     *
     * 每一段的幂等键都要按新的开始时刻重建 —— 键里带着开始时刻，
     * 复用旧键会让第二段把第一段覆盖掉。
     */
    private fun clipAroundManual(
        draft: TimeRecordDraft,
        manualRanges: List<MillisRange>,
        dayKey: Long,
    ): List<TimeRecordDraft> {
        if (manualRanges.isEmpty() || draft.endMillis <= draft.startMillis) return listOf(draft)

        var pieces = listOf(MillisRange(draft.startMillis, draft.endMillis))
        manualRanges.forEach { manual ->
            pieces = pieces.flatMap { piece -> subtract(piece, manual) }
        }

        return pieces
            // 挖剩的边角料不值得记（与前台会话同一个门槛）
            .filter { it.durationMillis >= ForegroundSessionBuilder.DEFAULT_MIN_SESSION_MILLIS }
            .map { piece ->
                draft.copy(
                    startMillis = piece.startMillis,
                    endMillis = piece.endMillisExclusive,
                    externalKey = draft.externalKey?.let { key ->
                        rebuildKey(key, dayKey, piece.startMillis)
                    },
                )
            }
    }

    /** `[a, b)` 挖掉 `cut` 之后剩下的部分（最多两段）。 */
    private fun subtract(piece: MillisRange, cut: MillisRange): List<MillisRange> {
        val overlapStart = maxOf(piece.startMillis, cut.startMillis)
        val overlapEnd = minOf(piece.endMillisExclusive, cut.endMillisExclusive)
        if (overlapEnd <= overlapStart) return listOf(piece)

        return buildList {
            if (overlapStart > piece.startMillis) add(MillisRange(piece.startMillis, overlapStart))
            if (overlapEnd < piece.endMillisExclusive) {
                add(MillisRange(overlapEnd, piece.endMillisExclusive))
            }
        }
    }

    /** 按新的开始时刻重建幂等键（两种前缀各自成对）。 */
    private fun rebuildKey(key: String, dayKey: Long, startMillis: Long): String = when {
        UsageRecordKeys.isScreenOffKey(key) -> UsageRecordKeys.buildScreenOff(dayKey, startMillis)
        else -> UsageRecordKeys.packageNameOf(key)
            ?.let { packageName -> UsageRecordKeys.build(dayKey, startMillis, packageName) }
            ?: key
    }

    companion object {
        /**
         * 六小时。取值理由是「够覆盖一次跨 4:00 的连续使用，又不至于把整天的原始事件
         * 反复读一遍」：凌晨 4:00 往前六小时 = 前一天 22:00，足够接上任何合理的连续会话。
         */
        const val DEFAULT_LOOKBACK_MILLIS = 6 * 60 * 60 * 1000L

        /**
         * 三十天。足够覆盖「周末回看上周」，又远大于实际会被分析的范围。
         */
        const val DEFAULT_ANALYZABLE_DAYS = 30L
    }
}