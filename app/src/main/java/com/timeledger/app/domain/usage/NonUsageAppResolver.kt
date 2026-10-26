package com.timeledger.app.domain.usage

/**
 * 挑出「不属于用户在使用」的包名（桌面、系统界面、输入法、系统组件）。
 *
 * 定义成端口而不是常量表，有两个原因：
 *
 * 1. **不能硬编码厂商包名。** 桌面在不同机器上是 `com.bbk.launcher2`、
 *    `com.miui.home`、`com.huawei.android.launcher`…… 写死一家等于对别家失效。
 *    正确做法是用标准 API 判断，只有平台层做得到，而会话重建（纯逻辑）只需要一个集合。
 * 2. **可测。** 会话重建的测试直接传一个集合进去，不需要 Android 环境。
 *
 * ## 为什么这件事必须做（真机数据，模块 20）
 * 第一版只排除了「桌面 / 系统界面 / 输入法」三类，结果手机跑了一天之后，
 * 归类页里躺着 **47 个待归类 App**，其中一半用时不到 1 分钟，还混着这些：
 *
 * | 包 | 它其实是什么 |
 * |---|---|
 * | `com.android.permissioncontroller` | 权限控制器（弹权限框的那个） |
 * | `com.vivo.permissionmanager` | 权限管理 |
 * | `com.android.packageinstaller` | 软件包安装程序 |
 * | `com.vivo.magazine` | 阅图锁屏 |
 * | `com.bbk.launcher2` | 系统桌面（17 分钟！） |
 *
 * 它们都不是「用户打开的应用」，而是系统面板、对话框与组件 ——
 * 用户凭什么要给「权限管理」归类？所以判断标准不是「列一张黑名单」，
 * 而是问一句：**这个东西，用户能自己在桌面上点开吗？**
 */
fun interface NonUsageAppResolver {

    /**
     * 传入观察到的一批包名，返回其中「不是用户在使用」的那部分。
     *
     * 平台实现要同时满足两类判断：
     * - **显式排除**：桌面（`CATEGORY_HOME`）、`com.android.systemui`、输入法；
     * - **通用规则**：没有 `MAIN`/`LAUNCHER` 入口的包（用户点不开的东西）。
     *
     * 允许失败：拿不到就返回空集合 —— 少排除几个包名只是时间轴上多几段噪音，
     * 而抛异常会让整天都分析不出来。
     */
    suspend fun resolve(observedPackages: Set<String>): Set<String>
}
