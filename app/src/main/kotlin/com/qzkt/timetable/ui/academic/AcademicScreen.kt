package com.qzkt.timetable.ui.academic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Grading
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * 教务页：课表、成绩、选课的聚合入口，底部页签切换。
 *
 * 三个子页面各自带 Scaffold 和状态逻辑，由外部以槽位传入；
 * 这里用 SaveableStateHolder 保活，切页签不丢课表的周次选择等状态。
 */
@Composable
fun AcademicScreen(
    timetableContent: @Composable () -> Unit,
    gradesContent: @Composable () -> Unit,
    courseSelectContent: @Composable () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val stateHolder = rememberSaveableStateHolder()

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            when (tab) {
                0 -> stateHolder.SaveableStateProvider("academic_timetable") { timetableContent() }
                1 -> stateHolder.SaveableStateProvider("academic_grades") { gradesContent() }
                else -> stateHolder.SaveableStateProvider("academic_course_select") { courseSelectContent() }
            }
        }
        NavigationBar {
            NavigationBarItem(
                selected = tab == 0,
                onClick = { tab = 0 },
                icon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                label = { Text("课表") },
            )
            NavigationBarItem(
                selected = tab == 1,
                onClick = { tab = 1 },
                icon = { Icon(Icons.Default.Grading, contentDescription = null) },
                label = { Text("成绩") },
            )
            NavigationBarItem(
                selected = tab == 2,
                onClick = { tab = 2 },
                icon = { Icon(Icons.Default.HowToReg, contentDescription = null) },
                label = { Text("选课") },
            )
        }
    }
}
