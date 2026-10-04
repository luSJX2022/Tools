package com.qzkt.timetable.jw.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeriodParserTest {

    @Test
    fun `两位打包格式`() {
        assertEquals(1..2, PeriodParser.parse("0102"))
        assertEquals(3..4, PeriodParser.parse("0304"))
        assertEquals(9..10, PeriodParser.parse("0910"))
        assertEquals(11..12, PeriodParser.parse("1112"))
    }

    @Test
    fun `连字符与逗号格式`() {
        assertEquals(1..2, PeriodParser.parse("1-2"))
        assertEquals(3..4, PeriodParser.parse("3~4"))
        assertEquals(5..6, PeriodParser.parse("5,6"))
        assertEquals(1..2, PeriodParser.parse("第1-2节"))
    }

    @Test
    fun `单节`() {
        assertEquals(5..5, PeriodParser.parse("5"))
        assertEquals(12..12, PeriodParser.parse("12"))
    }

    @Test
    fun `全角数字`() {
        assertEquals(1..2, PeriodParser.parse("０１０２"))
    }

    @Test
    fun `非法输入返回 null`() {
        assertNull(PeriodParser.parse(""))
        assertNull(PeriodParser.parse("上午"))
        assertNull(PeriodParser.parse("99"))
    }
}
