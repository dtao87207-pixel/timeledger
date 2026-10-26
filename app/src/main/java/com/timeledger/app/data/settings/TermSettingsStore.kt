package com.timeledger.app.data.settings

import android.content.Context
import com.timeledger.app.domain.plan.TermSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/**
 * 学期起始日的存储实现（模块 28）。
 *
 * ## 为什么是 SharedPreferences，而不是需求里写的 DataStore
 * 只有一个值（一个 `Long`），而引入 DataStore 需要**联网下载依赖** ——
 * 这台机器上 Gradle 的依赖都是缓存里的，`services.gradle.org` 一度不可达。
 * 为了一个设置项去冒「整条链卡住」的风险不值得，所以先用 Android 自带的
 * `SharedPreferences`：零依赖、进程内即时可见。
 *
 * 将来换成 DataStore 时，**只改这一个类**：接口 [TermSettings] 和所有调用方都不用动。
 * 这一点写在这里，是为了让接手的人知道这是个有意的取舍，不是忘了。
 *
 * ## 存的是 epochDay（逻辑日）
 * 与全项目的时间口径一致（需求十六节第 9 条：时间统一用时间戳/标准类型，
 * UI 再转成小时/分钟）。存 `Long` 而不是格式化字符串，也避免时区解析的坑。
 */
class SharedPrefsTermSettings(context: Context) : TermSettings {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 进程内缓存 + Flow：单个值的设置不需要每次读盘，界面订阅它也不会闪。
    private val state = MutableStateFlow(read())

    override fun observeTermStart(): Flow<LocalDate?> = state.asStateFlow()

    override fun currentTermStart(): LocalDate? = state.value

    override suspend fun setTermStart(date: LocalDate) {
        prefs.edit().putLong(KEY_TERM_START, date.toEpochDay()).apply()
        state.value = date
    }

    private fun read(): LocalDate? =
        if (prefs.contains(KEY_TERM_START)) {
            LocalDate.ofEpochDay(prefs.getLong(KEY_TERM_START, 0L))
        } else {
            null
        }

    private companion object {
        const val PREFS_NAME = "time_ledger_settings"
        const val KEY_TERM_START = "term_start_epoch_day"
    }
}
