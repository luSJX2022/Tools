package com.qzkt.timetable.cidaren

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自定义词库导入解析的测试（对照网页版 parseGlossary 的行为）。 */
class CidarenGlossaryTest {

    private val nl = "\n"

    @Test
    fun `CSV 行能识别音标与词性`() {
        val list = CidarenGlossary.parse("abandon,əˈbændən,v.,放弃；抛弃")
        assertEquals(1, list.size)
        val w = list[0]
        assertEquals("abandon", w.w)
        assertEquals("əˈbændən", w.ph)
        assertEquals("v.", w.pos)
        assertEquals("放弃；抛弃", w.cn)
        assertTrue(w.custom)
    }

    @Test
    fun `空格分隔行与注释行`() {
        val text = "resilient 有韧性的" + nl + "# 这是注释" + nl + "123 不是单词" + nl + "  " + nl + "cope,应对"
        val list = CidarenGlossary.parse(text)
        assertEquals(2, list.size)
        assertEquals("resilient", list[0].w)
        assertEquals("有韧性的", list[0].cn)
        assertEquals("cope", list[1].w)
    }

    @Test
    fun `JSON 字符串数组`() {
        val list = CidarenGlossary.parse("[\"ephemeral\", \"resilient\"]")
        assertEquals(2, list.size)
        assertEquals("ephemeral", list[0].w)
        assertEquals("resilient", list[1].w)
    }

    @Test
    fun `JSON 词条数组与字段别名`() {
        val json = """{"words":[{"word":"serendipity","meaning":"意外发现","phonetic":"/ˌserənˈdɪpəti/"},{"w":"cope","cn":"应对"}]}"""
        val list = CidarenGlossary.parse(json)
        assertEquals(2, list.size)
        assertEquals("serendipity", list[0].w)
        assertEquals("意外发现", list[0].cn)
        assertEquals("/ˌserənˈdɪpəti/", list[0].ph)
        assertEquals("cope", list[1].w)
    }

    @Test
    fun `非法输入不会产生词条`() {
        assertTrue(CidarenGlossary.parse("").isEmpty())
        assertTrue(CidarenGlossary.parse("中文一行").isEmpty())
        assertTrue(CidarenGlossary.parse("{坏 JSON").isEmpty())
    }

    @Test
    fun `合并时同名单词以新导入为准`() {
        val old = CidarenGlossary.parse("cope 应对（旧）")
        val incoming = CidarenGlossary.parse("cope 应对（新）" + nl + "resilient 有韧性的")
        val merged = CidarenGlossary.merge(old, incoming)
        assertEquals(2, merged.size)
        assertEquals("cope", merged[0].w)
        assertEquals("应对（新）", merged[0].cn)
    }

    @Test
    fun `导入的词条能进引擎并被查到`() {
        val data = CidarenData.fromJson(
            javaClass.classLoader!!.getResourceAsStream("cidaren/data.json")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        )
        val custom = CidarenGlossary.parse("serendipity,ˌserənˈdɪpəti,n.,意外发现美好事物的能力")
        CidarenEngine.setData(data, custom)
        val entry = CidarenEngine.lookup("serendipity")
        assertTrue("自定义词条应能被查到", entry != null)
        assertEquals("意外发现美好事物的能力", entry!!.cn)
        assertTrue("应标记为自定义", entry.custom)
        // 自定义词条的释义要能参与「中译英选词」
        val r = CidarenEngine.analyze("意外发现美好事物的能力" + nl + "A. serendipity" + nl + "B. resilient" + nl + "C. cope" + nl + "D. abandon")
        assertEquals("serendipity", r.answerText)
        CidarenEngine.setData(data, emptyList())
    }
}
