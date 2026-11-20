package com.qzkt.timetable.jw

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 教务系统里的一条考试安排。
 *
 * 不同学校的考试页字段名和排版都不一样，这里只保留能稳定拿到的几项：
 * 课程名 / 课程代码 / 考试时间 / 地点 / 座位 / 形式。时间同时存「原始文案」和解析结果，
 * 解析不出来时界面照样能靠原文案显示，不至于整条丢掉。
 */
data class ExamInfo(
    val courseName: String,
    val courseCode: String? = null,
    /** 原始时间文案，如「2026-11-21 星期六 19:00~21:00」。 */
    val timeText: String = "",
    val date: LocalDate? = null,
    /** 开考 / 结束时刻，当天 0 点起的分钟数；解析不出来为 null。 */
    val startMinute: Int? = null,
    val endMinute: Int? = null,
    val location: String? = null,
    /** 座位号。 */
    val seat: String? = null,
    /** 考试形式：闭卷 / 开卷 / 机考 / 论文… */
    val form: String? = null,
    val note: String? = null,
) {
    /** 排序键：有日期的排前面，同一天按开考时间（没有日期的排到最后）。 */
    val sortKey: Long
        get() = (date?.toEpochDay() ?: MISSING_DATE_EPOCH_DAY) * 1440 + (startMinute ?: 0)

    val startText: String? get() = startMinute?.let(::formatMinute)

    /** `19:00~21:00`；只有开考时间时就只有 `19:00`。 */
    val timeRangeText: String?
        get() {
            val start = startText ?: return null
            val end = endMinute?.let(::formatMinute)
            return if (end == null) start else "$start~$end"
        }
}

/** 没有日期时用的纪元日，比任何真实日期都大（排序用，不会溢出）。 */
private const val MISSING_DATE_EPOCH_DAY = 1_000_000L

internal fun formatMinute(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

/** 距离考试还有几天：「今天 / 明天 / N 天后 / 已结束」。 */
fun examDayLabel(date: LocalDate?, today: LocalDate = LocalDate.now()): String? {
    if (date == null) return null
    val days = ChronoUnit.DAYS.between(today, date).toInt()
    return when {
        days == 0 -> "今天"
        days == 1 -> "明天"
        days == 2 -> "后天"
        days > 2 -> "$days 天后"
        days == -1 -> "昨天"
        else -> "${-days} 天前"
    }
}

/** 日期 + 时刻的解析结果，字段都可能为 null。 */
internal data class ParsedExamTime(
    val date: LocalDate? = null,
    val startMinute: Int? = null,
    val endMinute: Int? = null,
)

/** `2026-11-21` / `2026/11/21` / `2026.11.21` / `2026年11月21日`。 */
private val DATE_PATTERN = Regex("(\\d{4})\\s*[-/.年]\\s*(\\d{1,2})\\s*[-/.月]\\s*(\\d{1,2})")

/** `19:00~21:00` / `19:00-21:00` / `19:00—21:00` / `19:00 至 21:00`。 */
private val TIME_RANGE_PATTERN =
    Regex("(\\d{1,2})\\s*[:：]\\s*(\\d{2})\\s*(?:[-~～—－–至到]|\\s{1,3})\\s*(\\d{1,2})\\s*[:：]\\s*(\\d{2})")

/** 只有开考时间：`19:00`。 */
private val TIME_SINGLE_PATTERN = Regex("(\\d{1,2})\\s*[:：]\\s*(\\d{2})")

/**
 * 从「考试时间」这一格里拆出日期和起止时刻。
 *
 * 强智各种版本的写法混在一起：`2026-11-21 星期六 19:00~21:00`、
 * `2026年11月21日(星期六)19:00-21:00`、单独一列 `19:00-21:00`。
 * 解析不出来不报错，交给界面显示原文案。
 */
internal fun parseExamTime(raw: String): ParsedExamTime {
    val text = raw.trim()
    if (text.isEmpty()) return ParsedExamTime()

    val date = DATE_PATTERN.find(text)?.let { match ->
        val (year, month, day) = match.destructured
        runCatching { LocalDate.of(year.toInt(), month.toInt(), day.toInt()) }.getOrNull()
    }

    val range = TIME_RANGE_PATTERN.find(text)
    if (range != null) {
        val (h1, m1, h2, m2) = range.destructured
        val start = runCatching { h1.toInt() * 60 + m1.toInt() }.getOrNull()
        val end = runCatching { h2.toInt() * 60 + m2.toInt() }.getOrNull()
        if (start != null && start in 0..1439) {
            return ParsedExamTime(date, start, end?.takeIf { it in 0..1439 && it > start })
        }
    }

    val single = TIME_SINGLE_PATTERN.find(text)?.let { match ->
        val (h, m) = match.destructured
        runCatching { h.toInt() * 60 + m.toInt() }.getOrNull()?.takeIf { it in 0..1439 }
    }
    return ParsedExamTime(date, single, null)
}

/**
 * 解析「考试安排」页的 HTML（强智网页版 `/jsxsd/xsks/xsksap_list` 这一类）。
 *
 * 各校表头文字和列序不一（序号 / 考试时间 / 考试地点 / 考试课程 / 考试形式…），
 * 按**表头文字**映射列而不是写死列号；页面上往往还有菜单、查询条件等别的表格，
 * 所以逐张表试，能解析出考试的才用。没有表头（纯单元格排版）时退回按行猜。
 */
internal fun parseExamsHtml(html: String): List<ExamInfo> {
    val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return emptyList()
    for (table in doc.select("table")) {
        val exams = runCatching { parseExamTable(table) }.getOrNull().orEmpty()
        if (exams.isNotEmpty()) return exams
    }
    return emptyList()
}

private fun parseExamTable(table: Element): List<ExamInfo> {
    // 表头可能占两行（合并单元格），取 th 最多的那一行
    val headerRow = table.select("tr").maxByOrNull { it.select("th").size }
    val headers = headerRow?.select("th")?.map { normalizeHeader(it.text()) }.orEmpty()
    if (headers.isEmpty()) return parseLooseExamRows(table)

    fun headerIndex(predicate: (String) -> Boolean): Int? =
        headers.indexOfFirst(predicate).takeIf { it >= 0 }

    /** 先找完全相等的表头，再找包含关系的，避免「课程」抢到「课程代码」。 */
    fun col(vararg aliases: String): Int? {
        aliases.forEach { alias ->
            headerIndex { it == alias }?.let { return it }
        }
        aliases.forEach { alias ->
            headerIndex { it.contains(alias) }?.let { return it }
        }
        return null
    }

    val nameCol = headerIndex { header ->
        (header.contains("考试课程") || header.contains("课程名称") || header.contains("课程名") ||
            header.contains("科目名称") || header == "科目" || header == "课程") &&
            !header.contains("代码") && !header.contains("编号") && !header.contains("课程号")
    } ?: return emptyList()

    val codeCol = col("课程代码", "课程编号", "课程号", "代码", "编号")
    // 日期和时间可能是两列（「考试日期」+「考试时间」），也可能挤在一列里
    val dateCol = headerIndex { it.contains("日期") && !it.contains("时间") }
        ?: col("考试日期", "日期")
    val timeCol = col("考试时间", "时间")
    val placeCol = col("考试地点", "考试教室", "考试场地", "考场", "地点", "教室")
    val seatCol = col("座位号", "座位")
    val formCol = col("考试形式", "考核方式", "考试方式", "形式", "方式")
    val noteCol = col("备注", "说明", "注意事项")

    return table.select("tr").mapNotNull { row ->
        val cells = row.select("td")
        if (cells.size < 2) return@mapNotNull null
        fun cell(index: Int?): String? =
            index?.let { cells.getOrNull(it)?.text()?.trim() }?.takeIf { it.isNotBlank() }

        val courseName = cell(nameCol)?.takeIf { it != "考试课程" && it != "课程名称" } ?: return@mapNotNull null
        val timeText = listOfNotNull(
            dateCol?.takeIf { it != timeCol }?.let { cell(it) },
            cell(timeCol),
        ).joinToString(" ")
        val parsed = parseExamTime(timeText)
        ExamInfo(
            courseName = courseName,
            courseCode = cell(codeCol),
            timeText = timeText,
            date = parsed.date,
            startMinute = parsed.startMinute,
            endMinute = parsed.endMinute,
            location = cell(placeCol),
            seat = cell(seatCol),
            form = cell(formCol),
            note = cell(noteCol),
        )
    }
}

/**
 * 没表头时的兜底：每行里找带日期 / 时间的那一格当考试时间，
 * 头一个像课程名的中文格子当课程名，带「楼 / 教室 / 考场 / #」的当地点。
 */
private fun parseLooseExamRows(table: Element): List<ExamInfo> {
    val result = mutableListOf<ExamInfo>()
    for (row in table.select("tr")) {
        val cells = row.select("td").map { it.text().trim() }.filter { it.isNotEmpty() }
        if (cells.size < 2) continue

        val timeIndex = cells.indexOfFirst { cell ->
            DATE_PATTERN.containsMatchIn(cell) || TIME_RANGE_PATTERN.containsMatchIn(cell)
        }
        if (timeIndex < 0) continue

        val others = cells.filterIndexed { index, _ -> index != timeIndex }
        val candidates = others.filter { cell ->
            cell.length >= 2 &&
                !DATE_PATTERN.containsMatchIn(cell) &&
                !TIME_RANGE_PATTERN.containsMatchIn(cell) &&
                !cell.matches(SEQUENCE_NUMBER_PATTERN)
        }
        // 课程名一般不含「楼 / 教室 / 考场 / 校区」，优先挑不像地点的
        val courseName = candidates.firstOrNull { !LOCATION_HINT_PATTERN.containsMatchIn(it) }
            ?: candidates.firstOrNull()
            ?: continue
        val location = others.firstOrNull { cell ->
            cell != courseName && LOCATION_HINT_PATTERN.containsMatchIn(cell)
        }

        val timeText = cells[timeIndex]
        val parsed = parseExamTime(timeText)
        result += ExamInfo(
            courseName = courseName,
            timeText = timeText,
            date = parsed.date,
            startMinute = parsed.startMinute,
            endMinute = parsed.endMinute,
            location = location,
        )
    }
    return result
}

/** 序号格：纯数字或「1、」这种。 */
private val SEQUENCE_NUMBER_PATTERN = Regex("^[\\d\\s.、,，]+$")

/** 地点的常见特征：3#A220 / 教学楼 / 考场 / 青岛校区。 */
private val LOCATION_HINT_PATTERN = Regex("(楼|教室|考场|校区|#|馆|室)")

private fun normalizeHeader(text: String): String =
    text.replace(Regex("[\\s\\u00a0]+"), "")
