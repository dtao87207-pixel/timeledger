package com.timeledger.app.ui.usage

import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.usage.UsageAnalysisResult
import com.timeledger.app.domain.usage.UsageSyncResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统感知状态文案测试。
 *
 * 这一块存在的唯一理由是「把话说清楚」：特殊权限不能弹对话框，
 * 用户只能自己摸到系统设置里；如果这里的话说不明白，
 * 用户要么不知道有自动记账这回事，要么授权后以为功能坏了。
 */
class UsageAccessUiMapperTest {

    private val mapper = UsageAccessUiMapper()

    private fun analysis(
        sessions: Int = 1,
        system: Int = 0,
        suggested: Int = 0,
        blocked: Int = 0,
        unresolved: List<String> = emptyList(),
    ) = UsageAnalysisResult(
        epochDay = 20_000L,
        analyzedRange = MillisRange(0L, 3_600_000L),
        sessionCount = sessions,
        writtenCount = system + suggested,
        systemCount = system,
        suggestedCount = suggested,
        blockedByManualCount = blocked,
        unresolvedPackages = unresolved,
    )

    private fun result(
        granted: Boolean = true,
        analyses: List<UsageAnalysisResult> = listOf(analysis()),
    ) = UsageSyncResult(
        granted = granted,
        newEventCount = 2,
        readEventCount = 2,
        analyses = analyses,
    )

    // ---------------- 未授权 ----------------

    @Test
    fun `without access the user is told how to grant it`() {
        val ui = mapper.map(granted = false, syncing = false, result = null)

        assertTrue(ui.isVisible)
        assertFalse(ui.granted)
        assertEquals("去授权", ui.actionLabel)
        // 说明里要写清「读的是什么」，否则没人愿意给这种权限
        assertTrue(ui.message.contains("使用情况访问"))
        assertTrue(ui.message.contains("不会读取任何内容"))
    }

    @Test
    fun `the permission prompt is shown even if a previous result exists`() {
        // 用户可能刚把权限关掉：此时必须回到引导态，而不是继续显示旧状态
        val ui = mapper.map(granted = false, syncing = false, result = result())

        assertFalse(ui.granted)
        assertEquals("去授权", ui.actionLabel)
        assertNull(ui.statusLabel)
    }

    // ---------------- 读取中 ----------------

    @Test
    fun `syncing is shown as a status line without a button`() {
        val ui = mapper.map(granted = true, syncing = true, result = null)

        assertTrue(ui.isVisible)
        assertNull(ui.actionLabel)
        assertEquals("正在读取系统记录…", ui.statusLabel)
    }

    // ---------------- 已授权 ----------------

    @Test
    fun `having access but no data is explained instead of shown as empty`() {
        // 「已授权但系统里还没记录」与「没授权」是两件事：
        // 都写成「暂无数据」的话，用户会一直去点授权按钮
        val ui = mapper.map(granted = true, syncing = false, result = result(analyses = listOf(analysis(sessions = 0))))

        assertTrue(ui.granted)
        assertTrue(ui.statusLabel!!.contains("已授权"))
        assertTrue(ui.statusLabel!!.contains("还没有可用的使用记录"))
        assertEquals("重新读取", ui.actionLabel)
    }

    @Test
    fun `the status line summarises what the system produced`() {
        val ui = mapper.map(
            granted = true,
            syncing = false,
            result = result(
                analyses = listOf(analysis(sessions = 5, system = 3, suggested = 1, unresolved = listOf("a"))),
            ),
        )

        val status = ui.statusLabel!!
        assertTrue(status.contains("读出 5 段时间"))
        assertTrue(status.contains("3 段已自动归类"))
        assertTrue(status.contains("1 段待确认"))
        assertTrue(status.contains("1 个 App 还没归类"))
    }

    @Test
    fun `overlapping manual records are explained rather than hidden`() {
        // 「被挡下 2 段」不能只字不提：用户会以为自己记的东西被系统吃掉了
        val ui = mapper.map(
            granted = true,
            syncing = false,
            result = result(analyses = listOf(analysis(sessions = 4, system = 2, blocked = 2))),
        )

        assertTrue(ui.statusLabel!!.contains("以你的记录为准"))
    }

    @Test
    fun `zero values are left out of the status line`() {
        val ui = mapper.map(
            granted = true,
            syncing = false,
            result = result(analyses = listOf(analysis(sessions = 3, system = 3))),
        )

        val status = ui.statusLabel!!
        assertFalse("没有待确认就不该提「0 段待确认」", status.contains("待确认"))
        assertFalse(status.contains("0 个 App"))
        assertFalse(status.contains("重叠"))
    }

    @Test
    fun `numbers are summed across the analysed days`() {
        val ui = mapper.map(
            granted = true,
            syncing = false,
            result = result(
                analyses = listOf(
                    analysis(sessions = 2, system = 2),
                    analysis(sessions = 3, system = 1, suggested = 2),
                ),
            ),
        )

        val status = ui.statusLabel!!
        assertTrue(status.contains("读出 5 段时间"))
        assertTrue(status.contains("3 段已自动归类"))
        assertTrue(status.contains("2 段待确认"))
    }

    @Test
    fun `a granted result without analyses is still described`() {
        // 采集区间为空（例如刚好在日边界上）时不该说「读出 0 段」然后什么都不解释
        val ui = mapper.map(granted = true, syncing = false, result = result(analyses = emptyList()))

        assertTrue(ui.statusLabel!!.contains("还没有可用的使用记录"))
    }

    @Test
    fun `granted but never synced shows nothing to say`() {
        val ui = mapper.map(granted = true, syncing = false, result = null)

        assertTrue(ui.granted)
        assertNull(ui.statusLabel)
        assertFalse("没有内容可显示时不该占地方", ui.isVisible)
    }
}
