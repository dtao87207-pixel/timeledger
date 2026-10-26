package com.timeledger.app.ui.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.timeledger.app.core.time.DayBoundary

/**
 * 三个每日提醒的闹钟安排。
 *
 * ## ⚠️ 本文件属于平台层，**尚未在真机上验证**
 * 闹钟是否按时触发、系统会不会把它合并或推迟、重启后是否恢复，
 * 都只能在真机上观察。这里能保证的只有编译通过与 API 用法符合官方文档。
 *
 * ## 三个刻意的选择
 *
 * 1. **用非精确闹钟**（`setInexactRepeating` 而不是 `setExactAndAllowWhileIdle`）。
 *    提醒不是闹钟，晚十几分钟毫无影响；换来的是省电，以及**不需要申请
 *    `SCHEDULE_EXACT_ALARM` 权限**（Android 12+ 上那是需要用户去系统设置里单独授予的）。
 * 2. **用 `RTC` 而不是 `RTC_WAKEUP`**：不唤醒熄屏中的设备。
 *    系统会在设备下一次醒来时补发，而这正好符合「低打扰」—— 凌晨那条尤其不该把手机叫醒。
 * 3. **同一个节点用固定的 requestCode**（`kind.ordinal`）：
 *    重复调用 `scheduleAll` 是幂等的（会替换而不是叠加闹钟），
 *    所以启动时无脑调一次、开机后再调一次都不会造成重复提醒。
 */
object ReminderScheduler {

    const val ACTION_REMIND = "com.timeledger.app.action.REMIND"
    const val EXTRA_KIND = "kind"

    fun scheduleAll(context: Context, dayBoundary: DayBoundary = DayBoundary.system()) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val now = dayBoundary.nowMillis()

        ReminderKind.entries.forEach { kind ->
            val trigger = ReminderSchedule.nextTrigger(kind, now, dayBoundary)
            alarms.setInexactRepeating(
                AlarmManager.RTC,
                trigger.triggerMillis,
                AlarmManager.INTERVAL_DAY,
                pendingIntent(context, kind),
            )
        }
    }

    /** 取消全部提醒（V1 还没有设置界面，先把入口留着，方便调试与将来的开关）。 */
    fun cancelAll(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        ReminderKind.entries.forEach { kind ->
            alarms.cancel(pendingIntent(context, kind))
        }
    }

    fun pendingIntent(context: Context, kind: ReminderKind): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMIND
            putExtra(EXTRA_KIND, kind.name)
        }
        return PendingIntent.getBroadcast(
            context,
            kind.ordinal,
            intent,
            // FLAG_IMMUTABLE 是 Android 12+ 的硬性要求；不加会直接崩
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
