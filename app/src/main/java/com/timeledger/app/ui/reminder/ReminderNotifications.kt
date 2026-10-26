package com.timeledger.app.ui.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.timeledger.app.R
import com.timeledger.app.ui.MainActivity

/**
 * 通知的渠道与发送。
 *
 * ## ⚠️ 本文件属于平台层，**尚未在真机上验证**
 * 本机没有模拟器也没有连接设备，因此「通知到底会不会出现、静默渠道是不是真的不响」
 * 只能在真机上确认。这里能保证的只有：编译通过、逻辑正确、API 用法符合官方文档。
 *
 * 装到手机上如果通知不响，**最可能的原因是通知权限没给**
 * （Android 13+ 需要 POST_NOTIFICATIONS，见 MainActivity 里的请求）。
 *
 * ## 两个渠道，不是一个
 * 需求要求「提醒必须低打扰」，而凌晨复盘那条如果和中午提醒共用一个渠道，
 * 用户要么全开要么全关 —— 那等于把「凌晨别吵我」这个诉求交给了用户自己去忍受。
 * 分成两个渠道之后，用户可以在系统设置里单独关掉任意一个，
 * 而且静默渠道本身就没有声音与震动。
 */
object ReminderNotifications {

    const val CHANNEL_DEFAULT = "reminders"
    const val CHANNEL_SILENT = "reminders_silent"

    /** 创建渠道。重复调用是安全的（同 id 会覆盖），所以启动时无脑调一次即可。 */
    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DEFAULT,
                "时间提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "中午与晚上的记录提醒"
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SILENT,
                "每日复盘",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "凌晨整理完成后静默告知，不响铃、不震动"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    /**
     * 发送提醒。[ReminderPlan.shouldNotify] 为 false 时什么都不做 ——
     * 不打扰就是不打扰，不搞「静默推一条空通知」这种事。
     *
     * 没有通知权限时 `notify` 会被系统忽略（不会崩溃），所以这里不需要额外判断权限：
     * 少一个分支，也少一处可能与系统行为不一致的判断。
     */
    fun post(context: Context, plan: ReminderPlan) {
        if (!plan.shouldNotify) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = if (plan.silent) CHANNEL_SILENT else CHANNEL_DEFAULT

        val notification = NotificationCompat.Builder(context, channel)
            // 通知图标必须是纯白剪影，用启动图标会在状态栏变成一块白方块
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(plan.title)
            .setContentText(plan.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(plan.body))
            .setPriority(
                if (plan.silent) NotificationCompat.PRIORITY_LOW
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()

        // 用节点序号当通知 id：同一个节点重复触发时是替换而不是堆叠
        manager.notify(plan.kind.ordinal, notification)
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
