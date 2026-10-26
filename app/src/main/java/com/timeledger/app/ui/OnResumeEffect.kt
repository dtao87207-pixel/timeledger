package com.timeledger.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 每次界面回到前台时执行一次 [block]。
 *
 * 为什么需要：用户去系统设置里打开「使用情况访问」，再回到本应用 ——
 * 这一去一回必须触发一次重新判断权限，否则首页上那张卡片不会消失，
 * 用户会以为授权没生效（而这正是这个功能最容易劝退人的地方）。
 *
 * 自己写而不是用 `LifecycleEventEffect`：那个 API 只在较新版本的
 * lifecycle-runtime-compose 里才有，而这里用 `LocalLifecycleOwner` +
 * `LifecycleEventObserver` 就能做到，**不额外依赖任何版本敏感的入口**。
 */
@Composable
fun OnResumeEffect(block: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // 用 rememberUpdatedState：block 每次重组都是新对象，
    // 但观察器只装一次，必须让它拿到的是最新的那个
    val currentBlock by rememberUpdatedState(block)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) currentBlock()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
