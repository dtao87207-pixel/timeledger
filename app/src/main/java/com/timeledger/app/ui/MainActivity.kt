package com.timeledger.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.timeledger.app.TimeLedgerApplication
import com.timeledger.app.ui.theme.TimeLedgerTheme

/**
 * 唯一的 Activity —— 界面全部由 Compose 绘制。
 *
 * 依赖容器从 Application 取，而不是在这里 new 一个：
 * 容器必须是全 App 单例（数据库、仓库都挂在上面），
 * 在 Activity 里重建会导致每次旋转屏幕都开一次数据库。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as TimeLedgerApplication).container
        setContent {
            TimeLedgerTheme {
                RequestNotificationPermission()
                TimeLedgerApp(container = container)
            }
        }
    }
}

/**
 * Android 13+ 的通知权限请求。
 *
 * ## ⚠️ 平台层，**尚未在真机验证**
 * 权限弹窗的实际行为（什么时候弹、用户拒绝后再问会怎样）只能在真机上确认。
 *
 * ## 为什么在启动时就问
 * 通知权限只在「用户第一次看到它有必要」时问效果最好，而这个 App 的提醒
 * 是固定时段触发的，没有更自然的时机。启动时问一次、拒绝了就不再纠缠
 * （`LaunchedEffect(Unit)` 每次启动只跑一次，系统自身也会在用户拒绝两次后不再弹窗）。
 *
 * ## 被拒绝也不能影响其它功能
 * 提醒只是锦上添花：记录、复盘、统计都不依赖通知。
 * 所以这里拿到结果后什么都不做，不弹二次说明、不做任何降级提示。
 */
@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒绝也不影响其它功能，什么都不做 */ }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
