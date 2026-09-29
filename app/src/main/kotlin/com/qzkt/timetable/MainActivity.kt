package com.qzkt.timetable

import android.Manifest
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qzkt.timetable.ui.MainViewModel
import com.qzkt.timetable.ui.MainViewModelFactory
import com.qzkt.timetable.ui.QzktApp
import com.qzkt.timetable.ui.theme.QzktTheme
import com.qzkt.timetable.ui.theme.ThemeMode
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** 外部传进来要播放的媒体（content:// 或 file://）。 */
    private val mediaUri = mutableStateOf<android.net.Uri?>(null)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝就静默降级 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 通知权限：Android 13+ 才有。拒绝不影响主要功能，只是收不到变动和上课提醒
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val container = appContainer
        // 从文件管理器"打开方式"进来时带着媒体地址
        mediaUri.value = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data

        setContent {
            val viewModel: MainViewModel = viewModel(factory = MainViewModelFactory(container))
            val settings by viewModel.settings.collectAsStateWithLifecycle()

            val themeMode = remember(settings.themeMode) {
                runCatching { ThemeMode.valueOf(settings.themeMode) }.getOrDefault(ThemeMode.SYSTEM)
            }

            QzktTheme(themeMode = themeMode, dynamicColor = settings.dynamicColor) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    QzktApp(viewModel, mediaUri.value)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        mediaUri.value = intent.takeIf { it.action == Intent.ACTION_VIEW }?.data
    }

    override fun onResume() {
        super.onResume()
        // 回到前台时顺手重排一次提醒：课表可能在别处（后台同步）刚更新过
        lifecycleScope.launch {
            runCatching { appContainer.classReminders.rescheduleUpcoming() }
        }
    }
}
