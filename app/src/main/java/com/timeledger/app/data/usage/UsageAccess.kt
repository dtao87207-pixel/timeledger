package com.timeledger.app.data.usage

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * 「使用情况访问」权限的判定与引导（需求六节）。
 *
 * 这是**特殊权限**，不是运行时权限：没有对话框可弹，只能跳系统设置让用户自己开。
 * 判定方式也不是 `checkSelfPermission`，而是查 AppOps：
 * `OPSTR_GET_USAGE_STATS` 只有 `MODE_ALLOWED` 才算真的给了，
 * `MODE_DEFAULT` 表示「还没问过用户」——那等于没给。
 *
 * ## 版本差异（需求十六节第 11 条：先确认官方行为再实现）
 * API 29 起 `unsafeCheckOpNoThrow` 才是安全的查法，旧版本只有 `checkOpNoThrow`。
 * 两个方法都存在于本机 SDK 的 `android.jar` 里（已用 `javap` 确认），
 * 所以这里按版本选一个调用，而不是赌某个方法在所有版本上都不抛。
 *
 * 本类不依赖任何界面类型，注册表里也不需要额外权限 —— 但**必须**在清单里声明
 * `android.permission.PACKAGE_USAGE_STATS`，否则「使用情况访问」列表里根本不会出现这个 App。
 */
object UsageAccess {

    /** 是否拿到了读取使用记录的权限。 */
    fun isGranted(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false

        val mode = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            }
        } catch (e: SecurityException) {
            // 个别 ROM 会对这个查询本身抛 SecurityException。此时按「没给」处理：
            // 引导用户去设置页看一眼，比在这里崩掉有用。
            return false
        }

        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * 跳到系统设置里的「使用情况访问」页面。
     *
     * 用 `FLAG_ACTIVITY_NEW_TASK` 是因为调用方可能没有 Activity 上下文。
     * 注意设置页**不一定**直接定位到本 App，用户要自己在列表里找到「时间记账」——
     * 所以界面上的文案必须写清楚让他找什么。
     */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
