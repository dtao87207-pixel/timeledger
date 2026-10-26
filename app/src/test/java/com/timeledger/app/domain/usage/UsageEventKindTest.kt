package com.timeledger.app.domain.usage

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 系统事件含义测试。
 *
 * 这里断言的是**平台常量本身**。它们的值是本次实现前用
 * `javap -constants` 从本机 SDK 的 `android.jar` 里读出来的，
 * 所以这些用例的作用是「把那次确认的结果钉住」：
 * 将来有人凭印象改了一个数字，测试会立刻失败。
 */
class UsageEventKindTest {

    @Test
    fun `foreground and background constants match the platform`() {
        assertEquals(1, UsageEventTypes.ACTIVITY_RESUMED)
        assertEquals(2, UsageEventTypes.ACTIVITY_PAUSED)
        assertEquals(23, UsageEventTypes.ACTIVITY_STOPPED)
    }

    @Test
    fun `old and new names share the same value`() {
        // MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND 已废弃，但值与
        // ACTIVITY_RESUMED / ACTIVITY_PAUSED 完全相同 —— 所以不需要按版本写两套判断
        assertEquals(UsageEventKind.FOREGROUND, UsageEventTypes.kindOf(1))
        assertEquals(UsageEventKind.BACKGROUND, UsageEventTypes.kindOf(2))
    }

    @Test
    fun `screen keyguard and device constants match the platform`() {
        assertEquals(15, UsageEventTypes.SCREEN_INTERACTIVE)
        assertEquals(16, UsageEventTypes.SCREEN_NON_INTERACTIVE)
        assertEquals(17, UsageEventTypes.KEYGUARD_SHOWN)
        assertEquals(18, UsageEventTypes.KEYGUARD_HIDDEN)
        assertEquals(26, UsageEventTypes.DEVICE_SHUTDOWN)
        assertEquals(27, UsageEventTypes.DEVICE_STARTUP)
    }

    @Test
    fun `event kinds are mapped to their meaning`() {
        assertEquals(UsageEventKind.FOREGROUND, UsageEventTypes.kindOf(1))
        assertEquals(UsageEventKind.BACKGROUND, UsageEventTypes.kindOf(2))
        assertEquals(UsageEventKind.ACTIVITY_STOPPED, UsageEventTypes.kindOf(23))
        assertEquals(UsageEventKind.SCREEN_ON, UsageEventTypes.kindOf(15))
        assertEquals(UsageEventKind.SCREEN_OFF, UsageEventTypes.kindOf(16))
        assertEquals(UsageEventKind.KEYGUARD_SHOWN, UsageEventTypes.kindOf(17))
        assertEquals(UsageEventKind.KEYGUARD_HIDDEN, UsageEventTypes.kindOf(18))
        assertEquals(UsageEventKind.DEVICE_SHUTDOWN, UsageEventTypes.kindOf(26))
        assertEquals(UsageEventKind.DEVICE_STARTUP, UsageEventTypes.kindOf(27))
    }

    @Test
    fun `foreground service events are not treated as the user using an app`() {
        // 19 / 20 是后台放音乐、常驻推送服务产生的。把它们当成「用户在用」，
        // 一晚上就会凭空多出几小时使用记录 —— 这是最容易悄悄错的地方。
        assertEquals(UsageEventKind.OTHER, UsageEventTypes.kindOf(19))
        assertEquals(UsageEventKind.OTHER, UsageEventTypes.kindOf(20))
    }

    @Test
    fun `unknown event types are not interpreted but also not invented`() {
        listOf(0, 3, 4, 5, 6, 7, 8, 11, 99, -1).forEach { raw ->
            assertEquals("eventType=$raw", UsageEventKind.OTHER, UsageEventTypes.kindOf(raw))
        }
    }
}
