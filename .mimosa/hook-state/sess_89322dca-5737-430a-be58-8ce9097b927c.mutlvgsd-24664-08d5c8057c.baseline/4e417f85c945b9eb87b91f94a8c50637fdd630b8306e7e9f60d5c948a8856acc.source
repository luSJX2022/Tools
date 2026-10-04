package com.qzkt.timetable.data

import com.qzkt.timetable.model.CourseSession
import kotlinx.serialization.Serializable

/** 本地保存的完整课表快照。 */
@Serializable
data class TimetableSnapshot(
    /** 学年学期标识，如 `2026-2027-1`。 */
    val xnxqh: String = "",
    /** 第一周周一，`yyyy-MM-dd`；空串表示未知。 */
    val firstMonday: String = "",
    val weekCount: Int = 20,
    /**
     * 每条记录的 [CourseSession.weeks] 保存它生效的周次。
     *
     * 强智的课表接口按周返回，所以同步时是"逐周替换"：
     * 拉第 N 周时，先把所有记录里的第 N 周摘掉，再把这一周抓到的课并进去。
     * 这样即使只刷新了部分周次，其余周次的数据也保持不变。
     */
    val sessions: List<CourseSession> = emptyList(),
    val updatedAt: Long = 0L,
    /** 当前周次（上次同步时教务系统报告的）。 */
    val currentWeek: Int = 0,
) {
    val isEmpty: Boolean get() = sessions.isEmpty()

    /** 本学期所有出现过的周次，升序。 */
    fun weekRange(): IntRange {
        val max = sessions.maxOfOrNull { it.weeks.maxOrNull() ?: 0 } ?: 0
        return 1..maxOf(weekCount, max, 1)
    }
}
