package com.qzkt.timetable.model

import kotlinx.serialization.Serializable

/** 学期信息。 */
@Serializable
data class Term(
    /** 学年学期标识，如 `2026-2027-1`。 */
    val xnxqh: String,
    /** 第一周周一的日期，`yyyy-MM-dd`。 */
    val startDate: String,
    /** 总周数。 */
    val weekCount: Int = 20,
    /** 教务系统当前教学周。 */
    val currentWeek: Int = 1,
) {
    /** 把"第 N 周星期 D"换算成真实日期（`yyyy-MM-dd`）。 */
    fun dateOf(week: Int, dayOfWeek: Int): String {
        val base = runCatching { java.time.LocalDate.parse(startDate) }.getOrNull() ?: return ""
        val offset = (week - 1) * 7L + (dayOfWeek - 1)
        return base.plusDays(offset).toString()
    }
}

/** 一天内的第 N 节课的时间。 */
@Serializable
data class TimeSlot(
    val period: Int,
    val start: String,
    val end: String,
)
