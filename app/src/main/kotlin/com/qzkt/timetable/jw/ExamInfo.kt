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

/** 强智的学年学期值，例如 `2025-2026-1`。 */
private val TERM_VALUE = Regex("""\d{4}-\d{4}-[12]""")

/**
 * 从教务页面里抠出可用的学年学期（`2025-2026-1` 这种）。
 *
 * 强智的考试列表页要带 `xnxq01id`（学年学期）才会出表格，不带时页面只有查询条件和空表，
 * 所以先抓查询页里 `<select>` 的选项，页面里没有 select 时再从原文里找。
 * 返回顺序：先 selected 的那项，再按页面顺序；去重。
 */
internal fun extractExamTermOptions(html: String): List<String> {
    if (html.isBlank()) return emptyList()
    val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return emptyList()
    val selected = LinkedHashSet<String>()
    val others = LinkedHashSet<String>()

    doc.select("select").forEach { select ->
        val hint = (select.attr("name") + " " + select.attr("id")).lowercase()
        val likelyTerm = hint.contains("xnxq") || hint.contains("xnxqid") || hint.contains("term")
        select.select("option").forEach { option ->
            val value = option.attr("value").trim()
            if (!TERM_VALUE.matches(value)) return@forEach
            if (option.hasAttr("selected") || likelyTerm && value == select.attr("value")) selected += value
            others += value
        }
    }

    if (selected.isEmpty() && others.isEmpty()) {
        TERM_VALUE.findAll(html).map { it.value }.forEach { others += it }
    }
    return (selected + others).toList()
}

/**
 * 页面是不是「操作过于频繁，已被注销登录」那种限流提示页。
 *
 * 强智被高频请求时的固定响应：alert('温馨提示：您操作系统过于频繁，已被注销登录，
 * 正常使用请勿高频操作！'); top.location.href='.../tpass'。
 * 命中后必须停止后续请求，否则会一直被注销。
 */
internal fun isRateLimitedHtml(html: String): Boolean {
    if (html.isBlank()) return false
    val squashed = html.replace(Regex("""\s+"""), "")
    return squashed.contains("操作过于频繁") ||
        squashed.contains("已被注销登录") ||
        squashed.contains("请勿高频操作")
}

/**
 * 判断一页 HTML 是不是教务的登录页。
 *
 * 不能只做字符串包含：强智的页面里常嵌着登录弹窗/脚本片段，含 `loginForm` 字样但其实是正常页面，
 * 只按字符串判断会把「已登录」误判成「登录失效」。所以要求**真的有登录表单元素**，
 * 并且页面里解析不出考试表（登录页不可能带考试表）。
 */
internal fun isLoginPageHtml(html: String): Boolean {
    if (html.isBlank()) return false
    val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return false
    val hasLoginForm = doc.selectFirst("form#loginForm, input#userAccount, input#userPassword") != null
    return hasLoginForm && parseExamsHtml(html).isEmpty()
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
    // 最后兜底：有的强智把考试列表渲染成 div/li 而不是 table，全文扫一遍
    return parseExamBlocks(doc)
}

/**
 * 解析失败时的诊断摘要：告诉用户/开发者「页面长什么样、卡在哪一步」。
 *
 * 线上教训：只把前 400 字原样打出来时，看到的永远是 DOCTYPE 和 head，
 * 真正有用的信息（有没有表格、表格几行几列、表头是什么）全被截掉了。
 */
internal fun describeExamPage(html: String): String {
    if (html.isBlank()) return "页面为空"
    val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return "HTML 解析失败"
    val title = doc.title().trim().take(40)
    val tables = doc.select("table")
    val parts = mutableListOf<String>()
    parts += "标题=" + title.ifEmpty { "(无)" }
    parts += "表格数=" + tables.size
    tables.take(3).forEachIndexed { index, table ->
        val rows = table.select("tr")
        val firstCells = rows.firstOrNull()?.select("td, th")?.map { it.text().trim().take(8) }.orEmpty()
        parts += "表" + (index + 1) + "(" + rows.size + "行/首行" + firstCells.size + "格:" +
            firstCells.take(6).joinToString("|") + ")"
    }
    if (tables.isEmpty()) {
        parts += "正文前80字=" + doc.text().replace(Regex("""\s+"""), " ").take(80)
    }
    return parts.joinToString("；")
}

/**
 * 文档级兜底解析：不依赖 table 结构，找「同一块里既有可解析的日期/时间、又有像课程名的文字」。
 *
 * 规则刻意从严（必须有年份日期或完整时间段），否则菜单、查询条件会被误当成考试。
 */
internal fun parseExamBlocks(doc: org.jsoup.nodes.Document): List<ExamInfo> {
    val result = mutableListOf<ExamInfo>()
    val seen = HashSet<String>()

    // 候选块：tr / li / div 里文本量适中、且含日期或时间段
    val candidates = doc.select("tr, li, div.NS_Table, div.list, div.row")
    for (block in candidates) {
        val text = block.text().trim()
        if (text.length !in 8..400) continue
        if (!DATE_PATTERN.containsMatchIn(text) && !TIME_RANGE_PATTERN.containsMatchIn(text)) continue
        // 只取最内层：内部子块已经有候选就跳过，避免同一场考试被抓多次
        if (block.select("tr, li").isNotEmpty()) continue

        val parsed = parseExamTime(text)
        val name = pickCourseName(text) ?: continue
        val key = name + "|" + text
        if (!seen.add(key)) continue
        result += ExamInfo(
            courseName = name,
            timeText = text.take(120),
            date = parsed.date,
            startMinute = parsed.startMinute,
            endMinute = parsed.endMinute,
            location = pickLocation(text),
        )
    }
    return result
}

/** 从一段文字里挑最像课程名的片段：去掉日期时间、地点、序号后的第一个较长中文串。 */
private fun pickCourseName(text: String): String? {
    val cleaned = text
        .replace(DATE_PATTERN, " ")
        .replace(TIME_RANGE_PATTERN, " ")
        .replace(TIME_SINGLE_PATTERN, " ")
    val pieces = cleaned.split(Regex("""[\s,，;；:：|]+"""))
        .map { it.trim() }
        .filter { it.length >= 2 }
        .filter { !it.matches(SEQUENCE_NUMBER_PATTERN) }
        .filter { !LOCATION_HINT_PATTERN.containsMatchIn(it) }
        .filter { !looksLikeExamHeader(it) }
    return pieces.firstOrNull()
}

/** 从一段文字里挑地点：带「楼/教室/考场/校区/#」的片段。 */
private fun pickLocation(text: String): String? =
    text.split(Regex("""[\s,，;；|]+"""))
        .map { it.trim() }
        .firstOrNull { it.length >= 2 && LOCATION_HINT_PATTERN.containsMatchIn(it) && !looksLikeExamHeader(it) }

private fun parseExamTable(table: Element): List<ExamInfo> {
    // 表头可能占两行（合并单元格），取 th 最多的那一行
    val thRow = table.select("tr").maxByOrNull { it.select("th").size }
    var headers = thRow?.select("th")?.map { normalizeHeader(it.text()) }.orEmpty()

    // 强智很多页面用 <td> 当表头（整行都是 td、没有 th），这时把「第一行」当表头试试，
    // 只有它确实像表头（含课程/时间/地点之类的词）才采用，否则退回按行猜。
    if (headers.isEmpty()) {
        val firstRow = table.select("tr").firstOrNull()
        val firstCells = firstRow?.select("td")?.map { normalizeHeader(it.text()) }.orEmpty()
        if (firstCells.size >= 2 && firstCells.count { looksLikeExamHeader(it) } >= 2) {
            headers = firstCells
        }
    }
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
            header.contains("科目名称") || header.contains("考试科目") || header.contains("考试名称") ||
            header == "科目" || header == "课程") &&
            !header.contains("代码") && !header.contains("编号") && !header.contains("课程号")
    } ?: headerIndex { header -> header.contains("课程") && !header.contains("代码") }
        ?: return emptyList()

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
        // 如果这一行就是被当成表头的那一行，跳过（否则会把「考试课程/考试时间」当数据）
        if (row === thRow && headers.isNotEmpty()) return@mapNotNull null
        if (cells.count { looksLikeExamHeader(normalizeHeader(it.text())) } >= 2) return@mapNotNull null
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

/**
 * 这一格像不像考试表的表头。
 *
 * 强智的考试页经常整行用 td 当表头（没有 th），所以需要判断「第一行是不是表头」。
 * 只认考试场景里真会出现的词，避免把普通数据行误当表头。
 */
internal fun looksLikeExamHeader(cell: String): Boolean {
    val t = cell.replace(Regex("[\\s\\u00a0]+"), "")
    if (t.isEmpty() || t.length > 12) return false
    val hints = listOf(
        "课程", "科目", "考试时间", "时间", "日期", "地点", "教室", "考场", "场地",
        "座位", "形式", "方式", "备注", "序号", "考核", "学分", "班级",
    )
    return hints.any { t.contains(it) }
}
