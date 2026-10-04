package com.qzkt.timetable.ui.anime

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.qzkt.timetable.data.anime.AnimeDetail
import com.qzkt.timetable.data.anime.AnimeFavorite

/**
 * 番剧详情：封面 + 简介 + 收藏 + 剧集列表。
 *
 * 点剧集 = 记进度 + 跳播放器页；看过的那几集变淡，正在看的那集高亮。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AnimeDetailScreen(
    vodId: String,
    viewModel: AnimeViewModel,
    onBack: () -> Unit,
    onPlay: () -> Unit,
) {
    val detailState by viewModel.detail.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val progressMap by viewModel.progress.collectAsStateWithLifecycle()

    LaunchedEffect(vodId) { viewModel.openDetail(vodId) }

    val detail = detailState.detail

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = detail?.name ?: "影视详情",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        when {
            detailState.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            detailState.error != null && detail == null -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("加载失败：${detailState.error}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { viewModel.openDetail(vodId) }) { Text("重试") }
            }
            detail != null -> DetailContent(
                detail = detail,
                isFavorite = favorites.any { it.source == detail.source && it.id == detail.id },
                progress = progressMap[AnimeViewModel.progressKey(detail.source, detail.id)],
                onToggleFavorite = {
                    viewModel.toggleFavorite(
                        AnimeFavorite(
                            source = detail.source,
                            id = detail.id,
                            name = detail.name,
                            pic = detail.pic,
                            remark = detail.remark,
                            addedAt = System.currentTimeMillis(),
                        ),
                    )
                },
                onPlayEpisode = { index ->
                    viewModel.requestPlay(detail, index)
                    onPlay()
                },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailContent(
    detail: AnimeDetail,
    isFavorite: Boolean,
    progress: com.qzkt.timetable.data.anime.AnimeProgress?,
    onToggleFavorite: () -> Unit,
    onPlayEpisode: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(detail.content) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row {
            Box(
                modifier = Modifier
                    .width(110.dp)
                    .aspectRatio(3f / 4f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (detail.pic.isNotEmpty()) {
                    AsyncImage(
                        model = detail.pic,
                        contentDescription = detail.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = detail.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                val meta = listOfNotNull(
                    detail.year.takeIf { it.isNotBlank() },
                    detail.area.takeIf { it.isNotBlank() },
                    detail.remark.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (detail.genre.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = detail.genre,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (isFavorite) "取消收藏" else "收藏",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = if (isFavorite) "已收藏" else "收藏",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                progress?.let {
                    Text(
                        text = "看到 ${it.episodeLabel}" + if (it.episodeCount > 0) "（共 ${it.episodeCount} 集）" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        if (detail.content.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = detail.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { expanded = !expanded },
            )
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(vertical = 0.dp)) {
                Text(if (expanded) "收起" else "展开")
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = if (detail.episodes.isEmpty()) "播放列表" else "播放列表（共 ${detail.episodes.size} 集）",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(8.dp))

        if (detail.episodes.isEmpty()) {
            Text(
                text = "这个源没有可播的分集",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                detail.episodes.forEachIndexed { index, episode ->
                    val watched = progress != null && index < progress.episodeIndex
                    val current = progress?.episodeIndex == index
                    FilterChip(
                        selected = current,
                        onClick = { onPlayEpisode(index) },
                        label = { Text(episode.label, fontSize = 13.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.alpha(if (watched) 0.55f else 1f),
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
