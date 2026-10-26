package com.timeledger.app.domain.usage

/**
 * 系统事件的**含义** —— 数据层刻意不解释，解释只发生在这里。
 *
 * ## 常量值怎么来的
 * 不是凭记忆写的：用 `javap -constants` 从本机 SDK 的 `android.jar`
 * （platforms/android-36）里直接读出来的，也就是编译这个 App 时用的同一份定义。
 * 需求十六节第 11 条要求「先确认官方行为再实现」，这就是那一步的证据。
 *
 * | 常量 | 值 | 含义 |
 * |---|---|---|
 * | `ACTIVITY_RESUMED` / `MOVE_TO_FOREGROUND` | 1 | 某个 App 到了前台 |
 * | `ACTIVITY_PAUSED` / `MOVE_TO_BACKGROUND` | 2 | 某个 App 退到后台 |
 * | `ACTIVITY_STOPPED` | 23 | 活动被停止（API 29+，比 PAUSED 更晚） |
 * | `SCREEN_INTERACTIVE` | 15 | 屏幕亮起 |
 * | `SCREEN_NON_INTERACTIVE` | 16 | 屏幕熄灭 |
 * | `KEYGUARD_SHOWN` | 17 | 锁屏出现 |
 * | `KEYGUARD_HIDDEN` | 18 | 锁屏解除 |
 * | `DEVICE_SHUTDOWN` | 26 | 关机 |
 * | `DEVICE_STARTUP` | 27 | 开机 |
 * | `FOREGROUND_SERVICE_START/STOP` | 19 / 20 | 前台**服务**起停（**不是**用户在用） |
 *
 * ## 两条容易踩的坑
 * 1. **1 / 2 是同一个值的两个名字**：`MOVE_TO_FOREGROUND` 早已废弃，
 *    新名字是 `ACTIVITY_RESUMED`，但**值相同**。所以一个分支就覆盖了两个版本，
 *    不需要按版本写两套判断 —— 这一点只有确认过常量值才敢下结论。
 * 2. **前台服务不等于用户在使用**。19 / 20 是后台放音乐、常驻推送服务产生的，
 *    把它当成「用户在用什么」，一个晚上就会凭空多出几小时「使用记录」。
 *    因此它们归入 [OTHER]，不参与会话重建。
 */
enum class UsageEventKind {
    /** 某个 App 到了前台，会话开始。 */
    FOREGROUND,

    /** 某个 App 退到后台，会话结束。 */
    BACKGROUND,

    /** 活动被停止：比 PAUSED 更晚、更确定的「结束了」。 */
    ACTIVITY_STOPPED,

    SCREEN_ON,
    SCREEN_OFF,
    KEYGUARD_SHOWN,
    KEYGUARD_HIDDEN,
    DEVICE_SHUTDOWN,
    DEVICE_STARTUP,

    /**
     * 已落库但本版本不解释的事件。
     *
     * 未知类型**不会丢**：原始 int 值在采集时已经存进 `usage_events`，
     * 将来想用它们，不需要重新采集（这正是数据层保存原始值的原因）。
     */
    OTHER,
}

/** 事件类型常量与解释。所有 `UsageEvents.Event` 的 int 值只在这里出现一次。 */
object UsageEventTypes {

    const val ACTIVITY_RESUMED = 1
    const val ACTIVITY_PAUSED = 2
    const val ACTIVITY_STOPPED = 23
    const val SCREEN_INTERACTIVE = 15
    const val SCREEN_NON_INTERACTIVE = 16
    const val KEYGUARD_SHOWN = 17
    const val KEYGUARD_HIDDEN = 18
    const val FOREGROUND_SERVICE_START = 19
    const val FOREGROUND_SERVICE_STOP = 20
    const val DEVICE_SHUTDOWN = 26
    const val DEVICE_STARTUP = 27

    fun kindOf(eventType: Int): UsageEventKind = when (eventType) {
        ACTIVITY_RESUMED -> UsageEventKind.FOREGROUND
        ACTIVITY_PAUSED -> UsageEventKind.BACKGROUND
        ACTIVITY_STOPPED -> UsageEventKind.ACTIVITY_STOPPED
        SCREEN_INTERACTIVE -> UsageEventKind.SCREEN_ON
        SCREEN_NON_INTERACTIVE -> UsageEventKind.SCREEN_OFF
        KEYGUARD_SHOWN -> UsageEventKind.KEYGUARD_SHOWN
        KEYGUARD_HIDDEN -> UsageEventKind.KEYGUARD_HIDDEN
        DEVICE_SHUTDOWN -> UsageEventKind.DEVICE_SHUTDOWN
        DEVICE_STARTUP -> UsageEventKind.DEVICE_STARTUP
        else -> UsageEventKind.OTHER
    }
}
