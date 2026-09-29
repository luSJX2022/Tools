package com.qzkt.timetable.jw.qz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列表式排版（没有表头、一行一门课）的兜底解析。
 *
 * 关键点是别把"第 5 节"这种格子当成"星期 5"——这两个字段的值形状一样，
 * 判断错了整张课表都会挪到错误的星期上。
 */
class QzWebParserListModeTest {

    private val listHtml = """
    <html><body>
    <table>
      <tr><td>序号</td><td>课程名称</td><td>星期</td><td>节次</td><td>教师</td><td>教室</td><td>周次</td></tr>
      <tr>
        <td>1</td>
        <td>高等数学<br>张三<br>教1-101<br>1-16周</td>
        <td>星期一</td>
        <td>第5节</td>
        <td></td>
        <td></td>
        <td></td>
      </tr>
    </table>
    </body></html>
    """

    @Test
    fun `星期列取中文而不是节次列的数字`() {
        val result = QzWebParser.parse(listHtml)

        // 表头行会被当成数据行试一遍，解析不出课程名就该被跳过
        val math = result.sessions.firstOrNull { it.name == "高等数学" }
        assertTrue("应该解析出高等数学，实际：${result.sessions.map { it.name }}", math != null)
        assertEquals(1, math!!.dayOfWeek)
        assertEquals(5, math.startPeriod)
        assertEquals("张三", math.teacher)
        assertEquals("教1-101", math.room)
        assertEquals((1..16).toList(), math.weeks)
    }
}
