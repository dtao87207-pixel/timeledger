package com.timeledger.app.domain.usage

import com.timeledger.app.core.time.MillisRange
import com.timeledger.app.domain.model.UsageEvent

/**
 * 读取系统使用记录的平台端口（需求六节）。
 *
 * 定义在 domain 而不是 data，是为了让 [UsageSyncService] 能在 JVM 上被测试：
 * 采集流程（游标、幂等、分析时机）都是逻辑，只有「怎么问系统要数据」是平台相关的。
 * 真机上唯一需要验证的东西，因此被压缩到这一个接口的实现里。
 */
interface UsageEventReader {

    /**
     * 系统是否允许读取使用记录。
     *
     * 这是**特殊权限**（「使用情况访问」），不在运行时权限之列，只能跳系统设置让用户手动开。
     * 未授权时 [read] 会返回空列表 —— 但「没授权」与「授权了但没有事件」是两件事，
     * 界面上必须能分开说，所以单独给出这个判断。
     */
    suspend fun isGranted(): Boolean

    /**
     * 读取区间内的原始事件（左闭右开），已映射成领域模型并带上幂等键。
     *
     * 未授权或有任何平台异常时返回空列表，不抛异常：
     * 「今天没有使用记录」与「系统不让我读」在数据层的处理是一样的（什么都不写），
     * 界面靠 [isGranted] 区分提示。
     */
    suspend fun read(range: MillisRange): List<UsageEvent>
}
