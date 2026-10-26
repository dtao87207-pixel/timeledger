package com.timeledger.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.di.AppContainer
import com.timeledger.app.domain.model.Activity
import com.timeledger.app.domain.model.Category
import com.timeledger.app.domain.model.TimeRecordDraft
import com.timeledger.app.domain.repository.ActivityRepository
import com.timeledger.app.domain.repository.CategoryRepository
import com.timeledger.app.domain.repository.TimeRecordRepository
import com.timeledger.app.domain.review.DayReviewService
import com.timeledger.app.domain.service.EnhancedGoalProgressService
import com.timeledger.app.domain.usage.UsageSyncResult
import com.timeledger.app.domain.usage.UsageSyncService
import com.timeledger.app.ui.day.CatalogLookup
import com.timeledger.app.ui.day.DayViewUiState
import com.timeledger.app.ui.day.DayViewStateMapper
import com.timeledger.app.ui.day.QuickAddOption
import com.timeledger.app.ui.day.RecordEditController
import com.timeledger.app.ui.day.RecordEditState
import com.timeledger.app.ui.day.RecordEditorUi
import com.timeledger.app.ui.day.RecordEditorUiMapper
import com.timeledger.app.ui.usage.UsageAccessUi
import com.timeledger.app.ui.usage.UsageAccessUiMapper
import com.timeledger.app.domain.model.GoalType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 首页 ViewModel。
 *
 * 它刻意很薄：只是把三路 Flow（复盘、分类目录、活动目录）合成一份 [DayViewUiState]，
 * 全部判断逻辑都在 [DayViewStateMapper] 里 —— 那个类可以在 JVM 上测，这个类不行
 * （它依赖 Android 的 ViewModel 与协程作用域）。把逻辑放在能测的地方，是这里的核心取舍。
 *
 * ## 订阅而不是拉取
 * 复盘、分类、活动三者任一变化都会重新合成界面状态：
 * 用户刚记了一笔、系统补采了数据、改了目标或课表，首页立刻跟着变。
 * 代价是每次变化都重算，而一整天的数据量很小，换来的是「永远不会显示过期数字」。
 *
 * ## 跨 04:00 自动翻篇
 * [currentLogicalDay] 在每天的分界时刻（默认凌晨 4:00）自动切换到新的逻辑日，
 * 而不是把「今天」在启动时算一次就固定下来 ——
 * 否则 App 开着过夜，第二天早上看到的还是昨天的复盘。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val dayReviewService: DayReviewService,
    private val categoryRepository: CategoryRepository,
    private val activityRepository: ActivityRepository,
    private val timeRecordRepository: TimeRecordRepository,
    private val usageSyncService: UsageSyncService,
    private val enhancedGoalProgressService: EnhancedGoalProgressService,
    private val dayBoundary: DayBoundary,
    private val mapper: DayViewStateMapper = DayViewStateMapper(dayBoundary.zone()),
) : ViewModel() {

    /** null 表示还没算出第一份状态（界面显示加载中）。 */
    val state: StateFlow<DayViewUiState?> = currentLogicalDay()
        .flatMapLatest { epochDay ->
            combine(
                dayReviewService.observeDayReview(epochDay),
                categoryRepository.observeCategories(includeArchived = true),
                activityRepository.observeActivities(includeArchived = true),
            ) { review, categories, activities ->
                mapper.map(
                    review = review,
                    catalog = CatalogLookup.from(categories, activities),
                    quickAddOptions = quickAddOptions(categories, activities),
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            // 界面不可见 5 秒后停止订阅，避免在后台白跑数据库查询；
            // 5 秒的缓冲是为了扛住旋转屏幕这类瞬时重建。
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    /**
     * 记录编辑：状态与动作放在 [RecordEditController] 里（首页与日历共用同一份），
     * 这里只负责把它和分类目录合成界面状态。
     *
     * 分层的理由与别处一样：控制器薄到不会出错，而「选项怎么筛、能不能保存、报什么错」
     * 都在 [RecordEditorUiMapper] 里 —— 那个类能在 JVM 上测。
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

    /**
     * 系统感知的状态与采集（需求六节）。
     *
     * 采集时机就放在这里：**每次回到首页时读一次**。
     * 不引入 `WorkManager` 或后台服务 —— 这款 App 的数据只有在用户回来看的时候才需要新，
     * 而额外的后台任务只会带来「为什么它在后台跑」的疑问和耗电。
     * 权限被撤销、用户刚授权、隔夜回来，三种情况都由「回首页时重读一次」覆盖。
     */
    private val usageGranted = MutableStateFlow<Boolean?>(null)
    private val usageSyncing = MutableStateFlow(false)
    private val usageResult = MutableStateFlow<UsageSyncResult?>(null)
    private val usageAccessMapper = UsageAccessUiMapper()

    private var syncJob: Job? = null

    /** 系统感知状态块；null 表示还没查过（第一次读取很快，不必显示加载态）。 */
    val usageAccess: StateFlow<UsageAccessUi?> = combine(
        usageGranted,
        usageSyncing,
        usageResult,
    ) { granted, syncing, result ->
        granted?.let { usageAccessMapper.map(granted = it, syncing = syncing, result = result) }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    /**
     * 读一次系统使用记录并重新分析。
     *
     * 重入保护用「上一个任务还在跑就跳过」，而不是排队：
     * 用户可能在首页与设置页之间来回几次，排队的结果是连读好几遍同样的数据。
     */
    fun refreshUsage() {
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            usageSyncing.value = true
            try {
                val result = usageSyncService.sync()
                usageGranted.value = result.granted
                usageResult.value = result
            } finally {
                usageSyncing.value = false
            }
        }
    }

    /**
     * 快速记一笔：从「现在」往前算 [minutes] 分钟。
     *
     * 只要求用户选活动与时长，不要求填起止时刻 —— 这正是需求三节「低操作成本」的落点：
     * 用户脑子里想的是「我刚学了半小时 Java」，而不是「14:05 到 14:35」。
     * 分类不传，由仓库按活动自动补全，界面不需要知道活动的归属。
     */
    fun quickAdd(activityId: Long, minutes: Int) {
        if (minutes <= 0) return
        viewModelScope.launch {
            val end = dayBoundary.nowMillis()
            timeRecordRepository.createManualRecord(
                TimeRecordDraft(
                    startMillis = end - minutes * 60_000L,
                    endMillis = end,
                    activityId = activityId,
                )
            )
        }
    }

    /**
     * 打开某条记录的编辑框（参数是记录 id，来自时间轴上被点的那一行）。
     *
     * 具体动作在 [RecordEditController] 里 —— 日历页用的是同一个控制器。
     */
    fun startEditRecord(recordId: Long) = recordEdit.open(recordId)

    /** 用户改了表单里的任意一项。 */
    fun updateRecordEdit(state: RecordEditState) = recordEdit.update(state)

    fun saveRecordEdit() = recordEdit.save()

    fun deleteRecordEdit() = recordEdit.delete()

    fun dismissRecordEdit() = recordEdit.dismiss()

    /**
     * 开始编辑目标进度
     */
    fun startGoalEdit(goalId: Long, epochDay: Long, achievedValue: Double) {
        viewModelScope.launch {
            enhancedGoalProgressService.setManualOverride(
                goalId = goalId,
                epochDay = epochDay,
                achievedValue = achievedValue
            )
        }
    }

    /**
     * 当前逻辑日，并在每天的分界时刻自动发一次新值。
     *
     * 用「距下一个分界还有多久」来 sleep，而不是每秒轮询：
     * 一天只需要醒一次，且切换时刻是精确的。
     */
    private fun currentLogicalDay(): Flow<Long> = flow {
        while (true) {
            val today = dayBoundary.todayEpochDay()
            emit(today)
            val nextBoundary = dayBoundary.dayRange(today).endMillisExclusive
            // 兜底至少睡 1 秒，避免时钟异常导致死循环空转
            delay((nextBoundary - dayBoundary.nowMillis()).coerceAtLeast(1_000L))
        }
    }.distinctUntilChanged()

    private fun quickAddOptions(
        categories: List<Category>,
        activities: List<Activity>,
    ): List<QuickAddOption> {
        val categoryNames = categories.associate { it.id to it.name }
        return activities
            .filterNot { it.isArchived }
            .sortedWith(compareBy({ it.categoryId }, { it.sortOrder }, { it.id }))
            .map { activity ->
                val categoryName = categoryNames[activity.categoryId]
                QuickAddOption(
                    activityId = activity.id,
                    // 带上分类前缀，避免同名的「阅读」在不同分类下分不清
                    label = if (categoryName != null) "$categoryName / ${activity.name}" else activity.name,
                )
            }
    }

    companion object {
        /** 手写工厂：容器只有一个，没必要为此引入 DI 框架（需求十六节第 14 条）。 */
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(
                    dayReviewService = container.dayReviewService,
                    categoryRepository = container.categoryRepository,
                    activityRepository = container.activityRepository,
                    timeRecordRepository = container.timeRecordRepository,
                    usageSyncService = container.usageSyncService,
                    enhancedGoalProgressService = container.enhancedGoalProgressService,
                    dayBoundary = container.dayBoundary,
                ) as T
            }
    }
}
