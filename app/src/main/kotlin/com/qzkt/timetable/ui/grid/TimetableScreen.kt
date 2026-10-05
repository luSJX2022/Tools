package com.qzkt.timetable.ui.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qzkt.timetable.data.AppSettings
import com.qzkt.timetable.data.TimetableSnapshot
import com.qzkt.timetable.model.CourseSession
import com.qzkt.timetable.ui.common.FirstMondayPickerDialog
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.absoluteValue

private val DAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")
private val ROW_HEIGHT = 58.dp
private val TIME_COLUMN_WIDTH = 34.dp
private val HAIRLINE = 0.5.dp

private val COURSE_COLORS = listOf(
    Color(0xFF1565C0), Color(0xFF00897B), Color(0xFFEF6C00), Color(0xFF6A1B9A),
    Color(0xFF2E7D32), Color(0xFFC62828), Color(0xFF00838F), Color(0xFF4527A0),
    Color(0xFFAD1457), Color(0xFF5D4037), Color(0xFF0277BD), Color(0xFF558B2F),
)

internal fun colorForCourse(name: String): Color =
    COURSE_COLORS[name.hashCode().absoluteValue % COURSE_COLORS.size]

/** 按"第一周周一"推算今天是第几教学周。 */
internal fun computeCurrentWeek(snapshot: TimetableSnapshot, today: LocalDate): Int {
    val monday = snapshot.firstMonday.takeIf { it.isNotBlank() }
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: return snapshot.currentWeek.coerceAtLeast(1)
    val days = ChronoUnit.DAYS.between(monday, today)
    return (Math.floorDiv(days, 7L) + 1).toInt().coerceIn(1, snapshot.weekCount.coerceAtLeast(1))
}

/**
 * 周视图课表。
 *
 * 左右滑动切换周次。同一门课的连堂（比如 1-2 节）用绝对定位在纵向合并成一个块 ——
 * 比逐格渲染更容易处理跨行，也不会出现格子边框被撑开的问题。
 *
 * 还没配置账号时这里不显示空课表，而是一块引导：告诉用户去哪儿配置 —— 首次使用
 * 不再一上来就弹配置向导（见 [com.qzkt.timetable.ui.QzktApp]）。
 *
 * 顶部栏右上角是「教务账号」入口：学期 / 作息时间表 / 课表同步 / 上课提醒 /
 * 数据那组工具并进了教务账号页（账号信息下面），看课表时顺手就能点到。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    snapshot: TimetableSnapshot,
    settings: AppSettings,
    displayWeek: Int,
    busy: Boolean,
    onWeekChange: (Int) -> Unit,
    onRefresh: () -> Unit,
    onSetFirstMonday: (String) -> Unit,
    onOpenAccount: () -> Unit = {},
) {
    val weekCount = snapshot.weekCount.coerceAtLeast(1)
    val today = remember { LocalDate.now() }
    val currentWeek = remember(snapshot, today) { computeCurrentWeek(snapshot, today) }
    var detail by remember { mutableStateOf<CourseSession?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(
        initialPage = (displayWeek - 1).coerceIn(0, weekCount - 1),
    ) { weekCount }

    // 外部改了周次（比如点了"回到本周"）时让 pager 跟上
    LaunchedEffect(displayWeek) {
        val target = (displayWeek - 1).coerceIn(0, weekCount - 1)
        if (pagerState.currentPage != target) pagerState.animateScrollToPage(target)
    }

    // 用户滑动后把周次同步回去
    LaunchedEffect(pagerState.currentPage) {
        onWeekChange(pagerState.currentPage + 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("第 $displayWeek 周", fontWeight = FontWeight.Bold)
                        WeekDateRange(snapshot.firstMonday, displayWeek)
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !busy) {
                        Icon(Icons.Default.Refresh, contentDescription = "立即同步")
                    }
                    IconButton(onClick = { onWeekChange(currentWeek) }) {
                        Icon(Icons.Default.Today, contentDescription = "回到本周")
                    }
                    IconButton(onClick = onOpenAccount) {
                        Icon(Icons.Default.AccountCircle, contentDescription = "教务账号")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!settings.configured) {
                FirstRunPanel(onConfigure = onOpenAccount)
            } else {
                if (snapshot.firstMonday.isBlank()) {
                    NoDateAnchorBanner(onPick = { showDatePicker = true })
                }

                if (settings.useWebImport && !settings.hasSession) {
                    Text(
                        text = "登录状态已失效（学校一般几小时后就会过期），课表暂时不会自动更新。点右上角的「教务账号」→「重新配置账号」，用应用内登录一次即可恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }

                WeekHeader(
                    snapshot = snapshot,
                    week = displayWeek,
                    currentWeek = currentWeek,
                    showWeekend = settings.showWeekend,
                )

                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    WeekGrid(
                        week = page + 1,
                        snapshot = snapshot,
                        settings = settings,
                        currentWeek = currentWeek,
                        today = today,
                        onCourseClick = { detail = it },
                    )
                }
            }
        }
    }

    detail?.let { session ->
        CourseDetailDialog(
            session = session,
            settings = settings,
            week = displayWeek,
            firstMonday = snapshot.firstMonday,
            onDismiss = { detail = null },
        )
    }

    if (showDatePicker) {
        FirstMondayPickerDialog(
            current = snapshot.firstMonday,
            onDismiss = { showDatePicker = false },
            onConfirm = { iso ->
                onSetFirstMonday(iso)
                showDatePicker = false
            },
        )
    }
}

@Composable
private fun FirstRunPanel(onConfigure: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.AccountCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text("还没有配置教务账号", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "填一次学校地址和学号，之后课表会自动导入；调课 / 停课 / 换教室也会通知你。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Button(onClick = onConfigure) { Text("去配置教务账号") }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "也可以点右上角的「教务账号」",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoDateAnchorBanner(onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "还不知道开学日期，课表上显示不出具体日期",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
        )
        TextButton(onClick = onPick) { Text("设置第 1 周周一") }
    }
}

@Composable
private fun WeekDateRange(firstMonday: String, week: Int) {
    if (firstMonday.isBlank()) return
    val monday = runCatching { LocalDate.parse(firstMonday) }.getOrNull() ?: return
    val start = monday.plusDays(((week - 1) * 7).toLong())
    val end = start.plusDays(6)
    Text(
        text = "${start.monthValue}月${start.dayOfMonth}日 - ${end.monthValue}月${end.dayOfMonth}日",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun WeekHeader(snapshot: TimetableSnapshot, week: Int, currentWeek: Int, showWeekend: Boolean) {
    val today = remember { LocalDate.now() }
    val monday = snapshot.firstMonday.takeIf { it.isNotBlank() }
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    // 关掉周末时表头只留周一到周五，和下面的网格列数保持一致
    val dayCount = if (showWeekend) 7 else 5

    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Spacer(modifier = Modifier.width(TIME_COLUMN_WIDTH))

        DAY_LABELS.take(dayCount).forEachIndexed { index, label ->
            val isToday = week == currentWeek && today.dayOfWeek.value == index + 1
            val date = monday?.plusDays(((week - 1) * 7 + index).toLong())
            val accent = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = label,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = accent,
                    fontSize = 13.sp,
                )
                if (date != null) {
                    Text(
                        text = "${date.monthValue}/${date.dayOfMonth}",
                        fontSize = 10.sp,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        color = if (isToday) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekGrid(
    week: Int,
    snapshot: TimetableSnapshot,
    settings: AppSettings,
    currentWeek: Int,
    today: LocalDate,
    onCourseClick: (CourseSession) -> Unit,
) {
    val maxPeriod = remember(snapshot) { maxOf(snapshot.sessions.maxOfOrNull { it.endPeriod } ?: 10, 10) }
    // 关掉周末时网格只排 5 列，落在周六周日的课不画（设置里随时能打开）
    val dayCount = if (settings.showWeekend) 7 else 5
    val inWeek = remember(snapshot, week, dayCount) {
        snapshot.sessions.filter { week in it.weeks && it.dayOfWeek <= dayCount }
    }
    val otherWeeks = remember(snapshot, week, settings.showOtherWeeks, dayCount) {
        // 只在别的周上、这一周不上的课灰显出来，便于看出单双周
        if (settings.showOtherWeeks) {
            snapshot.sessions.filter { week !in it.weeks && it.dayOfWeek <= dayCount }
        } else {
            emptyList()
        }
    }

    val scrollState = rememberScrollState()
    val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)

    Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val gridWidth: Dp = maxWidth - TIME_COLUMN_WIDTH
            val dayWidth: Dp = gridWidth / dayCount
            val gridHeight: Dp = ROW_HEIGHT * maxPeriod

            Row(modifier = Modifier.height(gridHeight)) {
                PeriodColumn(maxPeriod = maxPeriod, settings = settings)

                Box(modifier = Modifier.width(gridWidth).height(gridHeight)) {
                    // 横线（每节一条）
                    Column(modifier = Modifier.fillMaxSize()) {
                        repeat(maxPeriod) {
                            Box(
                                modifier = Modifier.height(ROW_HEIGHT).fillMaxWidth(),
                                contentAlignment = Alignment.BottomStart,
                            ) {
                                Box(modifier = Modifier.fillMaxWidth().height(HAIRLINE).background(lineColor))
                            }
                        }
                    }

                    // 今天所在列的高亮（周末被隐藏时今天落在周末就不画）
                    if (week == currentWeek && today.dayOfWeek.value <= dayCount) {
                        Box(
                            modifier = Modifier
                                .offset(x = dayWidth * (today.dayOfWeek.value - 1))
                                .width(dayWidth)
                                .height(gridHeight)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)),
                        )
                    }

                    // 竖线
                    Row(modifier = Modifier.width(gridWidth).height(gridHeight)) {
                        repeat(dayCount) {
                            Box(
                                modifier = Modifier.width(dayWidth).fillMaxHeight(),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Box(modifier = Modifier.width(HAIRLINE).fillMaxHeight().background(lineColor))
                            }
                        }
                    }

                    otherWeeks.forEach { session ->
                        CourseBlock(session = session, dayWidth = dayWidth, dimmed = true, onClick = {})
                    }

                    inWeek.forEach { session ->
                        CourseBlock(
                            session = session,
                            dayWidth = dayWidth,
                            dimmed = false,
                            onClick = { onCourseClick(session) },
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun PeriodColumn(maxPeriod: Int, settings: AppSettings) {
    Column(modifier = Modifier.width(TIME_COLUMN_WIDTH).fillMaxHeight()) {
        repeat(maxPeriod) { index ->
            val slot = settings.slotOf(index + 1)
            Column(
                modifier = Modifier.height(ROW_HEIGHT).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(text = "${index + 1}", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                if (slot != null) {
                    Text(
                        text = slot.start,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun CourseBlock(
    session: CourseSession,
    dayWidth: Dp,
    dimmed: Boolean,
    onClick: () -> Unit,
) {
    val span = (session.endPeriod - session.startPeriod + 1).coerceAtLeast(1)
    val baseColor = colorForCourse(session.name)
    // 非本周的课要"退到背景里"，但不能退成灰底灰字 —— 深色主题下 16% 透明度会糊成一团，
    // 看着像乱码。这里让底色保留得够清楚，再用主题里对比度足够的文字颜色。
    val background = if (dimmed) baseColor.copy(alpha = 0.34f) else baseColor
    val contentColor = if (dimmed) MaterialTheme.colorScheme.onSurface else Color.White

    Box(
        modifier = Modifier
            .offset(
                x = dayWidth * (session.dayOfWeek - 1) + 1.dp,
                y = ROW_HEIGHT * (session.startPeriod - 1) + 1.dp,
            )
            .width(dayWidth - 2.dp)
            .height(ROW_HEIGHT * span - 2.dp)
            .background(background, RoundedCornerShape(6.dp))
            .then(if (dimmed) Modifier else Modifier.clickable(onClick = onClick))
            .padding(horizontal = 3.dp, vertical = 3.dp),
    ) {
        Column {
            Text(
                text = session.name,
                color = contentColor,
                fontSize = 11.sp,
                // 三行只在跨 3 节以上时才给：两节的格子纵向刚好够放下
                // 「课程名 2 行 + 教室 2 行 + 教师 1 行」，再多就给不出余量了
                maxLines = if (span >= 3) 3 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (span >= 2) {
                if (session.room.isNotBlank()) {
                    Text(
                        text = "@${session.room}",
                        color = contentColor.copy(alpha = 0.92f),
                        fontSize = 9.sp,
                        // 7 列挤在手机宽度上，每列只有 40 多 dp，一行只放得下四五个字。
                        // 连堂的格子纵向有余量，让它换行，别截成"@教学…"等于没写。
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (session.teacher.isNotBlank()) {
                    Text(
                        text = session.teacher,
                        color = contentColor.copy(alpha = 0.92f),
                        fontSize = 9.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CourseDetailDialog(
    session: CourseSession,
    settings: AppSettings,
    week: Int,
    firstMonday: String,
    onDismiss: () -> Unit,
) {
    val startSlot = settings.slotOf(session.startPeriod)
    val endSlot = settings.slotOf(session.endPeriod)
    val timeText = if (startSlot != null && endSlot != null) {
        "${startSlot.start} - ${endSlot.end}"
    } else {
        "未配置作息时间"
    }

    // 这一周这一天具体是几月几号
    val dateText = firstMonday.takeIf { it.isNotBlank() }
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?.plusDays(((week - 1) * 7 + session.dayOfWeek - 1).toLong())
        ?.let { "（${it.monthValue}月${it.dayOfMonth}日）" }
        .orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(session.name, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                DetailRow(
                    "时间",
                    "周${DAY_LABELS.getOrElse(session.dayOfWeek - 1) { "?" }} 第 ${session.periodSpan} 节（$timeText）",
                )
                DetailRow("日期", "第 $week 周$dateText")
                DetailRow("周次", formatWeeks(session.weeks))
                DetailRow("教室", session.room.ifBlank { "未填" })
                DetailRow("教师", session.teacher.ifBlank { "未填" })
                if (session.teachingClass.isNotBlank()) DetailRow("教学班", session.teachingClass)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Text(text = value, fontSize = 13.sp)
    }
}

/** 把 `[1,2,3,5,6]` 压缩成 `1-3, 5-6 周`。 */
internal fun formatWeeks(weeks: List<Int>): String {
    if (weeks.isEmpty()) return "未指定"
    val sorted = weeks.distinct().sorted()
    val parts = mutableListOf<String>()
    var start = sorted.first()
    var prev = sorted.first()

    for (index in 1 until sorted.size) {
        val current = sorted[index]
        if (current == prev + 1) {
            prev = current
        } else {
            parts += if (start == prev) "$start" else "$start-$prev"
            start = current
            prev = current
        }
    }
    parts += if (start == prev) "$start" else "$start-$prev"
    return parts.joinToString(", ") + " 周"
}
