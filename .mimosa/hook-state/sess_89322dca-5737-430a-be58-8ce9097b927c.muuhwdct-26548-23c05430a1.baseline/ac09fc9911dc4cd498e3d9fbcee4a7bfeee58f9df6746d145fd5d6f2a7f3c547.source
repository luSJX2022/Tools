package com.qzkt.timetable.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qzkt.timetable.model.SyncChange
import com.qzkt.timetable.sync.dayLabel
import com.qzkt.timetable.sync.label
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * 课表变动日志：每次同步发现的新增/停课/调课都记在这里。
 *
 * 原先是底部页签，现在从课表顶部栏右上角的图标进来，所以顶栏要带返回。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangesScreen(changes: List<SyncChange>, onClear: () -> Unit, onBack: () -> Unit = {}) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("课表变动") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (changes.isNotEmpty()) {
                        IconButton(onClick = onClear) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "清空日志")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (changes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有变动记录", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "定时同步发现课程新增、停课或换教室时会记在这里",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(changes) { change -> ChangeCard(change) }
        }
    }
}

@Composable
private fun ChangeCard(change: SyncChange) {
    val accent = when (change.kind) {
        SyncChange.Kind.ADDED -> Color(0xFF2E7D32)
        SyncChange.Kind.REMOVED -> Color(0xFFC62828)
        SyncChange.Kind.MOVED -> Color(0xFFEF6C00)
        SyncChange.Kind.MODIFIED -> Color(0xFF1565C0)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            Box(modifier = Modifier.width(4.dp).height(46.dp).background(accent, RoundedCornerShape(2.dp)))

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = change.kind.label(),
                        color = accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(text = change.courseName, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "周${change.dayLabel()} 第 ${change.periodSpan} 节 · ${change.detail}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = Instant.ofEpochMilli(change.at).atZone(ZoneId.systemDefault()).format(TIME_FORMAT),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
