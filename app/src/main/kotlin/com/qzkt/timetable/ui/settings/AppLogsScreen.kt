package com.qzkt.timetable.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qzkt.timetable.data.AppLogStore
import com.qzkt.timetable.data.LogFilters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val store = remember(appContext) { AppLogStore(appContext) }
    val scope = rememberCoroutineScope()
    var content by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            try {
                content = withContext(Dispatchers.IO) { store.read() }
            } catch (e: Exception) {
                error = "读取日志失败：${e.message ?: e.javaClass.simpleName}"
            } finally {
                loading = false
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        appContext.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                            it.write(content)
                        } ?: throw IllegalStateException("无法创建导出文件")
                    }
                } catch (e: Exception) {
                    error = "导出失败：${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("应用日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(ClipData.newPlainText("应用日志", content))
                            copied = true
                        },
                        enabled = content.isNotBlank(),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = if (copied) "已复制" else "复制日志")
                    }
                    IconButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "应用运行日志")
                                putExtra(Intent.EXTRA_TEXT, content)
                            }
                            context.startActivity(Intent.createChooser(intent, "分享应用日志"))
                        },
                        enabled = content.isNotBlank(),
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "分享日志")
                    }
                    IconButton(
                        onClick = { confirmClear = true },
                        enabled = content.isNotBlank(),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "清空日志")
                    }
                },
            )
        },
    ) { padding ->
        when {
            loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            content.isBlank() && error == null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "暂无应用日志",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> Column(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                TextButton(
                    onClick = { exportLauncher.launch("Tools-运行日志.txt") },
                    enabled = content.isNotBlank(),
                    modifier = Modifier.align(Alignment.End).padding(horizontal = 12.dp),
                ) { Text("导出") }
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                val lines = remember(content) { content.lines().filter(String::isNotBlank) }
                // 按插件筛：插件日志的 tag 就是插件名（行格式「时间 级别/插件名: 内容」）
                // 按「整条日志」展示：崩溃堆栈等续行跟着首行走，不会再被插件筛选切断
                val entries = remember(lines) { LogFilters.entries(lines) }
                val pluginTags = remember(lines) { LogFilters.tagsIn(lines) }
                var selectedTag by remember { mutableStateOf<String?>(null) }
                val shown = remember(entries, selectedTag) {
                    entries.filter { entry -> selectedTag == null || entry.tag == selectedTag }
                }
                if (pluginTags.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = selectedTag == null,
                            onClick = { selectedTag = null },
                            label = { Text("全部 " + entries.size) },
                        )
                        pluginTags.forEach { tag ->
                            FilterChip(
                                selected = selectedTag == tag,
                                onClick = { selectedTag = tag },
                                label = { Text(tag + " " + entries.count { it.tag == tag }) },
                            )
                        }
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(shown) { entry ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            Text(
                                text = entry.text,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空应用日志？") },
            text = { Text("清空后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { store.clear() }
                            refresh()
                        } catch (e: Exception) {
                            error = "清空失败：${e.message ?: e.javaClass.simpleName}"
                        }
                    }
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}
