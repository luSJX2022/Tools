package com.qzkt.timetable.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LRC 歌词解析。
 *
 * 格式对齐 cplayer 的 `lyrics.c`，所以这些用例同时也在验证"两个平台读同一份 .lrc 结果一致"。
 */
class LrcParserTest {

    @Test
    fun `基本时间标签与多标签同行`() {
        val lines = LrcParser.parse(
            """
            [00:01.00]第一句
            [00:05.50][01:10.00]副歌（两句共用一行）
            [00:10.25]第三句
            """.trimIndent(),
        )

        // 结果是按时间排好序的，不是按文件行序
        assertEquals(4, lines.size)
        assertEquals(listOf(1000L, 5500L, 10_250L, 70_000L), lines.map { it.timeMs })
        assertEquals("第一句", lines[0].text)
        assertEquals(2, lines.count { it.text == "副歌（两句共用一行）" })
        assertEquals("第三句", lines.single { it.timeMs == 10_250L }.text)
    }

    @Test
    fun `小数位数按两百分秒三位毫秒`() {
        val lines = LrcParser.parse("[00:01]一位\n[00:02.5]两位\n[00:03.25]三位\n[00:04.125]四位")
        assertEquals(1000L, lines[0].timeMs)
        assertEquals(2500L, lines[1].timeMs)
        assertEquals(3250L, lines[2].timeMs)
        assertEquals(4125L, lines[3].timeMs)
    }

    @Test
    fun `offset 整体平移`() {
        val lines = LrcParser.parse("[offset:500]\n[00:01.00]晚半秒")
        assertEquals(1500L, lines.single().timeMs)

        // 负 offset 往前挪，且不允许出现负数时间
        val early = LrcParser.parse("[offset:-2000]\n[00:01.00]早两秒")
        assertEquals(-1000L, early.single().timeMs)
    }

    @Test
    fun `元数据标签会被忽略`() {
        val lines = LrcParser.parse(
            """
            [ti:歌名]
            [ar:歌手]
            [al:专辑]
            [by:谁做的]
            [00:01.00]正文
            """.trimIndent(),
        )
        assertEquals(1, lines.size)
        assertEquals("正文", lines.single().text)
    }

    @Test
    fun `增强型 LRC 的词级标签被剥掉`() {
        val lines = LrcParser.parse("[00:01.00]<00:01.00>你<00:01.50>好")
        assertEquals("你好", lines.single().text)
    }

    @Test
    fun `纯文本没有时间标签时返回空`() {
        assertTrue(LrcParser.parse("这不是歌词文件\n随便两行").isEmpty())
        assertTrue(LrcParser.parse("").isEmpty())
    }

    @Test
    fun `找当前行`() {
        val lines = LrcParser.parse("[00:05.00]第一句\n[00:10.00]第二句\n[00:15.00]第三句")

        assertEquals(-1, LrcParser.lineAt(lines, 0))       // 还没到第一句
        assertEquals(-1, LrcParser.lineAt(lines, 4999))
        assertEquals(0, LrcParser.lineAt(lines, 5000))      // 正好到点
        assertEquals(0, LrcParser.lineAt(lines, 9999))
        assertEquals(1, LrcParser.lineAt(lines, 10_000))
        assertEquals(2, LrcParser.lineAt(lines, 999_999))   // 最后一句之后停在最后一句
        assertEquals(-1, LrcParser.lineAt(emptyList(), 1000))
    }

    @Test
    fun `乱序的文件按时间排好`() {
        val lines = LrcParser.parse("[00:10.00]后\n[00:01.00]前")
        assertEquals(listOf("前", "后"), lines.map { it.text })
    }
}
