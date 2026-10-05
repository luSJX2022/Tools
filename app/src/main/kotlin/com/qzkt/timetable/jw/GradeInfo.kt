package com.qzkt.timetable.jw

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

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

/**
 * 解析成绩查询页的 HTML 表格（强智网页版 `/jsxsd/kscj/cjcx_query`）。
 *
 * 应用内登录那条路拿的是网页版会话，成绩页返回的是一整张 `<table id="dataList">`，
 * 不是 app.do 的 JSON，所以单独写这个解析器。
 *
 * 各校表头文字和列序不一（课程号/课程名/学分/成绩/课程性质/学年学期…），
 * 按表头文字映射列而不是写死列号；找不到「课程」列就当解析失败返回空。
 */
internal fun parseGradesHtml(html: String): List<GradeInfo> {
    val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return emptyList()
    val table = doc.select("table#dataList").first()
        ?: doc.select("table").firstOrNull { t ->
            t.select("th").any { it.text().replace(Regex("\\s"), "").contains("课程") }
        }
        ?: return emptyList()

    // 表头可能占两行（合并单元格），取 th 最多的一行当表头
    val headerRow = table.select("tr").maxByOrNull { it.select("th").size } ?: return emptyList()
    val headers = headerRow.select("th").map { it.text().replace(Regex("\\s"), "") }

    // 按别名的先后顺序找列：「课程名」优先于笼统的「课程」，免得命中「课程号」
    fun col(vararg aliases: String): Int? {
        for (alias in aliases) {
            val index = headers.indexOfFirst { it == alias || it.contains(alias) }
            if (index >= 0) return index
        }
        return null
    }
    val nameCol = col("课程名", "课程名称", "课程") ?: return emptyList()
    val scoreCol = col("成绩", "总评", "分数")
    val creditsCol = col("学分")
    val semesterCol = col("学年学期", "学期")
    val typeCol = col("课程性质", "性质", "类别")

    return table.select("tr")
        .filter { it.select("td").size >= 2 }
        .mapNotNull { row ->
            val cells = row.select("td")
            fun cell(index: Int?): String? =
                index?.let { cells.getOrNull(it)?.text()?.trim()?.ifBlank { null } }
            val courseName = cell(nameCol) ?: return@mapNotNull null
            GradeInfo(
                courseName = courseName,
                score = cell(scoreCol) ?: "",
                credits = cell(creditsCol),
                semester = cell(semesterCol),
                courseType = cell(typeCol),
            )
        }
        .filter { it.courseName.isNotBlank() }
}
