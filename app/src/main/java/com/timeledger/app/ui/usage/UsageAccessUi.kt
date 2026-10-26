package com.timeledger.app.ui.usage

import com.timeledger.app.domain.usage.UsageSyncResult

/**
 * 系统感知在首页上的一小块状态。
 *
 * ## 为什么需要它
 * 「使用情况访问」是特殊权限：没有对话框可弹，只能跳系统设置让用户自己找。
 * 如果不解释清楚，用户会遇到两种情况 ——
 * 一是不知道有这个功能（于是永远在手动记账），
 * 二是授权后看到时间轴没变化，以为坏了（其实那天本来就没有可用记录）。
 * 这一块就是用来把这两件事说清楚的。
 */
data class UsageAccessUi(
    /** 是否已授权。未授权时显示引导卡片。 */
    val granted: Boolean,
    val title: String,
    val message: String,
    /** 按钮文案；null 表示不需要按钮（例如正在读取）。 */
    val actionLabel: String?,
    /**
     * 第二个按钮：有未归类的 App 时出现，点了去归类页。
     *
     * 为什么必须有：状态里已经写着「N 个 App 还没归类」，
     * 如果只说问题不给入口，用户就得自己去猜这个功能藏在哪儿 ——
     * 而「归类一次、之后自动」正是这款 App 省事的开关。
     */
    val secondaryActionLabel: String?,
    /** 已授权时的状态一行字：系统这次读到了什么。 */
    val statusLabel: String?,
) {
    /** 完全无事可说时不必占地方。 */
    val isVisible: Boolean get() = !granted || statusLabel != null
}

/**
 * 系统感知状态 → 界面文案的**纯函数**映射。
 *
 * 与其他界面映射器同样的理由：本机无法目视验证界面，
 * 所以「什么时候提示什么」必须放在能被测试覆盖的地方。
 *
 * ## 最要紧的一条：区分「没授权」和「授权了但没数据」
 * 这两件事的界面提示完全不同 —— 前者要引导去设置，后者要说明系统里确实还没有记录
 * （刚授权、当天还没怎么用手机、或 ROM 没有提供历史）。
 * 混在一起说一句「暂无数据」，用户会一直去点授权按钮。
 */
class UsageAccessUiMapper {

    fun map(
        granted: Boolean,
        syncing: Boolean,
        result: UsageSyncResult?,
    ): UsageAccessUi {
        if (!granted) {
            return UsageAccessUi(
                granted = false,
                title = "让系统自动记账",
                message = "授权「使用情况访问」后，手机会把各 App 的使用记录交给本应用，" +
                    "由它还原出你一天的时间轴 —— 你只需要确认，不必手动记。" +
                    "本应用只读使用时长，不会读取任何内容。",
                actionLabel = "去授权",
                secondaryActionLabel = null,
                statusLabel = null,
            )
        }

        if (syncing) {
            return UsageAccessUi(
                granted = true,
                title = "系统感知",
                message = "",
                actionLabel = null,
                secondaryActionLabel = null,
                statusLabel = "正在读取系统记录…",
            )
        }

        val status = result?.let(::statusLabel)
        val hasUnresolved = result?.unresolvedPackages?.isNotEmpty() == true
        return UsageAccessUi(
            granted = true,
            title = "系统感知",
            message = "",
            // 已授权时给一个手动重读的入口：有些 ROM 的数据会延迟写入
            actionLabel = "重新读取",
            secondaryActionLabel = if (hasUnresolved) "去归类" else null,
            statusLabel = status,
        )
    }

    private fun statusLabel(result: UsageSyncResult): String {
        if (result.sessionCount == 0) {
            // 别写成「暂无数据」：那会让人以为授权没生效
            return "已授权，但系统里还没有可用的使用记录 —— " +
                "刚授权或今天还没怎么用手机时都是这样，晚点再回来看"
        }

        val parts = buildList {
            add("读出 ${result.sessionCount} 段时间")
            if (result.systemCount > 0) add("${result.systemCount} 段已自动归类")
            if (result.suggestedCount > 0) add("${result.suggestedCount} 段待确认")
            if (result.blockedByManualCount > 0) {
                // 手动记录优先是产品规则，这里要说明「不是丢了」
                add("${result.blockedByManualCount} 段与你的记录重叠，以你的记录为准")
            }
            if (result.unresolvedPackages.isNotEmpty()) {
                add("${result.unresolvedPackages.size} 个 App 还没归类")
            }
        }
        return parts.joinToString("，")
    }
}
