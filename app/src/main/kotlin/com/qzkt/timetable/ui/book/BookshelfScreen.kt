package com.qzkt.timetable.ui.book

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.qzkt.timetable.data.book.Book
import com.qzkt.timetable.data.book.BookSources
import com.qzkt.timetable.data.book.OnlineBook

/** 图书工具：书架（本地 TXT）+ 书城（在线书源）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    viewModel: BookViewModel,
    onBack: () -> Unit,
    onOpenBook: (id: String) -> Unit,
) {
    val context = LocalContext.current
    val books by viewModel.books.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val searchError by viewModel.searchError.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("shelf") }   // shelf：书架，store：书城
    var query by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Book?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                // 拿持久化读权限：重启之后还能读这个文件
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val name = queryDisplayName(context, uri) ?: "未命名.txt"
            viewModel.addBook(name, uri.toString())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("图书") },
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
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = tab == "shelf",
                    onClick = { tab = "shelf" },
                    label = { Text("书架") },
                )
                FilterChip(
                    selected = tab == "store",
                    onClick = { tab = "store" },
                    label = { Text("书城") },
                )
                Spacer(Modifier.weight(1f))
                if (tab == "shelf") {
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("text/*", "application/octet-stream")) },
                    ) { Text("导入 TXT") }
                }
            }

            when (tab) {
                "store" -> StoreTab(
                    query = query,
                    onQueryChange = { query = it },
                    searching = searching,
                    searchError = searchError,
                    results = searchResults,
                    onSearch = { viewModel.search(query) },
                    onOpenBook = { onlineBook ->
                        viewModel.addOnlineBook(onlineBook) { id -> onOpenBook(id) }
                    },
                )
                else -> ShelfTab(
                    books = books,
                    onOpenBook = { onOpenBook(it.id) },
                    onDelete = { pendingDelete = it },
                )
            }
        }
    }

    pendingDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("移出书架？") },
            text = { Text("「${book.name}」会从书架移除，阅读进度一并清掉；${if (book.kind == "online") "在线书随时能重新搜到" else "txt 文件本体不受影响"}。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeBook(book.id)
                    pendingDelete = null
                }) { Text("移出") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ShelfTab(books: List<Book>, onOpenBook: (Book) -> Unit, onDelete: (Book) -> Unit) {
    if (books.isEmpty()) {
        Spacer(Modifier.height(24.dp))
        Text(
            text = "书架还是空的。点「导入 TXT」加本地书，或切到「书城」搜在线书",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(books, key = { it.id }) { book ->
            BookRow(
                book = book,
                onClick = { onOpenBook(book) },
                onDelete = { onDelete(book) },
            )
        }
    }
}

/** 书城：搜索在线书源，点结果直接入库并进阅读页。 */
@Composable
private fun StoreTab(
    query: String,
    onQueryChange: (String) -> Unit,
    searching: Boolean,
    searchError: String?,
    results: List<com.qzkt.timetable.data.book.OnlineBook>?,
    onSearch: () -> Unit,
    onOpenBook: (com.qzkt.timetable.data.book.OnlineBook) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("书名 / 作者 / 关键词", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSearch, enabled = query.isNotBlank() && !searching) {
                Text(if (searching) "搜索中…" else "搜索")
            }
        }

        Text(
            text = "书源：${BookSources.all.first().name}（搜作者 / 题材关键词更容易命中）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            searching -> Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            searchError != null -> Text(
                text = "搜索失败：$searchError",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            results != null && results.isEmpty() -> Text(
                text = "没有搜到。换个关键词（作者名 / 题材词更容易命中）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            results != null -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(results, key = { it.sourceKey + it.bookUrl }) { onlineBook ->
                    OnlineBookRow(onlineBook = onlineBook, onClick = { onOpenBook(onlineBook) })
                }
            }
        }
    }
}

@Composable
private fun OnlineBookRow(onlineBook: com.qzkt.timetable.data.book.OnlineBook, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .size(width = 76.dp, height = 100.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                if (onlineBook.cover != null) {
                    AsyncImage(
                        model = onlineBook.cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text("无封面", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = onlineBook.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                onlineBook.author?.let { author ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                onlineBook.description?.let { description ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun BookRow(book: Book, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (book.kind == "online" && book.cover.isNotEmpty()) {
                AsyncImage(
                    model = book.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 44.dp, height = 58.dp)
                        .clip(MaterialTheme.shapes.small),
                )
                Spacer(Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = progressText(book),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Close, contentDescription = "移出书架")
            }
        }
    }
}

private fun progressText(book: Book): String {
    val prefix = if (book.kind == "online") {
        listOfNotNull(book.author.ifBlank { null }).joinToString(" · ").let { if (it.isEmpty()) "" else "$it · " }
    } else {
        ""
    }
    return prefix + when {
        book.lastParagraphTotal > 0 && book.kind == "online" ->
            "读到第 ${book.lastIndex + 1} 章（共 ${book.lastParagraphTotal} 章）"
        book.lastParagraphTotal > 0 ->
            "读到 " + (book.lastIndex + 1) * 100 / book.lastParagraphTotal + "%"
        else -> "还没开始读"
    }
}

/** 问系统拿导入文件的显示名（通常是文件名）。 */
private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
