package com.timeledger.app.data.usage

import android.content.Context
import android.content.pm.PackageManager

/**
 * 包名 → 应用名。
 *
 * 为什么需要它：时间轴上显示「微信」和显示「com.tencent.mm」是两种完全不同的体验 ——
 * 前者用户一眼能判断，后者等于没告诉他任何事。
 *
 * ## API 30+ 的包可见性
 * 从 Android 11 起，查询别的 App 需要声明可见性，否则 `getApplicationInfo` 会抛
 * `NameNotFoundException`。清单里用 `<queries>` 声明了「带 LAUNCHER 入口的应用」——
 * 这是官方推荐的、比 `QUERY_ALL_PACKAGES` 克制得多的做法：
 * 我们只需要给用户看得见的 App 起名字，不需要枚举整个系统。
 *
 * 查询失败一律返回 null（用户可能刚卸载、或是系统组件），
 * 界面会回退显示包名，不会因此少一条记录。
 */
class AndroidAppLabelResolver(private val context: Context) {

    fun labelOf(packageName: String): String? = try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString().trim().takeIf { it.isNotEmpty() }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
