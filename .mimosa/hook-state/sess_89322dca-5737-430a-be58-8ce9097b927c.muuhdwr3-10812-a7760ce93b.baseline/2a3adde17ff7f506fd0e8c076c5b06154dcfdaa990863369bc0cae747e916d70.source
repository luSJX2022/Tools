package com.qzkt.timetable.jw.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class WeekRangeParserTest {

    @Test
    fun `连续区间`() {
        assertEquals((1..16).toList(), WeekRangeParser.parse("1-16").weeks)
    }

    @Test
    fun `带周字与第字`() {
        assertEquals((1..16).toList(), WeekRangeParser.parse("第1-16周").weeks)
        assertEquals((1..8).toList(), WeekRangeParser.parse("1-8周").weeks)
    }

    @Test
    fun `离散周次`() {
        assertEquals(listOf(1, 3, 5, 6, 7, 8, 9), WeekRangeParser.parse("1,3,5-9").weeks)
        assertEquals(listOf(1, 3, 5, 7), WeekRangeParser.parse("1、3、5、7").weeks)
    }

    @Test
    fun `全角与中文逗号`() {
        assertEquals((1..16).toList(), WeekRangeParser.parse("１－１６").weeks)
        assertEquals(listOf(1, 3), WeekRangeParser.parse("1，3").weeks)
    }

    /**
     * 强智网页版的周次串长这样：`1-16(周)[0102]` —— 方括号里是节次。
     * 要是把方括号的数字也当成周次，`1-16` 会被读成 `1-160102`，整门课就没了。
     */
    @Test
    fun `方括号里的节次不算周次`() {
        assertEquals((1..16).toList(), WeekRangeParser.parse("1-16(周)[0102]").weeks)
        assertEquals(listOf(1, 3, 5, 7), WeekRangeParser.parse("1-8[0102](单)").weeks)
        assertEquals((1..16).toList(), WeekRangeParser.parse("1-16【0102】").weeks)
        assertEquals((9..16).toList(), WeekRangeParser.parse("9-16周[0506]").weeks)
    }

    @Test
    fun `单双周`() {
        val odd = WeekRangeParser.parse("1-8(单)")
        assertEquals(Parity.ODD, odd.parity)
        assertEquals(listOf(1, 3, 5, 7), odd.weeks)

        val even = WeekRangeParser.parse("1-8（双）")
        assertEquals(Parity.EVEN, even.parity)
        assertEquals(listOf(2, 4, 6, 8), even.weeks)

        val fromHint = WeekRangeParser.parse("1-8", parityHint = "单周")
        assertEquals(listOf(1, 3, 5, 7), fromHint.weeks)
    }

    @Test
    fun `同时出现单双周时不作过滤`() {
        assertEquals((1..4).toList(), WeekRangeParser.parse("1-4", parityHint = "单双周").weeks)
    }

    @Test
    fun `无法解析时返回空列表`() {
        assertEquals(emptyList<Int>(), WeekRangeParser.parse("").weeks)
        assertEquals(emptyList<Int>(), WeekRangeParser.parse("不分周次").weeks)
    }
}
