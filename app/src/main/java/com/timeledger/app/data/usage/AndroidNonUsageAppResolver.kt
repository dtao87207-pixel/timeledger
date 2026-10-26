package com.timeledger.app.data.usage

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.inputmethod.InputMethodManager
import com.timeledger.app.domain.usage.NonUsageAppResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 用标准 API 挑出「不是用户在做什么」的包名。
 *
 * 两类判断叠加，都不硬编码厂商包名：
 *
 * 1. **显式排除**：桌面（`ACTION_MAIN` + `CATEGORY_HOME`）、`com.android.systemui`、
 *    已启用的输入法。这三类是**切换的中间态**或系统面板，不是「用户打开的应用」。
 * 2. **通用规则**：没有 `MAIN`/`LAUNCHER` 入口的包 —— 用户根本点不开它。
 *    这一条覆盖了权限管理、权限控制器、软件包安装程序、锁屏画报这一类系统组件。
 *
 * 真机上验证过（vivo V2463A / Android 16）：
 * `packageinstaller` / `permissioncontroller` / `permissionmanager` / `systemui` /
 * `阅图锁屏` / `系统桌面` 全都没有桌面入口；而 `设置` / `相机` / `相册` / `健康` /
 * `计算器` / `浏览器` 都有 —— 后面这些用户确实会主动打开，该记就得记。
 *
 * 任何一步失败都只是少排除一项，所以每一步都 runCatching ——
 * 宁可时间轴多几段噪音，也不能因为一个查询异常让整天的分析不出来。
 */
class AndroidNonUsageAppResolver(private val context: Context) : NonUsageAppResolver {

    override suspend fun resolve(observedPackages: Set<String>): Set<String> =
        withContext(Dispatchers.IO) {
            val explicitlyExcluded = buildSet {
                add(SYSTEM_UI_PACKAGE)
                addAll(homePackages())
                addAll(inputMethodPackages())
                // TimeLedger 自身使用时间不计入统计，避免时间管理工具反而占用用户时间统计
                add(context.packageName)
            }
            observedPackages.filterTo(mutableSetOf()) { packageName ->
                packageName in explicitlyExcluded || !hasLauncherEntry(packageName)
            }
        }

    /**
     * 这个包能不能从桌面上点开。
     *
     * `getLaunchIntentForPackage` 在拿不到（没有桌面入口、或对本应用不可见）时返回 null。
     * 两种情况对我们都是同一个结论：**不该按「用户在使用」来记**。
     * 可见性由清单里的 `<queries>`（MAIN/LAUNCHER）保证 —— 有桌面入口的包都看得见。
     */
    private fun hasLauncherEntry(packageName: String): Boolean = runCatching {
        context.packageManager.getLaunchIntentForPackage(packageName) != null
    }.getOrDefault(false)

    /** 所有能当桌面的应用（用户可能装了第三方桌面）。 */
    private fun homePackages(): List<String> = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val manager = context.packageManager
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            manager.queryIntentActivities(intent, 0)
        }
        resolved.mapNotNull { it.activityInfo?.packageName }
    }.getOrDefault(emptyList())

    /** 已启用的输入法。 */
    private fun inputMethodPackages(): List<String> = runCatching {
        val manager = context.getSystemService(InputMethodManager::class.java)
            ?: return@runCatching emptyList()
        manager.enabledInputMethodList.mapNotNull { it.packageName }
    }.getOrDefault(emptyList())

    private companion object {
        /** 系统界面。这是 AOSP 里固定的包名，各家 ROM 沿用。 */
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}
