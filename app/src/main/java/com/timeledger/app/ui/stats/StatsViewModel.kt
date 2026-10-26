package com.timeledger.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.di.AppContainer
import com.timeledger.app.domain.goal.GoalProgressService
import com.timeledger.app.domain.repository.ActivityRepository
import com.timeledger.app.domain.repository.CategoryRepository
import com.timeledger.app.domain.statistics.StatisticsPeriod
import com.timeledger.app.domain.statistics.StatisticsService
import com.timeledger.app.ui.day.CatalogLookup
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * 统计页 ViewModel（需求十三节）。
 *
 * 只有一件事需要它决定：**当前看的是哪个周期**（周 / 月 / 年）以及**哪一期**
 * （可以往前翻看上周、上个月）。窗口怎么算、统计口径是什么，全在
 * `StatisticsService` 与 `StatisticsCalculator` 里，这里不重复一遍。
 *
 * 目标完成率走 `GoalProgressService.observeDayProgress(anchor)`：
 * 每个目标按**自己的周期**算（日目标算当天、周目标算本周），
 * 而不是硬套当前选中的统计周期 —— 「每周 15 小时」的目标在年视图里也依然是按周考核的。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(
    private val statisticsService: StatisticsService,
    private val goalProgressService: GoalProgressService,
    private val categoryRepository: CategoryRepository,
    private val activityRepository: ActivityRepository,
    private val dayBoundary: DayBoundary,
) : ViewModel() {

    private val mapper = StatsStateMapper()
    private val period = MutableStateFlow(StatisticsPeriod.WEEK)
    private val anchor = MutableStateFlow(dayBoundary.todayEpochDay())

    val state: StateFlow<StatsUiState?> = combine(period, anchor) { p, day -> p to day }
        .flatMapLatest { (selectedPeriod, anchorDay) ->
            combine(
                statisticsService.observeStatistics(selectedPeriod, anchorDay),
                goalProgressService.observeDayProgress(anchorDay),
                categoryRepository.observeCategories(includeArchived = true),
                activityRepository.observeActivities(includeArchived = true),
            ) { stats, goals, categories, activities ->
                mapper.map(
                    period = selectedPeriod,
                    stats = stats,
                    goalProgress = goals,
                    catalog = CatalogLookup.from(categories, activities),
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    fun selectPeriod(newPeriod: StatisticsPeriod) {
        period.value = newPeriod
    }

    fun showPreviousPeriod() = shift(-1L)

    fun showNextPeriod() = shift(1L)

    /** 回到包含今天的这一期。 */
    fun showCurrentPeriod() {
        anchor.value = dayBoundary.todayEpochDay()
    }

    /**
     * 按当前周期翻页。
     *
     * 周用天数平移、月用归一到 1 号的月份平移、年用年份平移 ——
     * 三者都委托给 `DayBoundary`，避免这里再写一遍日期算术（那正是最容易出差一两天的地方）。
     */
    private fun shift(delta: Long) {
        val current = anchor.value
        anchor.value = when (period.value) {
            StatisticsPeriod.WEEK -> dayBoundary.shiftDays(current, delta * DAYS_PER_WEEK)
            StatisticsPeriod.MONTH -> dayBoundary.shiftMonth(current, delta)
            StatisticsPeriod.YEAR -> dayBoundary.shiftYear(current, delta)
        }
    }

    companion object {
        private const val DAYS_PER_WEEK = 7L

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = StatsViewModel(
                    statisticsService = container.statisticsService,
                    goalProgressService = container.goalProgressService,
                    categoryRepository = container.categoryRepository,
                    activityRepository = container.activityRepository,
                    dayBoundary = container.dayBoundary,
                ) as T
            }
    }
}
