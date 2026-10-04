package com.qzkt.timetable.ui.anime

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.qzkt.timetable.data.anime.AnimeItem

/**
 * 番剧页：搜索 + 分类浏览 + 追番。
 *
 * 结构参考 AniCh 的浏览 → 搜索 → 详情 → 选集流程，海报三列网格，
 * 滑到底部自动翻页。选封面进详情，选集后跳播放器页。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AnimeScreen(
    viewModel: AnimeViewModel,
    onBack: () -> Unit,
    onOpenDetail: (id: String) -> Unit,
) {
    val listState by viewModel.list.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    var queryInput by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("影视") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = queryInput,
                onValueChange = { queryInput = it },
                placeholder = { Text("搜番剧名，比如「咒术回战」", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (queryInput.isNotEmpty()) {
                        IconButton(onClick = {
                            queryInput = ""
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
                                queryInput = keyword
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

            when (listState.mode) {
                AnimeListState.Mode.FAVORITES -> FavoritesGrid(
                    favorites = favorites,
                    onOpenDetail = onOpenDetail,
                )
                else -> BrowseGrid(
                    listState = listState,
                    viewModel = viewModel,
                    onOpenDetail = onOpenDetail,
                )
            }
        }
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
    favorites: List<com.qzkt.timetable.data.anime.AnimeFavorite>,
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
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(favorites, key = { it.source + it.id }) { fav ->
            PosterCard(
                name = fav.name,
                pic = fav.pic,
                remark = fav.remark.takeIf { it.isNotBlank() },
                onClick = { onOpenDetail(fav.id) },
            )
        }
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
