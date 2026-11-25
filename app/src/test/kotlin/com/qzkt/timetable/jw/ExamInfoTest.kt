package com.qzkt.timetable.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `login page detection requires a real login form`() {
        val login = """
            <html><head><title>登录</title></head><body>
              <form id="loginForm" action="/jsxsd/xk/LoginToXk" method="post">
                <input id="userAccount" name="userAccount"><input id="userPassword" name="userPassword">
              </form>
            </body></html>
        """.trimIndent()
        assertTrue(isLoginPageHtml(login))

        // 正常考试页里嵌了带 loginForm 字样的脚本，不能被当成登录页
        val examPage = """
            <html><body>
              <script>var s = "loginForm"; var u = "userAccount";</script>
              <table><tr><th>考试时间</th><th>考试课程</th><th>考试地点</th></tr>
                <tr><td>2026-11-21 19:00~21:00</td><td>大学英语III</td><td>3#A220</td></tr>
              </table>
            </body></html>
        """.trimIndent()
        assertFalse(isLoginPageHtml(examPage))

        assertFalse(isLoginPageHtml(""))
    }

    @Test
    fun `rate limited page detected`() {
        val limited = """
            <script language='javascript'>alert('温馨提示：您操作系统过于频繁，已被注销登录，正常使用请勿高频操作！');
            top.location.href='https://zs.hdxy.edu.cn/tpass';</script>
        """.trimIndent()
        assertTrue(isRateLimitedHtml(limited))
        // 正常页面不能误判
        assertFalse(isRateLimitedHtml("<table><tr><th>考试时间</th></tr></table>"))
        assertFalse(isRateLimitedHtml(""))
    }

    @Test
    fun `qiangzhi page uses td cells as the header row`() {
        // 线上真实页面（我的考试 - 考试安排查询）：整行都用 td，没有 th
        val html = """
            <html><head><title>我的考试 - 考试安排查询</title></head><body>
            <table class="Nsb_r_list Nsb_table">
              <tr>
                <td>序号</td><td>考试时间</td><td>考试地点</td><td>考试课程</td>
                <td>座位号</td><td>考试形式</td>
              </tr>
              <tr>
                <td>1</td><td>2026-12-01 星期一 09:00~11:00</td><td>3#A220</td>
                <td>大学英语III</td><td>12</td><td>闭卷</td>
              </tr>
            </table>
            </body></html>
        """.trimIndent()

        val exams = parseExamsHtml(html)
        assertEquals(1, exams.size)
        assertEquals("大学英语III", exams[0].courseName)
        assertEquals("3#A220", exams[0].location)
        assertEquals("12", exams[0].seat)
        assertEquals(LocalDate.of(2026, 12, 1), exams[0].date)
        assertEquals(9 * 60, exams[0].startMinute)
        // 表头行不能被当成一条考试
        assertTrue(exams.none { it.courseName.contains("考试课程") })
    }

    @Test
    fun `header row detection`() {
        assertTrue(looksLikeExamHeader("考试课程"))
        assertTrue(looksLikeExamHeader("考试时间"))
        assertTrue(looksLikeExamHeader("座位号"))
        assertFalse(looksLikeExamHeader("大学英语III"))
        assertFalse(looksLikeExamHeader("2026-12-01 星期一 09:00~11:00"))
    }

    @Test
    fun `div based exam list still parses`() {
        // 不是 table，而是 div 列表（部分强智模板）
        val html = """
            <html><body><div class='list'>
              <div>2026-12-05 14:00~16:00 数据结构 3#B101 座位 8</div>
            </div></body></html>
        """.trimIndent()
        val exams = parseExamsHtml(html)
        assertEquals(1, exams.size)
        assertEquals("数据结构", exams[0].courseName)
        assertEquals(LocalDate.of(2026, 12, 5), exams[0].date)
        assertEquals(14 * 60, exams[0].startMinute)
    }

    @Test
    fun `diagnostics describe structure not just doctype`() {
        val html = """
            <!DOCTYPE html><html><head><title>我的考试 - 考试安排查询</title></head><body>
            <table><tr><td>序号</td><td>考试时间</td><td>考试课程</td></tr></table>
            </body></html>
        """.trimIndent()
        val d = describeExamPage(html)
        assertTrue("要带标题", d.contains("我的考试"))
        assertTrue("要报表格数", d.contains("表格数=1"))
        assertTrue("要报表头格子", d.contains("考试课程"))
        assertFalse("不能只有 DOCTYPE", d.startsWith("<!DOCTYPE"))
    }

    @Test
    fun `haidu 13 column table with session column`() {
        // 线上真实表头（13 格）：序号|校区|考试校区|考试场次|课程编号|课程名称|...
        // 注意「考试时间」列可能是空的，日期时段塞在「考试场次」里
        val html = """
            <html><head><title>我的考试 - 考试安排查询</title></head><body>
            <table>
              <tr>
                <td>序号</td><td>校区</td><td>考试校区</td><td>考试场次</td><td>课程编号</td>
                <td>课程名称</td><td>考试日期</td><td>考试时间</td><td>考试地点</td>
                <td>座位号</td><td>考试形式</td><td>监考教师</td><td>备注</td>
              </tr>
              <tr>
                <td>1</td><td>青岛</td><td>青岛校区</td><td>2026-12-01 09:00~11:00 第1场</td>
                <td>BA060003</td><td>大学英语III</td><td>2026-12-01</td><td></td>
                <td>3#A220</td><td>12</td><td>闭卷</td><td>张三</td><td></td>
              </tr>
            </table>
            </body></html>
        """.trimIndent()

        val exams = parseExamsHtml(html)
        assertEquals(1, exams.size)
        assertEquals("大学英语III", exams[0].courseName)
        assertEquals("BA060003", exams[0].courseCode)
        assertEquals(LocalDate.of(2026, 12, 1), exams[0].date)
        assertEquals(9 * 60, exams[0].startMinute)
        assertEquals(11 * 60, exams[0].endMinute)
        assertEquals("3#A220", exams[0].location)
        assertEquals("12", exams[0].seat)
    }

    @Test
    fun `location falls back to campus when place is empty`() {
        val html = """
            <table>
              <tr><td>序号</td><td>校区</td><td>考试校区</td><td>课程名称</td><td>考试日期</td></tr>
              <tr><td>1</td><td>青岛</td><td>青岛校区</td><td>高等数学</td><td>2026-12-03</td></tr>
            </table>
        """.trimIndent()
        val exams = parseExamsHtml(html)
        assertEquals(1, exams.size)
        assertTrue("地点为空时用校区兜底", exams[0].location!!.contains("青岛"))
    }

    @Test
    fun `diagnostics list each row when table is tiny`() {
        val html = """
            <table>
              <tr><td>序号</td><td>课程名称</td><td>考试时间</td></tr>
              <tr><td>1</td><td>暂无数据</td><td></td></tr>
            </table>
        """.trimIndent()
        val d = describeExamPage(html)
        assertTrue("要打出数据行", d.contains("第2行"))
        assertTrue("要报解析结果", d.contains("解析出="))
    }

    @Test
    fun `extract real form fields from query page`() {
        // 线上首屏：查询条件表单 + 空表（2 行）。提交按钮不能被带上。
        val html = """
            <html><head><title>我的考试 - 考试安排查询</title></head><body>
            <form id="Form1" action="/jsxsd/xsks/xsksap_list" method="post">
              <input type="hidden" name="xnxq01id" value="2026-2027-1">
              <input type="hidden" name="xqlbmc" value="">
              <select name="ksmcdm">
                <option value="">全部场次</option>
                <option value="1" selected>期末考试</option>
              </select>
              <input type="checkbox" name="onlyFail" value="1">
              <input type="submit" name="submit" value="查询">
            </form>
            <table><tr><td>序号</td><td>课程名称</td></tr></table>
            </body></html>
        """.trimIndent()

        val fields = extractExamFormFields(html)
        assertEquals("2026-2027-1", fields["xnxq01id"])
        assertEquals("", fields["xqlbmc"])
        // 提交的是 option 的 value（浏览器的真实行为），不是显示文字
        assertEquals("1", fields["ksmcdm"])
        assertFalse("提交按钮不能带", fields.containsKey("submit"))
        assertFalse("未勾选的复选框不能带", fields.containsKey("onlyFail"))
    }

    @Test
    fun `no form yields empty map`() {
        assertTrue(extractExamFormFields("<html><body>没有表单</body></html>").isEmpty())
        assertTrue(extractExamFormFields("").isEmpty())
    }
}