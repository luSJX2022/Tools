package com.qzkt.timetable.ui.academic

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qzkt.timetable.data.xsxk.XsxkRound
import com.qzkt.timetable.ui.CourseRoundsUiState

/**
 * 选课页（原生）：列出学生选课中心里的选课轮次。
 *
 * 数据来自学校的选课中心页面（会话 cookie 直拉，会话过期自动登录续上）；
 * 没有开放轮次时显示空态（和学校页面上的「未查询到数据」一致）。
 * 点某一轮的「进入选课」跳系统浏览器 —— 那一步是学校自己的重 JS 页面，
 * 在外部浏览器里登录一次就是长期登录态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseSelectScreen(
    state: CourseRoundsUiState,
    onRefresh: () -> Unit,
    onSaveEntryUrl: (String) -> Unit,
    onBack: () -> Unit,
    onOpenWebLogin: () -> Unit = {},
) {
    val context = LocalContext.current

    fun openInBrowser(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选课") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                state.error?.let { error ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.needsRelogin) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.needsRelogin) {
                                Button(onClick = onOpenWebLogin) { Text("去应用内登录") }
                            }
                            OutlinedButton(onClick = onRefresh) { Text("重试") }
                        }
                        // 入口地址未知时给一个手动粘贴的地方（电脑上右键菜单项复制链接）
                        if (state.needsEntryUrl) {
                            Spacer(Modifier.height(20.dp))
                            ManualEntryRow(onSave = onSaveEntryUrl)
                        }
                    }
                }

                if (state.error == null) {
                    if (state.rounds.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = "当前没有开放的选课轮次",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "学校开放选课后，轮次会显示在这里",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp, 8.dp, 14.dp, 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(state.rounds, key = { it.name + it.term }) { round ->
                                RoundCard(
                                    round = round,
                                    onEnter = { round.entryUrl?.let { openInBrowser(it) } },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundCard(round: XsxkRound, onEnter: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(round.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                val meta = listOf(round.term, round.time).filter { it.isNotBlank() }
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = meta.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (round.entryUrl != null) {
                Button(onClick = onEnter) { Text("进入选课") }
            }
        }
    }
}

/** 手动粘贴选课中心网址（电脑上右键菜单里的「学生选课中心」→ 复制链接地址）。 */
@Composable
private fun ManualEntryRow(onSave: (String) -> Unit) {
    var manualUrl by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "也可以手动设置选课入口：在学校电脑上右键左侧菜单的「学生选课中心」→ 复制链接地址，粘贴到这里：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = manualUrl,
                onValueChange = { manualUrl = it },
                placeholder = { Text("https://…", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val url = manualUrl.trim()
                    if (url.isNotBlank()) onSave(url)
                },
            ) { Text("保存") }
        }
    }
}
