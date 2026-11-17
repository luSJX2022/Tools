package com.qzkt.timetable.ui.book

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    val categoryState by viewModel.category.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("shelf") }   // shelf：书架，store：书城
    var query by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Book?>(null) }
    // 图书设置（书源 / 阅读偏好 / 书架数据）：原来在设置页，挪到本页右上角
    var showSettings by rememberSaveable { mutableStateOf(false) }

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
                title = {
                    Column {
                        Text(
                            text = if (tab == "shelf") "我的书架" else "发现好书",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = if (tab == "shelf") "${books.size} 本藏书 · 接着上次阅读"
                            else "从开放书源探索更多作品",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "图书设置")
                    }
                },
            )
        },
        floatingActionButton = {
            if (tab == "shelf") {
                FloatingActionButton(
                    onClick = { importLauncher.launch(arrayOf("text/*", "application/octet-stream")) },
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = "导入 TXT")
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.88f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
            ) {
                Row(
                    modifier = Modifier.padding(5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LibraryTab(
                        title = "书架",
                        selected = tab == "shelf",
                        count = books.size.takeIf { tab == "shelf" },
                        modifier = Modifier.weight(1f),
                        onClick = { tab = "shelf" },
                    )
                    LibraryTab(
                        title = "书城",
                        selected = tab == "store",
                        modifier = Modifier.weight(1f),
                        onClick = { tab = "store" },
                    )
                }
            }

            when (tab) {
                "store" -> {
                    // 进书城时什么都不做就空着不好看：没有搜索结果也没选分类时，自动加载第一个分类
                    LaunchedEffect(categoryState.categoryId, searchResults, tab) {
                        if (viewModel.categories.isNotEmpty() && categoryState.categoryId.isEmpty() &&
                            searchResults == null && searchError == null && !searching
                        ) {
                            viewModel.categories.firstOrNull()?.second?.let { viewModel.openCategory(it) }
                        }
                    }
                    StoreTab(
                        query = query,
                        onQueryChange = { query = it },
                        searching = searching,
                        searchError = searchError,
                        results = searchResults,
                        onSearch = { viewModel.search(query) },
                        category = categoryState,
                        categories = viewModel.categories,
                        currentSourceName = viewModel.onlineSource.name,
                        onOpenCategory = { viewModel.openCategory(it) },
                        onCategoryNextPage = { viewModel.categoryNextPage() },
                        onOpenBook = { onlineBook ->
                            viewModel.addOnlineBook(onlineBook) { id -> onOpenBook(id) }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                else -> ShelfTab(
                    books = books,
                    onOpenBook = { onOpenBook(it.id) },
                    onDelete = { pendingDelete = it },
                    modifier = Modifier.weight(1f),
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

    if (showSettings) {
        BookSettingsDialog(
            viewModel = viewModel,
            onDismiss = { showSettings = false },
        )
    }
}

/** 图书设置：在线书源切换 + 阅读偏好（字号 / 夜间）+ 书架数据（从设置页挪进来，右上角齿轮打开）。 */
@Composable
private fun BookSettingsDialog(viewModel: BookViewModel, onDismiss: () -> Unit) {
    val fontSize by viewModel.fontSize.collectAsStateWithLifecycle()
    val night by viewModel.night.collectAsStateWithLifecycle()
    val selectedSourceKey by viewModel.sourceKey.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    var confirmClearBooks by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("图书设置") },
        text = {
            Column {
                // 在线书源：单选切换（某个源连不上就换一个）
                Text("在线书源", fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
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

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )

                // 阅读偏好
                Text("阅读偏好", fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("夜间模式", fontSize = 14.sp)
                        Text(
                            text = "阅读页用深色底（阅读页内也能切）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = night,
                        onCheckedChange = { viewModel.setNight(it) },
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )

                // 图书数据
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("书架数据", fontSize = 13.sp)
                        Text(
                            text = if (books.isEmpty()) "书架是空的" else "书架 ${books.size} 本",
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
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )

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
            dismissButton = {
                TextButton(onClick = { confirmClearBooks = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun LibraryTab(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    count: Int? = null,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(17.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(17.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (count != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ShelfTab(
    books: List<Book>,
    onOpenBook: (Book) -> Unit,
    onDelete: (Book) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (books.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(44.dp),
                )
                Text("书架还是空的", style = MaterialTheme.typography.titleMedium)
                Text(
                    "点右下角导入 TXT，或去书城发现一本好书",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 92.dp),
    ) {
        item {
            Text(
                "继续阅读",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        items(books, key = { it.id }) { book ->
            BookRow(
                book = book,
                onClick = { onOpenBook(book) },
                onDelete = { onDelete(book) },
            )
        }
    }
}

/** 书城：分类浏览 + 搜索在线书源，点结果直接入库并进阅读页。 */
@Composable
private fun StoreTab(
    query: String,
    onQueryChange: (String) -> Unit,
    searching: Boolean,
    searchError: String?,
    results: List<com.qzkt.timetable.data.book.OnlineBook>?,
    onSearch: () -> Unit,
    category: StoreCategoryState,
    categories: List<Pair<String, String>>,
    currentSourceName: String,
    onOpenCategory: (String) -> Unit,
    onCategoryNextPage: () -> Unit,
    onOpenBook: (com.qzkt.timetable.data.book.OnlineBook) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        placeholder = { Text("书名 / 作者 / 关键词", style = MaterialTheme.typography.bodySmall) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    IconButton(onClick = onSearch, enabled = query.isNotBlank() && !searching) {
                        if (searching) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Search, contentDescription = "搜索")
                        }
                    }
                }

                if (categories.isNotEmpty()) {
                    Text(
                        "分类浏览",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        categories.forEach { (label, id) ->
                            FilterChip(
                                selected = category.categoryId == id,
                                onClick = { onOpenCategory(id) },
                                label = { Text(label, fontSize = 13.sp) },
                            )
                        }
                    }
                }

                Text(
                    text = "书源：$currentSourceName（搜作者 / 题材关键词更容易命中）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when {
            // 分类浏览中
            category.categoryId.isNotEmpty() -> {
                if (category.books.isEmpty() && category.loading) {
                    item { StoreHint("加载中…", center = true) }
                } else if (category.error != null && category.books.isEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "加载失败：${category.error}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                text = "书源「$currentSourceName」连不上时，去 设置 →「图书」换一个书源再试",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { onOpenCategory(category.categoryId) }) { Text("重试") }
                        }
                    }
                } else {
                    items(category.books, key = { it.sourceKey + it.bookUrl }) { onlineBook ->
                        OnlineBookRow(onlineBook = onlineBook, onClick = { onOpenBook(onlineBook) })
                    }
                    if (category.loading && category.books.isNotEmpty()) {
                        item { StoreHint("加载中…", center = false) }
                    } else if (!category.hasNext && category.books.isNotEmpty()) {
                        item { StoreHint("没有更多了", center = true) }
                    } else if (category.hasNext) {
                        item {
                            TextButton(onClick = onCategoryNextPage, modifier = Modifier.fillMaxWidth()) {
                                Text("加载更多")
                            }
                        }
                    }
                }
            }
            // 搜索结果
            category.categoryId.isEmpty() && results == null && searchError == null && !searching ->
                item { StoreHint("输入关键词搜索，或点上方分类浏览", center = false) }
            searching -> item { StoreHint("搜索中…", center = true) }
            searchError != null -> item {
                Text(
                    text = "搜索失败：$searchError",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            results != null && results.isEmpty() -> item {
                StoreHint("没有搜到。换个关键词（作者名 / 题材词更容易命中）", center = true)
            }
            results != null -> items(results, key = { it.sourceKey + it.bookUrl }) { onlineBook ->
                OnlineBookRow(onlineBook = onlineBook, onClick = { onOpenBook(onlineBook) })
            }
        }
    }
}

@Composable
private fun StoreHint(text: String, center: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = if (center) Alignment.Center else Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OnlineBookRow(onlineBook: com.qzkt.timetable.data.book.OnlineBook, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
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
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 58.dp, height = 76.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.72f),
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (book.kind == "online" && book.cover.isNotEmpty()) {
                    AsyncImage(
                        model = book.cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = book.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when (book.kind) {
                        "online" -> book.author.ifBlank { "在线书籍" }
                        else -> "本地 TXT"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    text = progressText(book),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val progress = bookProgress(book)
                if (progress != null) {
                    Spacer(Modifier.height(5.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Close, contentDescription = "移出书架")
            }
        }
    }
}

private fun bookProgress(book: Book): Float? {
    val total = book.lastParagraphTotal
    if (total <= 0) return null
    val completed = if (book.kind == "online") book.lastIndex + 1 else book.lastIndex
    return (completed.toFloat() / total).coerceIn(0f, 1f)
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
