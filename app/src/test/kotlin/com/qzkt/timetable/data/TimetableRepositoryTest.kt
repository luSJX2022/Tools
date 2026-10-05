package com.qzkt.timetable.data

import com.qzkt.timetable.model.CourseSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate

class TimetableRepositoryTest {

    private lateinit var dir: java.io.File
    private lateinit var store: TimetableStore
    private lateinit var repository: TimetableRepository

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("qzkt-test").toFile()
        store = TimetableStore(dir)
        repository = TimetableRepository(store)
    }

    // ------------------------------------------------------------ 逐周合并

    @Test
    fun `同一门课在多周出现时周次会累加`() {
        repository.applyWeek(1, listOf(math(), physics()))
        repository.applyWeek(2, listOf(math()))

        val sessions = repository.snapshot.value.sessions
        assertEquals(listOf(1, 2), sessions.first { it.name == "高等数学" }.weeks)
        assertEquals(listOf(1), sessions.first { it.name == "大学物理" }.weeks)
    }

    @Test
    fun `某周课程消失时只从周次里摘掉那一周`() {
        repository.applyWeek(1, listOf(math()))
        repository.applyWeek(2, listOf(math()))
        // 第 3 周这门课没有出现在教务系统里
        repository.applyWeek(3, emptyList())

        val sessions = repository.snapshot.value.sessions
        assertEquals(listOf(1, 2), sessions.single().weeks)
    }

    @Test
    fun `所有周次都被摘掉后记录消失`() {
        repository.applyWeek(1, listOf(math()))
        repository.applyWeek(1, emptyList())
        assertTrue(repository.snapshot.value.sessions.isEmpty())
    }

    @Test
    fun `覆盖更新同一周的课程`() {
        repository.applyWeek(1, listOf(math(room = "教1-101")))
        repository.applyWeek(1, listOf(math(room = "教2-305")))

        val session = repository.snapshot.value.sessions.single()
        assertEquals("教2-305", session.room)
        assertEquals(listOf(1), session.weeks)
    }

    @Test
    fun `查询某一周的课`() {
        repository.applyWeek(1, listOf(math(), physics()))
        repository.applyWeek(2, listOf(physics()))

        assertEquals(setOf("高等数学", "大学物理"), repository.sessionsInWeek(1).map { it.name }.toSet())
        assertEquals(listOf("大学物理"), repository.sessionsInWeek(2).map { it.name })
        assertEquals(listOf("大学物理"), repository.sessionsOn(2, dayOfWeek = 3).map { it.name })
    }

    @Test
    fun `课表持久化后重新打开还在`() {
        repository.applyWeek(1, listOf(math(), physics()))

        // 重新打开（模拟重启 App）仍然读得到课表
        val reopened = TimetableRepository(TimetableStore(dir))
        assertEquals(repository.snapshot.value.sessions.size, reopened.snapshot.value.sessions.size)
    }

    // ------------------------------------------------------------ 周次推算

    @Test
    fun `按第一周周一推算当前教学周`() {
        val monday = LocalDate.of(2026, 9, 7) // 周一
        repository.setTerm("2026-2027-1", monday.toString(), weekCount = 20, currentWeek = 1)

        assertEquals(1, repository.currentWeek(monday))
        assertEquals(1, repository.currentWeek(monday.plusDays(6))) // 周日仍属第 1 周
        assertEquals(2, repository.currentWeek(monday.plusDays(7)))
        assertEquals(3, repository.currentWeek(monday.plusDays(17)))
        // 超出学期范围时被夹到总周数内
        assertEquals(20, repository.currentWeek(monday.plusDays(400)))
        // 学期开始之前也不会算出 0 或负数
        assertEquals(1, repository.currentWeek(monday.minusDays(30)))
    }

    @Test
    fun `开学日期未知时退回教务系统报告的周次`() {
        repository.setTerm("2026-2027-1", firstMonday = "", weekCount = 20, currentWeek = 7)
        assertEquals(7, repository.currentWeek())
    }

    // ------------------------------------------------------------ 辅助

    private fun math(start: Int = 1, end: Int = 2, room: String = "教1-101") =
        course("高等数学", day = 1, start = start, end = end, room = room)

    private fun physics() = course("大学物理", day = 3, start = 5, end = 6, room = "实验楼305")

    private fun course(name: String, day: Int, start: Int, end: Int, room: String) = CourseSession(
        id = CourseSession.makeId(name, day, start, end, "计算机2401"),
        name = name,
        teacher = "张三",
        room = room,
        dayOfWeek = day,
        startPeriod = start,
        endPeriod = end,
        weeks = emptyList(),
        teachingClass = "计算机2401",
    )
}
