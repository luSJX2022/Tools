package com.qzkt.timetable.ui.common

import com.qzkt.timetable.model.Term
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 开学日期这个锚点，以及由它算出来的每一天。
 *
 * 课表上每个日期都是从它推的，错一天整个学期都错位，所以单独测。
 */
class FirstMondayPickerTest {

    @Test
    fun `选周中任意一天都会归到那一周的周一`() {
        assertEquals(LocalDate.of(2026, 9, 7), mondayOf(LocalDate.of(2026, 9, 7)))  // 周一本身
        assertEquals(LocalDate.of(2026, 9, 7), mondayOf(LocalDate.of(2026, 9, 8)))  // 周二
        assertEquals(LocalDate.of(2026, 9, 7), mondayOf(LocalDate.of(2026, 9, 12))) // 周六
        assertEquals(LocalDate.of(2026, 9, 7), mondayOf(LocalDate.of(2026, 9, 13))) // 周日
    }

    @Test
    fun `归到周一之后一定是周一且偏移不超过六天`() {
        var date = LocalDate.of(2026, 1, 1)
        repeat(400) {
            val monday = mondayOf(date)
            assertEquals("$date 应当归到周一", DayOfWeek.MONDAY, monday.dayOfWeek)
            val offset = ChronoUnit.DAYS.between(monday, date)
            assertTrue("$date 的偏移应在 0..6，实际 $offset", offset in 0..6)
            date = date.plusDays(1)
        }
    }

    @Test
    fun `跨月跨年也对`() {
        assertEquals(LocalDate.of(2026, 12, 28), mondayOf(LocalDate.of(2027, 1, 1)))
        assertEquals(LocalDate.of(2027, 3, 1), mondayOf(LocalDate.of(2027, 3, 1)))
    }

    @Test
    fun `第N周星期D换算出的日期是星期D且逐周递增七天`() {
        val term = Term(xnxqh = "2026-2027-1", startDate = "2026-09-07", weekCount = 20, currentWeek = 1)

        // 第 1 周周一就是开学那天
        assertEquals("2026-09-07", term.dateOf(1, 1))
        assertEquals("2026-09-13", term.dateOf(1, 7)) // 第 1 周周日

        // 第 2 周周一往后整七天
        assertEquals("2026-09-14", term.dateOf(2, 1))

        // 每一周的同一星期几都是七天的倍数，且落在那一天上
        for (week in 1..20) {
            for (day in 1..7) {
                val date = LocalDate.parse(term.dateOf(week, day))
                assertEquals(DayOfWeek.of(day), date.dayOfWeek)
                assertEquals(
                    ((week - 1) * 7 + (day - 1)).toLong(),
                    ChronoUnit.DAYS.between(LocalDate.parse("2026-09-07"), date),
                )
            }
        }
    }
}
