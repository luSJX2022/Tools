package com.qzkt.timetable.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Grading
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qzkt.timetable.data.AppSettings
import com.qzkt.timetable.data.TimetableSnapshot
import com.qzkt.timetable.ui.grid.computeCurrentWeek
import java.time.LocalDate

/** 工具页一个入口的全部展示信息：key 对应用 AppSettings 里的 toolOrder / hiddenToolKeys。 */
private class ToolSpec(
    val key: String,
    val icon: ImageVector,
    val title: String,
    val subtitle: () -> String,
    val onClick: () -> Unit,
)

/**
 * 工具页：整个 App 的出发点。
 *
 * 课表和播放器原先各占一个底部页签，现在收进这里变成两个入口 ——
 * 底部页签只剩「工具」和「设置」，打开 App 先看到工具列表，再进具体功能。
 *
 * 右上角「编辑」进入编辑模式：不用进设置就能改主题样式、
 * 隐藏用不到的入口、上下调整入口顺序（写回 AppSettings，与设置页共享）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    snapshot: TimetableSnapshot,
    animeFavoriteCount: Int = 0,
    /** 还没配置教务账号时，课表入口直接说清楚要先去配置。 */
    configured: Boolean = true,
    /** 被用户在编辑模式（或「设置 → 个性化」）里藏起来的入口 key。 */
    hiddenEntries: Set<String> = emptySet(),
    /** 入口排列顺序（AppSettings.toolOrder，空表示默认顺序）。 */
    entryOrder: List<String> = emptyList(),
    themeMode: String = "SYSTEM",
    dynamicColor: Boolean = true,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit = {},
    onOpenTimetable: () -> Unit,
    onOpenResolve: () -> Unit = {},
    onOpenBooks: () -> Unit = {},
    onOpenGrades: () -> Unit = {},
    onOpenAnime: () -> Unit = {},
) {
    val today = remember { LocalDate.now() }
    val currentWeek = remember(snapshot, today) { computeCurrentWeek(snapshot, today) }

    var editMode by rememberSaveable { mutableStateOf(false) }
    val order = remember(entryOrder) { AppSettings.displayToolOrder(entryOrder) }

    val specs = remember(snapshot, currentWeek, configured, animeFavoriteCount) {
        listOf(
            ToolSpec(
                key = "timetable",
                icon = Icons.Default.CalendarMonth,
                title = "课表",
                subtitle = {
                    when {
                        !configured -> "还没配置教务账号，点进去配置"
                        snapshot.firstMonday.isBlank() -> "还没设置开学日期，点进去补上"
                        else -> {
                            val weeks = if (snapshot.weekCount > 0) " · 共 ${snapshot.weekCount} 周" else ""
                            "第 $currentWeek 周$weeks"
                        }
                    }
                },
                onClick = onOpenTimetable,
            ),
            ToolSpec(
                key = "book",
                icon = Icons.AutoMirrored.Filled.MenuBook,
                title = "图书",
                subtitle = { "本地 TXT 小说：书架、进度记忆、夜间模式" },
                onClick = onOpenBooks,
            ),
            ToolSpec(
                key = "resolve",
                icon = Icons.Default.Link,
                title = "链接解析",
                subtitle = { "B站 / 抖音分享链接，解析后直接播放" },
                onClick = onOpenResolve,
            ),
            ToolSpec(
                key = "anime",
                icon = Icons.Default.Movie,
                title = "影视",
                subtitle = {
                    if (animeFavoriteCount > 0) {
                        "收藏 $animeFavoriteCount 部 · 电影、剧集、动漫都能搜能看"
                    } else {
                        "电影、剧集、动漫，搜索、收藏和在线播放"
                    }
                },
                onClick = onOpenAnime,
            ),
            ToolSpec(
                key = "grades",
                icon = Icons.Default.Grading,
                title = "成绩",
                subtitle = { "教务系统成绩和学分查询" },
                onClick = onOpenGrades,
            ),
        ).associateBy { it.key }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editMode) "编辑首页" else "工具") },
                actions = {
                    IconButton(onClick = { editMode = !editMode }) {
                        Icon(
                            if (editMode) Icons.Default.Close else Icons.Default.Edit,
                            contentDescription = if (editMode) "退出编辑" else "编辑首页",
                            tint = if (editMode) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
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
            if (editMode) {
                ThemeEditCard(
                    themeMode = themeMode,
                    dynamicColor = dynamicColor,
                    onUpdate = onUpdate,
                )
                order.forEachIndexed { index, key ->
                    val spec = specs[key] ?: return@forEachIndexed
                    EntryEditRow(
                        spec = spec,
                        hidden = spec.key in hiddenEntries,
                        canUp = index > 0,
                        canDown = index < order.lastIndex,
                        onMove = { delta ->
                            val swapWith = index + delta
                            if (swapWith in order.indices) {
                                val newOrder = order.toMutableList().apply {
                                    val tmp = this[index]
                                    this[index] = this[swapWith]
                                    this[swapWith] = tmp
                                }
                                onUpdate { it.copy(toolOrder = newOrder) }
                            }
                        },
                        onToggleHidden = { hide ->
                            onUpdate {
                                it.copy(
                                    hiddenToolKeys = if (hide) {
                                        it.hiddenToolKeys + spec.key
                                    } else {
                                        it.hiddenToolKeys - spec.key
                                    },
                                )
                            }
                        },
                    )
                }
                Text(
                    text = "眼睛图标隐藏入口（设置里也能改），箭头调整顺序，" +
                        "改主题样式立即生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            } else {
                order.forEach { key ->
                    val spec = specs[key] ?: return@forEach
                    if (spec.key !in hiddenEntries) {
                        ToolEntry(
                            icon = spec.icon,
                            title = spec.title,
                            subtitle = spec.subtitle(),
                            onClick = spec.onClick,
                        )
                    }
                }
                if (hiddenEntries.isNotEmpty()) {
                    Text(
                        text = "部分入口已隐藏，点右上角编辑可以恢复",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ToolEntry(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 编辑模式顶部的主题卡片：跟随系统 / 浅色 / 深色 + 动态取色，与设置页同一份数据。 */
@Composable
private fun ThemeEditCard(
    themeMode: String,
    dynamicColor: Boolean,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("主题样式", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("SYSTEM" to "跟随系统", "LIGHT" to "浅色", "DARK" to "深色").forEach { (value, label) ->
                    FilterChip(
                        selected = themeMode == value,
                        onClick = { onUpdate { it.copy(themeMode = value) } },
                        label = { Text(label, fontSize = 12.sp) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("动态取色", fontSize = 14.sp)
                    Text(
                        "用系统壁纸配色（Android 12+）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = dynamicColor,
                    onCheckedChange = { on -> onUpdate { it.copy(dynamicColor = on) } },
                )
            }
        }
    }
}

/** 编辑模式里的入口行：上下箭头调顺序，眼睛图标隐藏/恢复。 */
@Composable
private fun EntryEditRow(
    spec: ToolSpec,
    hidden: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    onMove: (Int) -> Unit,
    onToggleHidden: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (hidden) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                spec.icon,
                contentDescription = null,
                tint = if (hidden) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    spec.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = if (hidden) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    spec.subtitle(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onMove(-1) }, enabled = canUp && !hidden) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = "上移",
                    tint = if (canUp && !hidden) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                    },
                )
            }
            IconButton(onClick = { onMove(1) }, enabled = canDown && !hidden) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "下移",
                    tint = if (canDown && !hidden) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                    },
                )
            }
            IconButton(onClick = { onToggleHidden(!hidden) }) {
                Icon(
                    if (hidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (hidden) "恢复显示" else "隐藏",
                    tint = if (hidden) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
    }
}
