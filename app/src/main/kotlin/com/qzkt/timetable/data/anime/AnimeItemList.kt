package com.qzkt.timetable.data.anime

/**
 * 影视列表的追加去重。
 *
 * LazyVerticalGrid 用「源 + id」当 key，只要同一个 key 出现两次 Compose 就会抛
 * IllegalArgumentException: Key ... was already used 直接闪退。
 * 快速切分类/搜索时，上一个请求晚到会把同一页再追加一次，所以追加统一走这里。
 */
object AnimeItemList {

    fun keyOf(item: AnimeItem): String = item.source + ":" + item.id

    /** 保留 current 顺序，只追加 key 不重复的 incoming。 */
    fun merge(current: List<AnimeItem>, incoming: List<AnimeItem>): List<AnimeItem> {
        if (incoming.isEmpty()) return current
        val seen = current.mapTo(HashSet(current.size + incoming.size)) { keyOf(it) }
        val out = ArrayList<AnimeItem>(current.size + incoming.size)
        out.addAll(current)
        incoming.forEach { item ->
            if (seen.add(keyOf(item))) out.add(item)
        }
        return out
    }

    /** 单页数据自身也可能带重复 id，首屏用这个去重。 */
    fun distinct(items: List<AnimeItem>): List<AnimeItem> = merge(emptyList(), items)
}
