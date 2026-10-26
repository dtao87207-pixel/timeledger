package com.timeledger.app.ui.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.timeledger.app.TimeLedgerApplication
import com.timeledger.app.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 提醒闹钟的接收器。
 *
 * ## ⚠️ 平台层，**尚未在真机验证**
 * 「闹钟到底会不会被触发」「通知会不会出现」只能在真机上确认。
 *
 * ## 这里为什么几乎不含判断
 * 「该不该提醒、说什么」全在 [ReminderPlanner] 里（纯函数、已测）。
 * 这个类只做三件事：拿到是哪个节点 → 把数据准备好 → 交给规划器并发送。
 * 这样即使平台层某处写错了，也不会让产品规则跟着错 —— 换个平台实现即可。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_REMIND) return

        val kind = ReminderKind.fromNameOrNull(intent.getStringExtra(ReminderScheduler.EXTRA_KIND))
            ?: return
        val application = context.applicationContext as? TimeLedgerApplication ?: return
        val container = application.container
        val appContext = context.applicationContext

        // goAsync：onReceive 必须在 10 秒内返回，而这里要查数据库。
        // 用它换一个「稍后结束」的许可，在协程里把整件事做完。
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ReminderNotifications.post(appContext, buildPlan(container, kind))
            } finally {
                // 无论成功失败都必须 finish，否则系统会认为接收器卡死
                pendingResult.finish()
            }
        }
    }

    private suspend fun buildPlan(container: AppContainer, kind: ReminderKind): ReminderPlan {
        val dayBoundary = container.dayBoundary
        val now = dayBoundary.nowMillis()

        // 该汇报哪一天交给纯逻辑层算：复盘是「刚刚结束的那一天」，
        // 而闹钟可能被系统推迟，所以按实际触发的那一刻算，平台层不自己判断。
        val targetDay = ReminderSchedule.targetDayFor(kind, now, dayBoundary)

        val review = container.dayReviewService.observeDayReview(targetDay).first()
        val goals = container.goalProgressService.observeDayProgress(targetDay).first()
        val noonMillis = dayBoundary.millisAtMinuteOfDay(targetDay, 12 * 60)

        return ReminderPlanner().plan(
            ReminderContext.of(
                kind = kind,
                review = review,
                goals = goals,
                noonMillis = noonMillis,
            )
        )
    }
}

/**
 * 开机后重新安排闹钟。
 *
 * 系统的闹钟在重启后会被清空 —— 没有这个接收器，用户重启一次手机，
 * 提醒就永久失效了，而这种「功能悄悄消失」的问题用户很难自己定位。
 *
 * 它必须 `exported="true"`：`BOOT_COMPLETED` 是系统广播，
 * 未导出的接收器收不到（系统只对同 UID 的接收器投递）。
 * 因此这里只做「重新安排」这一件事，不接受任何来自外部的指令。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        ReminderNotifications.ensureChannels(context)
        ReminderScheduler.scheduleAll(context)
    }
}
