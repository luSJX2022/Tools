package com.qzkt.timetable.ui.player

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

/**
 * Tools 的播放器工具。
 *
 * 解码用 Android 自带的媒体框架（Media3/ExoPlayer），走系统解码器，常见格式都能放。
 *
 * **播放器不在这个页面里**，而在 [PlaybackService] 里；这里通过 [MediaController] 连上去控制它。
 * 这样切后台 / 锁屏 / 页面被回收都还能继续放，通知栏和蓝牙耳机也能控制。
 *
 * 除了本地文件，也能直接放网络地址（HLS / DASH / 直链）；
 * 需要防盗链的站可以在「请求头」里补 Referer / User-Agent，见 [StreamHeaders]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    initialUri: Uri? = null,
    /** 是否全屏。状态由外层持有，这样全屏时外层能把底部页签一起收起来。 */
    fullscreen: Boolean = false,
    onFullscreenChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current

    var controller by remember { mutableStateOf<MediaController?>(null) }
    // 待播地址必须扛得住 Activity 重建（进全屏会旋转屏幕 → 重建），
    // 否则重建后会被重置回"初始文件"，把你刚选的文件顶掉。
    var pendingUriText by rememberSaveable { mutableStateOf(initialUri?.toString()) }
    // 初始文件只消费一次：重建时会拿着同一个 intent 再进来
    var initialHandled by rememberSaveable { mutableStateOf(false) }
    // 正在播的那一路。标题和歌词都从它派生，所以它自己也必须能扛重建 ——
    // 否则进一次全屏回来，标题就空了、歌词也没了（下面两个 val 就是干这个的）。
    var currentSource by rememberSaveable { mutableStateOf<String?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var lyricIndex by remember { mutableStateOf(-1) }
    var positionMs by remember { mutableStateOf(0L) }
    var repeatAll by remember { mutableStateOf(false) }
    var repeatOne by remember { mutableStateOf(false) }
    var shuffle by remember { mutableStateOf(false) }
    var urlInput by rememberSaveable { mutableStateOf("") }
    var headersInput by rememberSaveable { mutableStateOf("") }
    var headersExpanded by rememberSaveable { mutableStateOf(false) }
    val activity = remember(context) { context.findActivity() }

    val currentName = remember(context, currentSource) {
        currentSource?.let { nameOf(context, it) }
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

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // 本地文件不走网络，把上一路流留下的请求头清掉，免得带进无关请求
            StreamHeaders.clear()
            currentSource = uri.toString()
            lastError = null
            pendingUriText = uri.toString()
        }
    }

    // 从别的应用"打开方式"进来的那个文件，只处理一次
    LaunchedEffect(initialUri) {
        val uri = initialUri ?: return@LaunchedEffect
        if (initialHandled) return@LaunchedEffect
        initialHandled = true
        StreamHeaders.clear()
        currentSource = uri.toString()
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

    // 每 200ms 取一次位置推进歌词（Media3 没有逐帧回调，这样够用）
    LaunchedEffect(controller, lyrics) {
        while (true) {
            controller?.let {
                positionMs = it.currentPosition
                lyricIndex = LrcParser.lineAt(lyrics, positionMs)
            }
            delay(200)
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

    if (fullscreen) {
        Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> PlayerView(ctx).apply { useController = true } },
                update = { view -> view.player = controller },
            )
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
                    Column {
                        Text("播放器", style = MaterialTheme.typography.titleMedium)
                        currentName?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
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
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(260.dp),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = true
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                    }
                },
                update = { view -> view.player = controller },
            )

            Spacer(modifier = Modifier.height(8.dp))

            LyricsPanel(lines = lyrics, index = lyricIndex)

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

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("流媒体地址", style = MaterialTheme.typography.bodySmall) },
                    placeholder = { Text("https://…/index.m3u8", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        val url = normalizeStreamUrl(urlInput) ?: return@Button
                        // 请求头在按下的这一刻定下来，之后改输入框不影响正在播的这路
                        StreamHeaders.set(parseHeaderBlock(headersInput))
                        currentSource = url
                        lastError = null
                        pendingUriText = url
                        urlInput = ""
                    },
                    enabled = urlInput.isNotBlank(),
                ) { Text("播放") }
            }

            // 防盗链：不少站要带 Referer / User-Agent 才给数据，默认收起，别挡住常用操作
            val headerCount = remember(headersInput) { parseHeaderBlock(headersInput).size }
            TextButton(
                onClick = { headersExpanded = !headersExpanded },
                modifier = Modifier.padding(start = 12.dp),
            ) {
                Text(
                    text = if (headerCount == 0) "请求头（防盗链用）" else "请求头（已填 $headerCount 条）",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (headersExpanded) {
                OutlinedTextField(
                    value = headersInput,
                    onValueChange = { headersInput = it },
                    label = { Text("每行一个「名称: 值」", style = MaterialTheme.typography.bodySmall) },
                    placeholder = {
                        Text(
                            "Referer: https://www.example.com/\n" +
                                "User-Agent: Mozilla/5.0 (Linux; Android 16)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { pickMedia.launch(arrayOf("video/*", "audio/*")) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Text("  选择文件")
                }
                IconButton(onClick = { onFullscreenChange(true) }) {
                    Icon(Icons.Default.Fullscreen, contentDescription = "全屏")
                }
                OutlinedButton(onClick = { controller?.stop() }) { Text("停止") }
            }
        }
    }
}

/**
 * 歌词区：当前一句高亮，下面跟一句下文。
 *
 * 没找到歌词时明确说找不到，而不是留一片空白让人以为是坏了。
 */
@Composable
private fun LyricsPanel(lines: List<LrcLine>, index: Int) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        if (lines.isEmpty()) {
            Text(
                text = "（没找到同名 .lrc 歌词）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
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

/**
 * 把用户填的流媒体地址整成能用的 URL。
 *
 * 只填域名/路径时补 https；按后缀在 Media3 那边会自动选解析器
 * （`.m3u8` → HLS、`.mpd` → DASH、其余按普通媒体流）。
 */
private fun normalizeStreamUrl(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    return if (text.startsWith("http://", true) || text.startsWith("https://", true) ||
        text.startsWith("rtsp://", true) || text.startsWith("rtmp://", true)
    ) {
        text
    } else {
        "https://$text"
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
