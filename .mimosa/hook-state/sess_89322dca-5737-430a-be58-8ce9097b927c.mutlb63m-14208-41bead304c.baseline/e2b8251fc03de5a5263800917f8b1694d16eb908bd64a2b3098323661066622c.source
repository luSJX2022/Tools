package com.qzkt.timetable.model

import kotlinx.serialization.Serializable

/**
 * 一节课（已按周次展开前的"教学任务"粒度）。
 *
 * 同一门课在不同星期 / 不同节次会是一条独立记录；[weeks] 保存它生效的所有周次。
 */
@Serializable
data class CourseSession(
    val id: String,
    val name: String,
    val teacher: String = "",
    val room: String = "",
    /** 1 = 周一 … 7 = 周日。 */
    val dayOfWeek: Int,
    /** 1 起，含。 */
    val startPeriod: Int,
    /** 含。 */
    val endPeriod: Int,
    /** 生效周次，升序去重。 */
    val weeks: List<Int>,
    /** 教学班名称，用于区分同名课程。 */
    val teachingClass: String = "",
    /** 教务系统返回的原始字段，便于排查各校字段差异。 */
    val raw: Map<String, String> = emptyMap(),
) {
    val periodSpan: String get() = if (startPeriod == endPeriod) "$startPeriod" else "$startPeriod-$endPeriod"

    companion object {
        /**
         * 业务主键：课程名 + 星期 + 节次 + 教学班。
         *
         * 刻意不含教室 / 教师 / 周次 —— 这三者变化时应该被识别为"同一节课被改动"，
         * 而不是删掉旧课再加一门新课。
         */
        fun makeId(name: String, dayOfWeek: Int, startPeriod: Int, endPeriod: Int, teachingClass: String): String {
            val source = "$name|$dayOfWeek|$startPeriod|$endPeriod|$teachingClass"
            return sha1Hex(source).take(16)
        }
    }
}

internal fun sha1Hex(text: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
