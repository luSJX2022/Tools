package com.qzkt.timetable.jw.qz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QzWebParserTest {

    /** 经典排版：列是星期，行是节次，连堂用 rowspan 合并。 */
    private val gridHtml = """
    <html><body>
    <table id="kbtable" border="1">
      <tr>
        <th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th>
        <th>星期四</th><th>星期五</th><th>星期六</th><th>星期日</th>
      </tr>
      <tr>
        <td>第1节<br>08:00-08:45</td>
        <td rowspan="2">高等数学<br>张三<br>教1-101<br>1-16周</td>
        <td></td><td></td><td></td><td></td><td></td><td></td>
      </tr>
      <tr>
        <td>第2节<br>08:55-09:40</td>
        <td></td><td></td><td></td><td></td><td></td><td></td>
      </tr>
      <tr>
        <td>第3节<br>10:00-10:45</td>
        <td></td><td></td>
        <td>大学物理<br>李四<br>实验楼305<br>3-14周(单)</td>
        <td></td><td></td><td></td><td></td>
      </tr>
    </table>
    </body></html>
    """

    /** 一个格子里塞了两门课（同一时段冲突）。 */
    private val multiCourseCellHtml = """
    <html><body>
    <table>
      <tr>
        <th>x</th><th>星期一</th><th>星期二</th><th>星期三</th>
        <th>星期四</th><th>星期五</th><th>星期六</th><th>星期日</th>
      </tr>
      <tr>
        <td>第5节</td>
        <td>大学英语<br>王五<br>外语楼201<br>1-8周<br>体育<br>赵六<br>体育馆<br>9-16周</td>
        <td></td><td></td><td></td><td></td><td></td><td></td>
      </tr>
    </table>
    </body></html>
    """

    @Test
    fun `解析经典网格排版并正确合并连堂`() {
        val result = QzWebParser.parse(gridHtml)

        assertEquals(2, result.sessions.size)

        val math = result.sessions.first { it.name == "高等数学" }
        assertEquals(1, math.dayOfWeek)
        assertEquals(1, math.startPeriod)
        assertEquals(2, math.endPeriod) // rowspan=2 合并成连堂
        assertEquals("教1-101", math.room)
        assertEquals("张三", math.teacher)
        assertEquals((1..16).toList(), math.weeks)

        val physics = result.sessions.first { it.name == "大学物理" }
        assertEquals(3, physics.dayOfWeek)
        assertEquals(3, physics.startPeriod)
        assertEquals(3, physics.endPeriod)
        assertEquals("实验楼305", physics.room)
        assertEquals("李四", physics.teacher)
        assertEquals(listOf(3, 5, 7, 9, 11, 13), physics.weeks) // 单周
    }

    @Test
    fun `一个格子里有多门课时按周次拆开`() {
        val result = QzWebParser.parse(multiCourseCellHtml)

        assertEquals(2, result.sessions.size)

        val english = result.sessions.first { it.name == "大学英语" }
        assertEquals("王五", english.teacher)
        assertEquals("外语楼201", english.room)
        assertEquals((1..8).toList(), english.weeks)
        assertEquals(1, english.dayOfWeek)

        val pe = result.sessions.first { it.name == "体育" }
        assertEquals("赵六", pe.teacher)
        assertEquals("体育馆", pe.room)
        assertEquals((9..16).toList(), pe.weeks)
    }

    @Test
    fun `抓错页面时给出可读提示而不是崩掉`() {
        val result = QzWebParser.parse("<html><body><p>登录页面</p></body></html>")
        assertTrue(result.sessions.isEmpty())
        assertTrue(result.warnings.any { it.contains("没有表格") || it.contains("没能从页面里识别出课表") })
    }

    /**
     * 抓取脚本会把顶层页面和各个同源 iframe 的 HTML 分别抓回来。
     *
     * 这里刻意不能把它们拼成一份 HTML：强智登录后的主界面是 frameset，
     * 而 HTML 解析器遇到 `<frameset>` 就会把后面拼接的内容整段丢掉。
     * 所以拼起来的解析结果必然是 0 条 —— 分开解析、再合并才对。
     */
    @Test
    fun `frameset 主界面必须分开解析而不是拼接`() {
        val framesetDoc = """
        <html><head><title>强智教务系统</title></head>
        <frameset cols="20%,80%">
          <frame src="menu.jsp" name="menu">
          <frame src="xskbcx.jsp" name="main">
        </frameset></html>
        """

        // 拼成一份就全军覆没
        assertEquals(0, QzWebParser.parse(framesetDoc + gridHtml).sessions.size)

        // 分开解析再合并才有结果
        val merged = QzWebParser.parseDocuments(listOf(framesetDoc, gridHtml))
        assertEquals(2, merged.sessions.size)
        assertTrue(merged.sessions.any { it.name == "高等数学" })
    }

    @Test
    fun `多份文档里重复的课只保留一条`() {
        val merged = QzWebParser.parseDocuments(listOf(gridHtml, gridHtml))
        assertEquals(2, merged.sessions.size)
    }

    @Test
    fun `非 HTML 内容不会抛异常`() {
        val result = QzWebParser.parse("")
        assertTrue(result.sessions.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }
}
