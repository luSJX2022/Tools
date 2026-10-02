package com.qzkt.timetable.ui.player.link

/**
 * 分享链接的识别。
 *
 * 用户从 B站 / 抖音 App 里点「复制链接」，拿到的往往不是一条干净的地址，而是
 * 「【标题】 https://b23.tv/xxxx 复制此链接，打开App…」这样一整段文案；短链本身也
 * 看不出作品号。这里负责把文案里的地址抠出来、认平台、把 id 认出来。
 *
 * 这一层不联网（短链的跳转在解析器里做），所以识别逻辑可以拿真实分享文案直接测。
 */
enum class MediaPlatform(val label: String) {
    BILIBILI("B站"),
    DOUYIN("抖音"),
}

/** 认出来的一条链接。 */
sealed interface MediaLink {
    val platform: MediaPlatform

    /** 用户给的那条地址，短链就是短链。 */
    val url: String

    /**
     * B站视频、番剧或图集。
     *
     * 普通投稿用 [bvid]（或老的 [aid]），番剧用 [epId] / [ssId]，
     * **图文动态用 [opusId]、专栏用 [cvId]**；
     * 短链（b23.tv）刚拿到时这些都还是空的，要跟一次跳转才知道。
     */
    data class Bilibili(
        override val url: String,
        val bvid: String? = null,
        val aid: Long? = null,
        val epId: Long? = null,
        val ssId: Long? = null,
        /** 图文动态的 id（`www.bilibili.com/opus/{id}` 或 `t.bilibili.com/{id}`）。 */
        val opusId: Long? = null,
        /** 专栏 id（`www.bilibili.com/read/cv{id}`）。 */
        val cvId: Long? = null,
        /** `?p=2` 里的分P，从 1 开始。 */
        val page: Int = 1,
    ) : MediaLink {
        override val platform: MediaPlatform get() = MediaPlatform.BILIBILI
    }

    /** 抖音作品（视频 / 图文）。[awemeId] 为空时表示还是短链，需要跟跳转。 */
    data class Douyin(
        override val url: String,
        val awemeId: String? = null,
    ) : MediaLink {
        override val platform: MediaPlatform get() = MediaPlatform.DOUYIN
    }
}

private val BILIBILI_HOSTS = listOf("bilibili.com", "b23.tv", "bili2233.cn")
private val DOUYIN_HOSTS = listOf("douyin.com", "iesdouyin.com", "amemv.com")

/** 分享文案里的 http(s) 地址；中文标点直接当边界，省得把后面的说明文字也吞进来。 */
private val URL_IN_TEXT = Regex(
    """https?://[^\s\u4e00-\u9fff，。！？；：、"“”‘’（）()【】《》]+""",
    RegexOption.IGNORE_CASE,
)

/** 有的分享文案只有域名没有协议头（例如 www.bilibili.com/video/BV…）。 */
private val BARE_URL_IN_TEXT = Regex(
    """(?:www\.|m\.|v\.)?(?:bilibili\.com|b23\.tv|douyin\.com|iesdouyin\.com)/[^\s\u4e00-\u9fff，。！？；：、"“”‘’（）()【】《》]*""",
    RegexOption.IGNORE_CASE,
)

private val BV_PATTERN = Regex("""/video/(BV[0-9A-Za-z]{10})""")
private val AV_PATTERN = Regex("""/video/av(\d+)""", RegexOption.IGNORE_CASE)
private val EP_PATTERN = Regex("""/bangumi/play/ep(\d+)""", RegexOption.IGNORE_CASE)
private val SS_PATTERN = Regex("""/bangumi/play/ss(\d+)""", RegexOption.IGNORE_CASE)

/** 图文动态：`/opus/{id}`；老分享域 t.bilibili.com 直接用 `/{id}`，见 [parseBilibiliUrl]。 */
private val OPUS_PATTERN = Regex("""/opus/(\d+)""")

/** 专栏：`/read/cv{id}`。 */
private val CV_PATTERN = Regex("""/read/cv(\d+)""", RegexOption.IGNORE_CASE)

private val AWEME_ID_PATTERNS = listOf(
    Regex("""/(?:share/)?video/(\d{5,})"""),
    Regex("""/(?:share/)?note/(\d{5,})"""),
    Regex("""/(?:share/)?slides/(\d{5,})"""),
)

/**
 * 从一段文本（分享文案或直接粘贴的地址）里抠出第一条地址。
 *
 * 返回的地址已经去掉结尾的标点 —— 分享文案常常是「… https://b23.tv/abc123，复制此链接」，
 * 那个逗号是中文逗号还好，英文逗号/句点也常出现，留着会让请求 404。
 */
fun extractShareUrl(text: String): String? {
    val match = URL_IN_TEXT.find(text) ?: BARE_URL_IN_TEXT.find(text)
    val raw = match?.value ?: return null
    val cleaned = raw.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'')
    if (cleaned.isEmpty()) return null
    return if (cleaned.startsWith("http", true)) cleaned else "https://$cleaned"
}

/**
 * 认出一条 B站 / 抖音链接；不是这两个平台的地址返回 null（调用方按普通流地址处理）。
 */
fun detectShareLink(text: String): MediaLink? {
    val url = extractShareUrl(text) ?: return null
    val host = hostOf(url) ?: return null
    return when {
        hostMatches(host, BILIBILI_HOSTS) -> parseBilibiliUrl(url)
        hostMatches(host, DOUYIN_HOSTS) -> parseDouyinUrl(url)
        else -> null
    }
}

/**
 * 只看地址的形状，不看域名 —— 短链跳转之后拿到的是新地址，得再解析一次。
 */
internal fun parseBilibiliUrl(url: String): MediaLink.Bilibili {
    val path = pathOf(url)
    val query = queryOf(url)
    val host = hostOf(url).orEmpty()
    // t.bilibili.com/{id} 就是动态的老分享域，路径本身只有一串数字
    val legacyDynamic = if (host == "t.bilibili.com" || host.endsWith(".t.bilibili.com")) {
        Regex("""^/(\d{5,})/?""").find(path)?.groupValues?.get(1)?.toLongOrNull()
    } else {
        null
    }
    return MediaLink.Bilibili(
        url = url,
        bvid = BV_PATTERN.find(path)?.groupValues?.get(1),
        aid = AV_PATTERN.find(path)?.groupValues?.get(1)?.toLongOrNull(),
        epId = EP_PATTERN.find(path)?.groupValues?.get(1)?.toLongOrNull(),
        ssId = SS_PATTERN.find(path)?.groupValues?.get(1)?.toLongOrNull(),
        opusId = OPUS_PATTERN.find(path)?.groupValues?.get(1)?.toLongOrNull() ?: legacyDynamic,
        cvId = CV_PATTERN.find(path)?.groupValues?.get(1)?.toLongOrNull(),
        page = queryParam(query, "p")?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
    )
}

/** 同上：域名由调用方判断，这里只认作品号。 */
internal fun parseDouyinUrl(url: String): MediaLink.Douyin {
    val path = pathOf(url)
    val query = queryOf(url)
    val fromPath = AWEME_ID_PATTERNS.firstNotNullOfOrNull { it.find(path)?.groupValues?.get(1) }
    val fromQuery = listOf("modal_id", "vid", "item_ids", "aweme_id")
        .firstNotNullOfOrNull { name -> queryParam(query, name)?.takeIf { it.all(Char::isDigit) && it.length >= 5 } }
    return MediaLink.Douyin(url = url, awemeId = fromPath ?: fromQuery)
}

/** 主页/直播之类的地址可能是这一串数字，直接从 HTML 里捞。 */
internal fun awemeIdInHtml(html: String): String? =
    Regex(""""aweme_id"\s*:\s*"(\d{5,})"""").find(html)?.groupValues?.get(1)
        ?: Regex("""/(?:video|note)/(\d{10,})""").find(html)?.groupValues?.get(1)

internal fun hostOf(url: String): String? =
    runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()

internal fun hostMatches(host: String, known: List<String>): Boolean =
    known.any { host == it || host.endsWith(".$it") }

internal fun pathOf(url: String): String =
    runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault("")

internal fun queryOf(url: String): String =
    runCatching { java.net.URI(url).query.orEmpty() }.getOrDefault("")

internal fun queryParam(query: String?, name: String): String? {
    if (query.isNullOrEmpty()) return null
    return query.split('&').firstNotNullOfOrNull { part ->
        val eq = part.indexOf('=')
        if (eq > 0 && part.substring(0, eq).equals(name, ignoreCase = true)) part.substring(eq + 1) else null
    }
}
