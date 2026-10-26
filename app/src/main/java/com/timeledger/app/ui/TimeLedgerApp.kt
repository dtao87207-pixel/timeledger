package com.timeledger.app.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.timeledger.app.R
import com.timeledger.app.di.AppContainer
import com.timeledger.app.ui.calendar.CalendarScreen
import com.timeledger.app.ui.catalog.CatalogManagementScreen
import com.timeledger.app.ui.confirm.ConfirmationScreen
import com.timeledger.app.ui.goals.GoalManagementScreen
import com.timeledger.app.ui.home.HomeScreen
import com.timeledger.app.ui.mapping.AppMappingScreen
import com.timeledger.app.ui.plan.PlanManagementScreen
import com.timeledger.app.ui.stats.StatsScreen

/**
 * 三个主 Tab 的外壳（需求二节：首页与日历是两个主 Tab）。
 *
 * 为什么不用 navigation-compose：三个 Tab 之间没有参数传递、没有深层链接、
 * 也不需要返回栈，一个整数状态就够了。为此引入整套导航框架属于
 * 「为了高级而增加复杂度」（需求十六节第 14 条）。
 *
 * 二级页面（如目标管理）同样用一个整数下标表示，而不是再引入导航库 ——
 * 目前只有「显示哪个」这一种状态，没有参数要传。
 * 将来真需要带参数的深层跳转时，改造点只有这一个文件。
 */
private enum class AppTab(
    @param:StringRes val labelRes: Int,
    @param:DrawableRes val iconRes: Int,
) {
    HOME(R.string.tab_home, R.drawable.ic_tab_home),
    CALENDAR(R.string.tab_calendar, R.drawable.ic_tab_calendar),
    STATS(R.string.tab_stats, R.drawable.ic_tab_stats),
}

/** 二级页面。目标管理暂不占常驻 Tab：需求里首页才是「每日控制中心」。 */
private enum class DetailScreen {
    GOALS,
    CATALOG,
    PLAN,
    CONFIRM,

    /** App 归类（需求九节）：把系统采集到的 App 一次性指定归属。 */
    MAPPING,
}

/** 表示「不显示任何二级页面」。 */
private const val NO_DETAIL = -1

@Composable
fun TimeLedgerApp(container: AppContainer) {
    // 保存下标而不是枚举本身：Int 一定能进 Bundle，不依赖枚举的可序列化行为
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    var detailIndex by rememberSaveable { mutableIntStateOf(NO_DETAIL) }
    // 二级页面之间可以再进一层（目标管理 → 分类与活动）。
    // 只记一层来源就够了，再深就应该换成真正的导航栈，而不是继续加变量。
    var returnDetailIndex by rememberSaveable { mutableIntStateOf(NO_DETAIL) }

    fun openDetail(target: DetailScreen) {
        returnDetailIndex = detailIndex
        detailIndex = target.ordinal
    }

    fun closeDetail() {
        detailIndex = returnDetailIndex
        returnDetailIndex = NO_DETAIL
    }

    val detail = DetailScreen.entries.getOrNull(detailIndex)

    // 系统返回键（手势或三键导航）也要能返回上一级，而不是直接退出应用。
    // 顺序：二级页面 → 回上级页面；在别的 Tab 上 → 回首页；在首页 → 交给系统退出。
    // 没有这一段的话，用户在「目标管理」里按返回会直接把整个 App 关掉 ——
    // 那是很严重的体验问题（用户会以为 App 崩了）。
    BackHandler(enabled = detail != null || selectedIndex != 0) {
        if (detail != null) {
            closeDetail()
        } else {
            selectedIndex = 0
        }
    }

    if (detail != null) {
        // 二级页面用 Scaffold 兜一层：它提供状态栏 / 导航栏的安全边距。
        // 之前二级页面是直接铺满的，结果顶部的返回按钮被状态栏压住，
        // 看起来「太靠上」—— 那不是样式问题，是没处理系统栏内边距。
        Scaffold { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                when (detail) {
                    DetailScreen.GOALS -> GoalManagementScreen(
                        container = container,
                        onBack = ::closeDetail,
                        onManageCatalog = { openDetail(DetailScreen.CATALOG) },
                    )

                    DetailScreen.CATALOG -> CatalogManagementScreen(
                        container = container,
                        onBack = ::closeDetail,
                    )

                    DetailScreen.PLAN -> PlanManagementScreen(
                        container = container,
                        onBack = ::closeDetail,
                    )

                    DetailScreen.CONFIRM -> ConfirmationScreen(
                        container = container,
                        // 目前只从首页进入，所以永远是「今天」。
                        // ViewModel 本身已经接受任意逻辑日，将来从复盘通知进入「昨天」不需要改这里以外的东西。
                        epochDay = container.dayBoundary.todayEpochDay(),
                        onBack = ::closeDetail,
                    )

                    DetailScreen.MAPPING -> AppMappingScreen(
                        container = container,
                        onBack = ::closeDetail,
                    )
                }
            }
        }
        return
    }

    val tabs = AppTab.entries
    val selected = tabs[selectedIndex.coerceIn(0, tabs.lastIndex)]

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = index == selectedIndex,
                        onClick = { selectedIndex = index },
                        icon = {
                            Icon(
                                painter = painterResource(tab.iconRes),
                                // 标签文字已经说明了用途，图标再报一遍只会让读屏变啰嗦
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (selected) {
                AppTab.HOME -> HomeScreen(
                    container = container,
                    onManageGoals = { openDetail(DetailScreen.GOALS) },
                    onManagePlans = { openDetail(DetailScreen.PLAN) },
                    onReviewPending = { openDetail(DetailScreen.CONFIRM) },
                    onManageAppMapping = { openDetail(DetailScreen.MAPPING) },
                )

                AppTab.CALENDAR -> CalendarScreen(
                    container = container,
                    onManagePlans = { openDetail(DetailScreen.PLAN) },
                )

                AppTab.STATS -> StatsScreen(container = container)
            }
        }
    }
}
