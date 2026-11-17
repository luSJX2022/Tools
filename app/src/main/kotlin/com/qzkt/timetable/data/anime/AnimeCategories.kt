package com.qzkt.timetable.data.anime

/**
 * 影视中心「筛选」页自己的分类规则 —— 不再用影视站给的分类。
 *
 * 影视站的分类标签各站不同、还经常把「电影片」「连续剧」这种名字混着给，
 * 所以 App 这边定一套固定分类，按片名 + 备注 + 资源站元数据（filterMetadata）里的关键词归类。
 * 筛选页显示的就是这套分类；取数时从站点的「全部」流里拿，再按规则在本地筛。
 */
object AnimeCategories {

    /** 「全部」的 key（空串），与 [AnimeListState.categoryId] 的默认值一致。 */
    const val ALL_KEY: String = ""

    data class Rule(
        /** 本地分类 key，稳定不变（用于选中状态）。 */
        val key: String,
        /** 展示名。 */
        val name: String,
        /** 命中任意一个关键词就归入这一类。 */
        val keywords: List<String>,
        /** 命中这些词就不算这一类（优先级高于 keywords）。 */
        val excludeKeywords: List<String> = emptyList(),
    )

    /**
     * 分类顺序就是归类优先级：短剧 → 动漫 → 综艺 → 纪录片 → 电影 → 电视剧。
     * 关键词按资源站真实 type_name 写（如「动作片」「大陆综艺」「反转爽文」「国产动漫」），
     * 所以「短剧」不会被「剧」抢走、「剧场版」也不会被当成电视剧。
     */
    val RULES: List<Rule> = listOf(
        Rule(
            key = "short",
            name = "短剧",
            keywords = listOf(
                "短剧", "微短剧", "竖屏剧", "漫剧", "爽文", "言情",
                "古装仙侠", "重生民国", "穿越年代", "甜宠", "逆袭", "虐恋",
            ),
        ),
        Rule(
            key = "anime",
            name = "动漫",
            keywords = listOf("动漫", "动画", "番剧", "国漫", "日漫", "新番", "OVA", "泡面番"),
        ),
        Rule(
            key = "variety",
            name = "综艺",
            keywords = listOf("综艺", "真人秀", "脱口秀", "晚会", "访谈", "选秀"),
        ),
        Rule(
            key = "doc",
            name = "纪录片",
            keywords = listOf("纪录片", "记录片", "纪实", "专题片", "自然探索"),
        ),
        Rule(
            key = "movie",
            name = "电影",
            // 站点把电影叫「动作片 / 喜剧片 / 科幻片…」，逐一列出，别用「片」一刀切
            // （用「片」会把简介里带「片」字的综艺也算成电影）
            keywords = listOf(
                "电影", "影片", "剧场版", "大电影", "院线", "蓝光", "HD中字", "HD国语",
                "动作片", "喜剧片", "爱情片", "科幻片", "恐怖片", "惊悚片", "悬疑片",
                "剧情片", "战争片", "犯罪片", "冒险片", "灾难片", "伦理片", "理论片",
            ),
            // 「纪录片 / 动画片 / 专题片」都带「片」，但不是电影
            excludeKeywords = listOf("纪录片", "记录片", "动画片", "专题片", "短剧", "漫剧", "综艺"),
        ),
        Rule(
            key = "tv",
            name = "电视剧",
            // 站点的剧集分类都带「剧」：国产剧 / 欧美剧 / 日本剧 / 韩国剧 / 香港剧 / 海外剧
            keywords = listOf("剧", "电视剧", "连续剧", "美剧", "韩剧", "日剧", "港剧", "台剧"),
            excludeKeywords = listOf("短剧", "剧场版", "电影", "漫剧", "综艺", "动漫", "纪录片"),
        ),
    )

    /** 筛选页的分类 chips：全部 + 上面这套规则。 */
    fun chips(): List<Pair<String, String>> =
        listOf("全部" to ALL_KEY) + RULES.map { it.name to it.key }

    fun ruleOf(key: String): Rule? = RULES.firstOrNull { it.key == key }

    /** 展示名；未知 key 原样返回（兼容旧数据）。 */
    fun labelOf(key: String): String = ruleOf(key)?.name ?: key

    /**
     * 判断一部影片属于哪个本地分类：按 RULES 顺序找第一条命中的。
     * 只看片名 + 备注 + 资源站元数据，不联网。
     */
    fun classify(item: AnimeItem): String? {
        val haystack = (item.name + " " + item.remark + " " + item.filterMetadata).lowercase()
        return RULES.firstOrNull { rule ->
            rule.excludeKeywords.none { haystack.contains(it.lowercase()) } &&
                rule.keywords.any { haystack.contains(it.lowercase()) }
        }?.key
    }

    /** 按本地分类筛：key 为空（全部）时原样返回。 */
    fun filter(items: List<AnimeItem>, key: String?): List<AnimeItem> =
        if (key.isNullOrEmpty()) items else items.filter { classify(it) == key }
}