package com.qzkt.timetable.ui.common

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 选「第 1 周周一」。
 *
 * 课表要显示具体日期就必须有这个锚点：知道第 1 周从哪天开始，才能算出每一周每一天是几月几号。
 * 用户随便点一天也行，这里会**自动归到那一周的周一**——否则他填了周三，
 * 整个学期的日期都会偏两天。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirstMondayPickerDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val initial = current.takeIf { it.isNotBlank() }
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: LocalDate.now()

    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onConfirm(mondayOf(picked).toString())
                    }
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    ) {
        DatePicker(
            state = state,
            title = { Text("选择第 1 周的周一") },
            headline = { Text("选这一周里的任意一天，会自动归到周一") },
        )
    }
}

/** 取这一周的周一。 */
internal fun mondayOf(date: LocalDate): LocalDate =
    date.minusDays((date.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
