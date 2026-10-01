package com.qzkt.timetable.ui.player

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.LayoutInflater
import android.widget.ImageButton
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.qzkt.timetable.R
import com.qzkt.timetable.data.anime.AnimePlayRequest
import com.qzkt.timetable.ui.player.link.MediaDownloader
import com.qzkt.timetable.ui.player.link.canDownloadDirectly
import com.qzkt.timetable.ui.player.link.downloadableFileName
import com.qzkt.timetable.ui.player.link.readableSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 播放器页面（不在工具页显示，由「影视」选集和「链接解析」跳进来）。
 *
 * 解码用 Android 自带的媒体框架（Media3/ExoPlayer），走系统解码器，常见格式都能放。
 *
 * **播放器不在这个页面里**，而在 [PlaybackService] 里；这里通过 [MediaController] 连上去控制它。
 * 这样切后台 / 锁屏 / 页面被回收都还能继续放，通知栏和蓝牙耳机也能控制。
 *
 * 需要防盗链的站可以在解析页的「请求头」里补 Referer / User-Agent，见 [StreamHeaders]。
 *
 * 正在播的那一路也能**下载到本地**（Android 10+ 落在 `Movies/qzkt/`，见 [VideoDownloadStore]），
 * 用的是和播放完全相同的请求头 —— 少了 Referer/UA，CDN 会给 403。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    /** 是否全屏。状态由外层持有，这样全屏时外层能把底部页签一起收起来。 */
    fullscreen: Boolean = false,
    onFullscreenChange: (Boolean) -> Unit = {},
    /** 番剧页交过来的播放请求；组合时消费一次。 */
    playRequest: AnimePlayRequest? = null,
    onPlayRequestConsumed: () -> Unit = {},
    /** 番剧播放会话（含整部番剧集列表），放在 AnimeViewModel 里跨页面存活 ——
     *  退出播放器再从工具页进来，「选集」还是这部番。 */
    animeSession: AnimePlayRequest? = null,
    /** 播放器里选集换集后：更新会话并记进度。 */
    onEpisodeSwitched: (AnimePlayRequest) -> Unit = {},
) {
    val context = LocalContext.current

    var controller by remember { mutableStateOf<MediaController?>(null) }
    // 待播地址必须扛得住 Activity 重建（进全屏会旋转屏幕 → 重建）
    var pendingUriText by rememberSaveable { mutableStateOf<String?>(null) }
    // 正在播的那一路。标题和歌词都从它派生，所以它自己也必须能扛重建 ——
    // 否则进一次全屏回来，标题就空了、歌词也没了（下面两个 val 就是干这个的）。
    var currentSource by rememberSaveable { mutableStateOf<String?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var lyricIndex by remember { mutableStateOf(-1) }
    var positionMs by remember { mutableStateOf(0L) }
    var repeatAll by remember { mutableStateOf(false) }
    var repeatOne by remember { mutableStateOf(false) }
    var shuffle by remember { mutableStateOf(false) }
    // 解析出来的标题（B站/抖音的地址是 CDN 直链，光看地址看不出是什么视频）
    var resolvedTitle by rememberSaveable { mutableStateOf<String?>(null) }
    // 下载：进度、结果提示，以及下完之后那条本地地址（可以直接接着播）
    val downloader = remember { MediaDownloader() }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    var downloadStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val activity = remember(context) { context.findActivity() }

    var showEpisodes by rememberSaveable { mutableStateOf(false) }
    var showQuality by rememberSaveable { mutableStateOf(false) }
    // 快捷手势的提示气泡（横滑快进/快退、亮度、音量拖动时显示）
    var gestureHint by remember { mutableStateOf<String?>(null) }

    val currentName = remember(context, currentSource, resolvedTitle) {
        resolvedTitle ?: currentSource?.let { nameOf(context, it) }
    }
    val lyrics = remember(currentSource) {
        currentSource?.let { loadLyricsFor(Uri.parse(it)) } ?: emptyList()
    }

    // 全屏时先把返回键吃掉：这时按返回是"退出全屏"，不是离开播放器
    BackHandler(enabled = fullscreen) { onFullscreenChange(false) }

    // 全屏 = 横屏 + 隐藏状态栏/导航栏；退出时原样还回去
    LaunchedEffect(fullscreen, activity) {
        activity?.requestedOrientation =
            if (fullscreen) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        val view = activity?.window?.decorView ?: return@LaunchedEffect
        val bars = WindowCompat.getInsetsController(activity.window, view)
        if (fullscreen) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 离开播放器页时保证横屏/隐藏状态栏都被还原，别把整个应用卡在横屏
    DisposableEffect(activity) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.decorView?.let {
                WindowCompat.getInsetsController(activity.window, it).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // 连到后台服务里的播放会话
    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            { controller = runCatching { future.get() }.getOrNull() },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            controller = null
            MediaController.releaseFuture(future)
        }
    }

    // 番剧页选集跳过来的：直接当流媒体播，标题用「番名 · 集名」
    LaunchedEffect(playRequest) {
        val request = playRequest ?: return@LaunchedEffect
        StreamHeaders.clear()
        // 解析页过来的普通流地址不带标题（title 为空），回退用地址末段当标题
        resolvedTitle = request.title.ifBlank { null }
        currentSource = request.url
        lastError = null
        pendingUriText = request.url
        onPlayRequestConsumed()
    }

    // 有控制器 + 有待播地址时才真正开始放
    LaunchedEffect(controller, pendingUriText) {
        val media = pendingUriText ?: return@LaunchedEffect
        val c = controller ?: return@LaunchedEffect
        c.setMediaItem(MediaItem.fromUri(media))
        c.prepare()
        c.playWhenReady = true
        pendingUriText = null   // 消费掉，重建时不会再放一遍
    }

    LaunchedEffect(controller, repeatAll, repeatOne) {
        controller?.repeatMode = when {
            repeatOne -> Player.REPEAT_MODE_ONE
            repeatAll -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
    }
    LaunchedEffect(controller, shuffle) { controller?.shuffleModeEnabled = shuffle }

    // 推进歌词（Media3 没有逐帧回调，轮询取位置）。
    // 没歌词（看视频/番剧）时不碰任何状态 —— 全页每 200ms 重组一遍纯属浪费，
    // 也是播放时页面卡顿的来源之一。
    LaunchedEffect(controller, lyrics) {
        while (true) {
            if (lyrics.isNotEmpty()) {
                controller?.let {
                    positionMs = it.currentPosition
                    lyricIndex = LrcParser.lineAt(lyrics, positionMs)
                }
                delay(200)
            } else {
                delay(1_000)
            }
        }
    }

    DisposableEffect(controller) {
        val c = controller ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                lastError = error.message ?: error.errorCodeName
            }
        }
        c.addListener(listener)
        onDispose { c.removeListener(listener) }
    }

    // 播放器里直接换集：本地切源 + 更新会话/记进度，不用退出播放器回详情页
    fun playEpisode(index: Int) {
        val session = animeSession ?: return
        val episode = session.episodes.getOrNull(index) ?: return
        val updated = session.copy(
            url = episode.url,
            title = "${session.name} · ${episode.label}",
            currentIndex = index,
        )
        onEpisodeSwitched(updated)
        StreamHeaders.clear()
        resolvedTitle = updated.title
        currentSource = updated.url
        lastError = null
        pendingUriText = updated.url
        showEpisodes = false
    }

    // 两个画面（小窗 / 全屏）共用同一套自定义控制条；控制条里的「清晰度 / 选集」
    // 是常驻按钮，点开对应面板。全屏画面里不出现进全屏的按钮（退出用右上角那个）。
    fun PlayerView.wireControls(fullscreenButton: Boolean) {
        useController = true
        // 重缓冲/重新准备时保留最后一帧，别闪黑屏
        setKeepContentOnPlayerReset(true)
        findViewById<ImageButton>(R.id.player_quality)?.setOnClickListener { showQuality = true }
        findViewById<ImageButton>(R.id.player_episodes)?.setOnClickListener { showEpisodes = true }
        if (fullscreenButton) setFullscreenButtonClickListener { onFullscreenChange(true) }
    }

    // 两个面板必须放在全屏分支之前 —— 全屏分支是提前 return 的，
    // 放在后面的话全屏时永远走不到，按钮就「调不出来」了。

    if (fullscreen) {
        Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.view_player_video, null, false) as PlayerView)
                        .apply {
                            wireControls(fullscreenButton = false)
                            PlayerGestures(this, activity, { controller }, { gestureHint = it }).attach()
                        }
                },
                update = { view -> view.player = controller },
            )
            if (showEpisodes) {
                EpisodesOverlay(
                    session = animeSession,
                    modifier = Modifier.matchParentSize(),
                    onDismiss = { showEpisodes = false },
                    onSelect = { playEpisode(it) },
                )
            }
            if (showQuality) {
                QualityOverlay(
                    controller = controller,
                    modifier = Modifier.matchParentSize(),
                    onDismiss = { showQuality = false },
                )
            }
            gestureHint?.let { hint -> GestureHintOverlay(hint, Modifier.matchParentSize()) }
            IconButton(
                onClick = { onFullscreenChange(false) },
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            ) {
                Icon(
                    Icons.Default.FullscreenExit,
                    contentDescription = "退出全屏",
                    tint = androidx.compose.ui.graphics.Color.White,
                )
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // 只显示正在播的内容标题（加粗）；「播放器」三个字不再常驻
                    Text(
                        text = currentName ?: "播放器",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        },
    ) { padding ->
        // 横屏时这一列放不下，能滚才不至于把下面的按钮推出屏幕外
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // 视频画面：控制条里的「清晰度 / 选集」按钮点开的暗色菜单盖在画面上
            Box(modifier = Modifier.fillMaxWidth().height(260.dp)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        // 从 XML 而不是代码 new：要带上自定义控制条 view_player_controls.xml，
                        // 全屏按钮得排在设置齿轮左边
                        (LayoutInflater.from(ctx).inflate(R.layout.view_player_video, null, false) as PlayerView)
                            .apply {
                                wireControls(fullscreenButton = true)
                                setShowNextButton(false)
                                setShowPreviousButton(false)
                                PlayerGestures(this, activity, { controller }, { gestureHint = it }).attach()
                            }
                    },
                    update = { view -> view.player = controller },
                )
                if (showEpisodes) {
                    EpisodesOverlay(
                        session = animeSession,
                        modifier = Modifier.matchParentSize(),
                        onDismiss = { showEpisodes = false },
                        onSelect = { playEpisode(it) },
                    )
                }
                if (showQuality) {
                    QualityOverlay(
                        controller = controller,
                        modifier = Modifier.matchParentSize(),
                        onDismiss = { showQuality = false },
                    )
                }
                gestureHint?.let { hint -> GestureHintOverlay(hint, Modifier.matchParentSize()) }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 没找到同名歌词时整块收起来，别占地方
            if (lyrics.isNotEmpty()) LyricsPanel(lines = lyrics, index = lyricIndex)

            lastError?.let { message ->
                Text(
                    text = "播放失败：$message",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconToggleButton(
                    checked = repeatOne,
                    onCheckedChange = { repeatOne = it; if (it) repeatAll = false },
                ) {
                    Icon(Icons.Default.RepeatOne, contentDescription = "单曲循环")
                }
                IconToggleButton(
                    checked = repeatAll,
                    onCheckedChange = { repeatAll = it; if (it) repeatOne = false },
                ) {
                    Icon(Icons.Default.Repeat, contentDescription = "列表循环")
                }
                IconToggleButton(
                    checked = shuffle,
                    onCheckedChange = { shuffle = it },
                ) {
                    Icon(Icons.Default.Shuffle, contentDescription = "随机")
                }
            }

            // 下载单独占一行：两个带字的大按钮挤同一行时，文字会被压到竖排
            // （真机 1080×2392 上实测如此）
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = currentSource != null && !downloading,
                    onClick = {
                        val source = currentSource ?: return@OutlinedButton
                        if (!canDownloadDirectly(source)) {
                            downloadStatus = "这是 HLS / DASH 播放列表，需要合并分片才能成文件，这里下不了"
                            return@OutlinedButton
                        }
                        val fileName = downloadableFileName(resolvedTitle ?: currentName, source)
                        val target = runCatching { VideoDownloadStore(context).prepare(fileName) }.getOrNull()
                        if (target == null) {
                            downloadStatus = "下载失败：建不出文件"
                            return@OutlinedButton
                        }
                        scope.launch {
                            downloading = true
                            downloadProgress = null
                            downloadStatus = "正在下载 " + fileName + "…"
                            try {
                                val bytes = downloader.download(source, StreamHeaders.current, target.open()) { written, total ->
                                    // -1f 表示服务端没给总长度，用不确定进度条
                                    downloadProgress = total?.let { (written.toFloat() / it).coerceIn(0f, 1f) } ?: -1f
                                }
                                target.finish()
                                downloadStatus = "已保存到 " + target.location + "（" + readableSize(bytes) + "）"
                            } catch (e: CancellationException) {
                                target.abort()
                                throw e
                            } catch (e: Exception) {
                                target.abort()
                                downloadStatus = "下载失败：" + (e.message ?: e.toString())
                            } finally {
                                downloading = false
                                downloadProgress = null
                            }
                        }
                    },
                ) { Text(if (downloading) "下载中…" else "下载到本地") }
            }

            if (downloading) {
                val progress = downloadProgress
                if (progress != null && progress >= 0f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                }
            }

            downloadStatus?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
        }
    }
}

/** 手势提示气泡：横滑快进/快退、亮度、音量拖动时显示在画面中央。 */
@Composable
private fun GestureHintOverlay(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = text,
            fontSize = 15.sp,
            color = Color.White,
            modifier = Modifier
                .background(Color(0xB3000000), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** 选集面板的空态：播本地文件或直链时没有剧集列表。 */
@Composable
private fun PlayerMenuHint(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        color = Color.White.copy(alpha = 0.7f),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * 模仿 media3 设置菜单（控制条齿轮弹出的那种）的样式：暗底白字，整幅盖在画面上，
 * 点空白处收起。[modifier] 从 BoxScope 传 `Modifier.matchParentSize()`。
 */
@Composable
private fun PlayerMenuOverlay(title: String, modifier: Modifier = Modifier, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .background(Color(0xB3000000))
            .clickable(interactionSource = interaction, indication = null, onClick = onDismiss),
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        content()
    }
}

@Composable
private fun PlayerMenuRow(text: String, selected: Boolean, dimmed: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).alpha(if (dimmed) 0.5f else 1f),
        )
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 选集菜单（暗色，样式同设置菜单）。 */
@Composable
private fun EpisodesOverlay(
    session: AnimePlayRequest?,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val episodes = session?.episodes.orEmpty()
    PlayerMenuOverlay(
        title = if (episodes.isEmpty()) "选集" else "选集 · 共 ${episodes.size} 集",
        modifier = modifier,
        onDismiss = onDismiss,
    ) {
        if (episodes.isEmpty()) {
            PlayerMenuHint("当前播放没有剧集列表（播本地文件或链接时没有选集）")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(episodes) { index, episode ->
                    PlayerMenuRow(
                        text = episode.label,
                        selected = index == session?.currentIndex,
                        dimmed = index < (session?.currentIndex ?: 0),
                        onClick = { onSelect(index) },
                    )
                }
            }
        }
    }
}

/**
 * 清晰度菜单：列出当前流的视频分辨率，选中后用轨道选择参数限高。
 * 「自动」= 清掉手动限制，交给 ExoPlayer 按带宽自适应。
 */
@Composable
private fun QualityOverlay(
    controller: Player?,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
) {
    val heights = remember(controller) {
        controller?.currentTracks?.groups
            ?.filter { it.type == C.TRACK_TYPE_VIDEO }
            ?.flatMap { group ->
                (0 until group.length).mapNotNull { i ->
                    group.getTrackFormat(i).height.takeIf { h -> h > 0 }
                }
            }
            ?.distinct()
            ?.sortedDescending()
            .orEmpty()
    }
    val currentMaxHeight = controller?.trackSelectionParameters?.maxVideoHeight ?: Int.MAX_VALUE

    PlayerMenuOverlay(title = "清晰度", modifier = modifier, onDismiss = onDismiss) {
        if (heights.isEmpty()) {
            PlayerMenuHint("当前播放没有可选清晰度")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                val options: List<Pair<String, Int?>> = listOf("自动" to null) + heights.map { "${it}P" to it }
                itemsIndexed(options) { _, (label, maxHeight) ->
                    val selected = if (maxHeight == null) {
                        currentMaxHeight == Int.MAX_VALUE
                    } else {
                        currentMaxHeight == maxHeight
                    }
                    PlayerMenuRow(
                        text = label,
                        selected = selected,
                        onClick = {
                            controller?.let { c ->
                                c.trackSelectionParameters = c.trackSelectionParameters
                                    .buildUpon()
                                    .setMaxVideoSize(
                                        /* maxVideoWidth = */ Int.MAX_VALUE,
                                        /* maxVideoHeight = */ maxHeight ?: Int.MAX_VALUE,
                                    )
                                    .build()
                            }
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

/**
 * 歌词区：当前一句高亮，下面跟一句下文。只在真的找到歌词时才显示。
 */
@Composable
private fun LyricsPanel(lines: List<LrcLine>, index: Int) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        val current = lines.getOrNull(index)
        val next = lines.getOrNull(index + 1)
        Text(
            text = current?.text.orEmpty().ifEmpty { "…" },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = next?.text.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 从 Compose 的 Context 里掏出 Activity（改屏幕方向、控制系统栏都要用它）。 */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * 标题上显示什么：`content://` 问系统要文件名，网络地址取最后一段。
 */
private fun nameOf(context: Context, source: String): String {
    val uri = Uri.parse(source)
    return displayNameOf(context, uri) ?: source.take(60)
}

/**
 * 找同名的 .lrc 并解析。
 *
 * 只对 file:// 这种能算出兄弟路径的地址有效；content:// 是 SAF 给的不透明地址，
 * 拿不到同目录（系统限制，不是没做）。
 */
private fun loadLyricsFor(uri: Uri): List<LrcLine> {
    if (uri.scheme != "file") return emptyList()
    val path = uri.path ?: return emptyList()
    val dot = path.lastIndexOf('.')
    if (dot <= 0) return emptyList()
    return runCatching {
        val file = java.io.File(path.substring(0, dot) + ".lrc")
        if (!file.exists()) return emptyList()
        LrcParser.parse(file.readText())
    }.getOrDefault(emptyList())
}

/**
 * 从 content:// URI 里问出文件名，显示在标题上。
 */
private fun displayNameOf(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull() ?: uri.lastPathSegment
