package com.timeledger.app.domain.usage

import com.timeledger.app.domain.model.AppMapping
import com.timeledger.app.domain.model.RecordSource
import com.timeledger.app.domain.model.TimeRecordDraft

/** 推断的理由。界面上要能解释「系统凭什么这么判」，否则用户不会信任它。 */
enum class InferenceReason {
    /** 用户已经明确说过「这个 App 属于什么」。 */
    CONFIRMED_MAPPING,

    /** 用了内置/默认映射，有归属但没被用户确认过 —— 需要用户点一下才算数。 */
    DEFAULT_MAPPING,

    /** 有映射记录，但归属还没定（典型是浏览器：可能是查资料，也可能是刷视频）。 */
    UNRESOLVED_MAPPING,

    /** 完全没见过这个包名。 */
    UNKNOWN_APP,
}

/** 一段由系统推断出来的记录，附带它为什么这么判。 */
data class InferredUsageRecord(
    val draft: TimeRecordDraft,
    val source: RecordSource,
    val packageName: String,
    val reason: InferenceReason,
) {
    /** 置信度（与 [draft] 里的是同一个值，这里只为读起来方便）。 */
    val confidence: Double get() = draft.confidence
}

/**
 * 前台会话 + App 映射 → 待写入的时间记录（需求六、九节）。
 *
 * ## 核心规则：什么时候需要用户确认
 *
 * | 情况 | 来源 | 置信度 | 为什么 |
 * |---|---|---|---|
 * | 映射是用户确认过的 | `SYSTEM` | 1.0 | 用户说过「微信 = 社交」，再问一遍没有信息量 |
 * | 有归属但没确认过（系统默认映射） | `SUGGESTED` | 0.6 | 归属是系统猜的，要让用户点一下 |
 * | 映射没定归属 | `SYSTEM` | 0.3 | 「你用了微信 40 分钟」是事实，问 47 次没有意义 |
 * | 没见过这个包名 | `SYSTEM` | 0.3 | 同上，并且要请用户在**归类页**给它归类 |
 *
 * **后两行是模块 20 改的。** 第一版按需求三节的字面要求，把系统推测的记录都写成
 * `SUGGESTED`。真机跑了一天之后，数据库里躺着 **47 条待确认**，其中 77% 短于 5 分钟 ——
 * 用户还没归类过任何 App，于是**每一条记录都在问他一次**。
 * 那不是「少记录」，那是换了个方式记账。
 *
 * 改法不是「不问了」，而是**换个地方问**：
 * - 记录层不再按条问 —— 「你用了微信 40 分钟」是可观测的事实，直接落库（`SYSTEM`）；
 * - 归类页按 **App** 问一次 —— 用户点一次「微信 = 社交」，之后永久自动。
 *
 * 所以 `SUGGESTED` 现在只留给「系统猜了归属但用户没确认」这一种情况，
 * 那才是真正值得问的问题（「这个 App 是娱乐吗？」）。
 *
 * ## 置信度说的是「归属有多可信」，不是「时间有多准」
 * 时间来自系统，是事实；置信度低是因为**不知道用户在干什么**。
 * 把它理解成对时间的怀疑，会让人以为记录本身不可靠 —— 这会毁掉用户对时间轴的信任。
 *
 * ## 未归类的记录照样写，不丢
 * 见过一种做法：映射没确定就不写记录。那样用户会看到一段空白，
 * 而空白在这款 App 里恰恰意味着「没记录」—— 用户只会以为系统坏了。
 * 所以未归类的段也照写，分类留空，并把 App 名放进备注：
 * 时间轴上显示成「微信」，用户一眼就能说「对，那是社交」。
 */
class UsageRecordInferrer(
    private val confirmedConfidence: Double = CONFIRMED_CONFIDENCE,
    private val defaultMappingConfidence: Double = DEFAULT_MAPPING_CONFIDENCE,
    private val unknownConfidence: Double = UNKNOWN_CONFIDENCE,
) {

    fun infer(
        sessions: List<AppSession>,
        mappings: Map<String, AppMapping>,
        /** 归属哪个逻辑日 —— 幂等键要用（见 [UsageRecordKeys]）。 */
        dayKey: Long,
    ): List<InferredUsageRecord> = sessions.map { session ->
        val mapping = mappings[session.packageName]
        val (source, confidence, reason) = classify(mapping)

        InferredUsageRecord(
            draft = buildDraft(session, mapping, dayKey, confidence),
            source = source,
            packageName = session.packageName,
            reason = reason,
        )
    }

    /** 上面那张表的唯一实现处。 */
    private fun classify(mapping: AppMapping?): Triple<RecordSource, Double, InferenceReason> =
        when {
            mapping?.isUserConfirmed == true -> Triple(
                RecordSource.SYSTEM,
                confirmedConfidence,
                InferenceReason.CONFIRMED_MAPPING,
            )

            // 系统猜了归属但用户没点头：这才值得问一次
            mapping != null && !mapping.isUnresolved -> Triple(
                RecordSource.SUGGESTED,
                defaultMappingConfidence,
                InferenceReason.DEFAULT_MAPPING,
            )

            // 以下两种都是「不知道这是什么」：照写不误，但不逐条问 ——
            // 问 47 次同样的问题是折磨，归类页问一次才有用
            mapping != null -> Triple(
                RecordSource.SYSTEM,
                unknownConfidence,
                InferenceReason.UNRESOLVED_MAPPING,
            )

            else -> Triple(
                RecordSource.SYSTEM,
                unknownConfidence,
                InferenceReason.UNKNOWN_APP,
            )
        }

    private fun buildDraft(
        session: AppSession,
        mapping: AppMapping?,
        dayKey: Long,
        confidence: Double,
    ): TimeRecordDraft {
        // **App 名一律写进备注，不管有没有归类。**
        //
        // 第一版只在「未归类」时才写，理由是「已归类就不需要了」。真机上用了一天之后
        // 用户的原话是：「时间轴只有分类，没有具体某个应用，不知道我这段时间具体做了什么。」
        // —— 归类解决的是**统计口径**（娱乐两小时），而时间轴要回答的是
        // **这段时间具体在干什么**（刷抖音还是打王者）。两者不是一回事，
        // 所以我丢掉了一个用户真正需要的信息。
        //
        // 用备注字段承载它，是为了 V1 不给数据库加列（加列要迁移，而这一条不是非加不可）。
        // 代价是：用户手写备注会覆盖它。这个取舍写进了 README 决策 102。
        val appName = mapping?.appLabel?.takeIf { it.isNotBlank() } ?: session.packageName

        return TimeRecordDraft(
            startMillis = session.startMillis,
            endMillis = session.endMillisExclusive,
            categoryId = mapping?.categoryId,
            activityId = mapping?.activityId,
            note = appName,
            confidence = confidence,
            externalKey = UsageRecordKeys.build(dayKey, session.startMillis, session.packageName),
        )
    }

    companion object {
        const val CONFIRMED_CONFIDENCE = 1.0
        const val DEFAULT_MAPPING_CONFIDENCE = 0.6
        const val UNKNOWN_CONFIDENCE = 0.3
    }
}
