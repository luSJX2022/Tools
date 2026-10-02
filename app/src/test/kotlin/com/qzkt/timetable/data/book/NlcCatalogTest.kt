package com.qzkt.timetable.data.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用真实抓取的国图 OPAC 检索页（搜「红楼梦」的完整页面）做夹具 ——
 * 页面结构变没变、解析坏没坏，跑这个测试就知道。
 * 夹具在 app/src/test/resources/nlc_search.html，别改它，要更新就重新抓一份真的。
 */
class NlcCatalogTest {

    private fun fixture(): String =
        javaClass.classLoader!!.getResourceAsStream("nlc_search.html")!!
            .bufferedReader().use { it.readText() }

    @Test
    fun `解析检索结果页`() {
        val page = parseNlcSearchPage(fixture(), "http://opac.nlc.cn/", 1)

        assertEquals(9151, page.total)
        assertEquals(1, page.page)
        assertEquals(10, page.records.size)   // 一页 10 条
        assertEquals(916, page.pageCount)
        assertTrue(page.hasNext)
        assertFalse(page.hasPrev)
        assertTrue(page.sessionUrl!!.startsWith("http://opac.nlc.cn:80/F/"))
    }

    @Test
    fun `一条记录的完整字段`() {
        val page = parseNlcSearchPage(fixture(), "http://opac.nlc.cn/", 1)

        // 第 1 条：标题 + 系统号
        assertEquals("NLC01014133540", page.records[0].docNumber)
        assertTrue(page.records[0].title.startsWith("诗情“话”意"))

        // 第 2 条（敝帚集），字段值都对照原始抓取页核过
        val record = page.records[1]
        assertEquals("NLC01014182394", record.docNumber)
        assertEquals("敝帚集 [专著] : 冯其庸论《红楼梦》 / 冯其庸著", record.title)
        assertEquals("冯其庸 (1924~2017) 著", record.author)
        assertEquals("北京时代华文书局", record.publisher)
        assertEquals("2026", record.year)
        assertEquals("978-7-5699-5162-2 CNY98.00", record.isbn)
        assertEquals("图书", record.format)   // BK 翻成中文
        assertTrue(record.detailUrl!!.startsWith("http://opac.nlc.cn"))
    }

    @Test
    fun `翻页状态在最后一页不会越界`() {
        val last = NlcSearchPage(total = 9151, page = 916, records = emptyList())
        assertFalse(last.hasNext)
        assertTrue(last.hasPrev)

        val only = NlcSearchPage(total = 3, page = 1, records = emptyList())
        assertFalse(only.hasNext)
        assertFalse(only.hasPrev)
        assertEquals(1, only.pageCount)
    }
}
