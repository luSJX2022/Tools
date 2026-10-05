package com.qzkt.timetable.jw

import org.json.JSONArray
import org.json.JSONObject

/** 教务系统返回的一条成绩记录。 */
data class GradeInfo(
    /** 课程名称。 */
    val courseName: String,
    /** 成绩（可能是数字、等级制或文字）。 */
    val score: String,
    /** 学分（字符串，解析由 UI 做）。 */
    val credits: String? = null,
    /** 学年学期（如 2025-2026-1）。 */
    val semester: String? = null,
    /** 课程性质（必修 / 选修 / …）。 */
    val courseType: String? = null,
)

/**
 * 解析成绩 JSON（强智 app.do `method=getKccjCx`）。
 *
 * 不同学校的字段名不一样，用别名表兜底；成绩可能是数字、等级制或文字。
 * 顶层可能是数组，也可能包在 `data` / `items` 键里。
 */
internal fun parseGrades(json: String): List<GradeInfo> {
    val trimmed = json.trim()
    val array = when {
        trimmed.startsWith("[") -> JSONArray(trimmed)
        trimmed.startsWith("{") -> {
            val obj = JSONObject(trimmed)
            // 有些学校包一层 {"code":0,"data":[...]}
            val nested = obj.optJSONArray("data")
                ?: obj.optJSONArray("items")
                ?: obj.optJSONArray("list")
                ?: return emptyList()
            nested
        }
        else -> return emptyList()
    }
    return (0 until array.length()).mapNotNull { index ->
        val obj = array.optJSONObject(index) ?: return@mapNotNull null
        parseGradeItem(obj)
    }.filter { it.courseName.isNotBlank() }
}

/** 字段别名：不同学校的键名不一样，按优先级取第一个非空的。 */
private val ALIASES_COURSE_NAME = listOf("kcmc", "courseName", "kc", "course_name")
private val ALIASES_SCORE = listOf("cj", "zcj", "bfzcj", "score", "cj1", "grade")
private val ALIASES_CREDITS = listOf("xf", "xuefen", "credit", "credits")
private val ALIASES_SEMESTER = listOf("xnxq", "xn", "xq", "semester", "xnxqmc")
private val ALIASES_COURSE_TYPE = listOf("kcxz", "kclb", "courseType", "course_type")

private fun pick(obj: JSONObject, aliases: List<String>): String? =
    aliases.firstNotNullOfOrNull { key -> obj.stringOrNull(key) }

private fun JSONObject.stringOrNull(key: String): String? {
    val v = optString(key).trim()
    return v.ifBlank { null }
}

private fun parseGradeItem(obj: JSONObject): GradeInfo? {
    val courseName = pick(obj, ALIASES_COURSE_NAME) ?: return null
    val score = pick(obj, ALIASES_SCORE) ?: ""
    val credits = pick(obj, ALIASES_CREDITS)
    val semester = pick(obj, ALIASES_SEMESTER)
    val courseType = pick(obj, ALIASES_COURSE_TYPE)
    return GradeInfo(
        courseName = courseName,
        score = score,
        credits = credits,
        semester = semester,
        courseType = courseType,
    )
}
