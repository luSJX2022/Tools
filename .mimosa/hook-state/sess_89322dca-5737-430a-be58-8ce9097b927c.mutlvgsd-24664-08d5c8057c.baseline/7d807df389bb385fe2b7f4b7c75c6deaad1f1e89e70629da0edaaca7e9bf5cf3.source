package com.qzkt.timetable.jw.qz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学校课表格子的真实结构（从真机导出的页面里看出来的）。
 *
 * 两个关键点，都踩过坑：
 * 1. 每个字段都带 `title` 标注，按标注取比按文字顺序猜可靠得多；
 * 2. 同一个格子里，**同一门课渲染了两遍** —— 一个可见的 `div.kbcontent1` 只有
 *    课程名/周次/教室，另一个 `display:none` 的 `div.kbcontent` 才带教师和教学楼。
 *    只按 id 去重会把带教师的那条丢掉，必须合并字段。
 */
class WebParserTitledTest {

    private fun cell(vararg divs: String) = """
    <html><body>
    <table>
      <tr>
        <th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th>
        <th>星期四</th><th>星期五</th><th>星期六</th><th>星期日</th>
      </tr>
      <tr>
        <td>第一二节</td>
        <td>${divs.joinToString("")}</td>
        <td></td><td></td><td></td><td></td><td></td><td></td>
      </tr>
      <tr>
        <td>第三四节</td>
        <td></td><td></td><td></td><td></td><td></td><td></td><td></td>
      </tr>
    </table>
    </body></html>
    """.trimIndent()

    /** 可见的简版：没有教师。 */
    private val visible = """
    <div id="kb-1-1" class="kbcontent1">
      <font>数字电子技术</font><br/>
      <font title='周次(节次)'>1-4,7-8,10-11(周)</font><br/>
      <font title='教室'>3#A205</font><br/>
    </div>
    """.trimIndent()

    /** 隐藏的详版：才有教师和教学楼；前面还夹着排课元数据。 */
    private val hidden = """
    <div id="kb-1-2" style="display: none;" class="kbcontent">
      <font title='通知单编号'>通知单编号：BD1700522026202710001</font><br/>
      <font title='班级'>班级：电子信息工程2025级[1-2]班</font><br/>
      <font title='备注'>备注：</font><br/>
      <font>((理论:40,实验:8),理论:32)</font><br/>
      ----------------------<br/>
      <font>数字电子技术</font><br/>
      <font title='教师'>郑皓春</font><br/>
      <font title='周次(节次)'>1-4,7-8,10-11(周)[01-02节]</font><br/>
      <font title='教学楼'>【3#教学楼（青岛）】</font><br/>
      <font title='教室'>3#A205</font><br/>
    </div>
    """.trimIndent()

    @Test
    fun `简版和详版要合并成一门课并带上教师`() {
        val result = QzWebParser.parse(cell(visible, hidden))

        assertEquals("两遍渲染应当合并成一条，实际：${result.sessions.map { it.name }}", 1, result.sessions.size)
        val course = result.sessions.single()
        assertEquals("数字电子技术", course.name)
        assertEquals("郑皓春", course.teacher)
        // 教室保留学校自己显示的那个（简版的 "3#A205"），不去拼"教学楼+教室"的长版本 ——
        // 课表格子只有 38dp 宽，长的会被截断，学校怎么显示就怎么用
        assertEquals("3#A205", course.room)
        assertEquals((1..4).toList() + listOf(7, 8, 10, 11), course.weeks)
        assertEquals(1, course.startPeriod)
        assertEquals(2, course.endPeriod)
    }

    @Test
    fun `排课元数据和学时说明不会变成课程`() {
        val result = QzWebParser.parse(cell(visible, hidden))

        val names = result.sessions.map { it.name }
        assertTrue("不该出现通知单编号：$names", names.none { it.contains("通知单编号") })
        assertTrue("不该出现备注：$names", names.none { it.startsWith("备注") })
        assertTrue("不该出现学时说明：$names", names.none { it.contains("理论:") })
    }

    @Test
    fun `没有 title 标注的页面仍走通用解析`() {
        // 老模板没有 font[title]，不能被这套解析吃掉
        val plain = """
        <html><body><table>
          <tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th>
              <th>星期四</th><th>星期五</th><th>星期六</th><th>星期日</th></tr>
          <tr><td>第1节</td>
              <td>高等数学<br>张三<br>教1-101<br>1-16周</td>
              <td></td><td></td><td></td><td></td><td></td><td></td></tr>
          <tr><td>第2节</td><td></td><td></td><td></td><td></td><td></td><td></td><td></td></tr>
        </table></body></html>
        """.trimIndent()

        val course = QzWebParser.parse(plain).sessions.single()
        assertEquals("高等数学", course.name)
        assertEquals("张三", course.teacher)
        assertEquals("教1-101", course.room)
    }
}
