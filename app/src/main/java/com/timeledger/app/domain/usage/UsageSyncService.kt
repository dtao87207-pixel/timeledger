package com.timeledger.app.domain.usage

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.repository.AppMappingRepository
import com.timeledger.app.domain.repository.UsageEventRepository

/** 一次采集 + 分析的结果。界面用它说明「系统这次做了什么」。 */
data class UsageSyncResult(
    /** 是否拿到了「使用情况访问」权限。false 时后面的数字都不可信，界面要引导授权。 */
    val granted: Boolean,
    /** 本次新落库的原始事件条数（重复采集会得到 0，这是正常的）。 */
    val newEventCount: Int,
    /** 本次读到的原始事件总数（含已经在库里的）。 */
    val readEventCount: Int,
    /** 每个被分析的日子一条结果。 */
    val analyses: List<UsageAnalysisResult>,
    /** 本次清理掉的过期原始事件条数。 */
    val prunedEventCount: Int = 0,
) {
    val writtenCount: Int get() = analyses.sumOf { it.writtenCount }

    val suggestedCount: Int get() = analyses.sumOf { it.suggestedCount }

    val systemCount: Int get() = analyses.sumOf { it.systemCount }

    val blockedByManualCount: Int get() = analyses.sumOf { it.blockedByManualCount }

    val sessionCount: Int get() = analyses.sumOf { it.sessionCount }

    /** 还没归类的 App（跨多天合并，按停留时长降序）。 */
    val unresolvedPackages: List<String> get() = analyses
        .flatMap { it.unresolvedPackages }
        .distinct()

    companion object {
        fun denied(): UsageSyncResult =
            UsageSyncResult(granted = false, newEventCount = 0, readEventCount = 0, analyses = emptyList())
    }
}

/**
 * 系统感知的采集编排：**取回原始事件 → 落库 → 分析**。
 *
 * ## 采集区间为什么固定重读，而不是用「上次采集到哪」的游标
 * 数据层有 `getLatestEventMillis()` 游标，但这里刻意不用：
 * 事件可能因为权限被撤销、系统清理历史、或用户在别的时区飞行而出现空洞，
 * 靠游标增量推进一旦错过一次就永久错过。重读的开销很小（一次查询两天多的数据），
 * 而落库靠 `event_key` 幂等去重 —— **重复读不会写重复数据**，代价只是几毫秒。
 *
 * ## 为什么连「昨天」一起分析
 * 逻辑日是 04:00~次日 04:00。凌晨 2:00 的使用属于「昨天」，
 * 而只分析今天的话，那一段永远不会被生成出来 —— 用户翻到昨天会看到一天空白。
 */
class UsageSyncService(
    private val reader: UsageEventReader,
    private val usageEventRepository: UsageEventRepository,
    private val appMappingRepository: AppMappingRepository,
    private val usageAnalysisService: UsageAnalysisService,
    private val dayBoundary: DayBoundary,
    /**
     * 应用名解析（平台层实现）。
     *
     * 用函数而不是接口：domain 层不该知道 `PackageManager` 的存在，
     * 而这里需要的只是一个「包名 → 应用名」的纯查询。解析不到就返回 null，
     * 界面回退显示包名。
     */
    private val appLabelResolver: suspend (String) -> String? = { null },
    /**
     * 原始事件的保留天数。
     *
     * `usage_events` 是唯一会持续增长的表：一天几百条，一年十几万条。
     * 里面存的是**原始事实**，推断逻辑将来改了也用得上 —— 但永远留着没有意义，
     * 因为推断只发生在最近两天（见 [UsageAnalysisService] 的 `analyzableDays` 守卫）。
     *
     * 保留期取得比「会分析的范围」宽得多，是为了不在边界上做危险的事：
     * 万一将来要重算上周，数据还在。
     */
    private val retentionDays: Long = DEFAULT_RETENTION_DAYS,
) {

    suspend fun isAccessGranted(): Boolean = reader.isGranted()

    /** 采集并分析今天（默认还会带上昨天，见类注释）。 */
    suspend fun sync(
        epochDay: Long = dayBoundary.todayEpochDay(),
        includePreviousDay: Boolean = true,
    ): UsageSyncResult {
        if (!reader.isGranted()) return UsageSyncResult.denied()

        val days = buildList {
            add(epochDay)
            if (includePreviousDay) add(epochDay - 1)
        }

        val windowStart = days.minOf { dayBoundary.dayRange(it).startMillis }
        // 结束时刻收在「现在」：否则此刻还在用的 App 会被记成一直用到明天 04:00
        val now = dayBoundary.nowMillis()
        val readRange = MillisRange(windowStart, now)
        if (readRange.durationMillis <= 0L) {
            // 理论上到不了（今天的窗口起点一定早于此刻），但这里不留一个会读到未来区间的分支
            return UsageSyncResult(granted = true, newEventCount = 0, readEventCount = 0, analyses = emptyList())
        }

        val events = reader.read(readRange)
        val newEventCount = usageEventRepository.appendEvents(events)

        // 先把见过的 App 登记成「未确定归属」的映射，并补上应用名 ——
        // 时间轴上显示「微信」而不是「com.tencent.mm」，全靠这一步。
        // ensureDefaultMapping 不会覆盖用户已确认的映射。
        events.mapNotNull { it.packageName }.distinct().forEach { packageName ->
            appMappingRepository.ensureDefaultMapping(
                packageName = packageName,
                appLabel = appLabelResolver(packageName),
            )
        }

        // 先分析较早的一天：分析用的是「那一天窗口内」的事件，两天互不影响
        val analyses = days.sorted().map { usageAnalysisService.analyzeDay(it) }

        // 分析完再清理旧的原始事件：保留期按「最近要分析的日子」往前算，
        // 保证刚分析过的数据一定还在。顺序不能反 —— 先清后分析会把当天要用的数据清掉。
        val pruned = usageEventRepository.pruneOlderThan(
            beforeMillis = windowStart - retentionDays * 86_400_000L
        )

        return UsageSyncResult(
            granted = true,
            newEventCount = newEventCount,
            readEventCount = events.size,
            analyses = analyses,
            prunedEventCount = pruned,
        )
    }

    companion object {
        /** 六十天。比「会分析的范围」宽得多，边界上不做危险的事。 */
        const val DEFAULT_RETENTION_DAYS = 60L
    }
}
