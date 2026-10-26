package com.timeledger.app.ui.day

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap

/**
 * 取一个应用图标，给时间轴当标记用。
 *
 * 用户的原话：「你的时间轴的颜色可以直接利用应用的图标来标记更加好看」——
 * 比起一个色点，图标一眼就能认出是哪个 App，而且不用我们自己维护配色。
 *
 * ## 为什么放在界面层而不是映射器里
 * `Drawable` 是 Android 类型，而所有 `*StateMapper` 都是纯 JVM 类（这样它们能在
 * 没有 Android 环境的情况下被测试）。所以映射器只负责把**包名**放进
 * `TimelineRow.packageName`，取图标这一步留在 Composable 里。
 *
 * ## 缓存
 * `remember(packageName)` 保证同一个包名只查一次：时间轴可能有几十行，
 * 每次都走 PackageManager 会明显卡顿。查不到（已卸载、或包不可见）返回 null，
 * 调用方退回一个色点 —— **没有图标也要能看**。
 */
@Composable
fun rememberAppIcon(packageName: String?): ImageBitmap? {
    val context = LocalContext.current
    return remember(packageName) {
        if (packageName == null) return@remember null
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            // 缩到 96px 再转位图：图标在界面上只有十几 dp，原图可能是 512×512
            drawable.toBitmap(width = ICON_PIXELS, height = ICON_PIXELS).asImageBitmap()
        }.getOrNull()
    }
}

private const val ICON_PIXELS = 96
