package com.qzkt.timetable.model

import kotlinx.serialization.Serializable

/** 一次同步发现的课表变化。 */
@Serializable
data class SyncChange(
    val kind: Kind,
    val courseName: String,
    val dayOfWeek: Int,
    val periodSpan: String,
    val detail: String,
    val at: Long = System.currentTimeMillis(),
) {
    enum class Kind {
        /** 新增课程。 */
        ADDED,

        /** 删除课程（停课 / 退课）。 */
        REMOVED,

        /** 教室、教师或周次发生变化。 */
        MODIFIED,

        /** 同一门课的星期 / 节次变了（调课）。 */
        MOVED,
    }
}

/** 一次同步的结果。 */
@Serializable
data class SyncReport(
    val success: Boolean,
    val message: String,
    val totalSessions: Int = 0,
    val changes: List<SyncChange> = emptyList(),
    val at: Long = System.currentTimeMillis(),
)
