package com.qzkt.timetable.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qzkt.timetable.data.AppSettings
import com.qzkt.timetable.data.TimetableSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * 教务账号：连的是哪个学校、用的哪个学号、登录还有没有效。
 *
 * 这块内容原先挤在设置页最上面。挪到课表顶部栏右上角的头像入口之后，
 * 看课表时想确认「现在用的是哪个账号 / 哪次同步」不用再翻设置页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    settings: AppSettings,
    snapshot: TimetableSnapshot,
    onBack: () -> Unit,
    onReconfigure: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("教务账号") },
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
            SectionCard("账号") {
                InfoRow("学校地址", settings.baseUrl.ifBlank { "未配置" })
                InfoRow("学号", settings.username.ifBlank { "未配置" })
                InfoRow("登录方式", if (settings.useWebImport) "应用内登录（WebView）" else "账号密码")
                if (settings.useWebImport) {
                    InfoRow("登录状态", if (settings.hasSession) "已登录" else "已失效，需要重新登录一次")
                    if (settings.hasSession && settings.sessionSavedAt > 0) {
                        InfoRow("登录时间", formatTime(settings.sessionSavedAt))
                    }
                }
            }

            SectionCard("课表") {
                InfoRow("学期", snapshot.xnxqh.ifBlank { "—" })
                InfoRow("课程条数", snapshot.sessions.size.toString() + " 条")
                InfoRow("上次同步", formatTime(snapshot.updatedAt))
            }

            OutlinedButton(
                onClick = onReconfigure,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("重新配置账号") }

            Text(
                text = "重新配置只是回到首次配置向导改地址/学号，本地课表不会立刻删掉；" +
                    "换学校或换账号后，记得重新导入一次课表。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp),
        )
        Text(text = value, fontSize = 13.sp)
    }
}

/** 时间戳转本地时间；0 表示还没同步过。 */
private fun formatTime(epochMillis: Long): String =
    if (epochMillis <= 0) {
        "还没有同步过"
    } else {
        TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
    }
