package com.qzkt.timetable.data.anime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeContentFilterTest {

    @Test
    fun `built in rules catch AI labels without matching ordinary titles`() {
        val aiItems = listOf(
            AnimeItem("source", "1", "AI短剧：末日重生"),
            AnimeItem("source", "2", "未来世界", remark = "AIGC作品"),
            AnimeItem("source", "3", "人工智能生成动画"),
            AnimeItem("source", "4", "AI-generated video"),
            AnimeItem("source", "5", "AI短片"),
            AnimeItem("source", "6", "【A.I.】恋爱实验"),
            AnimeItem("source", "7", "云端奇遇", remark = "人工智能制作"),
            AnimeItem("source", "8", "机器生成的奇幻世界"),
            AnimeItem("source", "9", "AI配音版"),
            AnimeItem("source", "13", "开局大帝封顶我"),
            AnimeItem("source", "14", "谁让这个文盲修仙"),
            AnimeItem("source", "15", "重生后前夫抢着娶我"),
            AnimeItem("source", "16", "踏凡问仙"),
            AnimeItem("source", "17", "收徒就变强这个徒弟"),
            AnimeItem("source", "18", "一个馒头换来的首辅夫君"),
            AnimeItem("source", "19", "女扮男装农家子的科学"),
            AnimeItem("source", "20", "裴总别装了你尾巴露出来了"),
            AnimeItem(
                "source",
                "21",
                "云上奇缘",
                filterMetadata = "剧情短剧 AIGC生成",
            ),
        )
        val ordinaryItems = listOf(
            AnimeItem("source", "10", "Said the Spider"),
            AnimeItem("source", "11", "Detail of a Dream"),
            AnimeItem("source", "12", "普通影片"),
        )

        assertTrue(aiItems.all(AnimeContentFilter::isAiGenerated))
        assertTrue(ordinaryItems.none(AnimeContentFilter::isAiGenerated))
    }

    @Test
    fun `filter can be disabled and preserves original order`() {
        val items = listOf(
            AnimeItem("source", "1", "AI动画"),
            AnimeItem("source", "2", "普通影片"),
        )

        assertEquals(listOf(items[1]), AnimeContentFilter.visible(items, enabled = true))
        assertEquals(items, AnimeContentFilter.visible(items, enabled = false))
    }
}
