package com.qzkt.timetable.jw.qz

import com.qzkt.timetable.jw.parse.DayOfWeekParser
import com.qzkt.timetable.jw.parse.KbcxParseResult
import com.qzkt.timetable.jw.parse.PeriodParser
import com.qzkt.timetable.jw.parse.WeekRangeParser
import com.qzkt.timetable.model.CourseSession
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 从强智 **网页版**课表页面里解析课程。
 *
 * 这是 `app.do` 移动端接口用不了时的兜底通道：在应用内的 WebView 里正常登录教务系统，
 * 打开课表页后把页面 HTML 交给这里解析。
 *
 * 强智网页版各校排版不同，但主体都是"一张大表格，列是星期，行是节次"，
 * 格子内容用 `<br>` 或嵌套 div 分行。所以按这个结构做通用解析：
 * 先建一张单元格占位矩阵（正确处理 colspan/rowspan，否则合并单元格会让列错位），
 * 再逐格解析；表格结构对不上时退回"一行一门课"的列表式解析。
 */
object QzWebParser {

    private val ROOM_MARKERS = listOf('楼', '室', '馆', '区', '教', '厅')
    private val DAY_HEADER = Regex("(星期|周)[一二三四五六日天]")
    private val PERSON_NAME = Regex("^[\\u4e00-\\u9fa5]{2,4}([,，、][\\u4e00-\\u9fa5]{2,4})*$")

    fun parse(html: String, termWeekCount: Int = 20): KbcxParseResult {
        val doc = runCatching { Jsoup.parse(html) }.getOrNull()
            ?: return KbcxParseResult(emptyList(), listOf("页面内容无法解析"), emptyList())

        val tables = doc.select("table").sortedByDescending { it.select("tr").size * it.select("td,th").size }
        if (tables.isEmpty()) {
            return KbcxParseResult(emptyList(), listOf("页面里没有表格，确认一下是不是已经打开了课表页面"), emptyList())
        }

        val warnings = mutableListOf<String>()
        for (table in tables) {
            val sessions = parseGridTable(table, termWeekCount, warnings)
            if (sessions.isNotEmpty()) return KbcxParseResult(mergeSessions(sessions), warnings, emptyList())
        }
        for (table in tables) {
            val sessions = parseListTable(table, termWeekCount, warnings)
            if (sessions.isNotEmpty()) return KbcxParseResult(mergeSessions(sessions), warnings, emptyList())
        }

        warnings += "没能从页面里识别出课表。可能这所学校的课表不是表格排版，或者还没进到课表页面。"
        return KbcxParseResult(emptyList(), warnings, emptyList())
    }

    /**
     * 解析网页导入抓回来的一批文档（顶层页面 + 各个同源 iframe），合并结果。
     *
     * **必须一个一个分开解析，不能把它们拼成一份 HTML。** 强智登录之后的主界面基本
     * 都是 `<frameset>`（左边菜单、右边内容），而 HTML 解析器一旦遇到 `<frameset>`
     * 就进入 frameset 模式，后面拼接上来的内容会被整段丢掉 —— 结果就是"抓取成功但一门课都没有"。
     */
    fun parseDocuments(documents: List<String>, termWeekCount: Int = 20): KbcxParseResult {
        val results = documents.filter { it.isNotBlank() }.map { parse(it, termWeekCount) }
        // 注意：这里不能先 distinctBy —— 同一门课会有多条记录（简版没教师、详版才有），
        // 先按 id 去重会把带教师的那条丢掉，要交给 mergeSessions 合并字段
        val sessions = results.flatMap { it.sessions }

        val warnings = if (sessions.isEmpty()) {
            results.flatMap { it.warnings }.distinct().ifEmpty { listOf("抓回来的页面是空的") }
        } else {
            // 已经有课了，其他 frame 解析不出来就不必再打扰用户
            results.flatMap { it.warnings }
                .filterNot { it.contains("没有表格") || it.contains("没能从页面里识别出课表") }
                .distinct()
        }

        return KbcxParseResult(mergeSessions(sessions), warnings, results.flatMap { it.rawItems })
    }

    // ------------------------------------------------------------------ 网格排版

    /** 一个单元格在表格里的实际位置与跨度。 */
    private data class CellInfo(
        val cell: Element,
        val row: Int,
        val col: Int,
        val rowSpan: Int,
        val colSpan: Int,
    )

    private fun key(row: Int, col: Int): Long = row.toLong() * 10_000L + col

    /** 建立占位矩阵：把每个单元格填到它覆盖的所有 (行, 列) 上。 */
    private fun buildMatrix(rows: List<Element>): Pair<Map<Long, CellInfo>, Int> {
        val matrix = HashMap<Long, CellInfo>()
        var maxCol = 0

        rows.forEachIndexed { rowIndex, row ->
            var col = 0
            row.select("td,th").forEach { cell ->
                while (matrix.containsKey(key(rowIndex, col))) col++

                val rowSpan = cell.attr("rowspan").toIntOrNull()?.coerceAtLeast(1) ?: 1
                val colSpan = cell.attr("colspan").toIntOrNull()?.coerceAtLeast(1) ?: 1
                val info = CellInfo(cell, rowIndex, col, rowSpan, colSpan)

                for (dr in 0 until rowSpan) {
                    for (dc in 0 until colSpan) {
                        matrix[key(rowIndex + dr, col + dc)] = info
                    }
                }
                col += colSpan
                maxCol = maxOf(maxCol, col)
            }
        }
        return matrix to maxCol
    }

    /** 经典排版：列 = 星期，行 = 节次。 */
    private fun parseGridTable(
        table: Element,
        weekCount: Int,
        warnings: MutableList<String>,
    ): List<CourseSession> {
        val rows = table.select("tr")
        if (rows.size < 2) return emptyList()

        val (matrix, maxCol) = buildMatrix(rows)

        // 表头行：前 3 行里"星期"字样最多的那一行
        val headerRow = (0..minOf(2, rows.size - 1)).maxByOrNull { rowIndex ->
            (0 until maxCol).count { col ->
                val info = matrix[key(rowIndex, col)]
                info != null && info.row == rowIndex && DAY_HEADER.containsMatchIn(info.cell.text())
            }
        } ?: return emptyList()

        val dayByColumn = mutableMapOf<Int, Int>()
        for (col in 0 until maxCol) {
            val info = matrix[key(headerRow, col)] ?: continue
            if (info.row != headerRow) continue
            val day = DayOfWeekParser.parse(info.cell.text()) ?: continue
            if (DAY_HEADER.containsMatchIn(info.cell.text())) {
                for (dc in 0 until info.colSpan) dayByColumn[col + dc] = day
            }
        }
        if (dayByColumn.size < 5) return emptyList()

        val periodColumns = (0 until maxCol).filter { it !in dayByColumn }

        // 每一行对应第几节：优先读左侧节次格的文字；被 rowspan 覆盖到的行沿用上一行的区间，
        // 都读不出来时按"表头下面第几行"推算。
        val periodAt = arrayOfNulls<IntRange>(rows.size)
        var carried: IntRange? = null
        for (rowIndex in rows.indices) {
            var parsed: IntRange? = null
            for (col in periodColumns) {
                val info = matrix[key(rowIndex, col)] ?: continue
                if (info.row != rowIndex) continue
                parsed = cellLines(info.cell).firstOrNull()?.let { PeriodParser.parse(it) }
                    ?: PeriodParser.parse(info.cell.text())
                if (parsed != null) break
            }
            if (parsed != null) carried = parsed
            periodAt[rowIndex] = parsed
                ?: carried
                ?: (rowIndex - headerRow).takeIf { it >= 1 }?.let { it..it }
        }

        val sessions = mutableListOf<CourseSession>()
        for (rowIndex in rows.indices) {
            if (rowIndex <= headerRow) continue

            for (col in 0 until maxCol) {
                val info = matrix[key(rowIndex, col)] ?: continue
                // 每个单元格只在它自己的左上角处理一次
                if (info.row != rowIndex || info.col != col) continue

                val day = dayByColumn[col] ?: continue
                val lines = cellLines(info.cell)
                if (lines.isEmpty()) continue

                // 格子里明确写了 `[01-02节]` 就以它为准：学校会把同一个课程块
                // 在它覆盖的每一行都渲染一遍，只看行位置会把一门两节的课拆成两条
                val explicit = extractPeriodRange(lines)
                val startPeriod = explicit?.first ?: periodAt[rowIndex]?.first ?: (rowIndex - headerRow)
                val endPeriod = explicit?.last ?: maxOf(
                    periodAt[rowIndex]?.last ?: startPeriod,
                    startPeriod + info.rowSpan - 1,
                )

                // 优先按页面自带的字段标注解析（强智 jsxsd 模板），拿不到再退回文字推断
                val titled = WebParserTitled.buildSessions(
                    cell = info.cell,
                    day = day,
                    startPeriod = startPeriod,
                    endPeriod = endPeriod,
                    weekCount = weekCount,
                )
                sessions += titled.ifEmpty {
                    buildSessions(
                        lines = lines,
                        day = day,
                        startPeriod = startPeriod,
                        endPeriod = endPeriod,
                        weekCount = weekCount,
                        warnings = warnings,
                    )
                }
            }
        }
        return sessions
    }

    // ------------------------------------------------------------------ 列表排版

    /** 兜底排版：一行一门课，课程信息都在同一行里。 */
    private fun parseListTable(
        table: Element,
        weekCount: Int,
        warnings: MutableList<String>,
    ): List<CourseSession> {
        val sessions = mutableListOf<CourseSession>()

        table.select("tr").forEach { row ->
            val cells = row.select("td,th")
            if (cells.size < 3) return@forEach

            // 认星期要避开"第 5 节"这种同时能被当成星期的格子：
            // 优先看带"星期/周X"字样的，其次才接受纯数字且不像是节次的
            val dayCell = cells.firstOrNull { DAY_HEADER.containsMatchIn(it.text()) }
                ?: cells.firstOrNull { cell ->
                    val text = cell.text().trim()
                    DayOfWeekParser.parse(text) != null && PeriodParser.parse(text) == null
                }
            val day = dayCell?.let { DayOfWeekParser.parse(it.text()) } ?: return@forEach

            val periodCandidates = cells.filter { it !== dayCell && !DAY_HEADER.containsMatchIn(it.text()) }
            val period = pickPeriod(periodCandidates) ?: return@forEach

            val contentCell = cells.maxByOrNull { it.text().length } ?: return@forEach
            val lines = cellLines(contentCell)
            if (lines.isEmpty()) return@forEach

            sessions += buildSessions(
                lines = lines,
                day = day,
                startPeriod = period.first,
                endPeriod = period.last,
                weekCount = weekCount,
                warnings = warnings,
            )
        }
        return sessions
    }

    /**
     * 从一行里挑出"节次"那一格。
     *
     * 不能简单地取第一个能解析成数字的格子：列表式表格的第一列常常是序号，
     * "1" 会被当成第 1 节，整门课就挪到错误的节次上了。所以按可信度依次退让。
     */
    private fun pickPeriod(candidates: List<Element>): IntRange? {
        // ① 带"节"字的，最明确
        candidates.firstNotNullOfOrNull { cell ->
            cellLines(cell).firstOrNull { it.contains('节') }?.let { PeriodParser.parse(it) }
        }?.let { return it }

        // ② 形如 0102 / 1-2 / 1,2 的，大概率就是节次而不是序号
        candidates.firstNotNullOfOrNull { cell ->
            val first = cellLines(cell).firstOrNull().orEmpty()
            val structured = Regex("^\\d{4}$").matches(first) || first.contains('-') || first.contains(',')
            if (structured) PeriodParser.parse(first) else null
        }?.let { return it }

        // ③ 实在没有，就跳过第一格（通常是序号列）再找
        return candidates.drop(1).firstNotNullOfOrNull { cell ->
            cellLines(cell).firstOrNull()?.let { PeriodParser.parse(it) }
        }
    }

    // ------------------------------------------------------------------ 单元格内容

    /** 把单元格拆成一行行文本，兼容 `<br>` 与嵌套 div。 */
    private fun cellLines(cell: Element): List<String> {
        val clone = cell.clone()
        clone.select("br").append("\\n")
        return clone.text()
            .replace("\\n", "\n")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * 一个单元格里可能塞了多门课（同一时段有冲突课时会这样），
     * 用"读到第二个周次描述"作为下一门课的开始。
     */
    private fun buildSessions(
        lines: List<String>,
        day: Int,
        startPeriod: Int,
        endPeriod: Int,
        weekCount: Int,
        warnings: MutableList<String>,
    ): List<CourseSession> {
        // 先把"通知单编号 / 班级 / 备注 / 学时"这类排课元数据剔掉 ——
        // 学校把它们和课程信息塞在同一个格子里，不滤掉就会被当成课程名
        val content = lines.filterNot { isScheduleMetadata(it) }
        if (content.isEmpty()) return emptyList()

        val groups = mutableListOf<MutableList<String>>()
        content.forEach { line ->
            val last = groups.lastOrNull()
            val weekLineOfLast = last?.firstOrNull { looksLikeWeeks(it) }
            val lastHasWeeks = weekLineOfLast != null
            // 强智的格子按「课程名 / 教师 / 教室 / 周次」排列，周次是这一门课的结尾；
            // 所以一条已经有周次的组，遇到新内容就说明下一门课开始了。
            // 例外有两个：周次后面的"单周/双周"限定词，以及排序不同的学校把教室放在周次之后。
            when {
                last == null -> groups += mutableListOf(line)
                !lastHasWeeks -> last += line
                isParityOnly(line) -> last += line
                looksLikeRoom(line, weekLineOfLast) -> last += line
                else -> groups += mutableListOf(line)
            }
        }
        val end = endPeriod.coerceAtLeast(startPeriod)

        return groups.mapNotNull { group ->
            // 强智格子里自下而上的顺序一般是：课程名 / 教师 / 教室 / 周次，
            // 所以先按"含楼室馆"认出教室，剩下的第一行中文就是课程名，再往后是教师。
            val weekLine = group.firstOrNull { looksLikeWeeks(it) }
            val room = group.firstOrNull { looksLikeRoom(it, weekLine) }
            val name = group.firstOrNull { line ->
                line != weekLine && line != room && line.any { c -> c in '\u4e00'..'\u9fa5' }
            } ?: return@mapNotNull null
            val teacher = group.firstOrNull { line ->
                line != weekLine && line != room && line != name && PERSON_NAME.matches(line)
            }

            val weekSpec = weekLine?.let { WeekRangeParser.parse(it, group.joinToString(" ")) }
            val weeks = weekSpec?.weeks?.takeIf { it.isNotEmpty() }
                ?: (1..weekCount.coerceAtLeast(1)).toList().also {
                    warnings += "「$name」没读到周次，已按第 1-$weekCount 周处理"
                }

            CourseSession(
                id = CourseSession.makeId(name, day, startPeriod, end, ""),
                name = name,
                teacher = teacher.orEmpty(),
                room = room.orEmpty(),
                dayOfWeek = day,
                startPeriod = startPeriod,
                endPeriod = end,
                weeks = weeks.distinct().sorted(),
                teachingClass = "",
                raw = mapOf("source" to "web", "lines" to group.joinToString(" / ")),
            )
        }
    }

    private fun looksLikeWeeks(line: String): Boolean {
        if (!line.contains('周') && !Regex("^\\d+\\s*[-~,，]\\s*\\d+").containsMatchIn(line)) return false
        return WeekRangeParser.parse(line).weeks.isNotEmpty()
    }

    /**
     * 合并同一个 id 的多条记录，而不是简单地留第一条。
     *
     * 学校会把同一门课渲染两遍：一段只给课程名/周次/教室，另一段才带教师、教学楼。
     * 两段的 id 相同（同一门课同一时段），直接去重会把有教师的那条丢掉。
     */
    private fun mergeSessions(sessions: List<CourseSession>): List<CourseSession> {
        val merged = LinkedHashMap<String, CourseSession>()
        sessions.forEach { session ->
            val existing = merged[session.id]
            merged[session.id] = if (existing == null) {
                session
            } else {
                existing.copy(
                    teacher = existing.teacher.ifBlank { session.teacher },
                    room = existing.room.ifBlank { session.room },
                    weeks = (existing.weeks + session.weeks).distinct().sorted(),
                )
            }
        }
        return merged.values.toList()
    }

    private fun looksLikeRoom(line: String, weekLine: String?): Boolean {
        if (line == weekLine) return false
        if (line.length !in 2..24) return false
        // "3#A315" 这种带井号的编号也是教室
        if (Regex("^[0-9]+#[A-Za-z0-9]").containsMatchIn(line.trim())) return true
        if (ROOM_MARKERS.none { line.contains(it) }) return false
        // 带数字的（教1-101、实验楼305）基本就是教室，短的（体育馆）也是；
        // 长的纯中文（教育心理学）更像课程名，别误判
        return line.any { it.isDigit() } || line.length <= 4
    }

    /** 分隔线，比如 `---------------------`；线上面是排课元数据，下面是课程信息。 */
    private val SEPARATOR = Regex("^[-=─—]{4,}$")

    /** 强智课表格子里混着的排课元数据前缀，这些不是给学生看的课程信息。 */
    private val METADATA_PREFIXES = listOf(
        "通知单编号", "班级：", "班级:", "备注：", "备注:", "选课人数",
        "课程编号", "学分：", "学分:", "总学时", "上课人数",
    )

    /**
     * 判断这一行是不是排课元数据。
     *
     * 学校把「通知单编号 / 班级 / 备注 / 学时」这些和课程信息塞在同一个格子里，
     * 不过滤掉的话它们会被当成课程名，课表上就会冒出「通知单编号：BD17…」这种条目。
     */
    private fun isScheduleMetadata(line: String): Boolean {
        val text = line.trim()
        if (text.isEmpty()) return true
        if (SEPARATOR.matches(text)) return true
        if (METADATA_PREFIXES.any { text.startsWith(it) }) return true
        // "(理论:40,实验:8)"、"((理论:40,实验:8),理论:32)" 这类学时说明
        return Regex("^\\(+\\s*[（(]?\\s*(理论|实验|上机|实践|实训)\\s*[:：]\\s*\\d+").containsMatchIn(text)
    }

    /**
     * 从格子里抠出明确写的节次，比如 `12(周)[01-02节]` 里的 01-02。
     *
     * 这个比表格行位置可信得多：学校把同一个课程块在它覆盖的每一行都渲染了一遍，
     * 只按行位置读就会把一门两节的课拆成两条。
     */
    private fun extractPeriodRange(lines: List<String>): IntRange? {
        for (line in lines) {
            val m = Regex("\\[\\s*(\\d{1,2})\\s*(?:[-~—]\\s*(\\d{1,2})\\s*)?节?\\s*]").find(line) ?: continue
            val start = m.groupValues[1].toIntOrNull() ?: continue
            val end = m.groupValues[2].toIntOrNull()?.takeIf { it >= start } ?: start
            if (start in 1..20) return start..end.coerceAtMost(20)
        }
        return null
    }

    /** 只表示单双周的短词，比如"单周""双周"，通常紧跟在周次后面。 */
    private fun isParityOnly(line: String): Boolean =
        line.length <= 3 && (line.contains('单') || line.contains('双')) && line.none { it.isDigit() }
}
