package com.qzkt.timetable.ui.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CODE128（Code Set B）编码测试。
 * 小程序的收件码用 JsBarcode 画 CODE128，这里校验我们的编码与标准表逐符号一致。
 */
class Code128Test {

    /** 把模块序列还原成「条空宽度串」，便于和标准表对比。 */
    private fun runs(modules: BooleanArray): String {
        var start = 0
        while (start < modules.size && !modules[start]) start++
        var end = modules.size - 1
        while (end >= 0 && !modules[end]) end--
        val out = StringBuilder()
        if (start > end) return ""
        var current = modules[start]
        var count = 0
        for (i in start..end) {
            if (modules[i] == current) count++ else { out.append(count); current = modules[i]; count = 1 }
        }
        out.append(count)
        return out.toString()
    }

    @Test
    fun `ABC 的编码符号与标准表一致`() {
        // Start B(104) + A(33) + B(34) + C(35) + 校验码(1) + Stop(106)
        val expected = "211214" + "111323" + "131123" + "131321" + "222122" + "2331112"
        assertEquals(expected, runs(Code128.encode("ABC")))
    }

    @Test
    fun `含左右静区与固定模块数`() {
        val modules = Code128.encode("ABC")
        // 5 个 11 模块符号 + Stop 13 + 左右各 10 静区
        assertEquals(11 * 5 + 13 + 20, modules.size)
        assertFalse("左侧应是静区", modules[0])
        assertFalse("右侧应是静区", modules[modules.size - 1])
    }

    @Test
    fun `真实收件码能编码且长度正确`() {
        val code = "wx202611144403567227738328459"
        assertTrue(Code128.canEncode(code))
        val modules = Code128.encode(code)
        assertEquals(11 * (code.length + 2) + 13 + 20, modules.size)
        // 首符号必须是 Start B
        assertEquals("211214", runs(modules).substring(0, 6))
    }

    @Test
    fun `不支持的字符会被拒绝`() {
        assertFalse(Code128.canEncode(""))
        assertFalse(Code128.canEncode("收件码"))
        assertTrue(Code128.canEncode("wx2026"))
        var thrown = false
        try { Code128.encode("码") } catch (e: IllegalArgumentException) { thrown = true }
        assertTrue(thrown)
    }
}
