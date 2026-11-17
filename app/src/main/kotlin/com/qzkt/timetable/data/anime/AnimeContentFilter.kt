package com.qzkt.timetable.data.anime

/** Built-in title/remark rules for hiding AI-generated video listings. */
object AnimeContentFilter {
    private val aiMarker = Regex(
        pattern = """(?:^|[^A-Za-z0-9])(?:A[\s._·-]*I|AIGC)(?:$|[^A-Za-z0-9])|(?:^|[^A-Za-z0-9])AI(?:generated|created|produced|made|animated|cartoon|film|movie|video)""",
        option = RegexOption.IGNORE_CASE,
    )
    private val chineseAiMarker = Regex("""人工智能|生成式(?:人工智能|AI)|机器生成|智能生成""")
    private val aiShortDramaPattern = Regex(
        """(?:开局.{0,12}(?:大帝|仙帝|女帝|修仙|系统|收徒)|重生.{0,12}(?:前夫|首辅|霸总|总裁|大帝)|(?:大帝|仙帝|修仙|问仙|收徒就变强|女扮男装|农家子|文盲修仙|尾巴露出来)|馒头.{0,12}(?:夫君|首辅|少爷))""",
    )

    fun isAiGenerated(item: AnimeItem): Boolean =
        sequenceOf(item.name, item.remark, item.filterMetadata).any { text ->
            aiMarker.containsMatchIn(text) ||
                chineseAiMarker.containsMatchIn(text) ||
                aiShortDramaPattern.containsMatchIn(text)
        }

    fun visible(items: List<AnimeItem>, enabled: Boolean): List<AnimeItem> =
        if (enabled) items.filterNot(::isAiGenerated) else items
}
