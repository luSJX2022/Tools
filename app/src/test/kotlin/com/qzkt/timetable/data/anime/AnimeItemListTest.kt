package com.qzkt.timetable.data.anime

import org.junit.Assert.assertEquals
import org.junit.Test

/** LazyGrid 重复 key 闪退（IllegalArgumentException: Key ... was already used）的防护测试。 */
class AnimeItemListTest {

    private fun item(source: String = "lziapi", id: String) = AnimeItem(source = source, id = id, name = "片$id")

    @Test
    fun `追加时丢掉重复 key`() {
        val first = listOf(item(id = "162599"), item(id = "162600"))
        val second = listOf(item(id = "162600"), item(id = "162601"))
        val merged = AnimeItemList.merge(first, second)
        assertEquals(listOf("162599", "162600", "162601"), merged.map { it.id })
    }

    @Test
    fun `同一页内部重复也去重`() {
        val page = listOf(item(id = "1"), item(id = "1"), item(id = "2"))
        assertEquals(listOf("1", "2"), AnimeItemList.distinct(page).map { it.id })
    }

    @Test
    fun `不同源同 id 不算重复`() {
        val items = listOf(item("bfzy", "1"), item("lziapi", "1"))
        assertEquals(2, AnimeItemList.distinct(items).size)
    }

    @Test
    fun `空输入保持原样`() {
        val items = listOf(item(id = "1"))
        assertEquals(items, AnimeItemList.merge(items, emptyList()))
        assertEquals(emptyList<AnimeItem>(), AnimeItemList.distinct(emptyList()))
    }
}
