package com.timeledger.app.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.di.AppContainer
import com.timeledger.app.domain.goal.GoalProgressService
import com.timeledger.app.domain.repository.ActivityRepository
import com.timeledger.app.domain.repository.CategoryRepository
import com.timeledger.app.domain.repository.TimeRecordRepository
import com.timeledger.app.domain.review.DayReviewService
import com.timeledger.app.domain.statistics.StatisticsPeriod
import com.timeledger.app.domain.statistics.StatisticsService
import com.timeledger.app.ui.day.CatalogLookup
import com.timeledger.app.ui.day.DayViewStateMapper
import com.timeledger.app.ui.day.RecordEditController
import com.timeledger.app.ui.day.RecordEditState
import com.timeledger.app.ui.day.RecordEditorUi
import com.timeledger.app.ui.day.RecordEditorUiMapper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * 日历页 ViewModel（需求二节）。
 *
 * 三路数据：
 * - **当月逐日统计**（`StatisticsService`，月周期）→ 每格显示多少小时、强度条；
 * - **每天的目标进度**（`GoalProgressService.observeRangeProgress`）→ 每格显示 `2/3`；
 * - **选中那天的复盘**（`DayReviewService`）→ 下方详情，与首页共用同一套映射。
 *
 * 三者都订阅而不是拉取：用户改了记录、目标或课表，日历立刻跟着变。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(
    private val statisticsService: StatisticsService,
    private val goalProgressService: GoalProgressService,
    private val dayReviewService: DayReviewService,
    private val timeRecordRepository: TimeRecordRepository,
    private val categoryRepository: CategoryRepository,
    private val activityRepository: ActivityRepository,
    private val dayBoundary: DayBoundary,
) : ViewModel() {

    private val detailMapper = DayViewStateMapper(dayBoundary.zone())
    private val mapper = CalendarStateMapper(todayEpochDay = { dayBoundary.todayEpochDay() })

    /** 当前显示的月份（锚点始终是该月 1 号，见 DayBoundary.shiftMonth 的说明）。 */
    private val anchor = MutableStateFlow(dayBoundary.monthFirstEpochDay(dayBoundary.todayEpochDay()))

    /** 选中的日期，默认今天。 */
    private val selected = MutableStateFlow(dayBoundary.todayEpochDay())

    val state: StateFlow<CalendarUiState?> = combine(anchor, selected) { month, day -> month to day }
        .flatMapLatest { (monthAnchor, selectedDay) ->
            combine(
                statisticsService.observeStatistics(StatisticsPeriod.MONTH, monthAnchor),
                goalProgressService.observeRangeProgress(
                    fromEpochDay = dayBoundary.monthFirstEpochDay(monthAnchor),
                    toEpochDay = dayBoundary.monthLastEpochDay(monthAnchor),
                ),
                dayReviewService.observeDayReview(selectedDay),
                categoryRepository.observeCategories(includeArchived = true),
                activityRepository.observeActivities(includeArchived = true),
            ) { monthStats, goalProgress, review, categories, activities ->
                mapper.map(
                    anchorEpochDay = monthAnchor,
                    selectedEpochDay = selectedDay,
                    monthStats = monthStats,
                    goalProgressByDay = goalProgress.associateBy { it.epochDay },
                    selectedDetail = detailMapper.map(
                        review = review,
                        catalog = CatalogLookup.from(categories, activities),
                        // 历史日期不提供快速添加（以「此刻」为基准会写错时间）；
                        // 即便是今天，日历页也不放这个按钮，保持「首页负责记录、日历负责回看」的分工
                        quickAddOptions = emptyList(),
                    ),
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    /**
     * 记录编辑框 —— 与首页共用同一个控制器与同一套映射。
     *
     * 日历里同样需要它：用户最常见的「记错了」场景，就是翻到前几天回看时发现的。
     */
    private val recordEdit = RecordEditController(
        repository = timeRecordRepository,
        dayBoundary = dayBoundary,
        scope = viewModelScope,
    )

    private val editorMapper = RecordEditorUiMapper(dayBoundary)

    /** 记录编辑框的界面状态；null 表示不显示。 */
    val recordEditor: StateFlow<RecordEditorUi?> = combine(
        recordEdit.snapshot,
        categoryRepository.observeCategories(includeArchived = true),
        activityRepository.observeActivities(includeArchived = true),
    ) { snapshot, categories, activities ->
        editorMapper.map(snapshot.form, snapshot.error, categories, activities)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    fun startEditRecord(recordId: Long) = recordEdit.open(recordId)

    fun updateRecordEdit(state: RecordEditState) = recordEdit.update(state)

    fun saveRecordEdit() = recordEdit.save()

    fun deleteRecordEdit() = recordEdit.delete()

    fun dismissRecordEdit() = recordEdit.dismiss()

    fun showPreviousMonth() = shiftMonth(-1L)

    fun showNextMonth() = shiftMonth(1L)

    fun selectDay(epochDay: Long) {
        selected.value = epochDay
    }

    /** 回到今天（同时把显示月份拉回本月）。 */
    fun showToday() {
        val today = dayBoundary.todayEpochDay()
        anchor.value = dayBoundary.monthFirstEpochDay(today)
        selected.value = today
    }

    private fun shiftMonth(delta: Long) {
        val target = dayBoundary.shiftMonth(anchor.value, delta)
        anchor.value = target
        // 翻月后把选中日挪到该月 1 号：否则下方详情还停在上个月的那一天，
        // 用户会以为「翻月没生效」
        selected.value = target
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CalendarViewModel(
                    statisticsService = container.statisticsService,
                    goalProgressService = container.goalProgressService,
                    dayReviewService = container.dayReviewService,
                    timeRecordRepository = container.timeRecordRepository,
                    categoryRepository = container.categoryRepository,
                    activityRepository = container.activityRepository,
                    dayBoundary = container.dayBoundary,
                ) as T
            }
    }
}
