package com.qzkt.timetable.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.imageLoader
import com.qzkt.timetable.data.AppSettings
import com.qzkt.timetable.data.anime.MacCmsSource
import com.qzkt.timetable.data.update.UpdateChecker
import com.qzkt.timetable.ui.book.BookViewModel
import com.qzkt.timetable.ui.common.SectionCard
import com.qzkt.timetable.ui.player.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    animeFavoriteCount: Int = 0,
    onClearAnimeFavorites: () -> Unit = {},
    videoSources: List<MacCmsSource> = emptyList(),
    videoSourceKey: String = "",
    onSelectVideoSource: (String) -> Unit = {},
    bookViewModel: BookViewModel? = null,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("设置") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 学期、作息时间表、课表同步、上课提醒、数据搬去了课表页顶栏的「课表工具」
            AppearanceSection(settings = settings, onUpdate = onUpdate)
            if (videoSources.isNotEmpty()) {
                VideoSourceCard(
                    sources = videoSources,
                    selectedKey = videoSourceKey,
                    onSelect = onSelectVideoSource,
                )
            }
            bookViewModel?.let { vm -> BookSettingsCard(viewModel = vm, animeFavoriteCount = animeFavoriteCount) }
            StorageCard(
                animeFavoriteCount = animeFavoriteCount,
                onClearAnimeFavorites = onClearAnimeFavorites,
            )
            AboutCard()
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 影视源：选择影视页从哪个资源站取数据，各源收录和速度不同。 */
@Composable
private fun VideoSourceCard(sources: List<MacCmsSource>, selectedKey: String, onSelect: (String) -> Unit) {
    SectionCard("影视源") {
        sources.forEach { source ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(source.key) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = source.key == selectedKey,
                    onClick = { onSelect(source.key) },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(source.name, fontSize = 14.sp)
                    Text(
                        text = source.baseUrl
                            .removePrefix("https://").removePrefix("http://")
                            .substringBefore('/') +
                            " · " + source.categories.joinToString("／") { it.first },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "某个源打不开或内容不全时换个试试",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppearanceSection(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit) {
    SectionCard("外观") {
        Text("主题", fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("SYSTEM" to "跟随系统", "LIGHT" to "浅色", "DARK" to "深色").forEach { (value, label) ->
                FilterChip(
                    selected = settings.themeMode == value,
                    onClick = { onUpdate { it.copy(themeMode = value) } },
                    label = { Text(label, fontSize = 12.sp) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        SwitchRow(
            title = "动态取色",
            subtitle = "用系统壁纸配色（Android 12+）",
            checked = settings.dynamicColor,
            onCheckedChange = { on -> onUpdate { it.copy(dynamicColor = on) } },
        )

        Spacer(Modifier.height(8.dp))
        SwitchRow(
            title = "显示非本周课程",
            subtitle = "把单双周不上课的课灰色显示出来",
            checked = settings.showOtherWeeks,
            onCheckedChange = { on -> onUpdate { it.copy(showOtherWeeks = on) } },
        )
    }
}

/** 存储管理：播放缓存、图片缓存、追番数据，各自显示占用并可以清理。 */
@Composable
private fun StorageCard(animeFavoriteCount: Int, onClearAnimeFavorites: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var playbackSize by remember { mutableStateOf<Long?>(null) }
    var imageSize by remember { mutableStateOf<Long?>(null) }
    var confirmClearFavorites by remember { mutableStateOf(false) }

    fun refreshSizes() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val playback = dirSize(File(context.cacheDir, "media_cache"))
                val image = runCatching {
                    context.imageLoader.diskCache?.directory?.let { dirSize(File(it.toString())) }
                }.getOrNull() ?: 0L
                playback to image
            }
            playbackSize = result.first
            imageSize = result.second
        }
    }
    LaunchedEffect(Unit) { refreshSizes() }

    SectionCard("存储") {
        StorageRow(
            title = "播放缓存",
            desc = "看过的视频分片，上限 256MB",
            size = formatBytes(playbackSize),
            action = "清理",
            onAction = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val cache = PlaybackService.playbackCache(context)
                        cache.keys.forEach { key -> cache.removeResource(key) }
                    }
                    refreshSizes()
                }
            },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        StorageRow(
            title = "图片缓存",
            desc = "番剧封面等图片",
            size = formatBytes(imageSize),
            action = "清理",
            onAction = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            context.imageLoader.diskCache?.clear()
                            context.imageLoader.memoryCache?.clear()
                        }
                    }
                    refreshSizes()
                }
            },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        StorageRow(
            title = "影视收藏",
            desc = "本地保存的收藏与观看进度",
            size = if (animeFavoriteCount > 0) "$animeFavoriteCount 部" else "暂无",
            action = if (animeFavoriteCount > 0) "清空" else "",
            onAction = { confirmClearFavorites = true },
        )
    }

    if (confirmClearFavorites) {
        AlertDialog(
            onDismissRequest = { confirmClearFavorites = false },
            title = { Text("清空收藏？") },
            text = { Text("会删掉全部收藏和观看进度，不影响视频缓存。") },
            confirmButton = {
                TextButton(onClick = {
                    onClearAnimeFavorites()
                    confirmClearFavorites = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearFavorites = false }) { Text("取消") }
            },
        )
    }
}

/** 图书设置：在线书源切换 + 阅读偏好（字号 / 夜间）+ 图书数据管理。 */
@Composable
private fun BookSettingsCard(viewModel: BookViewModel, animeFavoriteCount: Int) {
    val scope = rememberCoroutineScope()
    val sourceName = viewModel.onlineSource.name
    val fontSize by viewModel.fontSize.collectAsStateWithLifecycle()
    val night by viewModel.night.collectAsStateWithLifecycle()
    val selectedSourceKey by viewModel.sourceKey.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    var confirmClearBooks by remember { mutableStateOf(false) }

    SectionCard("图书") {
        // 在线书源：单选切换（某个源连不上就换一个）
        Text("在线书源", fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        viewModel.availableSources.forEach { source ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setOnlineSource(source.key) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = source.key == selectedSourceKey,
                    onClick = { viewModel.setOnlineSource(source.key) },
                )
                Text(
                    text = source.name,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        // 阅读偏好
        Text("阅读偏好", fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("正文字号", fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { viewModel.setFontSize(fontSize - 1) }, enabled = fontSize > 14) {
                Text("A-")
            }
            Text(fontSize.toString() + " sp", fontSize = 13.sp)
            TextButton(onClick = { viewModel.setFontSize(fontSize + 1) }, enabled = fontSize < 30) {
                Text("A+")
            }
        }
        SwitchRow(
            title = "夜间模式",
            subtitle = "阅读页用深色底（阅读页内也能切）",
            checked = night,
            onCheckedChange = { viewModel.setNight(it) },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        // 图书数据
        Text("图书数据", fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (books.isEmpty()) "书架是空的" else "书架 ${books.size} 本" +
                        if (animeFavoriteCount > 0) " · 收藏进度已含在书架里" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "本地 TXT 文件不计入应用占用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = { confirmClearBooks = true },
                enabled = books.isNotEmpty(),
            ) { Text("清空书架") }
        }
    }

    if (confirmClearBooks) {
        AlertDialog(
            onDismissRequest = { confirmClearBooks = false },
            title = { Text("清空书架？") },
            text = { Text("会移除全部书籍（本地 TXT 和在线书）与阅读进度，不影响阅读偏好设置。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllBooks()
                    confirmClearBooks = false
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClearBooks = false }) { Text("取消") } },
        )
    }
}

/** 检查更新在界面上的几种状态。 */private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val currentVersion: String) : UpdateUiState
    data class Available(val release: UpdateChecker.Release) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

/** 关于：当前版本 + 检查更新（读 GitHub Releases，新版可跳浏览器下载）。 */
@Composable
private fun AboutCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    var state by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }

    SectionCard("关于") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("当前版本", fontSize = 14.sp)
                Text(
                    text = "Tools v$currentVersion",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (state) {
                is UpdateUiState.Checking -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                else -> TextButton(onClick = {
                    scope.launch {
                        state = UpdateUiState.Checking
                        state = UpdateChecker.check(currentVersion).fold(
                            onSuccess = { result ->
                                when (result) {
                                    is UpdateChecker.CheckResult.UpToDate -> UpdateUiState.UpToDate(result.currentVersion)
                                    is UpdateChecker.CheckResult.NewVersion -> UpdateUiState.Available(result.release)
                                }
                            },
                            onFailure = { UpdateUiState.Failed(it.message ?: "未知错误") },
                        )
                    }
                }) { Text("检查更新") }
            }
        }

        when (val s = state) {
            is UpdateUiState.UpToDate -> Text(
                text = "已是最新版本（v${s.currentVersion}）",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            is UpdateUiState.Failed -> Text(
                text = "检查失败：${s.message}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error,
            )
            is UpdateUiState.Available -> {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "发现新版本 v${s.release.version}" +
                        if (s.release.title.isNotBlank() && s.release.title != "v${s.release.version}") {
                            "（${s.release.title}）"
                        } else {
                            ""
                        },
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (s.release.notes.isNotBlank()) {
                    var expanded by remember { mutableStateOf(false) }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = s.release.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 4,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { expanded = !expanded },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val url = s.release.apkUrl ?: s.release.pageUrl
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (s.release.apkUrl != null) "下载更新" else "打开发布页")
                }
            }
            UpdateUiState.Idle -> Unit
            UpdateUiState.Checking -> Unit
        }
    }
}

@Composable
private fun StorageRow(title: String, desc: String, size: String, action: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(
                text = "$desc，当前 $size",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (action.isNotEmpty()) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

private fun dirSize(dir: File): Long =
    runCatching { dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)

private fun formatBytes(bytes: Long?): String = when {
    bytes == null -> "统计中…"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
