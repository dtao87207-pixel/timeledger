package com.timeledger.app.domain.usage

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.model.AppMapping
import com.timeledger.app.domain.model.UsageEvent
import com.timeledger.app.domain.repository.AppMappingRepository
import com.timeledger.app.domain.repository.SaveOutcome
import com.timeledger.app.domain.repository.UsageEventRepository
import kotlinx.coroutines.flow.Flow

/**
 * 某个 App 最近的使用情况，供映射界面排序与说明（需求九节）。
 *
 * 统计的是**会话**而不是原始事件：会话已经过「排除系统组件」与「合并快速切回」
 * 两道处理，所以「打开了 8 次」「一共 52 分钟」这两句话才站得住。
 * 用原始 RESUMED 计数的话，通知把 App 拉起来闪一下也会算成一次「打开」——
 * 真机上微信 30 小时里有 759 次这种一闪而过。
 */
data class AppUsageHint(
    val packageName: String,
    /** 最近一次到前台时刻；窗口内没出现过则为 null。 */
    val lastForegroundMillis: Long?,
    /** 窗口内被打开的次数（会话数）。 */
    val sessionCount: Int,
    /** 窗口内的总前台时长。用来判断「值不值得请用户归类」。 */
    val foregroundMillis: Long,
) {
    /** 用时很少的 App 不值得打扰用户：它照样被记录，只是不催着归类。 */
    fun isWorthAsking(thresholdMillis: Long = WORTH_ASKING_MILLIS): Boolean =
        foregroundMillis >= thresholdMillis

    companion object {
        /**
         * 十分钟。真机数据：47 个待归类里有 24 个用时不到 1 分钟
         * （天气 0 分钟、信息 0.1 分钟、钱包 0 分钟……）。
         * 为这些弹一次「请归类」，用户只会觉得这个 App 很烦。
         */
        const val WORTH_ASKING_MILLIS = 10 * 60 * 1000L
    }
}

/**
 * App → 行为映射的读写入口（需求九节）。
 *
 * ## 它解决的产品问题
 * 系统能知道「你用了微信 40 分钟」，但不知道「微信 = 社交」。
 * 用户说一次，之后这个 App 的所有记录就自动归类（`UsageRecordInferrer`
 * 对用户确认过的映射直接写 `SYSTEM`，不再逐条问）。
 * **没有这一页，用户只能在一堆「未归类」的记录里逐条确认** ——
 * 那不是「少记录」，那是换了个方式记账。
 *
 * ## 为什么需要一个服务类而不是让界面直接调仓库
 * 「最近用过哪些 App、打开了几次」要把原始事件按包名聚合，
 * 这是有判断的逻辑（只算前台、只算窗口内、结果要排序）。
 * 放在这里就能用 JVM 测试钉死，界面只负责画。
 */
class AppMappingService(
    private val appMappingRepository: AppMappingRepository,
    private val usageEventRepository: UsageEventRepository,
    private val dayBoundary: DayBoundary,
    /** 看最近多少天。七天足够反映「平时真的在用」，又不会把几个月前的噪音带进来。 */
    private val lookbackDays: Long = DEFAULT_LOOKBACK_DAYS,
    /** 与采集/分析用同一套会话规则，否则这一页的时长和首页对不上。 */
    private val sessionBuilder: ForegroundSessionBuilder = ForegroundSessionBuilder(),
    private val nonUsageAppResolver: NonUsageAppResolver = NonUsageAppResolver { emptySet() },
) {

    fun observeMappings(): Flow<List<AppMapping>> = appMappingRepository.observeMappings()

    /**
     * 最近使用情况：包名 → 提示。窗口内没出现过的包名不会出现在结果里。
     *
     * 用**同一套**会话规则（排除系统组件 + 合并快速切回），这样这一页看到的时长
     * 与首页时间轴是同一个口径 —— 两个页面各算一套的话，
     * 用户会看到「这里说 52 分钟、那里说 3 分钟」，然后谁都不信。
     */
    suspend fun usageHints(): Map<String, AppUsageHint> {
        val now = dayBoundary.nowMillis()
        val range = MillisRange(now - lookbackDays * 86_400_000L, now)
        val events = usageEventRepository.getEventsIn(range)
        if (events.isEmpty()) return emptyMap()

        val ignored = nonUsageAppResolver.resolve(
            events.mapNotNullTo(mutableSetOf()) { it.packageName }
        )
        val sessions = sessionBuilder.build(events, range, ignored)

        return sessions
            .groupBy { it.packageName }
            .mapValues { (packageName, packageSessions) ->
                AppUsageHint(
                    packageName = packageName,
                    lastForegroundMillis = packageSessions.maxOf { it.endMillisExclusive },
                    sessionCount = packageSessions.size,
                    foregroundMillis = packageSessions.sumOf { it.durationMillis },
                )
            }
    }

    /**
     * 给某个 App 指定归属；两者都传 null 表示「这个 App 不用归类」。
     *
     * 这不是一个「空操作」：仓库会把 [AppMapping.isUserConfirmed] 置为 true，
     * 之后推断层就不会再拿它来问用户了（未归类但已确认 → 直接写 `SYSTEM`）。
     * 用户说「不用归类」也是一个明确的决定，值得被记住。
     */
    suspend fun assign(
        packageName: String,
        categoryId: Long?,
        activityId: Long?,
    ): SaveOutcome = appMappingRepository.confirmMapping(
        packageName = packageName,
        categoryId = categoryId,
        activityId = activityId,
    )

    companion object {
        const val DEFAULT_LOOKBACK_DAYS = 7L
    }
}
