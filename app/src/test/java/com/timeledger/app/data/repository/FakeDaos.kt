package com.timeledger.app.data.repository

import com.timeledger.app.data.local.dao.ActivityDao
import com.timeledger.app.data.local.dao.CategoryDao
import com.timeledger.app.data.local.dao.TimeRecordDao
import com.timeledger.app.data.local.entity.ActivityEntity
import com.timeledger.app.data.local.entity.CategoryEntity
import com.timeledger.app.data.local.entity.TimeRecordEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 测试用的内存 DAO 替身。
 *
 * ## 为什么不用 Mock 框架
 * 这些仓库的行为几乎全部取决于**查询条件**（「有没有重叠的手动记录」「这个幂等键是否已存在」），
 * 用 mock 就得在测试里逐条写死返回值，等于把被测逻辑抄一遍。
 * 用内存实现可以让测试按真实数据流走一遍，断言也更接近真实场景。
 *
 * ## 为什么 SQL 语义要在这里重写一遍
 * 这是这套替身的**已知局限**，必须说清楚：
 * 下面把 SQL 里的判定条件（区间重叠、幂等键唯一、软删除过滤）在 Kotlin 里重写了一份。
 * 因此它验证的是**仓库的编排逻辑**，而不是 Room 真的会那样执行 SQL。
 * SQL 本身（索引、唯一约束、`UPDATE ... WHERE source IN (...)`）需要
 * 连真实 SQLite 才能验证，那属于仪器化/Robolectric 测试的范围，本轮没有引入。
 * 重写时严格对齐 SQL，任何一边改动都必须同步改另一边。
 */
class FakeCategoryDao : CategoryDao {

    private val rows = mutableListOf<CategoryEntity>()
    private var nextId = 1L

    /** 便于测试直接读全量数据。 */
    fun all(): List<CategoryEntity> = rows.toList()

    private fun sorted(): List<CategoryEntity> =
        rows.sortedWith(compareBy({ it.sortOrder }, { it.id }))

    override fun observeAll(): Flow<List<CategoryEntity>> = flowOf(sorted())

    override fun observeActive(): Flow<List<CategoryEntity>> =
        flowOf(sorted().filter { !it.isArchived })

    override suspend fun getById(id: Long): CategoryEntity? = rows.firstOrNull { it.id == id }

    override suspend fun getByName(name: String): CategoryEntity? =
        rows.firstOrNull { it.name == name }

    override suspend fun count(): Int = rows.size

    override suspend fun insert(entity: CategoryEntity): Long {
        // 对齐 categories.name 的唯一索引。
        // 用 IllegalStateException 而不是 SQLiteConstraintException：
        // 单元测试跑在 JVM 上，android.jar 里的类都是会抛 "Stub!" 的空壳，
        // 连 new 一下都不行，所以替身里不能引用任何 android.* 类型。
        if (rows.any { it.name == entity.name }) {
            throw IllegalStateException("UNIQUE constraint failed: categories.name")
        }
        val id = if (entity.id == 0L) nextId++ else entity.id
        rows += entity.copy(id = id)
        return id
    }

    override suspend fun insertAll(entities: List<CategoryEntity>): List<Long> =
        entities.map { insert(it) }

    /** 对齐 INSERT OR IGNORE：名称冲突时返回 -1，而不是抛异常。 */
    override suspend fun insertIgnore(entity: CategoryEntity): Long {
        if (rows.any { it.name == entity.name }) return -1L
        return insert(entity)
    }

    override suspend fun update(entity: CategoryEntity) {
        val index = rows.indexOfFirst { it.id == entity.id }
        if (index >= 0) rows[index] = entity
    }

    override suspend fun archive(id: Long, updatedAtMillis: Long) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) {
            rows[index] = rows[index].copy(isArchived = true, updatedAtMillis = updatedAtMillis)
        }
    }
}

/** ActivityDao 的内存替身。唯一索引 `(category_id, name)` 的语义与真实表一致。 */
class FakeActivityDao : ActivityDao {

    private val rows = mutableListOf<ActivityEntity>()
    private var nextId = 1L

    fun all(): List<ActivityEntity> = rows.toList()

    private fun sorted(): List<ActivityEntity> =
        rows.sortedWith(compareBy({ it.categoryId }, { it.sortOrder }, { it.id }))

    override fun observeAll(): Flow<List<ActivityEntity>> = flowOf(sorted())

    override fun observeActive(): Flow<List<ActivityEntity>> =
        flowOf(sorted().filter { !it.isArchived })

    override fun observeActiveByCategory(categoryId: Long): Flow<List<ActivityEntity>> =
        flowOf(sorted().filter { it.categoryId == categoryId && !it.isArchived })

    override suspend fun getById(id: Long): ActivityEntity? = rows.firstOrNull { it.id == id }

    override suspend fun getByCategoryAndName(categoryId: Long, name: String): ActivityEntity? =
        rows.firstOrNull { it.categoryId == categoryId && it.name == name }

    override suspend fun count(): Int = rows.size

    override suspend fun insert(entity: ActivityEntity): Long {
        if (rows.any { it.categoryId == entity.categoryId && it.name == entity.name }) {
            throw IllegalStateException(
                "UNIQUE constraint failed: activities.category_id, activities.name"
            )
        }
        val id = if (entity.id == 0L) nextId++ else entity.id
        rows += entity.copy(id = id)
        return id
    }

    override suspend fun insertAll(entities: List<ActivityEntity>): List<Long> =
        entities.map { insert(it) }

    /** 对齐 INSERT OR IGNORE：`(category_id, name)` 冲突时返回 -1。 */
    override suspend fun insertIgnore(entity: ActivityEntity): Long {
        if (rows.any { it.categoryId == entity.categoryId && it.name == entity.name }) return -1L
        return insert(entity)
    }

    override suspend fun update(entity: ActivityEntity) {
        val index = rows.indexOfFirst { it.id == entity.id }
        if (index >= 0) rows[index] = entity
    }

    override suspend fun archive(id: Long, updatedAtMillis: Long) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) {
            rows[index] = rows[index].copy(isArchived = true, updatedAtMillis = updatedAtMillis)
        }
    }
}

/** TimeRecordDao 的内存替身。区间与软删除的判定严格对齐 TimeRecordDao 里的 SQL。 */
class FakeTimeRecordDao : TimeRecordDao {

    private val rows = mutableListOf<TimeRecordEntity>()
    private var nextId = 1L

    fun all(): List<TimeRecordEntity> = rows.toList()

    /** 对齐 SQL 里「与区间有关」的判定：有重叠，或零长度记录落在区间内。 */
    private fun TimeRecordEntity.relevantTo(rangeStart: Long, rangeEnd: Long): Boolean =
        startMillis < rangeEnd &&
            (endMillis > rangeStart || (endMillis == startMillis && startMillis >= rangeStart))

    private fun TimeRecordEntity.liveInRange(rangeStart: Long, rangeEnd: Long): Boolean =
        !isDeleted && relevantTo(rangeStart, rangeEnd)

    private fun ordered(list: List<TimeRecordEntity>): List<TimeRecordEntity> =
        list.sortedWith(compareBy({ it.startMillis }, { it.id }))

    override fun observeInRange(rangeStartMillis: Long, rangeEndMillis: Long): Flow<List<TimeRecordEntity>> =
        flowOf(ordered(rows.filter { it.liveInRange(rangeStartMillis, rangeEndMillis) }))

    override suspend fun getInRange(rangeStartMillis: Long, rangeEndMillis: Long): List<TimeRecordEntity> =
        ordered(rows.filter { it.liveInRange(rangeStartMillis, rangeEndMillis) })

    override suspend fun getById(id: Long): TimeRecordEntity? = rows.firstOrNull { it.id == id }

    override suspend fun getByExternalKeyIncludingDeleted(externalKey: String): TimeRecordEntity? =
        rows.firstOrNull { it.externalKey == externalKey }

    override suspend fun getOverlappingManualRecords(
        startMillis: Long,
        endMillis: Long,
    ): List<TimeRecordEntity> = ordered(
        rows.filter {
            !it.isDeleted && it.source == "MANUAL" &&
                it.startMillis < endMillis && it.endMillis > startMillis
        }
    )

    override suspend fun hasOverlappingManualRecord(startMillis: Long, endMillis: Long): Boolean =
        getOverlappingManualRecords(startMillis, endMillis).isNotEmpty()

    override suspend fun insert(entity: TimeRecordEntity): Long {
        val id = if (entity.id == 0L) nextId++ else entity.id
        rows += entity.copy(id = id)
        return id
    }

    override suspend fun insertIgnore(entity: TimeRecordEntity): Long {
        // 对齐 external_key 的唯一索引：冲突时不去重、不报错，而是返回 -1。
        // 注意与 SQLite 一致 —— 唯一索引把**已软删除的行**也算在内。
        val key = entity.externalKey
        if (key != null && rows.any { it.externalKey == key }) return -1L
        return insert(entity)
    }

    override suspend fun insertAllIgnore(entities: List<TimeRecordEntity>): List<Long> =
        entities.map { insertIgnore(it) }

    override suspend fun update(entity: TimeRecordEntity) {
        val index = rows.indexOfFirst { it.id == entity.id }
        if (index >= 0) rows[index] = entity
    }

    override suspend fun softDelete(id: Long, updatedAtMillis: Long) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) {
            rows[index] = rows[index].copy(isDeleted = true, updatedAtMillis = updatedAtMillis)
        }
    }

    override suspend fun softDeleteSystemGeneratedStartingIn(
        rangeStartMillis: Long,
        rangeEndMillis: Long,
        updatedAtMillis: Long,
    ): Int {
        // 对齐 SQL：判据是**起始时刻**落在区间内（左闭右开）。
        // 这样「结束时刻正好等于区间起点」的前一天记录不会被误删 ——
        // 真机上真的踩到过（熄屏时段被切成两天，前一天那段被次日的分析删掉）。
        var affected = 0
        for (index in rows.indices) {
            val row = rows[index]
            val systemGenerated = row.source == "SYSTEM" || row.source == "SUGGESTED"
            if (!row.isDeleted && systemGenerated &&
                row.startMillis >= rangeStartMillis && row.startMillis < rangeEndMillis
            ) {
                rows[index] = row.copy(isDeleted = true, updatedAtMillis = updatedAtMillis)
                affected++
            }
        }
        return affected
    }

    override suspend fun getMaxStartMillis(): Long? =
        rows.filter { !it.isDeleted }.maxOfOrNull { it.startMillis }
}
