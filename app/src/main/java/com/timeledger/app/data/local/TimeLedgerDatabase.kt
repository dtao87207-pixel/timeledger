package com.timeledger.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.timeledger.app.data.local.dao.ActivityDao
import com.timeledger.app.data.local.dao.AppMappingDao
import com.timeledger.app.data.local.dao.CategoryDao
import com.timeledger.app.data.local.dao.GoalDao
import com.timeledger.app.data.local.dao.GoalProgressOverrideDao
import com.timeledger.app.data.local.dao.PlanBlockDao
import com.timeledger.app.data.local.dao.TimeRecordDao
import com.timeledger.app.data.local.dao.UsageEventDao
import com.timeledger.app.data.local.entity.ActivityEntity
import com.timeledger.app.data.local.entity.AppMappingEntity
import com.timeledger.app.data.local.entity.CategoryEntity
import com.timeledger.app.data.local.entity.GoalEntity
import com.timeledger.app.data.local.entity.GoalProgressOverrideEntity
import com.timeledger.app.data.local.entity.PlanBlockEntity
import com.timeledger.app.data.local.entity.TimeRecordEntity
import com.timeledger.app.data.local.entity.UsageEventEntity

@Database(
    entities = [
        CategoryEntity::class,
        ActivityEntity::class,
        TimeRecordEntity::class,
        GoalEntity::class,
        PlanBlockEntity::class,
        AppMappingEntity::class,
        UsageEventEntity::class,
        GoalProgressOverrideEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class TimeLedgerDatabase : RoomDatabase() {

    abstract fun categoryDao(): CategoryDao
    abstract fun activityDao(): ActivityDao
    abstract fun timeRecordDao(): TimeRecordDao
    abstract fun goalDao(): GoalDao
    abstract fun planBlockDao(): PlanBlockDao
    abstract fun appMappingDao(): AppMappingDao
    abstract fun usageEventDao(): UsageEventDao
    abstract fun goalProgressOverrideDao(): GoalProgressOverrideDao

    companion object {
        const val DATABASE_NAME = "time_ledger.db"

        /**
         * **v1 → v2（模块 28）：计划块加「上哪些周」。**
         *
         * 只加一列，默认空字符串 = **每周都上** —— 这正是加这一列之前所有计划块的语义，
         * 所以这次迁移**不改变任何现有行为**，也不需要回填数据。
         *
         * 默认值必须写在 SQL 里：老行是数据库里**已经存在**的，`ALTER TABLE`
         * 得自己给出值；Kotlin 的默认参数只对新插入的行生效。
         * 这就是数据库类注释里「新增字段必须带默认值」那条规矩的意思。
         */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE plan_blocks ADD COLUMN weeks_spec TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * **v2 → v3（功能增强）：添加目标进度覆盖表。**
         */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `goal_progress_overrides` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `goal_id` INTEGER NOT NULL,
                        `epoch_day` INTEGER NOT NULL,
                        `achieved_value` REAL NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        `is_deleted` INTEGER NOT NULL
                    )
                """.trimIndent())
                
                // 创建索引提高查询性能
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_goal_progress_overrides_goal_id_epoch_day` ON `goal_progress_overrides` (`goal_id`, `epoch_day`)")
            }
        }

        /**
         * 迁移列表。
         *
         * 保留这个常量，是为了让「加迁移」有一个唯一且显眼的落点，
         * 避免以后有人在别处随手 `.fallbackToDestructiveMigration()` ——
         * 那会让一次疏忽的版本号变更**静默清空用户所有历史记录**。
         */
        val ALL_MIGRATIONS: List<androidx.room.migration.Migration> = listOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}
