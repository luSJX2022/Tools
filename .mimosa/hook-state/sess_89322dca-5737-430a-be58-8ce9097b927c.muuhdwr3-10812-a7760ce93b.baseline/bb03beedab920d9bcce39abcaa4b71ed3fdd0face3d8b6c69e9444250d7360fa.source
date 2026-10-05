package com.qzkt.timetable.jw.qz

import com.qzkt.timetable.jw.parse.WeekRangeParser
import com.qzkt.timetable.model.CourseSession
import org.jsoup.nodes.Element

/**
 * 按页面自带的字段标注解析课表格子（强智 jsxsd 这一代模板）。
 *
 * 学校页面上每个字段都写了 `title`，不用猜文字顺序：
 * ```
 * <font>数字电子技术</font>                          ← 没 title 的是课程名
 * <font title='周次(节次)'>1-4,7-8,10-11(周)</font>
 * <font title='教室'>3#A205</font>
 * <font title='教师'>郑皓春</font>
 * <font title='教学楼'>【3#教学楼（青岛）】</font>
 * <font title='通知单编号'>通知单编号：BD17…</font>    ← 排课元数据，页面上默认隐藏
 * <font title='班级'>班级：…</font>
 * <font title='备注'>备注：</font>
 * ```
 *
 * 两个实测出来的坑：
 * 1. 学校把**同一门课渲染了两遍** —— 一段只有课程名/周次/教室，另一段才有教师/教学楼，中间还夹着元数据；
 *    所以一个小格子如果只有一个课程名，就把整个格子里的字段都算给它。这也顺带解释了
 *    为什么之前会解析出两倍数量的条目。
 * 2. 元数据（通知单编号 / 班级 / 备注）和学时说明（"((理论:40,实验:8),理论:32)"）都不是课程信息，
 *    不过滤就会变成课表上的条目。
 *
 * 拿不到标注时返回空列表，由调用方退回通用的文字推断解析。
 */
internal object WebParserTitled {

    private const val T_WEEKS = "周次(节次)"
    private const val T_ROOM = "教室"
    private const val T_BUILDING = "教学楼"
    private const val T_TEACHER = "教师"

    /** 排课元数据：页面上默认都不显示，不是给学生看的课程信息。 */
    private val METADATA_TITLES = setOf(
        "通知单编号", "班级", "备注", "选课人数", "课程编号", "学分", "总学时",
    )

    /** 格子里明确写的节次，比如 `12(周)[01-02节]`；必须带"节"字，免得把"班级：…[1-2]班"当成节次。 */
    private val PERIOD_IN_TEXT = Regex("\\[\\s*(\\d{1,2})\\s*(?:[-~—]\\s*(\\d{1,2})\\s*)?节\\s*]")

    /** 学时说明，长得像 `(理论:40,实验:8)` 或 `((理论:40,实验:8),理论:32)`。 */
    private val HOURS_NOTE = Regex("^\\(+\\s*[（(]?\\s*(理论|实验|上机|实践|实训)\\s*[:：]\\s*\\d+")

    fun buildSessions(
        cell: Element,
        day: Int,
        startPeriod: Int,
        endPeriod: Int,
        weekCount: Int,
    ): List<CourseSession> {
        val fonts = cell.select("font")
        if (fonts.isEmpty()) return emptyList()
        // 一个 title 都没有，说明不是这套模板，交给通用解析
        if (fonts.none { it.hasAttr("title") }) return emptyList()

        val usable = fonts.filterNot { isNoise(it) }
        val names = usable.filter { it.attr("title").isBlank() }
        if (names.isEmpty()) return emptyList()

        val blocks = if (names.size <= 1) listOf(usable) else splitIntoBlocks(usable)
        return blocks.mapNotNull { buildOne(it, day, startPeriod, endPeriod, weekCount) }
    }

    private fun isNoise(font: Element): Boolean {
        val title = font.attr("title").trim()
        if (title in METADATA_TITLES) return true
        if (title.isNotEmpty()) return false
        return HOURS_NOTE.containsMatchIn(font.text().trim())
    }

    /** 一个格子里有多门课时，按"下一段课程名"切块。 */
    private fun splitIntoBlocks(fonts: List<Element>): List<List<Element>> {
        val blocks = mutableListOf<MutableList<Element>>()
        fonts.forEach { font ->
            val current = blocks.lastOrNull()
            val currentHasName = current?.any { it.attr("title").isBlank() } == true
            if (current == null || (font.attr("title").isBlank() && currentHasName)) {
                blocks += mutableListOf(font)
            } else {
                current.add(font)
            }
        }
        return blocks
    }

    private fun buildOne(
        block: List<Element>,
        day: Int,
        startPeriod: Int,
        endPeriod: Int,
        weekCount: Int,
    ): CourseSession? {
        val name = block.firstOrNull { it.attr("title").isBlank() }?.text()?.trim().orEmpty()
        if (name.isEmpty()) return null

        val teacher = block.firstOrNull { it.attr("title") == T_TEACHER }?.text()?.trim().orEmpty()
        val building = block.firstOrNull { it.attr("title") == T_BUILDING }?.text()?.trim().orEmpty()
        val roomNumber = block.firstOrNull { it.attr("title") == T_ROOM }?.text()?.trim().orEmpty()
        // "【3#教学楼（青岛）】" + "3#A315" 拼起来才是完整位置
        val room = listOf(building, roomNumber).filter { it.isNotEmpty() }.joinToString(" ")

        val weekText = block.firstOrNull { it.attr("title") == T_WEEKS }?.text()?.trim().orEmpty()
        val weeks = WeekRangeParser.parse(weekText, weekText).weeks
            .takeIf { it.isNotEmpty() }
            ?: (1..weekCount.coerceAtLeast(1)).toList()

        // 格子里写了 `[01-02节]` 就以它为准，比表格行位置可靠
        val explicit = PERIOD_IN_TEXT.find(weekText)?.let { match ->
            val start = match.groupValues[1].toIntOrNull() ?: return@let null
            val end = match.groupValues[2].toIntOrNull()?.takeIf { it >= start } ?: start
            start..end.coerceAtMost(20)
        }
        val start = explicit?.first ?: startPeriod
        val end = explicit?.last ?: endPeriod

        return CourseSession(
            id = CourseSession.makeId(name, day, start, end, ""),
            name = name,
            teacher = teacher,
            room = room,
            dayOfWeek = day,
            startPeriod = start,
            endPeriod = end,
            weeks = weeks.distinct().sorted(),
            teachingClass = "",
            raw = mapOf("source" to "web-titled", "weeks" to weekText),
        )
    }
}
