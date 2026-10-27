package com.qzkt.timetable.ui.anime

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.qzkt.timetable.data.anime.AnimeFavorite
import com.qzkt.timetable.data.anime.AnimeItem
import com.qzkt.timetable.data.anime.AnimeProgress
import com.qzkt.timetable.ui.common.LiquidGlassBackdrop
import kotlinx.coroutines.delay

/** 影视中心底部悬浮导航的四个页签。 */
private enum class AnimeTab(val label: String) {
    HOME("推荐"),
    FILTER("筛选"),
    FAVORITES("番剧"),
    RANK("排行"),
}

private fun AnimeTab.icon(): ImageVector = when (this) {
    AnimeTab.HOME -> Icons.Default.Home
    AnimeTab.FILTER -> Icons.Default.Tune
    AnimeTab.FAVORITES -> Icons.Default.Favorite
    AnimeTab.RANK -> Icons.Default.EmojiEvents
}

/**
 * 影视中心：首页推荐（banner + 观看历史 + 推荐网格）、筛选搜索、番剧收藏、排行四个页签，
 * 底部悬浮液态玻璃导航条。内容衬在 [LiquidGlassBackdrop] 上。
 *
 * 结构参考主流影视 App：顶部返回 + 搜索 / 历史 / 更多，底部四个页签悬浮不顶满宽度。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AnimeScreen(
    viewModel: AnimeViewModel,
    onBack: () -> Unit,
    onOpenDetail: (id: String) -> Unit,
    onManageSources: () -> Unit,
) {
    val listState by viewModel.list.collectAsStateWithLifecycle()
    val homeFeed by viewModel.home.collectAsStateWithLifecycle()
    val latestFeed by viewModel.latest.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    var queryInput by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf(AnimeTab.HOME) }
    // 影视设置（资源站 / 收藏管理）：原来在设置页，挪到本页右上角更多菜单
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    // 排行页签第一次打开时才发请求
    LaunchedEffect(tab) {
        if (tab == AnimeTab.RANK) viewModel.ensureLatestLoaded()
    }

    Box(Modifier.fillMaxSize()) {
        LiquidGlassBackdrop()
        AnimeScaffoldContent(
            viewModel = viewModel,
            listState = listState,
            homeFeed = homeFeed,
            latestFeed = latestFeed,
            favorites = favorites,
            searchHistory = searchHistory,
            progress = progress,
            queryInput = queryInput,
            onQueryChange = { queryInput = it },
            tab = tab,
            onTabChange = { tab = it },
            onShowSettings = { showSettings = true },
            onBack = onBack,
            onOpenDetail = onOpenDetail,
        )
    }

    if (showSettings) {
        AnimeSettingsDialog(
            viewModel = viewModel,
            favoriteCount = favorites.size,
            onManageSources = {
                showSettings = false
                onManageSources()
            },
            onDismiss = { showSettings = false },
        )
    }
}

/** 顶栏 + 页签内容 + 悬浮底栏；Scaffold 全透明，底下垫着液态玻璃背景。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnimeScaffoldContent(
    viewModel: AnimeViewModel,
    listState: AnimeListState,
    homeFeed: AnimeFeedState,
    latestFeed: AnimeFeedState,
    favorites: List<AnimeFavorite>,
    searchHistory: List<String>,
    progress: Map<String, AnimeProgress>,
    queryInput: String,
    onQueryChange: (String) -> Unit,
    tab: AnimeTab,
    onTabChange: (AnimeTab) -> Unit,
    onShowSettings: () -> Unit,
    onBack: () -> Unit,
    onOpenDetail: (id: String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = { Text("影视中心") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { onTabChange(AnimeTab.FILTER) }) {
                        Icon(Icons.Default.Search, contentDescription = "搜索")
                    }
                    IconButton(onClick = { onTabChange(AnimeTab.FAVORITES) }) {
                        Icon(Icons.Default.History, contentDescription = "观看历史")
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("影视设置") },
                                onClick = {
                                    showMenu = false
                                    onShowSettings()
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 内容区和底栏之间留出悬浮导航的位置
            Box(Modifier.fillMaxSize().padding(bottom = 92.dp)) {
                when (tab) {
                    AnimeTab.HOME -> HomeTab(
                        feed = homeFeed,
                        favorites = favorites,
                        progress = progress,
                        onOpenDetail = onOpenDetail,
                        onLoadMore = { viewModel.loadMoreHome() },
                    )

                    AnimeTab.FILTER -> FilterTab(
                        viewModel = viewModel,
                        listState = listState,
                        searchHistory = searchHistory,
                        queryInput = queryInput,
                        onQueryChange = onQueryChange,
                        keyboard = keyboard,
                        onOpenDetail = onOpenDetail,
                    )

                    AnimeTab.FAVORITES -> FavoritesGrid(
                        favorites = favorites,
                        progress = progress,
                        onOpenDetail = onOpenDetail,
                    )

                    AnimeTab.RANK -> RankTab(
                        feed = latestFeed,
                        onOpenDetail = onOpenDetail,
                        onLoadMore = { viewModel.loadMoreLatest() },
                    )
                }
            }

            FloatingGlassNavBar(
                selected = tab,
                onSelect = onTabChange,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * 首页「推荐」：轮播 banner、合规提示条、观看历史横滑、推荐网格一个 LazyVerticalGrid 串到底，
 * 翻页逻辑跟筛选页一致（触底前 6 项预取）。
 */
@Composable
private fun HomeTab(
    feed: AnimeFeedState,
    favorites: List<AnimeFavorite>,
    progress: Map<String, AnimeProgress>,
    onOpenDetail: (id: String) -> Unit,
    onLoadMore: () -> Unit,
) {
    val gridState = rememberLazyGridState()

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                val s = feed
                val total = gridState.layoutInfo.totalItemsCount
                if (s.items.isNotEmpty() && !s.loading && s.hasMore && last >= total - 6) {
                    onLoadMore()
                }
            }
    }

    // 看到一半的收藏按最近观看排序，首页「观看历史」横滑展示
    val continueWatching = favorites
        .mapNotNull { fav ->
            progress[AnimeViewModel.progressKey(fav.source, fav.id)]?.let { fav to it }
        }
        .sortedByDescending { it.second.updatedAt }
        .take(12)

    var noticeVisible by rememberSaveable { mutableStateOf(true) }

    when {
        feed.items.isEmpty() && feed.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        feed.items.isEmpty() && feed.error != null -> ErrorHint(
            message = feed.error,
            onRetry = onLoadMore,
        )
        feed.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "暂时没有内容，换个资源站试试",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "banner", span = { GridItemSpan(3) }) {
                BannerPager(items = feed.items.take(5), onOpenDetail = onOpenDetail)
            }
            if (noticeVisible) {
                item(key = "notice", span = { GridItemSpan(3) }) {
                    NoticeBar(onClose = { noticeVisible = false })
                }
            }
            if (continueWatching.isNotEmpty()) {
                item(key = "history_title", span = { GridItemSpan(3) }) {
                    SectionTitle("观看历史")
                }
                item(key = "history_row", span = { GridItemSpan(3) }) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(continueWatching, key = { it.first.source + it.first.id }) { (fav, prog) ->
                            ContinueCard(
                                name = fav.name,
                                pic = fav.pic,
                                progressLabel = prog.episodeLabel,
                                onClick = { onOpenDetail(fav.id) },
                            )
                        }
                    }
                }
            }
            item(key = "recommend_title", span = { GridItemSpan(3) }) {
                SectionTitle("推荐")
            }
            items(feed.items, key = { it.source + it.id }) { item ->
                PosterCard(
                    name = item.name,
                    pic = item.pic,
                    remark = item.remark.takeIf { it.isNotBlank() },
                    onClick = { onOpenDetail(item.id) },
                )
            }
        }
    }

    if (feed.loading && feed.items.isNotEmpty()) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/** 「排行」= 不限分类的最新更新（接口没有真排行，见 AnimeViewModel.fetchLatest）。 */
@Composable
private fun RankTab(
    feed: AnimeFeedState,
    onOpenDetail: (id: String) -> Unit,
    onLoadMore: () -> Unit,
) {
    val gridState = rememberLazyGridState()

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                val s = feed
                val total = gridState.layoutInfo.totalItemsCount
                if (s.items.isNotEmpty() && !s.loading && s.hasMore && last >= total - 6) {
                    onLoadMore()
                }
            }
    }

    when {
        feed.items.isEmpty() && feed.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        feed.items.isEmpty() && feed.error != null -> ErrorHint(message = feed.error, onRetry = onLoadMore)
        feed.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "暂时没有内容",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "rank_title", span = { GridItemSpan(3) }) {
                SectionTitle("排行 · 最近更新")
            }
            items(feed.items, key = { it.source + it.id }) { item ->
                PosterCard(
                    name = item.name,
                    pic = item.pic,
                    remark = item.remark.takeIf { it.isNotBlank() },
                    onClick = { onOpenDetail(item.id) },
                )
            }
        }
    }

    if (feed.loading && feed.items.isNotEmpty()) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/** 筛选页签：搜索框 + 搜索历史 + 分类 chips + 网格，跟旧版影视页一致。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterTab(
    viewModel: AnimeViewModel,
    listState: AnimeListState,
    searchHistory: List<String>,
    queryInput: String,
    onQueryChange: (String) -> Unit,
    keyboard: androidx.compose.ui.platform.SoftwareKeyboardController?,
    onOpenDetail: (id: String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = queryInput,
            onValueChange = onQueryChange,
            placeholder = { Text("搜番剧名，比如「咒术回战」", style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (queryInput.isNotEmpty()) {
                    IconButton(onClick = {
                        onQueryChange("")
                        if (listState.mode == AnimeListState.Mode.SEARCH) viewModel.backToCategory()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "清空")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Search,
            ),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                viewModel.search(queryInput)
            }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
        )

        // 搜索框为空、又不在搜索结果里时，露出搜索历史：点一下直接搜
        if (queryInput.isEmpty() && listState.mode != AnimeListState.Mode.SEARCH && searchHistory.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "搜索历史",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.clearSearchHistory() }) { Text("清空", fontSize = 12.sp) }
            }
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                searchHistory.forEach { keyword ->
                    SuggestionChip(
                        onClick = {
                            onQueryChange(keyword)
                            keyboard?.hide()
                            viewModel.search(keyword)
                        },
                        label = {
                            Text(keyword, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = listState.mode == AnimeListState.Mode.FAVORITES,
                onClick = { viewModel.showFavorites() },
                label = { Text("收藏", fontSize = 13.sp) },
            )
            viewModel.categories.forEach { (label, id) ->
                FilterChip(
                    selected = listState.mode == AnimeListState.Mode.CATEGORY && listState.categoryId == id,
                    onClick = { viewModel.selectCategory(id) },
                    label = { Text(label, fontSize = 13.sp) },
                )
            }
        }

        BrowseGrid(
            listState = listState,
            viewModel = viewModel,
            onOpenDetail = onOpenDetail,
        )
    }
}

/** 悬浮液态玻璃底栏：半透明渐变 + 顶部高光 + 细白边，选中项带主色药丸底。 */
@Composable
private fun FloatingGlassNavBar(
    selected: AnimeTab,
    onSelect: (AnimeTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp)
                .shadow(
                    elevation = 16.dp,
                    shape = shape,
                    ambientColor = Color.Black.copy(alpha = 0.25f),
                    spotColor = Color.Black.copy(alpha = 0.35f),
                )
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.58f),
                        ),
                    ),
                )
                .border(1.dp, Color.White.copy(alpha = 0.30f), shape),
        ) {
            // 顶上一道玻璃高光
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.16f),
                                Color.White.copy(alpha = 0.04f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
            Row(Modifier.matchParentSize().padding(horizontal = 6.dp)) {
                AnimeTab.entries.forEach { item ->
                    GlassNavItem(
                        tab = item,
                        selected = item == selected,
                        onClick = { onSelect(item) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassNavItem(
    tab: AnimeTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) active.copy(alpha = 0.26f) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                tab.icon(),
                contentDescription = tab.label,
                tint = if (selected) active else inactive,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = tab.label,
            fontSize = 11.sp,
            color = if (selected) active else inactive,
            maxLines = 1,
        )
    }
}

/** 首页轮播：推荐 feed 的前几部，4.5 秒自动翻页，点封面进详情。 */
@Composable
private fun BannerPager(items: List<AnimeItem>, onOpenDetail: (id: String) -> Unit) {
    if (items.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { items.size })

    if (items.size > 1) {
        LaunchedEffect(pagerState) {
            while (true) {
                delay(4500)
                val next = (pagerState.currentPage + 1) % items.size
                runCatching { pagerState.animateScrollToPage(next) }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
            .clip(RoundedCornerShape(16.dp)),
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val item = items[page]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onOpenDetail(item.id) },
            ) {
                if (item.pic.isNotEmpty()) {
                    AsyncImage(
                        model = item.pic,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .align(Alignment.BottomStart)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)),
                            ),
                        ),
                )
                Text(
                    text = item.name,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }
        // 页码小圆点
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            items.forEachIndexed { index, _ ->
                Box(
                    Modifier
                        .size(if (index == pagerState.currentPage) 7.dp else 5.dp)
                        .clip(CircleShape)
                        .background(
                            if (index == pagerState.currentPage) Color.White
                            else Color.White.copy(alpha = 0.45f),
                        ),
                )
            }
        }
    }
}

/** 合规提示条：可关闭。 */
@Composable
private fun NoticeBar(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "请仅观看公有领域或已获授权的内容，上传者提供的许可信息需自行核验",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "关闭提示",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/** 影视设置：源管理入口 + 收藏数据管理（右上角更多菜单打开）。 */
@Composable
private fun AnimeSettingsDialog(
    viewModel: AnimeViewModel,
    favoriteCount: Int,
    onManageSources: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmClearFavorites by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("影视设置") },
        text = {
            Column {
                val sourceKey by viewModel.sourceKey.collectAsStateWithLifecycle()
                val sources by viewModel.sources.collectAsStateWithLifecycle()
                val current = sources.firstOrNull { it.key == sourceKey }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onManageSources)
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("影视源管理", fontSize = 14.sp)
                        Text(
                            text = "当前 " + (current?.name ?: "无可用源") +
                                " · 共 ${sources.size} 个可用源",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "公有领域目录仅收录 API 标注为 Public Domain Mark / CC0 的项目；上传者提供的许可信息仍需自行核验。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("影视收藏", fontSize = 14.sp)
                        Text(
                            text = "本地保存的收藏与观看进度，" +
                                if (favoriteCount > 0) "当前 $favoriteCount 部" else "当前暂无",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (favoriteCount > 0) {
                        TextButton(onClick = { confirmClearFavorites = true }) { Text("清空") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )

    if (confirmClearFavorites) {
        AlertDialog(
            onDismissRequest = { confirmClearFavorites = false },
            title = { Text("清空收藏？") },
            text = { Text("会删掉全部收藏和观看进度，不影响视频缓存。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearFavorites()
                    confirmClearFavorites = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearFavorites = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun BrowseGrid(
    listState: AnimeListState,
    viewModel: AnimeViewModel,
    onOpenDetail: (id: String) -> Unit,
) {
    val gridState = rememberLazyGridState()

    // 滑到底部附近自动翻下一页；从 StateFlow 里现读状态，避免闭包拿到旧值
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                val s = viewModel.list.value
                if (s.items.isNotEmpty() && !s.loading && s.hasMore && last >= s.items.size - 6) {
                    viewModel.loadMore()
                }
            }
    }

    when {
        listState.items.isEmpty() && listState.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        listState.items.isEmpty() && listState.error != null -> ErrorHint(
            message = listState.error,
            onRetry = { viewModel.loadMore() },
        )
        listState.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("没有找到相关影片", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(listState.items, key = { it.source + it.id }) { item ->
                PosterCard(
                    name = item.name,
                    pic = item.pic,
                    remark = item.remark.takeIf { it.isNotBlank() },
                    onClick = { onOpenDetail(item.id) },
                )
            }
        }
    }

    if (listState.loading && listState.items.isNotEmpty()) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun FavoritesGrid(
    favorites: List<AnimeFavorite>,
    progress: Map<String, AnimeProgress>,
    onOpenDetail: (id: String) -> Unit,
) {
    if (favorites.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "还没有收藏。搜索一部影片，进详情页点「收藏」",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    // 继续观看：看到一半的收藏按最近观看排序，点一下直接回到详情页（会话记得看到哪集）
    val continueWatching = favorites
        .mapNotNull { fav ->
            progress[AnimeViewModel.progressKey(fav.source, fav.id)]?.let { fav to it }
        }
        .sortedByDescending { it.second.updatedAt }
        .take(12)

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (continueWatching.isNotEmpty()) {
            item(key = "continue_title", span = { GridItemSpan(3) }) {
                Text(
                    "继续观看",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            item(key = "continue_row", span = { GridItemSpan(3) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(continueWatching, key = { it.first.source + it.first.id }) { (fav, prog) ->
                        ContinueCard(
                            name = fav.name,
                            pic = fav.pic,
                            progressLabel = prog.episodeLabel,
                            onClick = { onOpenDetail(fav.id) },
                        )
                    }
                }
            }
        }
        items(favorites, key = { it.source + it.id }) { fav ->
            // 有观看进度时把「看到第几集」并进角标，跟更新信息一起显示
            val seen = progress[AnimeViewModel.progressKey(fav.source, fav.id)]
                ?.episodeLabel?.takeIf { it.isNotBlank() }
            PosterCard(
                name = fav.name,
                pic = fav.pic,
                remark = listOfNotNull(fav.remark.takeIf { it.isNotBlank() }, seen?.let { "看到 $it" })
                    .joinToString(" · ").takeIf { it.isNotBlank() },
                onClick = { onOpenDetail(fav.id) },
            )
        }
    }
}

/** 继续观看的小卡片：固定宽度的封面 + 片名 + 看到哪集。 */
@Composable
private fun ContinueCard(name: String, pic: String, progressLabel: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(106.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (pic.isNotEmpty()) {
                AsyncImage(
                    model = pic,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (progressLabel.isNotBlank()) {
                Text(
                    text = "看到 $progressLabel",
                    fontSize = 10.sp,
                    maxLines = 1,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = name,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PosterCard(name: String, pic: String, remark: String?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (pic.isNotEmpty()) {
                AsyncImage(
                    model = pic,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (!remark.isNullOrEmpty()) {
                Text(
                    text = remark,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ErrorHint(message: String?, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("加载失败：${message ?: "未知错误"}", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onRetry) { Text("重试") }
    }
}
