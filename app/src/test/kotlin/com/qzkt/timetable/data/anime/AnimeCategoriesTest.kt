package com.qzkt.timetable.data.anime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 本地分类规则测试：筛选页不再用影视站分类，全靠这套规则。 */
class AnimeCategoriesTest {

    private fun item(name: String, remark: String = "", meta: String = "") =
        AnimeItem(source = "test", id = name, name = name, remark = remark, filterMetadata = meta)

    @Test
    fun `按片名、备注、资源站元数据归类`() {
        assertEquals("movie", AnimeCategories.classify(item("流浪地球2", "HD国语", "电影")))
        assertEquals("anime", AnimeCategories.classify(item("进击的巨人 最终季", "更新至第28集", "动漫")))
        assertEquals("variety", AnimeCategories.classify(item("奔跑吧兄弟", "第7期", "综艺")))
        assertEquals("doc", AnimeCategories.classify(item("地球脉动", "", "纪录片")))
        assertEquals("short", AnimeCategories.classify(item("总裁的替身新娘", "全80集", "短剧")))
        assertEquals("tv", AnimeCategories.classify(item("狂飙", "全39集", "国产剧")))
        assertNull("无关键词时不应硬塞分类", AnimeCategories.classify(item("某个条目")))
    }

    @Test
    fun `剧场版不归电视剧；动画剧场版归动漫`() {
        // 优先级：短剧 → 动漫 → 综艺 → 纪录片 → 电影 → 电视剧
        assertEquals("anime", AnimeCategories.classify(item("某动画 剧场版", "HD中字", "")))
        assertEquals("movie", AnimeCategories.classify(item("某电影 剧场版", "HD中字", "")))
        assertEquals("movie", AnimeCategories.classify(item("名侦探柯南：百万美元的五棱星", "电影", "电影")))
        // 电视剧排除「剧场版」
        assertEquals("movie", AnimeCategories.classify(item("剧集改编 剧场版", "", "动作片")))
    }

    @Test
    fun `筛选：空 key 返回全部，指定 key 只留命中`() {
        val items = listOf(
            item("流浪地球2", "HD国语", "电影"),
            item("进击的巨人", "更新至第28集", "动漫"),
            item("奔跑吧兄弟", "第7期", "综艺"),
        )
        assertEquals(items, AnimeCategories.filter(items, ""))
        assertEquals(items, AnimeCategories.filter(items, null))
        assertEquals(listOf(items[1]), AnimeCategories.filter(items, "anime"))
        assertEquals(listOf(items[0]), AnimeCategories.filter(items, "movie"))
    }

    @Test
    fun `chips：全部在最前，key 不重复`() {
        val chips = AnimeCategories.chips()
        assertEquals("全部" to "", chips.first())
        assertEquals(AnimeCategories.RULES.size + 1, chips.size)
        assertEquals(AnimeCategories.RULES.size, chips.drop(1).map { it.second }.toSet().size)
        assertEquals(AnimeCategories.RULES.size, chips.drop(1).map { it.first }.toSet().size)
        assertEquals("电影", AnimeCategories.labelOf("movie"))
        // 未知 key 原样返回，兼容旧数据
        assertEquals("old-key", AnimeCategories.labelOf("old-key"))
    }

    @Test
    fun `按资源站真实分类名归类`() {
        val cases = mapOf(
            "动作片" to "movie", "喜剧片" to "movie", "恐怖片" to "movie",
            "大陆综艺" to "variety", "港台综艺" to "variety", "日韩综艺" to "variety",
            "国产动漫" to "anime", "日韩动漫" to "anime",
            "国产剧" to "tv", "欧美剧" to "tv", "韩国剧" to "tv", "香港剧" to "tv", "海外剧" to "tv",
            "短剧" to "short", "反转爽文" to "short", "现代言情" to "short", "AI漫剧" to "short",
            "纪录片" to "doc",
        )
        cases.forEach { (type, expect) ->
            val item = AnimeItem(source = "bfzy", id = "1", name = "测试", filterMetadata = type)
            assertEquals("「" + type + "」应归到 " + expect, expect, AnimeCategories.classify(item))
        }
        // 动画片属于动漫，不能算电影（虽然带「片」）
        assertEquals("anime", AnimeCategories.classify(AnimeItem("bfzy", "2", "测试", filterMetadata = "动画片")))
        // 综艺简介里带「片」字也不能被算成电影（线上真实数据踩过）
        assertEquals("variety", AnimeCategories.classify(AnimeItem("lziapi", "3", "跟风旅行团", filterMetadata = "港台综艺")))
        // 站点写「记录片」也要能落到纪录片
        assertEquals("doc", AnimeCategories.classify(AnimeItem("lziapi", "4", "公开的秘密", filterMetadata = "记录片")))
    }
}
