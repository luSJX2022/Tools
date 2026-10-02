package com.qzkt.timetable.ui.book

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext

/**
 * TXT 阅读页：整本读入内存按空行切段，LazyColumn 顺着滚，
 * 进度按「段落下标」记录 —— 重排、换字号都不丢位置。
 *
 * 点屏幕中间呼出上下的操作条：返回 + 书名在顶上，字号 / 夜间 / 进度在底下。
 */
@Composable
fun ReaderScreen(
    bookId: String,
    viewModel: BookViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val books by viewModel.books.collectAsStateWithLifecycle()
    val fontSize by viewModel.fontSize.collectAsStateWithLifecycle()
    val night by viewModel.night.collectAsStateWithLifecycle()
    val book = books.firstOrNull { it.id == bookId }

    var text by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var lastIndex by remember { mutableIntStateOf(book?.lastIndex ?: 0) }

    val bgColor = if (night) Color(0xFF121212) else MaterialTheme.colorScheme.background
    val textColor = if (night) Color(0xFFC8C8C8) else MaterialTheme.colorScheme.onBackground
    val barColor = if (night) Color(0xFF1D1D1D) else MaterialTheme.colorScheme.surfaceVariant

    LaunchedEffect(book?.uri) {
        val uri = book?.uri ?: return@LaunchedEffect
        text = null
        loadError = null
        text = withContext(Dispatchers.IO) {
            runCatching { readBookText(context, Uri.parse(uri)) }
                .onFailure { loadError = it.message ?: "打不开这个文件" }
                .getOrNull()
        }
    }

    val paragraphs = remember(text) {
        text?.replace("\r\n", "\n")?.split('\n').orEmpty()
    }
    val listState = rememberLazyListState()

    // 恢复上次进度：等正文加载完、段落总数和记录时一致才跳
    LaunchedEffect(paragraphs, book?.lastIndex) {
        val target = book?.lastIndex ?: 0
        val total = book?.lastParagraphTotal ?: 0
        if (paragraphs.isNotEmpty() && total in 1..paragraphs.size && target in 0 until paragraphs.size) {
            listState.scrollToItem(target)
        }
        lastIndex = if (target in 0 until paragraphs.size) target else 0
    }

    // 滚动时把位置记到内存；落盘放在离开页面时（DisposableEffect）一次写
    LaunchedEffect(paragraphs) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .drop(1)
            .collect { lastIndex = it }
    }
    DisposableEffect(Unit) {
        onDispose {
            val currentBook = book ?: return@onDispose
            if (paragraphs.isNotEmpty()) {
                viewModel.saveProgress(currentBook.id, lastIndex, paragraphs.size)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor),
    ) {
        when {
            text == null && loadError == null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            loadError != null -> Text(
                text = "打不开这本书：$loadError",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 18.dp,
                    vertical = 24.dp,
                ),
            ) {
                items(paragraphs.size) { index ->
                    val paragraph = paragraphs[index]
                    Text(
                        // 空行保留成一段小空隙，正文段落正常渲染
                        text = paragraph.ifBlank { " " },
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.7f).sp,
                        color = if (paragraph.isBlank()) Color.Transparent else textColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    )
                }
            }
        }

        // 顶栏（书名 + 返回），点屏幕中间切换显隐
        if (menuOpen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(barColor)
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    text = book?.name ?: "阅读",
                    color = textColor,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 16.dp),
                )
            }

            // 底栏：字号 / 夜间 / 进度
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(barColor)
                    .align(Alignment.BottomCenter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { viewModel.setFontSize(fontSize - 1) }) { Text("A-", color = textColor) }
                TextButton(onClick = { viewModel.setFontSize(fontSize + 1) }) { Text("A+", color = textColor) }
                TextButton(onClick = { viewModel.setNight(!night) }) {
                    Text(if (night) "日间" else "夜间", color = textColor)
                }
                Box(modifier = Modifier.weight(1f))
                Text(
                    text = progressLabel(lastIndex, paragraphs.size),
                    color = textColor,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        // 点屏幕中间：切换操作条（透明点击区，不碰 LazyColumn 的滚动）
        if (text != null && loadError == null) {
            val centerInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clickable(
                        interactionSource = centerInteraction,
                        indication = null,
                        onClick = { menuOpen = !menuOpen },
                    )
                    .padding(horizontal = 60.dp, vertical = 120.dp),
            )
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
