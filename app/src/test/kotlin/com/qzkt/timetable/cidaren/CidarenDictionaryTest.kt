package com.qzkt.timetable.cidaren

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 词库完整性测试：内置词库 = 精讲词条 + 完整四级词库（合并去重）。
 * 四级词表来自公开的四级大纲词表（4536 条，带音标与中文释义）。
 */
class CidarenDictionaryTest {

    private val nl = "\n"

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun data() = CidarenData.fromJson(resource("cidaren/data.json"), resource("cidaren/cet4.json"))

    @Test
    fun `四级词库规模与质量`() {
        val d = data()
        assertTrue("四级词条应不少于 4500 条，实际 " + d.cet4.size, d.cet4.size >= 4500)
        assertEquals("精讲词条应为 851 条", 851, d.rich.size)
        assertTrue("合并后应超过 4600 条，实际 " + d.words.size, d.words.size > 4600)

        val expect = (d.rich + d.cet4).distinctBy { it.w.lowercase() }.size
        assertEquals("合并后条数应等于去重结果", expect, d.words.size)

        val noCn = d.cet4.filter { w -> !w.cn.any { it.code in 0x4E00..0x9FFF } }
        assertTrue("每条四级词条都该有中文释义，异常 " + noCn.size + " 条：" + noCn.take(3).map { it.w }, noCn.isEmpty())
        val noPh = d.cet4.count { it.ph.isEmpty() }
        assertTrue("带音标的四级词条应超过 95%，实际缺 " + noPh + " 条", noPh < d.cet4.size * 0.05)
        val noPos = d.cet4.count { it.pos.isEmpty() }
        assertTrue("四级词条应带词性，缺失 " + noPos + " 条", noPos < d.cet4.size * 0.05)

        val dup = d.cet4.groupBy { it.w }.filterValues { it.size > 1 }
        assertTrue("四级表里不应有重复单词：" + dup.keys.take(3), dup.isEmpty())
        val odd = d.cet4.filter { !Regex("""^[a-z][a-z'’-]*$""").matches(it.w) }
        assertTrue("单词应只含字母（可带撇号/连字符）：" + odd.take(3).map { it.w }, odd.isEmpty())
    }

    @Test
    fun `四级表特有的词也能查到并参与解析`() {
        val d = data()
        assertTrue("精讲里不该有 zebra", d.rich.none { it.w == "zebra" })
        CidarenEngine.setData(d)

        val zebra = CidarenEngine.lookup("zebra")
        assertTrue("应从四级词库查到 zebra", zebra != null)
        assertEquals("斑马", zebra!!.cn)
        assertEquals("n.", zebra.pos)

        // 中文释义 → 选词：完全靠四级词库完成
        val r = CidarenEngine.analyze("斑马" + nl + "A. zebra" + nl + "B. zone" + nl + "C. zero" + nl + "D. zoo")
        assertEquals("zebra", r.answerText)
        assertTrue("置信度应过得去，实际 " + r.confidence, r.confidence > 0.4)
    }

    @Test
    fun `同名时优先用精讲词条`() {
        CidarenEngine.setData(data())
        val abandon = CidarenEngine.lookup("abandon")
        assertTrue("abandon 应命中精讲词条", abandon != null)
        assertTrue("精讲词条带搭配", abandon!!.colloc.isNotEmpty())
        assertTrue("精讲词条带例句", abandon.ex.en.isNotEmpty())
        assertTrue("精讲词条带词根", abandon.root.isNotEmpty())
    }
}
