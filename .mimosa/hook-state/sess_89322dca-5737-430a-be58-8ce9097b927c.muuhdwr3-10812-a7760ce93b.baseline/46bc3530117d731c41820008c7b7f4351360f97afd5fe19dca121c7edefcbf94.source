package com.qzkt.timetable

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qzkt.timetable.ui.MainViewModel
import com.qzkt.timetable.ui.MainViewModelFactory
import com.qzkt.timetable.ui.QzktApp
import com.qzkt.timetable.ui.anime.AnimeViewModel
import com.qzkt.timetable.ui.anime.AnimeViewModelFactory
import com.qzkt.timetable.ui.book.BookViewModel
import com.qzkt.timetable.ui.book.BookViewModelFactory
import com.qzkt.timetable.ui.grades.GradesViewModel
import com.qzkt.timetable.ui.grades.GradesViewModelFactory
import com.qzkt.timetable.ui.common.SplashOverlay
import com.qzkt.timetable.ui.theme.QzktTheme
import com.qzkt.timetable.ui.theme.ThemeMode
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

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

        setContent {
            val viewModel: MainViewModel = viewModel(factory = MainViewModelFactory(container))
            val animeViewModel: AnimeViewModel = viewModel(factory = AnimeViewModelFactory(container))
            val bookViewModel: BookViewModel = viewModel(factory = BookViewModelFactory(container))
            val gradesViewModel: GradesViewModel = viewModel(factory = GradesViewModelFactory(container))
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            // 开屏动画：每次正常启动来一段，旋转重建后不重播
            var showSplash by rememberSaveable { mutableStateOf(true) }

            val themeMode = remember(settings.themeMode) {
                runCatching { ThemeMode.valueOf(settings.themeMode) }.getOrDefault(ThemeMode.SYSTEM)
            }

            QzktTheme(themeMode = themeMode, dynamicColor = settings.dynamicColor) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        QzktApp(viewModel, animeViewModel, bookViewModel, gradesViewModel)
                    }
                    AnimatedVisibility(visible = showSplash, exit = fadeOut(animationSpec = tween(350))) {
                        SplashOverlay(onFinished = { showSplash = false })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 回到前台时顺手重排一次提醒：课表可能在别处（后台同步）刚更新过
        lifecycleScope.launch {
            runCatching { appContainer.classReminders.rescheduleUpcoming() }
        }
    }
}
