package com.qzkt.timetable.ui.account

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qzkt.timetable.data.AppSettings
import com.qzkt.timetable.data.TimetableSnapshot
import com.qzkt.timetable.model.TimeSlot
import com.qzkt.timetable.sync.SyncScheduler
import com.qzkt.timetable.ui.common.FirstMondayPickerDialog
import com.qzkt.timetable.ui.common.SectionCard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private val INTERVAL_OPTIONS = listOf(
    15 to "15 分钟",
    30 to "30 分钟",
    60 to "1 小时",
    180 to "3 小时",
    360 to "6 小时",
    720 to "12 小时",
    1440 to "24 小时",
)

private val REMIND_OPTIONS = listOf(5 to "5 分钟", 10 to "10 分钟", 15 to "15 分钟", 20 to "20 分钟", 30 to "30 分钟")

/**
 * 教务账号：连的是哪个学校、用的哪个学号、登录还有没有效。
 *
 * 这块内容原先挤在设置页最上面。挪到课表顶部栏右上角的头像入口之后，
 * 看课表时想确认「现在用的是哪个账号 / 哪次同步」不用再翻设置页。
 *
 * 课表相关的那组工具（学期 / 作息时间表 / 课表同步 / 上课提醒 / 数据）原先
 * 在课表顶栏单独的「课表工具」页里，现在并到账号信息下面 —— 顶栏少一个图标，
 * 这些本来就是围着这个账号转的配置。
 *
 * 首次使用（`configured == false`）时这页只留「配置教务账号」这一件事：
 * 还没连上学校，学期/同步/提醒这些设置摆出来只会让人迷惑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    settings: AppSettings,
    snapshot: TimetableSnapshot,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onClearTimetable: () -> Unit,
    onOpenDebug: () -> Unit,
    onBack: () -> Unit,
    /** 去首次配置向导（也用在「重新配置账号」）。 */
    onConfigure: () -> Unit,
) {
    var editingSlot by remember { mutableStateOf<TimeSlot?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

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
                if (!settings.configured) {
                    Text(
                        text = "还没有配置教务账号",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                InfoRow("学校地址", settings.baseUrl.ifBlank { "未配置" })
                InfoRow("学号", settings.username.ifBlank { "未配置" })
                if (settings.configured) {
                    InfoRow("登录方式", if (settings.useWebImport) "应用内登录（WebView）" else "账号密码")
                    if (settings.useWebImport) {
                        InfoRow("登录状态", if (settings.hasSession) "已登录" else "已失效，需要重新登录一次")
                        if (settings.hasSession && settings.sessionSavedAt > 0) {
                            InfoRow("登录时间", formatTime(settings.sessionSavedAt))
                        }
                    }
                }
            }

            if (settings.configured) {
                SectionCard("课表") {
                    InfoRow("学期", snapshot.xnxqh.ifBlank { "—" })
                    InfoRow("课程条数", snapshot.sessions.size.toString() + " 条")
                    InfoRow("上次同步", formatTime(snapshot.updatedAt))
                }
            }

            Button(
                onClick = onConfigure,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (settings.configured) "重新配置账号" else "配置教务账号") }

            Text(
                text = if (settings.configured) {
                    "重新配置只是回到配置向导改地址/学号，本地课表不会立刻删掉；" +
                        "换学校或换账号后，记得重新导入一次课表。"
                } else {
                    "填学校教务系统地址 + 学号，导入一次就会生成课表；" +
                        "配好之后这里会出现学期、同步、提醒这些设置。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // —— 课表工具（学期 / 作息 / 同步 / 提醒 / 数据）：配好账号才有意义 ——

            if (settings.configured) {
                TermSection(settings = settings, onUpdate = onUpdate)
                TimeTableSection(settings = settings, onEdit = { editingSlot = it })
                SyncSection(settings = settings, onUpdate = onUpdate)
                ReminderSection(settings = settings, onUpdate = onUpdate)
                DataSection(
                    onOpenDebug = onOpenDebug,
                    onClearTimetable = { confirmClear = true },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    editingSlot?.let { slot ->
        SlotEditDialog(
            slot = slot,
            onDismiss = { editingSlot = null },
            onSave = { updated ->
                onUpdate { current ->
                    current.copy(slots = current.slots.map { if (it.period == updated.period) updated else it })
                }
                editingSlot = null
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空课表？") },
            text = { Text("会删掉本地课表，账号配置保留。下次同步可以重新拉取。") },
            confirmButton = {
                TextButton(onClick = {
                    onClearTimetable()
                    confirmClear = false
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun TermSection(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    var firstMonday by remember(settings.firstMonday) { mutableStateOf(settings.firstMonday) }
    var weekCount by remember(settings.weekCount) { mutableStateOf(settings.weekCount.toString()) }
    var showPicker by remember { mutableStateOf(false) }

    SectionCard("学期") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = firstMonday,
                onValueChange = { firstMonday = it },
                label = { Text("第 1 周周一") },
                placeholder = { Text("2026-09-07") },
                singleLine = true,
                isError = firstMonday.isNotBlank() &&
                    runCatching { java.time.LocalDate.parse(firstMonday) }.isFailure,
                supportingText = { Text("课表上的具体日期全靠它") },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { showPicker = true }) { Text("选日期") }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = weekCount,
                onValueChange = { weekCount = it.filter { c -> c.isDigit() }.take(2) },
                label = { Text("学期周数") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(140.dp),
            )
            Spacer(Modifier.width(12.dp))
            TextButton(
                onClick = {
                    onUpdate {
                        it.copy(
                            firstMonday = firstMonday.trim(),
                            weekCount = weekCount.toIntOrNull()?.coerceIn(1, 40) ?: it.weekCount,
                        )
                    }
                },
            ) { Text("保存") }
        }
    }

    if (showPicker) {
        FirstMondayPickerDialog(
            current = firstMonday,
            onDismiss = { showPicker = false },
            onConfirm = { iso ->
                firstMonday = iso
                onUpdate { it.copy(firstMonday = iso) }
                showPicker = false
            },
        )
    }
}

@Composable
private fun TimeTableSection(settings: AppSettings, onEdit: (TimeSlot) -> Unit) {
    SectionCard("作息时间表") {
        Text(
            text = "点任意一节改时间。不同学校作息不同，这里的时长会影响「下一节课」和提醒。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        settings.slots.forEach { slot ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("第 ${slot.period} 节", modifier = Modifier.width(70.dp), fontSize = 13.sp)
                Text("${slot.start} - ${slot.end}", fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { onEdit(slot) }) { Text("修改") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun SyncSection(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit) {
    SectionCard("课表同步") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("后台定时同步", fontSize = 14.sp)
                Text(
                    text = "发现调课、停课、换教室时通知你",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.syncEnabled,
                onCheckedChange = { enabled -> onUpdate { it.copy(syncEnabled = enabled) } },
            )
        }

        Spacer(Modifier.height(10.dp))
        Text("同步间隔", fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))

        // 两行放不下就让它自然换行
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            INTERVAL_OPTIONS.chunked(4).forEach { rowOptions ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowOptions.forEach { (minutes, label) ->
                        FilterChip(
                            selected = settings.syncIntervalMinutes == minutes,
                            onClick = { onUpdate { it.copy(syncIntervalMinutes = minutes) } },
                            label = { Text(label, fontSize = 12.sp) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "系统限制后台任务最快 ${SyncScheduler.MIN_INTERVAL_MINUTES} 分钟一次；" +
                "手机省电策略可能在更晚的时间才执行。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReminderSection(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit) {
    SectionCard("上课提醒") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("提前提醒", fontSize = 14.sp)
                Text(
                    text = "下一节课开始前发通知",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.remindEnabled,
                onCheckedChange = { enabled -> onUpdate { it.copy(remindEnabled = enabled) } },
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            REMIND_OPTIONS.forEach { (minutes, label) ->
                FilterChip(
                    selected = settings.remindBeforeMinutes == minutes,
                    onClick = { onUpdate { it.copy(remindBeforeMinutes = minutes) } },
                    label = { Text(label, fontSize = 12.sp) },
                )
            }
        }
    }
}

@Composable
private fun DataSection(onOpenDebug: () -> Unit, onClearTimetable: () -> Unit) {
    SectionCard("数据") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("查看接口原始返回", fontSize = 14.sp)
                Text(
                    text = "课表解析不出来时，来这里看学校到底返回了什么",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
        TextButton(onClick = onOpenDebug) { Text("打开") }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onClearTimetable) { Text("清空课表数据", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun SlotEditDialog(slot: TimeSlot, onDismiss: () -> Unit, onSave: (TimeSlot) -> Unit) {
    var start by remember(slot) { mutableStateOf(slot.start) }
    var end by remember(slot) { mutableStateOf(slot.end) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("第 ${slot.period} 节时间") },
        text = {
            Column {
                OutlinedTextField(
                    value = start,
                    onValueChange = { start = it },
                    label = { Text("开始（HH:mm）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = end,
                    onValueChange = { end = it },
                    label = { Text("结束（HH:mm）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(slot.copy(start = start.trim(), end = end.trim())) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
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
