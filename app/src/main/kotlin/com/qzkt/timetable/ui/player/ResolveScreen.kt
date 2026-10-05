package com.qzkt.timetable.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.qzkt.timetable.data.anime.AnimePlayRequest
import com.qzkt.timetable.ui.common.SectionCard
import com.qzkt.timetable.ui.player.link.MediaDownloader
import com.qzkt.timetable.ui.player.link.MediaLinkResolver
import com.qzkt.timetable.ui.player.link.ResolvedMedia
import com.qzkt.timetable.ui.player.link.detectShareLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/**
 * 链接解析：从播放器里单独拆出来的入口。
 *
 * B站 / 抖音的**分享链接**（短链、或者夹在一堆说明文字里的地址）先联网换成一条
 * 真实地址再交给播放器；普通流地址（`.m3u8` / 直链）不走解析直接播。
 * 防盗链站可以在「请求头」里补 Referer / User-Agent，见 [StreamHeaders]。
 *
 * 解析成功后**停留在本页**展示作品信息（封面 / 作者 / 发布时间 / 播放量 / 简介 / 直链），
 * 点「播放」才跳到播放器页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResolveScreen(
    onBack: () -> Unit,
    onPlay: (AnimePlayRequest) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val linkResolver = remember { MediaLinkResolver() }
    val keyboard = LocalSoftwareKeyboardController.current

    var urlInput by rememberSaveable { mutableStateOf("") }
    var headersInput by rememberSaveable { mutableStateOf("") }
    var headersExpanded by rememberSaveable { mutableStateOf(false) }
    // 分享链接要先联网才能换到真实地址，解析期间按钮显示「解析中…」，防止重复点
    var resolving by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var resolved by remember { mutableStateOf<ResolvedMedia?>(null) }

    fun resolve() {
        if (urlInput.isBlank() || resolving) return
        val raw = urlInput.trim()
        val userHeaders = parseHeaderBlock(headersInput)
        keyboard?.hide()

        val link = detectShareLink(raw)
        if (link == null) {
            // 不是分享链接，就当普通流地址处理：没有平台信息，直接给直链结果
            val url = normalizeStreamUrl(raw)
            if (url == null) {
                lastError = "看不出来这是个链接，检查一下再试"
                return
            }
            StreamHeaders.set(userHeaders)
            urlInput = ""
            resolved = ResolvedMedia(
                url = url,
                title = null,
                headers = userHeaders,
                platform = null,
            )
            return
        }

        // 分享链接：先联网换成一条真实地址，再把平台要求的请求头一起交给播放器
        scope.launch {
            resolving = true
            lastError = null
            try {
                val media = linkResolver.resolve(raw, userHeaders)
                StreamHeaders.set(media.headers)
                urlInput = ""
                resolved = media
            } catch (e: CancellationException) {
                throw e   // 页面被销毁时的正常取消，不算解析失败
            } catch (e: Exception) {
                lastError = "解析失败：" + (e.message ?: e.toString())
            } finally {
                resolving = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("链接解析") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                label = { Text("视频链接 / 分享链接", style = MaterialTheme.typography.bodySmall) },
                placeholder = {
                    Text(
                        "B站、抖音的分享链接，或 https://…/index.m3u8",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { resolve() }),
                modifier = Modifier.fillMaxWidth(),
            )

            // 防盗链：不少站要带 Referer / User-Agent 才给数据，默认收起
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
                                "User-Agent: Mozilla/5.0 (Linux; Android 16)\n" +
                                "Cookie: SESSDATA=…（B站 1080P 需要）",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = { resolve() },
                enabled = urlInput.isNotBlank() && !resolving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (resolving) "解析中…" else if (resolved != null) "重新解析" else "解析")
            }

            Text(
                text = "支持 B站 / 抖音 的分享链接（短链、带说明文字都行），图集会列出每张图的直链，" +
                    "图文 / 视频带的 BGM 也能直接播；普通流地址（.m3u8 / 直链）不走解析直接给直链。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            lastError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            resolved?.let { media ->
                ResolvedCard(
                    media = media,
                    onPlay = onPlay,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 解析结果卡：封面 / 标题 / 作者 / 发布时间 / 播放量 / 简介 / 直链（可复制）+ 播放按钮。 */
@Composable
private fun ResolvedCard(media: ResolvedMedia, onPlay: (AnimePlayRequest) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    // 图集 / 图文的图片查看器：null = 关闭，非空 = 打开并定位到这一张
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    var copiedMusic by remember { mutableStateOf(false) }
    LaunchedEffect(copiedMusic) {
        if (copiedMusic) {
            delay(1500)
            copiedMusic = false
        }
    }

    SectionCard("解析结果") {
        Row {
            Box(
                modifier = Modifier
                    .width(150.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = media.images.isNotEmpty()) { viewerIndex = 0 },
                contentAlignment = Alignment.Center,
            ) {
                if (media.cover != null) {
                    AsyncImage(
                        model = media.cover,
                        contentDescription = "封面",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = "无封面",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = media.title ?: "未命名视频",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                media.author?.let { author ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val metaLine = listOfNotNull(
                    media.publishTime?.let { "发布于 ${formatDate(it)}" },
                    media.playCount?.let { "播放 ${formatCount(it)}" },
                ).joinToString(" · ")
                if (metaLine.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = metaLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        media.description?.let { description ->
            Spacer(Modifier.height(10.dp))
            var expanded by remember(description) { mutableStateOf(false) }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { expanded = !expanded },
            )
        }

        Spacer(Modifier.height(10.dp))

        if (media.images.isNotEmpty()) {
            // 抖音图集：没有视频可播，把每张原图的直链列出来供复制保存
            Text(
                text = "图集（共 ${media.images.size} 张）",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            var copiedImage by remember { mutableStateOf(-1) }
            LaunchedEffect(copiedImage) {
                if (copiedImage >= 0) {
                    delay(1500)
                    copiedImage = -1
                }
            }
            media.images.forEachIndexed { index, imageUrl ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clickable { viewerIndex = index },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "图 ${index + 1}",
                        fontSize = 13.sp,
                        modifier = Modifier.width(44.dp),
                    )
                    Text(
                        text = imageUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(imageUrl))
                        copiedImage = index
                    }) { Text(if (copiedImage == index) "已复制" else "复制") }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "图集作品没有视频，把图片链接复制到浏览器打开即可保存原图",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(6.dp))
            var galleryStatus by remember(media.images) { mutableStateOf<String?>(null) }
            var downloadingImages by remember(media.images) { mutableStateOf(false) }
            val galleryDownloader = remember { MediaDownloader() }
            Button(
                onClick = {
                    if (downloadingImages) return@Button
                    scope.launch {
                        downloadingImages = true
                        var ok = 0
                        var fail = 0
                        val base = (media.title ?: "抖音图集")
                            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                            .take(40)
                        withContext(Dispatchers.IO) {
                            media.images.forEachIndexed { index, imageUrl ->
                                galleryStatus = "正在下载 ${index + 1}/${media.images.size} 张…"
                                val target = runCatching {
                                    VideoDownloadStore(context).prepare(base + "-" + (index + 1) + ".jpg")
                                }.getOrNull()
                                if (target == null) {
                                    fail++
                                    return@forEachIndexed
                                }
                                try {
                                    galleryDownloader.download(imageUrl, emptyMap(), target.open()) { _, _ -> }
                                    target.finish()
                                    ok++
                                } catch (e: CancellationException) {
                                    target.abort()
                                    throw e
                                } catch (e: Exception) {
                                    target.abort()
                                    fail++
                                }
                            }
                        }
                        galleryStatus = "完成：成功 $ok 张" + if (fail > 0) "，失败 $fail 张" else ""
                        downloadingImages = false
                    }
                },
                enabled = !downloadingImages,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (downloadingImages) "下载中…" else "下载全部图片") }
            galleryStatus?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text("视频直链", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = media.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(media.url))
                    copied = true
                }) { Text(if (copied) "已复制" else "复制") }
            }
        }

        // 图集 / 图文：内置查看器（点封面或任意一条图链打开，左右翻页）
        if (viewerIndex != null && media.images.isNotEmpty()) {
            ImageViewerDialog(
                images = media.images,
                initialIndex = viewerIndex ?: 0,
                onDismiss = { viewerIndex = null },
            )
        }

        media.musicUrl?.let { musicUrl ->
            Spacer(Modifier.height(10.dp))
            Text("背景音乐", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = media.musicTitle ?: "未命名音乐",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(musicUrl))
                    copiedMusic = true
                }) { Text(if (copiedMusic) "已复制" else "复制") }
            }
            // 图文作品没有视频，BGM 是它唯一能直接播的；视频作品也能单独听 BGM
            Button(
                onClick = {
                    onPlay(
                        AnimePlayRequest(
                            url = musicUrl,
                            title = "BGM · " + (media.musicTitle ?: media.title ?: ""),
                            description = media.description,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("播放 BGM") }
        }

        media.warning?.let { warning ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = warning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 图集没有视频可播，不放播放按钮
        if (media.url.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    onPlay(
                        AnimePlayRequest(
                            url = media.url,
                            title = media.title ?: "",
                            description = media.description,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("播放")
            }
        }
    }
}

/** 全屏图片查看器：左右翻页看大图。 */
@Composable
private fun ImageViewerDialog(images: List<String>, initialIndex: Int, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val pagerState = androidx.compose.foundation.pager.rememberPagerState(
            initialPage = initialIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0)),
        ) { images.size }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            androidx.compose.foundation.pager.HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                AsyncImage(
                    model = images[page],
                    contentDescription = "图 ${page + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                text = "${pagerState.currentPage + 1}/${images.size}",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
            }
        }
    }
}

/** epoch 秒 → 「2026年10月1日」。 */
private fun formatDate(epochSeconds: Long): String =
    java.time.Instant.ofEpochSecond(epochSeconds).atZone(java.time.ZoneId.systemDefault())
        .let { "${it.year}年${it.monthValue}月${it.dayOfMonth}日" }

/** 播放量按中文习惯缩写：1.2亿 / 45.6万 / 789。 */
private fun formatCount(count: Long): String = when {
    count >= 100_000_000L -> String.format("%.1f亿", count / 100_000_000.0)
    count >= 10_000L -> String.format("%.1f万", count / 10_000.0)
    else -> count.toString()
}
