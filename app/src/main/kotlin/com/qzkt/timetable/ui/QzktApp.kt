package com.qzkt.timetable.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Widgets
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
import com.qzkt.timetable.ui.account.AccountScreen
import com.qzkt.timetable.ui.anime.AnimeDetailScreen
import com.qzkt.timetable.ui.anime.AnimeScreen
import com.qzkt.timetable.ui.anime.AnimeViewModel
import com.qzkt.timetable.ui.debug.DebugScreen
import com.qzkt.timetable.ui.grid.TimetableScreen
import com.qzkt.timetable.ui.log.ChangesScreen
import com.qzkt.timetable.ui.player.PlayerScreen
import com.qzkt.timetable.ui.player.ResolveScreen
import com.qzkt.timetable.ui.settings.SettingsScreen
import com.qzkt.timetable.ui.setup.SetupScreen
import com.qzkt.timetable.ui.anime.AnimeViewModelFactory
import com.qzkt.timetable.ui.book.BookshelfScreen
import com.qzkt.timetable.ui.book.BookViewModel
import com.qzkt.timetable.ui.book.BookViewModelFactory
import com.qzkt.timetable.ui.book.ReaderScreen
import com.qzkt.timetable.ui.tools.ToolsScreen
import com.qzkt.timetable.ui.web.WebImportScreen

private object Routes {
    /** 工具页：起始页，课表和播放器的入口都在这里。 */
    const val TOOLS = "tools"
    const val TIMETABLE = "timetable"
    const val PLAYER = "player"
    /** 影视：浏览、收藏、选集播放。 */
    const val ANIME = "anime"
    /** 链接解析：B站 / 抖音分享链接换真实地址后交给播放器。 */
    const val RESOLVE = "resolve"
    /** 图书：本地 TXT 书架。 */
    const val BOOK = "book"
    /** 参数是书籍 id。 */
    const val BOOK_READ = "book_read/{bookId}"
    /** 参数是资源站里的番剧 id。 */
    const val ANIME_DETAIL = "anime_detail/{vodId}"
    const val CHANGES = "changes"
    const val SETTINGS = "settings"
    const val DEBUG = "debug"
    const val SETUP = "setup"
    const val WEB = "web"
    const val ACCOUNT = "account"
}

/** 从「工具」页进去的页面：停在这些页时底部「工具」页签保持选中。 */
private val TOOLS_SUB_PAGES = setOf(
    Routes.TIMETABLE,
    Routes.PLAYER,
    Routes.RESOLVE,
    Routes.ANIME,
    Routes.BOOK,
    Routes.BOOK_READ,
    Routes.CHANGES,
    Routes.ACCOUNT,
)

private data class BottomTab(val route: String, val label: String, val icon: ImageVector)

// 底部页签只剩「工具」和「设置」：课表、播放器变成了工具页里的两个入口。
private val TABS = listOf(
    BottomTab(Routes.TOOLS, "工具", Icons.Default.Widgets),
    BottomTab(Routes.SETTINGS, "设置", Icons.Default.Settings),
)

@Composable
fun QzktApp(
    viewModel: MainViewModel,
    animeViewModel: AnimeViewModel,
    bookViewModel: BookViewModel,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val changes by viewModel.changes.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val displayWeek by viewModel.displayWeek.collectAsStateWithLifecycle()
    val webImportResult by viewModel.webImportResult.collectAsStateWithLifecycle()
    val animeFavorites by animeViewModel.favorites.collectAsStateWithLifecycle()
    val animeSession by animeViewModel.animeSession.collectAsStateWithLifecycle()
    val animeSourceKey by animeViewModel.sourceKey.collectAsStateWithLifecycle()

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

    // 导航栈不随 configured 重建：首次启动直接落到工具页，配置教务账号是从课表页进去的
    // （配置成功后由 Routes.SETUP 那段自己退回上一页，见下面的 LaunchedEffect）。
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // 底部页签一直显示：以前没配置时是「向导优先」的全屏流程，现在首次启动也直接进主页。
    // 阅读页例外：那里底部要留给「上一章 / 目录 / 下一章」，App 的页签不跟它抢地方。
    val showBottomBar = currentRoute != Routes.DEBUG &&
        currentRoute != Routes.SETUP && currentRoute != Routes.WEB &&
        currentRoute != Routes.BOOK_READ &&
        !(currentRoute == Routes.PLAYER && playerFullscreen)

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TABS.forEach { tab ->
                        // 课表 / 播放器 / 它们的子页现在是从「工具」页进去的，
                        // 停在这些页时「工具」页签保持选中，
                        // 否则底部会出现「哪个页签都没选中」的怪状态
                        val selected = backStackEntry?.destination?.hierarchy?.any { it.route == tab.route } == true ||
                            (tab.route == Routes.TOOLS && currentRoute in TOOLS_SUB_PAGES)
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
                // 起点永远是工具页（主页）：首次使用也是先进主页，
                // 用户进课表页之后从右上角「教务账号」去配置（课表页有引导）。
                startDestination = Routes.TOOLS,
                modifier = Modifier.weight(1f),
            ) {
                composable(Routes.SETUP) {
                    // 配置成功后自己退回上一页（首次是从 课表 → 教务账号 → 这里进来的）。
                    // 只认「进来时没配置、后来变成已配置」，免得已配置状态下进错页面被立刻弹走。
                    // rememberSaveable：从「在应用内登录」那张网页页（WEB）返回时，
                    // SETUP 会重新进组合，普通 remember 会丢，导致配置成功后不自动退回。
                    var wasUnconfigured by rememberSaveable { mutableStateOf(!settings.configured) }
                    LaunchedEffect(settings.configured) {
                        if (!settings.configured) {
                            wasUnconfigured = true
                        } else if (wasUnconfigured) {
                            navController.popBackStack()
                        }
                    }

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

                composable(Routes.TOOLS) {
                    ToolsScreen(
                        snapshot = snapshot,
                        animeFavoriteCount = animeFavorites.size,
                        configured = settings.configured,
                        onOpenTimetable = { navController.navigate(Routes.TIMETABLE) },
                        onOpenResolve = { navController.navigate(Routes.RESOLVE) },
                        onOpenBooks = { navController.navigate(Routes.BOOK) },
                        onOpenAnime = { navController.navigate(Routes.ANIME) },
                    )
                }

                composable(Routes.RESOLVE) {
                    ResolveScreen(
                        onBack = { navController.popBackStack() },
                        onPlay = { request ->
                            animeViewModel.playResolved(request)
                            navController.navigate(Routes.PLAYER)
                        },
                    )
                }

                composable(Routes.BOOK) {
                    BookshelfScreen(
                        viewModel = bookViewModel,
                        onBack = { navController.popBackStack() },
                        onOpenBook = { id -> navController.navigate("book_read/$id") },
                    )
                }

                composable(Routes.BOOK_READ) { entry ->
                    ReaderScreen(
                        bookId = entry.arguments?.getString("bookId").orEmpty(),
                        viewModel = bookViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.ANIME) {
                    AnimeScreen(
                        viewModel = animeViewModel,
                        onBack = { navController.popBackStack() },
                        onOpenDetail = { id -> navController.navigate("anime_detail/$id") },
                    )
                }

                composable(Routes.ANIME_DETAIL) { entry ->
                    AnimeDetailScreen(
                        vodId = entry.arguments?.getString("vodId").orEmpty(),
                        viewModel = animeViewModel,
                        onBack = { navController.popBackStack() },
                        onPlay = { navController.navigate(Routes.PLAYER) },
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
                        changeCount = changes.size,
                        onOpenChanges = { navController.navigate(Routes.CHANGES) },
                        onOpenAccount = { navController.navigate(Routes.ACCOUNT) },
                    )
                }

                composable(Routes.ACCOUNT) {
                    AccountScreen(
                        settings = settings,
                        snapshot = snapshot,
                        onUpdate = viewModel::saveSettings,
                        onClearTimetable = viewModel::clearTimetable,
                        onOpenDebug = { navController.navigate(Routes.DEBUG) },
                        onBack = { navController.popBackStack() },
                        onConfigure = { navController.navigate(Routes.SETUP) },
                    )
                }

                composable(Routes.PLAYER) {
                    PlayerScreen(
                        fullscreen = playerFullscreen,
                        onFullscreenChange = { playerFullscreen = it },
                        // 番剧页点进来的一路：PlayerScreen 组合时消费
                        playRequest = animeViewModel.pendingPlay,
                        onPlayRequestConsumed = { animeViewModel.consumePlayRequest() },
                        // 播放会话放 VM 里：退出播放器再进来，选集内容还在
                        animeSession = animeSession,
                        onEpisodeSwitched = { animeViewModel.switchEpisode(it) },
                    )
                }

                composable(Routes.CHANGES) {
                    ChangesScreen(
                        changes = changes,
                        onClear = viewModel::clearChanges,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        settings = settings,
                        onUpdate = viewModel::saveSettings,
                        animeFavoriteCount = animeFavorites.size,
                        onClearAnimeFavorites = { animeViewModel.clearFavorites() },
                        videoSources = animeViewModel.availableSources,
                        videoSourceKey = animeSourceKey,
                        onSelectVideoSource = { animeViewModel.selectSource(it) },
                        bookViewModel = bookViewModel,
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
