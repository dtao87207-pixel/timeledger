package com.timeledger.app

import android.app.Application
import com.timeledger.app.di.AppContainer
import com.timeledger.app.ui.reminder.ReminderNotifications
import com.timeledger.app.ui.reminder.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application：组装依赖容器，并在启动时补齐内置分类/活动。
 *
 * 关于种子的执行时机：
 * 这里用后台协程异步写入，而不是阻塞 onCreate。因此「首次启动的那一瞬间」
 * 界面可能短暂看到空分类列表 —— 这是可接受的，因为 UI 订阅的是 Flow，
 * 种子写完会自动刷新。反过来，如果在 onCreate 里同步等数据库写入，
 * 会拖慢冷启动，代价更大。
 *
 * 种子是逐条幂等的，所以进程被杀、重复启动都不会写重（见 CatalogSeederImpl）。
 */
class TimeLedgerApplication : Application() {

    lateinit var container: AppContainer
        private set

    /**
     * 应用级协程作用域：生命周期与进程一致，不需要在 onDestroy 里取消
     * （Application 没有 onDestroy 回调）。用 SupervisorJob 保证
     * 一个任务失败不会拖垮其它任务。
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // 通知渠道与三个每日提醒闹钟，启动时安排一次即可：
        // 渠道同 id 会覆盖，闹钟同 requestCode 会替换，所以重复调用是幂等的。
        //
        // ⚠️ 这两行的实际效果（通知是否出现、闹钟是否按时触发）**需要真机验证**，
        // 本机没有设备也没有模拟器。逻辑部分（要不要提醒、说什么）已在
        // ReminderPlanner / ReminderSchedule 里用单元测试覆盖。
        ReminderNotifications.ensureChannels(this)
        ReminderScheduler.scheduleAll(this, container.dayBoundary)

        applicationScope.launch {
            container.catalogSeeder.seedDefaults()
        }
    }
}
