package com.timeledger.app.data.repository

import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.data.local.dao.ActivityDao
import com.timeledger.app.data.local.dao.CategoryDao
import com.timeledger.app.data.local.dao.TimeRecordDao
import com.timeledger.app.data.local.entity.TimeRecordEntity
import com.timeledger.app.data.mapper.toDomain
import com.timeledger.app.domain.model.RecordSource
import com.timeledger.app.domain.model.TimeRecord
import com.timeledger.app.domain.model.TimeRecordDraft
import com.timeledger.app.domain.repository.RejectReason
import com.timeledger.app.domain.repository.SaveOutcome
import com.timeledger.app.domain.repository.TimeRecordRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 时间记录仓库实现。
 *
 * 这个类承担三件**不能交给 UI 或调用方自觉**的事：
 *
 * 1. **手动记录不可被系统覆盖**：所有系统写入路径都先查重叠的手动记录，命中即拒绝。
 * 2. **归属一致性**：活动非空时，分类必须等于该活动的分类；
 *    调用方只给活动、不给分类时自动补全，而不是让它变成一个「有活动没分类」的脏记录。
 * 3. **重新分析时的幂等键占用**：软删除的行仍占着 external_key 唯一索引，
 *    因此重新分析必须走「复活并覆盖」而不是「插入」，否则新结果会一条都写不进去。
 *    这是最容易漏、又最难排查的一个坑，对应测试见 TimeRecordRepositoryImplTest。
 */
class TimeRecordRepositoryImpl(
    private val timeRecordDao: TimeRecordDao,
    private val categoryDao: CategoryDao,
    private val activityDao: ActivityDao,
    private val dayBoundary: DayBoundary,
) : TimeRecordRepository {

    override fun observeRecordsIn(range: MillisRange): Flow<List<TimeRecord>> =
        timeRecordDao.observeInRange(range.startMillis, range.endMillisExclusive)
            .map { entities -> entities.map { it.toDomain() } }

    override fun observeRecordsOfDay(epochDay: Long): Flow<List<TimeRecord>> =
        observeRecordsIn(dayBoundary.dayRange(epochDay))

    override suspend fun getRecordsIn(range: MillisRange): List<TimeRecord> =
        timeRecordDao.getInRange(range.startMillis, range.endMillisExclusive)
            .map { it.toDomain() }

    override suspend fun getRecord(id: Long): TimeRecord? =
        timeRecordDao.getById(id)?.toDomain()

    override suspend fun getOverlappingManualRecords(range: MillisRange): List<TimeRecord> =
        timeRecordDao.getOverlappingManualRecords(range.startMillis, range.endMillisExclusive)
            .map { it.toDomain() }

    override suspend fun createManualRecord(draft: TimeRecordDraft): SaveOutcome {
        validateDraft(draft)?.let { return SaveOutcome.Rejected(it) }
        val binding = when (val result = resolveBinding(draft.categoryId, draft.activityId)) {
            is BindingResult.Invalid -> return SaveOutcome.Rejected(result.reason)
            is BindingResult.Resolved -> result
        }

        val now = dayBoundary.nowMillis()
        val id = timeRecordDao.insert(
            TimeRecordEntity(
                startMillis = draft.startMillis,
                endMillis = draft.endMillis,
                categoryId = binding.categoryId,
                activityId = binding.activityId,
                // 手动记录一律 MANUAL、置信度 1.0：草稿里的 confidence/externalKey
                // 在这个路径上被刻意忽略，防止调用方「自封」来源。
                source = RecordSource.MANUAL.name,
                confidence = 1.0,
                countValue = draft.countValue,
                note = draft.note.normalizedNote(),
                externalKey = null,
                isDeleted = false,
                createdAtMillis = now,
                updatedAtMillis = now,
            )
        )
        return SaveOutcome.Saved(id)
    }

    override suspend fun updateRecord(id: Long, draft: TimeRecordDraft): SaveOutcome {
        val existing = timeRecordDao.getById(id)
            ?: return SaveOutcome.Rejected(RejectReason.RECORD_NOT_FOUND)
        if (existing.isDeleted) return SaveOutcome.Rejected(RejectReason.RECORD_NOT_FOUND)

        validateDraft(draft)?.let { return SaveOutcome.Rejected(it) }
        val binding = when (val result = resolveBinding(draft.categoryId, draft.activityId)) {
            is BindingResult.Invalid -> return SaveOutcome.Rejected(result.reason)
            is BindingResult.Resolved -> result
        }

        // 来源变更规则：
        // - 本来就是用户手动记录：保持 MANUAL，不能被一次编辑降级；
        // - 系统/推测记录被用户改过：变成 MERGED，表示「用户确认过的结果」（需求三节）。
        val newSource = if (existing.source == RecordSource.MANUAL.name) {
            RecordSource.MANUAL
        } else {
            RecordSource.MERGED
        }

        timeRecordDao.update(
            existing.copy(
                startMillis = draft.startMillis,
                endMillis = draft.endMillis,
                categoryId = binding.categoryId,
                activityId = binding.activityId,
                source = newSource.name,
                // 用户确认过的记录，置信度就是满的。
                confidence = 1.0,
                countValue = draft.countValue,
                note = draft.note.normalizedNote(),
                updatedAtMillis = dayBoundary.nowMillis(),
            )
        )
        return SaveOutcome.Saved(id)
    }

    override suspend fun deleteRecord(id: Long): Boolean {
        val existing = timeRecordDao.getById(id) ?: return false
        if (existing.isDeleted) return true
        timeRecordDao.softDelete(id, dayBoundary.nowMillis())
        return true
    }

    override suspend fun saveSystemRecord(
        draft: TimeRecordDraft,
        source: RecordSource,
    ): SaveOutcome {
        require(source.isSystemGenerated) {
            "saveSystemRecord 只接受 SYSTEM / SUGGESTED 来源，实际为 $source"
        }
        val externalKey = draft.externalKey
            ?: return SaveOutcome.Rejected(RejectReason.MISSING_EXTERNAL_KEY)

        validateDraft(draft)?.let { return SaveOutcome.Rejected(it) }
        val binding = when (val result = resolveBinding(draft.categoryId, draft.activityId)) {
            is BindingResult.Invalid -> return SaveOutcome.Rejected(result.reason)
            is BindingResult.Resolved -> result
        }

        val existing = timeRecordDao.getByExternalKeyIncludingDeleted(externalKey)
        if (existing != null && !existing.isDeleted) {
            // 真正的重复采集：静默跳过，不算错误。
            return SaveOutcome.Skipped
        }

        // 手动记录保护：零长度记录不占时间，不参与重叠判断。
        val spans = draft.endMillis > draft.startMillis
        if (spans && timeRecordDao.hasOverlappingManualRecord(draft.startMillis, draft.endMillis)) {
            return SaveOutcome.Rejected(RejectReason.OVERLAPS_MANUAL_RECORD)
        }

        val now = dayBoundary.nowMillis()
        if (existing != null) {
            // 这条行之前被软删除过（同一时段被重新分析）：复活并覆盖成新结果。
            timeRecordDao.update(
                existing.copy(
                    startMillis = draft.startMillis,
                    endMillis = draft.endMillis,
                    categoryId = binding.categoryId,
                    activityId = binding.activityId,
                    source = source.name,
                    confidence = draft.confidence,
                    countValue = draft.countValue,
                    note = draft.note.normalizedNote(),
                    isDeleted = false,
                    updatedAtMillis = now,
                )
            )
            return SaveOutcome.Saved(existing.id)
        }

        val id = timeRecordDao.insertIgnore(
            TimeRecordEntity(
                startMillis = draft.startMillis,
                endMillis = draft.endMillis,
                categoryId = binding.categoryId,
                activityId = binding.activityId,
                source = source.name,
                confidence = draft.confidence,
                countValue = draft.countValue,
                note = draft.note.normalizedNote(),
                externalKey = externalKey,
                isDeleted = false,
                createdAtMillis = now,
                updatedAtMillis = now,
            )
        )
        if (id == -1L) return SaveOutcome.Skipped
        return SaveOutcome.Saved(id)
    }

    /**
     * 清空某区间内**由系统生成**的记录（重新分析前调用）。
     *
     * 判据从「时间段重叠」改成了「起始时刻落在区间内」：
     * 记录是裁剪到当天窗口的，两者本该等价，但**结束时刻正好等于区间起点**的跨日记录
     * （熄屏时段 `[00:43, 04:00]` 与次日窗口 `[04:00, …]`）会被重叠判据误伤 ——
     * 真机上表现为「前一天夜里那段熄屏，每分析一次次日就消失一次」。
     * 现在的判据与全局的「按开始时刻归属」规则一致。
     */
    override suspend fun clearSystemGeneratedIn(range: MillisRange): Int =
        timeRecordDao.softDeleteSystemGeneratedStartingIn(
            rangeStartMillis = range.startMillis,
            rangeEndMillis = range.endMillisExclusive,
            updatedAtMillis = dayBoundary.nowMillis(),
        )

    override suspend fun replaceSystemGeneratedIn(
        range: MillisRange,
        drafts: List<TimeRecordDraft>,
        source: RecordSource,
    ): Int {
        require(source.isSystemGenerated) {
            "replaceSystemGeneratedIn 只接受 SYSTEM / SUGGESTED 来源，实际为 $source"
        }
        clearSystemGeneratedIn(range)

        var savedCount = 0
        drafts.forEach { draft ->
            if (saveSystemRecord(draft, source) is SaveOutcome.Saved) savedCount++
        }
        return savedCount
    }

    /** 时间范围与计数的基础校验；归属校验见 [resolveBinding]。 */
    private fun validateDraft(draft: TimeRecordDraft): RejectReason? {
        if (draft.endMillis < draft.startMillis) return RejectReason.INVALID_TIME_RANGE
        val count = draft.countValue
        if (count != null && (count.isNaN() || count < 0.0)) {
            return RejectReason.INVALID_COUNT_VALUE
        }
        // 置信度越界属于程序错误（不是用户输入），用 require 直接暴露出来。
        require(draft.confidence in 0.0..1.0) {
            "置信度必须在 0.0~1.0 之间，实际为 ${draft.confidence}"
        }
        return null
    }

    /** 归属校验的结果。 */
    private sealed interface BindingResult {
        data class Resolved(val categoryId: Long?, val activityId: Long?) : BindingResult
        data class Invalid(val reason: RejectReason) : BindingResult
    }

    /**
     * 校验并补全分类/活动绑定。
     *
     * 都为空是**合法**的：系统刚采集到、还没推断出分类时就是这种状态（需求七节）。
     * 只给活动不给分类时会自动补上活动所属分类，避免产生「有活动没分类」的记录 ——
     * 那种记录在按分类统计时会被漏掉，是最隐蔽的统计偏差来源。
     */
    private suspend fun resolveBinding(
        categoryId: Long?,
        activityId: Long?,
    ): BindingResult {
        if (activityId != null) {
            val activity = activityDao.getById(activityId)
            if (activity == null || activity.isArchived) {
                return BindingResult.Invalid(RejectReason.UNKNOWN_CATEGORY_OR_ACTIVITY)
            }
            if (categoryId != null && categoryId != activity.categoryId) {
                return BindingResult.Invalid(RejectReason.ACTIVITY_CATEGORY_MISMATCH)
            }
            val effectiveCategoryId = categoryId ?: activity.categoryId
            val category = categoryDao.getById(effectiveCategoryId)
            if (category == null || category.isArchived) {
                return BindingResult.Invalid(RejectReason.UNKNOWN_CATEGORY_OR_ACTIVITY)
            }
            return BindingResult.Resolved(effectiveCategoryId, activityId)
        }

        if (categoryId != null) {
            val category = categoryDao.getById(categoryId)
            if (category == null || category.isArchived) {
                return BindingResult.Invalid(RejectReason.UNKNOWN_CATEGORY_OR_ACTIVITY)
            }
            return BindingResult.Resolved(categoryId, null)
        }

        return BindingResult.Resolved(null, null)
    }
}

/** 备注统一处理：去掉首尾空白，全空白视为没有备注（避免存一堆 " " 进去）。 */
private fun String?.normalizedNote(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
