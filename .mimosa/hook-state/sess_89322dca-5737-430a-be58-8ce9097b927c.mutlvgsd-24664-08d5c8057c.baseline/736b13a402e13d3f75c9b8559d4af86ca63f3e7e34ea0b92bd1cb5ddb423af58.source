package com.qzkt.timetable.jw.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KbcxParserTest {

    /** 布局 A：强智 app.do 常见的紧凑键名。 */
    private val layoutA = """
    [
      {"jsxm":"张三","kcmc":"高等数学","jsmc":"教1-101","xqjmc":"星期一","zcd":"1-16","jcs":"0102","xqj":"1","jxbmc":"计算机2401"},
      {"jsxm":"李四","kcmc":"大学物理","jsmc":"实验楼305","xqjmc":"星期三","zcd":"3-14","jcs":"0506","xqj":"3","jxbmc":"计算机2401"}
    ]
    """

    /** 布局 B：键名偏英文。 */
    private val layoutB = """
    [
      {"courseName":"大学英语","teacher":"李四","classroom":"外语楼305","weekday":"2","period":"0304","weeks":"1-8","className":"英语A班"}
    ]
    """

    /** 布局 C：键名完全陌生，只能靠值的长相推断。 */
    private val layoutC = """
    [
      {"a1":"线性代数","a2":"王五","a3":"综合楼201","a4":"星期三","a5":"5-6","a6":"1-16周","a7":"数学2401"}
    ]
    """

    @Test
    fun `布局A 按别名表解析`() {
        val result = KbcxParser.parseJson(layoutA)
        assertEquals(2, result.sessions.size)
        assertTrue(result.warnings.isEmpty())

        val math = result.sessions.first { it.name == "高等数学" }
        assertEquals("张三", math.teacher)
        assertEquals("教1-101", math.room)
        assertEquals(1, math.dayOfWeek)
        assertEquals(1, math.startPeriod)
        assertEquals(2, math.endPeriod)
        assertEquals((1..16).toList(), math.weeks)
        assertEquals("计算机2401", math.teachingClass)

        val physics = result.sessions.first { it.name == "大学物理" }
        assertEquals(3, physics.dayOfWeek)
        assertEquals(5..6, physics.startPeriod..physics.endPeriod)
        assertEquals((3..14).toList(), physics.weeks)
    }

    @Test
    fun `布局B 英文键名同样能解析`() {
        val result = KbcxParser.parseJson(layoutB)
        assertEquals(1, result.sessions.size)

        val english = result.sessions.single()
        assertEquals("大学英语", english.name)
        assertEquals("李四", english.teacher)
        assertEquals("外语楼305", english.room)
        assertEquals(2, english.dayOfWeek)
        assertEquals(3, english.startPeriod)
        assertEquals(4, english.endPeriod)
        assertEquals((1..8).toList(), english.weeks)
        assertEquals("英语A班", english.teachingClass)
    }

    @Test
    fun `布局C 全靠启发式推断`() {
        val result = KbcxParser.parseJson(layoutC)
        assertEquals(1, result.sessions.size)

        val session = result.sessions.single()
        assertEquals("线性代数", session.name)
        assertEquals("王五", session.teacher)
        assertEquals("综合楼201", session.room)
        assertEquals(3, session.dayOfWeek)
        assertEquals(5, session.startPeriod)
        assertEquals(6, session.endPeriod)
        assertEquals((1..16).toList(), session.weeks)
        assertEquals("数学2401", session.teachingClass)
    }

    @Test
    fun `包裹在 data 字段里也能解析`() {
        val wrapped = """{"code":0,"data":${layoutA.trim()}}"""
        assertEquals(2, KbcxParser.parseJson(wrapped).sessions.size)
    }

    @Test
    fun `周次识别不出时兜底为整学期并给出告警`() {
        val odd = """[{"kcmc":"体育","xqj":"5","jcs":"0708","jsmc":"体育馆","zcd":"不分周次"}]"""
        val result = KbcxParser.parseJson(odd, termWeekCount = 18)
        val session = result.sessions.single()
        assertEquals((1..18).toList(), session.weeks)
        assertTrue(result.warnings.any { it.contains("周次识别不出") })
    }

    @Test
    fun `缺少星期或节次时跳过并告警`() {
        val broken = """[{"kcmc":"神秘课程","zcd":"1-16"}]"""
        val result = KbcxParser.parseJson(broken)
        assertTrue(result.sessions.isEmpty())
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `相同课程的 id 稳定`() {
        val first = KbcxParser.parseJson(layoutA).sessions.first { it.name == "高等数学" }.id
        val second = KbcxParser.parseJson(layoutA).sessions.first { it.name == "高等数学" }.id
        assertEquals(first, second)
    }
}
