package com.qzkt.timetable.jw.qz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**青岛农业大学海都学院真实课表格子**的内容做回归测试。
 *
 * 数据是从真机上导出来的（`CourseSession.raw["lines"]` 保留了每格解析时看到的原始行），
 * 所以这不是我编的样例，是学校页面的真实长相。
 *
 * 当时的问题：81 条课程里有 36 条的名字是"通知单编号：…"、61 条教师/教室为空。
 */
class QzWebParserRealSchoolTest {

    /** 学校把排课元数据和课程信息塞在同一个格子里，中间用一排横线隔开。 */
    private val metadataAndCourse = """
    <html><body>
    <table>
      <tr>
        <th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th>
        <th>星期四</th><th>星期五</th><th>星期六</th><th>星期日</th>
      </tr>
      <tr>
        <td>第1节</td>
        <td><div class="kbcontent">通知单编号：BD1700522026202710001<br>班级：电子信息工程2025级[1-2]班<br>备注：<br>(理论:40,实验:8)<br>---------------------<br>毛泽东思想和中国特色社会主义理论体系概论<br>姜莎莎<br>12(周)[01-02节]<br>【3#教学楼（青岛）】 3#A315</div></td>
        <td></td><td></td><td></td><td></td><td></td><td></td>
      </tr>
      <tr>
        <td>第2节</td>
        <td></td><td></td><td></td><td></td><td></td><td></td><td></td>
      </tr>
    </table>
    </body></html>
    """

    /** 只有课程名和周次、没有教室教师的那种格子。 */
    private val nameAndWeeksOnly = """
    <html><body>
    <table>
      <tr>
        <th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th>
        <th>星期四</th><th>星期五</th><th>星期六</th><th>星期日</th>
      </tr>
      <tr>
        <td>第1节</td>
        <td><div class="kbcontent">数字电子技术<br>1-4,7-8,10-11(周)</div></td>
        <td></td><td></td><td></td><td></td><td></td><td></td>
      </tr>
      <tr><td>第2节</td><td></td><td></td><td></td><td></td><td></td><td></td><td></td></tr>
    </table>
    </body></html>
    """

    @Test
    fun `排课元数据不会被当成课程`() {
        val result = QzWebParser.parse(metadataAndCourse)

        assertEquals("整个格子应当只产出一门课，实际：${result.sessions.map { it.name }}", 1, result.sessions.size)
        val course = result.sessions.single()

        assertEquals("毛泽东思想和中国特色社会主义理论体系概论", course.name)
        assertEquals("姜莎莎", course.teacher)
        assertEquals("【3#教学楼（青岛）】 3#A315", course.room)
        assertEquals(listOf(12), course.weeks)
        // 节次取自格子里的 [01-02节]，而不是表格行位置
        assertEquals(1, course.startPeriod)
        assertEquals(2, course.endPeriod)

        assertTrue("名字里不该出现通知单编号", !course.name.contains("通知单编号"))
    }

    @Test
    fun `只有课程名和周次的格子也能解析`() {
        val result = QzWebParser.parse(nameAndWeeksOnly)

        val course = result.sessions.single()
        assertEquals("数字电子技术", course.name)
        assertEquals(listOf(1, 2, 3, 4, 7, 8, 10, 11), course.weeks)
    }

    @Test
    fun `班级里的方括号不会被当成节次`() {
        // "班级：电子信息工程2025级[1-2]班" 里的 [1-2] 是班级序号，不是第 1-2 节
        val result = QzWebParser.parse(metadataAndCourse)
        val course = result.sessions.single()
        // 真正的节次来自 [01-02节]
        assertEquals(1, course.startPeriod)
        assertEquals(2, course.endPeriod)
    }
}
