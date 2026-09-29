package com.qzkt.timetable.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.qzkt.timetable.ui.debug.DebugScreen
import com.qzkt.timetable.ui.grid.TimetableScreen
import com.qzkt.timetable.ui.log.ChangesScreen
import com.qzkt.timetable.ui.player.PlayerScreen
import com.qzkt.timetable.ui.settings.SettingsScreen
import com.qzkt.timetable.ui.setup.SetupScreen
import com.qzkt.timetable.ui.web.WebImportScreen

private object Routes {
    const val TIMETABLE = "timetable"
    const val CHANGES = "changes"
    const val SETTINGS = "settings"
    const val DEBUG = "debug"
    const val SETUP = "setup"
    const val WEB = "web"
    const val PLAYER = "player"
}

private data class BottomTab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    BottomTab(Routes.TIMETABLE, "课表", Icons.Default.CalendarMonth),
    BottomTab(Routes.PLAYER, "播放器", Icons.Default.PlayCircle),
    BottomTab(Routes.CHANGES, "变动", Icons.AutoMirrored.Filled.List),
    BottomTab(Routes.SETTINGS, "设置", Icons.Default.Settings),
)

@Composable
fun QzktApp(viewModel: MainViewModel, initialMediaUri: android.net.Uri? = null) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val changes by viewModel.changes.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val displayWeek by viewModel.displayWeek.collectAsStateWithLifecycle()
    val webImportResult by viewModel.webImportResult.collectAsStateWithLifecycle()

    val snackbarHost = remember { SnackbarHostState() }

    // 播放器全屏时把底部页签一起收起来，才是真的沉浸式。
    // 状态放在这一层是因为底部条属于外层外壳，播放器页管不到它。
    // 一律从 false 起步：不管怎么进来的（包括「打开方式」）都不要一上来就全屏，
    // 全屏交给播放器页右上角那个按钮。
    var playerFullscreen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // configured 只会从 false 变成 true 一次，所以重建导航栈只会发生一次（配置完成时）
    key(settings.configured) {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        val showBottomBar = settings.configured && currentRoute != Routes.DEBUG &&
            currentRoute != Routes.SETUP && currentRoute != Routes.WEB &&
            !(currentRoute == Routes.PLAYER && playerFullscreen)

        Scaffold(
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar {
                        TABS.forEach { tab ->
                            val selected = backStackEntry?.destination?.hierarchy?.any { it.route == tab.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHost) },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (uiState.busy) {
                    if (uiState.progressTotal > 0) {
                        LinearProgressIndicator(
                            progress = { uiState.progressDone.toFloat() / uiState.progressTotal.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (uiState.busyLabel.isNotBlank()) {
                        Text(
                            text = uiState.busyLabel,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = when {
                        initialMediaUri != null -> Routes.PLAYER
                        settings.configured -> Routes.TIMETABLE
                        else -> Routes.SETUP
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    composable(Routes.SETUP) {
                        SetupScreen(
                            settings = settings,
                            uiState = uiState,
                            onTest = viewModel::saveAndTest,
                            onImport = viewModel::saveAndImport,
                            onSave = viewModel::saveConfig,
                            onOpenWebImport = { navController.navigate(Routes.WEB) },
                        )
                    }

                    composable(Routes.WEB) {
                        WebImportScreen(
                            baseUrl = settings.baseUrl,
                            busy = uiState.busy,
                            lastResult = webImportResult,
                            onBack = { navController.popBackStack() },
                            onDocuments = viewModel::importFromHtml,
                            onDirectFetch = viewModel::importWithSession,
                        )
                    }

                    composable(Routes.TIMETABLE) {
                        TimetableScreen(
                            snapshot = snapshot,
                            settings = settings,
                            displayWeek = displayWeek,
                            busy = uiState.busy,
                            onWeekChange = viewModel::showWeek,
                            onRefresh = viewModel::refresh,
                            onSetFirstMonday = viewModel::setFirstMonday,
                        )
                    }

                    composable(Routes.PLAYER) {
                        PlayerScreen(
                            initialUri = initialMediaUri,
                            fullscreen = playerFullscreen,
                            onFullscreenChange = { playerFullscreen = it },
                        )
                    }

                    composable(Routes.CHANGES) {
                        ChangesScreen(changes = changes, onClear = viewModel::clearChanges)
                    }

                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            settings = settings,
                            snapshot = snapshot,
                            onUpdate = viewModel::saveSettings,
                            onClearTimetable = viewModel::clearTimetable,
                            onOpenDebug = { navController.navigate(Routes.DEBUG) },
                            onReconfigure = {
                                viewModel.saveSettings { it.copy(configured = false) }
                            },
                        )
                    }

                    composable(Routes.DEBUG) {
                        DebugScreen(
                            exchanges = viewModel.diagnostics,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}
