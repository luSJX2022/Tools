package com.qzkt.timetable.ui.academic

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qzkt.timetable.jw.ExamInfo
import com.qzkt.timetable.jw.examDayLabel
import java.time.LocalDate

/**
 * 教务页里的「考试安排」页签。
 *
 * 数据来自教务系统的考试安排页（走应用内登录留下的会话），
 * 有日期的按时间排，「今天 / 明天 / N 天后」直接标在卡片上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamsScreen(
    viewModel: ExamsViewModel,
    onBack: () -> Unit,
    /** 「该去应用内登录」类错误的出口：直接跳进 WebView 登录页。 */
    onOpenWebLogin: () -> Unit = {},
    onOpenDrawer: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val errorMsg = state.error
    var showSnippet by remember { mutableStateOf(false) }

    // 第一次进来直接查一次：用户点进「考试」就是想看安排的，不用再点一次按钮
    LaunchedEffect(Unit) {
        if (!state.hasLoaded && !state.loading) viewModel.refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("考试安排") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.hasLoaded) {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "重新查询")
                        }
                    }
                    onOpenDrawer?.let { openDrawer ->
                        IconButton(onClick = openDrawer) {
                            Icon(Icons.Default.Menu, contentDescription = "打开侧边菜单")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                errorMsg != null -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = errorMsg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    if (state.needsRelogin) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "在页面里登录一次，之后考试安排就能直接查了。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(onClick = onOpenWebLogin) { Text("去应用内登录") }
                    }
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { viewModel.refresh() }) { Text("重试") }
                }

                !state.hasLoaded -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "还没有查询过考试安排",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { viewModel.refresh() }) { Text("查询考试安排") }
                }

                state.exams.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "这学期还没有考试安排",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { viewModel.refresh() }) { Text("重新查询") }
                    if (state.pageSnippet.isNotBlank()) {
                        TextButton(onClick = { showSnippet = true }) { Text("查看教务返回的页面") }
                    }
                }

                else -> ExamsContent(exams = state.exams)
            }
        }
    }

    if (showSnippet) {
        AlertDialog(
            onDismissRequest = { showSnippet = false },
            title = { Text("教务返回的页面（前 400 字）") },
            text = {
                Text(
                    text = state.pageSnippet,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { showSnippet = false }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun ExamsContent(exams: List<ExamInfo>) {
    val today = LocalDate.now()
    // 没解析出日期的也放在「待考」里，宁可多显示也不漏考
    val upcoming = exams.filter { it.date == null || !it.date.isBefore(today) }
    val finished = exams.filter { it.date != null && it.date.isBefore(today) }
    val next = upcoming.firstOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp, 8.dp, 14.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(
                        text = "共 ${exams.size} 场，待考 ${upcoming.size} 场",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (next != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = buildString {
                                append("下一场：")
                                append(next.courseName)
                                next.date?.let { date ->
                                    append(" · ")
                                    append(shortDate(date))
                                    examDayLabel(date, today)?.let { label -> append("（$label）") }
                                }
                                next.timeRangeText?.let { time -> append(" ").append(time) }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }

        if (upcoming.isNotEmpty()) {
            item(key = "header_upcoming") { SectionHeader("待考") }
            itemsIndexed(upcoming, key = { index, _ -> "upcoming_$index" }) { _, exam ->
                ExamCard(exam = exam, today = today, upcoming = true)
            }
        }

        if (finished.isNotEmpty()) {
            item(key = "header_finished") { SectionHeader("已结束") }
            itemsIndexed(finished, key = { index, _ -> "finished_$index" }) { _, exam ->
                ExamCard(exam = exam, today = today, upcoming = false)
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun ExamCard(exam: ExamInfo, today: LocalDate, upcoming: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        colors = CardDefaults.cardColors(
            containerColor = if (upcoming) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = exam.courseName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (upcoming) {
                    val label = exam.date?.let { examDayLabel(it, today) }
                    if (label != null) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }

            exam.courseCode?.let { code ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "课程代码：$code",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            InfoRow(
                icon = Icons.Default.Schedule,
                text = buildString {
                    val date = exam.date
                    if (date != null) {
                        append(shortDate(date))
                        append(" ")
                        append(weekdayText(date))
                    }
                    val time = exam.timeRangeText
                    if (time != null) {
                        if (isNotEmpty()) append(" ")
                        append(time)
                    }
                    if (isEmpty()) append(exam.timeText.ifBlank { "时间未定" })
                },
            )
            InfoRow(icon = Icons.Default.Place, text = exam.location ?: "地点未定")
            exam.seat?.let { InfoRow(icon = Icons.Default.EventSeat, text = "座位 $it") }
            exam.form?.let { InfoRow(icon = Icons.Default.Info, text = it) }
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier.padding(top = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private fun weekdayText(date: LocalDate): String = WEEKDAYS[date.dayOfWeek.value - 1]

private fun shortDate(date: LocalDate): String = "${date.monthValue}月${date.dayOfMonth}日"
