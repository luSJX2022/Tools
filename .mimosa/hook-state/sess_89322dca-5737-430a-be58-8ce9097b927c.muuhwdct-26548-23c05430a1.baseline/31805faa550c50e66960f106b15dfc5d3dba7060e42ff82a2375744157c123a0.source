package com.qzkt.timetable.ui.book

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qzkt.timetable.data.book.Book
import com.qzkt.timetable.data.book.BookSources
import com.qzkt.timetable.data.book.OnlineChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext

/**
 * 阅读页。kind = local：整本 txt 读入内存按段落滚；
 * kind = online：按章节从书源拉正文，菜单里有上一章 / 目录 / 下一章。
 * 进度：local 记段落下标，online 记章节下标。
 */
@Composable
fun ReaderScreen(
    bookId: String,
    viewModel: BookViewModel,
    onBack: () -> Unit,
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val book = books.firstOrNull { it.id == bookId }

    when {
        book == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        book.kind == "online" -> OnlineReader(book = book, viewModel = viewModel, onBack = onBack)
        else -> LocalReader(book = book, viewModel = viewModel, onBack = onBack)
    }
}

// ---------- 本地 TXT ----------

@Composable
private fun LocalReader(book: Book, viewModel: BookViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val fontSize by viewModel.fontSize.collectAsStateWithLifecycle()
    val night by viewModel.night.collectAsStateWithLifecycle()

    var text by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var lastIndex by remember { mutableIntStateOf(book.lastIndex) }

    val bgColor = if (night) Color(0xFF121212) else MaterialTheme.colorScheme.background
    val textColor = if (night) Color(0xFFC8C8C8) else MaterialTheme.colorScheme.onBackground

    LaunchedEffect(book.uri) {
        text = null
        loadError = null
        text = withContext(Dispatchers.IO) {
            runCatching { readBookText(context, Uri.parse(book.uri)) }
                .onFailure { loadError = it.message ?: "打不开这个文件" }
                .getOrNull()
        }
    }

    val paragraphs = remember(text) {
        text?.replace("\r\n", "\n")?.split('\n').orEmpty()
    }
    val listState = rememberLazyListState()

    // 恢复上次进度：段落总数和记录时一致才跳（文件被改过就不乱跳）
    LaunchedEffect(paragraphs, book.lastIndex, book.lastParagraphTotal) {
        val target = book.lastIndex
        if (paragraphs.isNotEmpty() && book.lastParagraphTotal == paragraphs.size && target in 0 until paragraphs.size) {
            listState.scrollToItem(target)
        }
        lastIndex = if (target in 0 until paragraphs.size) target else 0
    }

    LaunchedEffect(paragraphs) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .drop(1)
            .collect { lastIndex = it }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (paragraphs.isNotEmpty()) {
                viewModel.saveProgress(book.id, lastIndex, paragraphs.size)
            }
        }
    }

    ReaderScaffold(
        title = book.name,
        night = night,
        onBack = onBack,
        bottomBar = {
            TextButton(onClick = { viewModel.setFontSize(fontSize - 1) }) { Text("A-", color = textColor) }
            TextButton(onClick = { viewModel.setFontSize(fontSize + 1) }) { Text("A+", color = textColor) }
            TextButton(onClick = { viewModel.setNight(!night) }) {
                Text(if (night) "日间" else "夜间", color = textColor)
            }
            Box(Modifier.weight(1f))
            Text(
                text = progressLabel(lastIndex, paragraphs.size),
                color = textColor,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        },
    ) { padding ->
        when {
            text == null && loadError == null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            loadError != null -> Text(
                text = "打不开这本书：$loadError",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center).padding(padding).padding(24.dp),
            )
            else -> LazyColumn(
                state = listState,
                // 顶栏 / 底栏现在常驻，正文按它们的高度留出空间，别被压住
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            ) {
                items(paragraphs.size) { index ->
                    val paragraph = paragraphs[index]
                    Text(
                        text = paragraph.ifBlank { " " },
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.7f).sp,
                        color = if (paragraph.isBlank()) Color.Transparent else textColor,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    )
                }
            }
        }
    }
}

// ---------- 在线书源 ----------

@Composable
private fun OnlineReader(book: Book, viewModel: BookViewModel, onBack: () -> Unit) {
    val fontSize by viewModel.fontSize.collectAsStateWithLifecycle()
    val night by viewModel.night.collectAsStateWithLifecycle()
    val source = remember(book.sourceKey) { BookSources.byKey(book.sourceKey) }

    var chapters by remember { mutableStateOf<List<OnlineChapter>?>(null) }
    var chapterIndex by remember { mutableIntStateOf(book.lastIndex) }
    var content by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var showCatalog by remember { mutableStateOf(false) }
    var lastIndex by remember { mutableIntStateOf(book.lastIndex) }

    val bgColor = if (night) Color(0xFF121212) else MaterialTheme.colorScheme.background
    val textColor = if (night) Color(0xFFC8C8C8) else MaterialTheme.colorScheme.onBackground
    val barColor = if (night) Color(0xFF1D1D1D) else MaterialTheme.colorScheme.surfaceVariant

    // 换章（或重试）时重新拉正文；目录顺路拉一次
    LaunchedEffect(chapterIndex, reloadTick) {
        loadError = null
        content = null
        try {
            val list = chapters ?: source.catalog(book.bookUrl).also { chapters = it }
            val chapter = list.getOrNull(chapterIndex) ?: error("章节下标超出范围")
            lastIndex = chapterIndex
            content = source.content(chapter)
        } catch (e: Exception) {
            loadError = e.message ?: "加载失败"
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            viewModel.saveProgress(book.id, lastIndex, chapters?.size ?: 0)
        }
    }

    val chapterTitle = chapters?.getOrNull(chapterIndex)?.title.orEmpty()
    val paragraphs = remember(content) { content?.replace("\r\n", "\n")?.split('\n').orEmpty() }

    ReaderScaffold(
        title = book.name,
        night = night,
        onBack = onBack,
        bottomBar = {
            val chapterCount = chapters?.size ?: 0
            TextButton(
                onClick = { chapterIndex-- },
                enabled = chapterIndex > 0,
            ) { Text("上一章", color = textColor) }
            TextButton(onClick = { showCatalog = true }) { Text("目录", color = textColor) }
            TextButton(
                onClick = { chapterIndex++ },
                enabled = chapterCount > 0 && chapterIndex < chapterCount - 1,
            ) { Text("下一章", color = textColor) }
            Box(Modifier.weight(1f))
            Text(
                text = if (chapterCount > 0) "第 ${chapterIndex + 1}/$chapterCount 章" else "…",
                color = textColor,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        },
    ) { padding ->
        when {
            (content == null || chapters == null) && loadError == null ->
                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

            loadError != null -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                Text("加载失败：$loadError", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { reloadTick++ }) { Text("重试") }
            }

            else -> LazyColumn(
                // 顶栏 / 底栏常驻，正文按它们的高度留出空间
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            ) {
                if (chapterTitle.isNotBlank()) {
                    item {
                        Text(
                            text = chapterTitle,
                            fontSize = (fontSize + 2).sp,
                            fontWeight = FontWeight.Bold,
                            color = textColor,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }
                items(paragraphs.size) { index ->
                    val paragraph = paragraphs[index]
                    Text(
                        text = paragraph.ifBlank { " " },
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.7f).sp,
                        color = if (paragraph.isBlank()) Color.Transparent else textColor,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    )
                }
            }
        }
    }

    if (showCatalog) {
        val list = chapters
        AlertDialog(
            onDismissRequest = { showCatalog = false },
            title = { Text("目录（共 ${list?.size ?: 0} 章）") },
            text = {
                if (list == null) {
                    Text("目录还在加载…")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(list.size) { index ->
                            Text(
                                text = list[index].title,
                                fontSize = 14.sp,
                                color = if (index == chapterIndex) MaterialTheme.colorScheme.primary else textColor,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        chapterIndex = index
                                        showCatalog = false
                                    }
                                    .padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCatalog = false }) { Text("关闭") }
            },
        )
    }
}

// ---------- 共用骨架 ----------

/** 操作条高度：正文按它上下留白（IconButton 默认 48dp）。 */
private val READER_BAR_HEIGHT = 48.dp

/**
 * 阅读页骨架：背景 + 正文 + **常驻的**顶栏 / 底栏。
 *
 * 以前两条操作条要靠点屏幕中间呼出、再点收回。现在阅读页不再挂 App 的
 * 「工具 / 设置」页签（见 QzktApp 的 showBottomBar），底部这一行就是阅读时唯一的
 * 操作入口（上一章 / 目录 / 下一章），藏起来用户根本找不到 —— 所以改成常驻。
 */
@Composable
private fun ReaderScaffold(
    title: String,
    night: Boolean,
    onBack: () -> Unit,
    bottomBar: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
    content: @Composable androidx.compose.foundation.layout.BoxScope.(padding: androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    val barColor = if (night) Color(0xFF1D1D1D) else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (night) Color(0xFFC8C8C8) else MaterialTheme.colorScheme.onBackground
    val bgColor = if (night) Color(0xFF121212) else MaterialTheme.colorScheme.background

    Box(modifier = Modifier.fillMaxSize().background(bgColor)) {
        content(
            androidx.compose.foundation.layout.PaddingValues(
                top = READER_BAR_HEIGHT + 6.dp,
                bottom = READER_BAR_HEIGHT + 6.dp,
            ),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(barColor)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = textColor)
            }
            Text(
                text = title,
                color = textColor,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 16.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(barColor)
                .align(Alignment.BottomCenter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            bottomBar()
        }
    }
}

private fun progressLabel(lastIndex: Int, total: Int): String =
    if (total <= 0) "0%" else ((lastIndex + 1) * 100 / total).coerceAtMost(100).toString() + "%"

/**
 * 读入整本 txt：先按 UTF-8 解，乱码（替换符）多就退回 GBK —— 中文小说两种编码最常见。
 */
private fun readBookText(context: android.content.Context, uri: Uri): String {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("打不开这个文件")
    val utf8 = bytes.decodeToString()
    val bad = utf8.count { it == '\uFFFD' }
    return if (bad > utf8.length * 0.01) {
        runCatching { String(bytes, charset("GBK")) }.getOrElse { utf8 }.removePrefix("\uFEFF")
    } else {
        utf8.removePrefix("\uFEFF")
    }
}
