package com.qzkt.timetable.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 考试安排解析：表头映射列、日期/时刻拆分、没表头时的兜底，以及
 * 「页面上有别的表格」时不能把菜单/查询条件当成考试。
 */
class ExamInfoTest {

    @Test
    fun `parse exam table by header`() {
        val html = """
            <html><body>
            <table class="Nsb_r_list Nsb_table">
              <tr>
                <th>序号</th><th>考试时间</th><th>考试地点</th><th>考试课程</th>
                <th>课程代码</th><th>考试形式</th><th>座位号</th>
              </tr>
              <tr>
                <td>1</td><td>2026-11-21 星期六 19:00~21:00</td>
                <td>青岛校区3#教学楼（青岛）3#A220</td><td>毛泽东思想和中国特色社会主义理论体系概论</td>
                <td>BA090023</td><td>闭卷</td><td>12</td>
              </tr>
              <tr>
                <td>2</td><td>2026-11-22 星期日 19:00-21:00</td>
                <td>青岛校区3#教学楼（青岛）3#A125</td><td>大学英语III</td>
                <td>BA060003</td><td>闭卷</td><td></td>
              </tr>
            </table>
            </body></html>
        """.trimIndent()

        val exams = parseExamsHtml(html)
        assertEquals(2, exams.size)

        val first = exams[0]
        assertEquals("毛泽东思想和中国特色社会主义理论体系概论", first.courseName)
        assertEquals("BA090023", first.courseCode)
        assertEquals(LocalDate.of(2026, 11, 21), first.date)
        assertEquals(19 * 60, first.startMinute)
        assertEquals(21 * 60, first.endMinute)
        assertEquals("19:00~21:00", first.timeRangeText)
        assertEquals("青岛校区3#教学楼（青岛）3#A220", first.location)
        assertEquals("12", first.seat)
        assertEquals("闭卷", first.form)

        assertEquals("大学英语III", exams[1].courseName)
        assertNull(exams[1].seat)
    }

    @Test
    fun `column order does not matter and code column is not the course name`() {
        // 有的学校把课程代码放在课程名前面
        val html = """
            <table>
              <tr><th>课程代码</th><th>课程名称</th><th>考试日期</th><th>考试时间</th><th>考场</th></tr>
              <tr><td>BD170052</td><td>数字电子技术</td><td>2026-11-27</td><td>19:00-21:00</td><td>3#A101</td></tr>
            </table>
        """.trimIndent()

        val exams = parseExamsHtml(html)
        assertEquals(1, exams.size)
        assertEquals("数字电子技术", exams[0].courseName)
        assertEquals("BD170052", exams[0].courseCode)
        assertEquals(LocalDate.of(2026, 11, 27), exams[0].date)
        assertEquals("3#A101", exams[0].location)
    }

    @Test
    fun `skips tables that are not exam tables`() {
        val html = """
            <html><body>
            <table><tr><th>学年学期</th><th>选课名称</th><th>选课时间</th><th>操作</th></tr>
              <tr><td>2026-2027-1</td><td>第一轮选课</td><td>2026-09-01 12:00</td><td><a href="#">进入选课</a></td></tr>
            </table>
            <table>
              <tr><th>序号</th><th>考试时间</th><th>考试课程</th><th>考试地点</th></tr>
              <tr><td>1</td><td>2026-11-21 19:00-21:00</td><td>大学英语III</td><td>3#A125</td></tr>
            </table>
            </body></html>
        """.trimIndent()

        val exams = parseExamsHtml(html)
        assertEquals(1, exams.size)
        assertEquals("大学英语III", exams[0].courseName)
    }

    @Test
    fun `falls back to loose rows when the page has no table header`() {
        val html = """
            <table>
              <tr><td>1</td><td>2026-11-21 19:00-21:00</td><td>数字电子技术</td><td>3#A101</td></tr>
              <tr><td>2</td><td>2026-11-22 19:00-21:00</td><td>大学英语III</td><td>3#A125</td></tr>
            </table>
        """.trimIndent()

        val exams = parseExamsHtml(html)
        assertEquals(2, exams.size)
        assertEquals("数字电子技术", exams[0].courseName)
        assertEquals("3#A101", exams[0].location)
        assertEquals(LocalDate.of(2026, 11, 21), exams[0].date)
    }

    @Test
    fun `empty page and login page give no exams`() {
        assertTrue(parseExamsHtml("").isEmpty())
        assertTrue(parseExamsHtml("<html><body>没有考试</body></html>").isEmpty())
        assertTrue(
            parseExamsHtml("<html><body><input id=\"userAccount\"><input id=\"userPassword\"></body></html>").isEmpty(),
        )
    }

    @Test
    fun `parse exam time variants`() {
        val chinese = parseExamTime("2026年11月21日(星期六)19:00-21:00")
        assertEquals(LocalDate.of(2026, 11, 21), chinese.date)
        assertEquals(19 * 60, chinese.startMinute)
        assertEquals(21 * 60, chinese.endMinute)

        val dashed = parseExamTime("2026/11/27 19:00 至 21:00")
        assertEquals(LocalDate.of(2026, 11, 27), dashed.date)
        assertEquals(19 * 60, dashed.startMinute)

        val weekOnly = parseExamTime("第13周 星期六 19:00-21:00")
        assertNull(weekOnly.date)
        assertEquals(19 * 60, weekOnly.startMinute)
        assertEquals(21 * 60, weekOnly.endMinute)

        val unknown = parseExamTime("另行通知")
        assertNull(unknown.date)
        assertNull(unknown.startMinute)
    }

    @Test
    fun `day label counts days`() {
        val today = LocalDate.of(2026, 11, 20)
        assertEquals("今天", examDayLabel(today, today))
        assertEquals("明天", examDayLabel(today.plusDays(1), today))
        assertEquals("后天", examDayLabel(today.plusDays(2), today))
        assertEquals("7 天后", examDayLabel(today.plusDays(7), today))
        assertEquals("昨天", examDayLabel(today.minusDays(1), today))
        assertNull(examDayLabel(null, today))
    }

    @Test
    fun `extract term options from qiangzhi query page`() {
        val html = """
            <form id="Form1" action="/jsxsd/xsks/xsksap_list" method="post">
              <select id="xnxq01id" name="xnxq01id">
                <option value="2026-2027-1" selected>2026-2027-1</option>
                <option value="2025-2026-2">2025-2026-2</option>
              </select>
            </form>
        """.trimIndent()
        assertEquals(listOf("2026-2027-1", "2025-2026-2"), extractExamTermOptions(html))
    }

    @Test
    fun `term options fall back to raw text`() {
        val html = """
            <select id="xqlb" name="xqlb"><option value="1">第一学期</option></select>
            <p>当前学期 2026-2027-1 的考试安排</p>
        """.trimIndent()
        assertEquals(listOf("2026-2027-1"), extractExamTermOptions(html))
        assertTrue(extractExamTermOptions("").isEmpty())
    }

    @Test
    fun `qiangzhi exam table keeps seat and location`() {
        val html = """
            <table class="Nsb_r_list Nsb_table">
              <tr><th>序号</th><th>考试时间</th><th>考试地点</th><th>考试课程</th><th>座位号</th><th>考试形式</th></tr>
              <tr><td>1</td><td>2026-11-21 19:00~21:00</td><td>3#A220</td><td>大学英语III</td><td>12</td><td>闭卷</td></tr>
            </table>
        """.trimIndent()
        val exams = parseExamsHtml(html)
        assertEquals(1, exams.size)
        assertEquals("大学英语III", exams[0].courseName)
        assertEquals("12", exams[0].seat)
        assertEquals("3#A220", exams[0].location)
    }
}
