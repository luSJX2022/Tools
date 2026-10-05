package com.qzkt.timetable.ui.player.link

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 抖音：短链先跟跳转拿到作品号（aweme_id），再去分享页里抠 `_ROUTER_DATA`。
 *
 * 抖音没有给普通开发者留「按 id 取直链」的公开接口：网页版接口要 a_bogus 签名，
 * 老接口经常空手而归。分享页（iesdouyin.com/share/video/{id}/）是唯一不需要签名、
 * 又能拿到播放地址的入口，它的 HTML 里内嵌了一整块 JSON，播放地址就在里面。
 *
 * 两点必须注意：
 * - 分享页只认手机 UA，桌面 UA 会拿到空壳页面；
 * - 直链里有 `/playwm/` 的是**带水印**的那一路，换成 `/play/` 才是不带水印的。
 */
class DouyinResolver(
    private val client: OkHttpClient = defaultLinkClient(),
    private val webBase: String = "https://www.iesdouyin.com",
    private val apiBase: String = "https://www.iesdouyin.com",
    /** 换 ttwid 的接口；单测里指到假服务器，免得测试去打真实网络。 */
    private val ttwidUrl: String = DEFAULT_TTWID_URL,
    /**
     * 补音乐播放地址的 app 接口（按 music_id 查）；分享页 SSR 不给 play_url，
     * 单测里指到假服务器。
     */
    private val musicApiBase: String = "https://aweme.snssdk.com",
) {

    /** ttwid 只取一次。 */
    private var ttwidChecked = false

    /** 注册接口换到的 ttwid 值；分享页请求要显式带上（见 [shareHeaders]）。 */
    private var ttwidValue: String? = null

    suspend fun resolve(link: MediaLink.Douyin, userHeaders: Map<String, String> = emptyMap()): ResolvedMedia =
        withContext(Dispatchers.IO) { resolveBlocking(link, userHeaders) }

    private fun resolveBlocking(link: MediaLink.Douyin, userHeaders: Map<String, String>): ResolvedMedia {
        ensureTtwid(userHeaders)

        var awemeId = link.awemeId
        var musicId = link.musicId
        // 作品类型：普通视频 / 图集（note）/ 幻灯片（slides）—— 三者的分享页路径不一样
        var kind = kindOf(link.url)
        var firstPage: String? = null
        if (awemeId == null) {
            val jumped = client.fetch(link.url, shareHeaders(userHeaders, MOBILE_UA))
            firstPage = jumped.body.takeIf { it.isNotBlank() }
            val parsed = parseDouyinUrl(jumped.finalUrl)
            awemeId = parsed.awemeId
            musicId = musicId ?: parsed.musicId
            if (awemeId == null && musicId == null) {
                awemeId = firstPage?.let { awemeIdInHtml(it) }
                    ?: throw LinkResolveException("这条抖音链接里没有作品号（HTTP " + jumped.code + "）")
            }
            // 短链跳转后的真身路径带着类型（…/share/note/{id}/），覆盖短链上认不出来的
            kindOf(jumped.finalUrl).takeIf { it != KIND_VIDEO }?.let { kind = it }
        }

        // 音乐页链接：不抓分享页（音乐分享页没有作品数据），直接按音乐解析
        if (awemeId == null && musicId != null) {
            return resolveMusic(musicId, userHeaders)
        }

        // 图集（note）的分享页在 /share/note/{id}/，用 /share/video/{id}/ 去要会拿错页面
        val shareUrl = "$webBase/share/$kind/$awemeId/"
        var lastHtml = firstPage
        // 图文（note）作品里除了 images 还带一段自动生成的幻灯片视频（play_addr），
        // 但那段地址包出来经常是坏的 —— **有图集就按图集返回，视频只做兜底**。
        var videoFallback: DouyinItem? = null
        var videoFallbackHtml: String? = null
        var videoFallbackUa: String = MOBILE_UA
        USER_AGENTS.forEachIndexed { index, ua ->
            val html = (if (index == 0) firstPage else null)
                ?: client.fetch(shareUrl, shareHeaders(userHeaders, ua)).body
            lastHtml = html
            val item = parseRouterData(html)?.let { fillMusic(it, userHeaders) }
            if (item?.images?.isNotEmpty() == true) {
                Log.i(TAG, "抖音分享页命中 UA#" + (index + 1) + "（图集 " + item.images.size + " 张）")
                return item.asResolved(html, playHeaders(userHeaders, ua))
            }
            if (item?.playUrl != null && videoFallback == null) {
                videoFallback = item
                videoFallbackHtml = html
                videoFallbackUa = ua
            }
            Log.w(TAG, "抖音 UA#" + (index + 1) + " 没有图集：" + (routerDataStructure(html) ?: "没有 _ROUTER_DATA"))
        }

        // 所有 UA 都没拿到图集，但拿到了视频地址：按视频返回
        videoFallback?.let { item ->
            Log.i(TAG, "抖音按视频兜底返回")
            return item.asResolved(videoFallbackHtml.orEmpty(), playHeaders(userHeaders, videoFallbackUa))
        }

        // 老接口兜底（分享页改版时偶尔还能用）
        val fallback = parseItemInfo(
            client.fetch("$apiBase/web/api/v2/aweme/iteminfo/?item_ids=$awemeId", shareHeaders(userHeaders, MOBILE_UA)).body,
        )?.let { fillMusic(it, userHeaders) }
        if (fallback != null && (fallback.images.isNotEmpty() || fallback.playUrl != null)) {
            return fallback.asResolved(lastHtml.orEmpty(), playHeaders(userHeaders, MOBILE_UA))
        }

        // 说清楚卡在哪一步，别让用户对着一句「失败」猜
        val html = lastHtml.orEmpty()
        val hint = when {
            html.isBlank() -> "分享页是空的"
            !html.contains("_ROUTER_DATA") -> "分享页里没有 _ROUTER_DATA（页面可能改版或被要求验证）"
            else -> "分享页里没有播放地址或图集" + (routerDataSummary(html)?.let { "（$it）" } ?: "")
        }
        throw LinkResolveException("抖音没有返回可看的内容（" + hint + "）：链接可能已失效，或这个作品需要登录才能看")
    }

    /**
     * 分享页的 SSR 必须带 ttwid（实测无 ttwid 时图文分享页是空壳，`_ROUTER_DATA`
     * 里只有页面元数据、没有作品数据）——显式放进 Cookie 头，不依赖 CookieJar
     * 的域名匹配；jar 里其它的抖音 Cookie 一起带上无妨。
     */
    private fun shareHeaders(userHeaders: Map<String, String>, ua: String): Map<String, String> {
        val cookies = (client.cookieJar as? LinkCookieJar)?.header().orEmpty()
            .ifBlank { ttwidValue?.let { "ttwid=$it" } ?: "" }
        val base = userHeaders + mapOf("User-Agent" to ua, "Referer" to "https://www.douyin.com/")
        return if (cookies.isBlank()) base else base + ("Cookie" to cookies)
    }

    /** 播放/下载那一侧不走 CookieJar，Cookie 得显式带（ttwid 之类）。 */
    private fun playHeaders(userHeaders: Map<String, String>, ua: String): Map<String, String> =
        userHeaders + mapOf("User-Agent" to ua) +
            ((client.cookieJar as? LinkCookieJar)?.header()?.takeIf { it.isNotBlank() }
                ?.let { mapOf("Cookie" to it) } ?: emptyMap())

    /**
     * 补背景音乐播放地址。
     *
     * 实测分享页 SSR 的 `music` 只有元数据（mid / 歌名 / 封面），**没有 play_url** ——
     * 直接读永远拿不到 BGM。用 mid 走 app 的 music/detail 接口把 play_url 换回来；
     * 接口挂了或音乐没地址（纯原创声被下架等）就保持 null，界面不出现 BGM 区块。
     */
    private fun fillMusic(item: DouyinItem, userHeaders: Map<String, String>): DouyinItem {
        if (item.musicUrl != null || item.musicId == null) return item
        val body = runCatching {
            client.fetch(musicDetailUrl(item.musicId), shareHeaders(userHeaders, MOBILE_UA)).body
        }.getOrNull() ?: return item
        val music = parseMusicDetail(body) ?: return item
        Log.i(TAG, "抖音 BGM 走 music/detail 补齐：" + (music.title ?: item.musicId))
        return item.copy(musicUrl = music.url, musicTitle = item.musicTitle ?: music.title)
    }

    /** 音乐页链接的解析：直接按音乐返回（封面 / 歌名 / 播放地址都在 music/detail 里）。 */
    private fun resolveMusic(musicId: String, userHeaders: Map<String, String>): ResolvedMedia {
        val body = runCatching {
            client.fetch(musicDetailUrl(musicId), shareHeaders(userHeaders, MOBILE_UA)).body
        }.getOrNull().orEmpty()
        val music = parseMusicDetail(body)
            ?: throw LinkResolveException("抖音没有返回这首音乐的播放地址（音乐可能已下架或需要登录）")
        return ResolvedMedia(
            url = music.url,
            title = music.title,
            headers = playHeaders(userHeaders, MOBILE_UA),
            platform = MediaPlatform.DOUYIN,
            cover = music.cover,
            description = music.title,
        )
    }

    private fun musicDetailUrl(musicId: String): String =
        "$musicApiBase/aweme/v1/music/detail/?music_id=$musicId" +
            "&aid=1128&version_name=23.5.0&device_platform=android&os_version=2333"

    private fun DouyinItem.asResolved(page: String, headers: Map<String, String>): ResolvedMedia =
        ResolvedMedia(
            // 图集作品以图为主：不放幻灯片视频的播放地址，界面按图集展示
            url = if (images.isNotEmpty()) "" else playUrl.orEmpty(),
            title = title ?: titleInHtml(page),
            headers = headers,
            platform = MediaPlatform.DOUYIN,
            author = author,
            cover = cover,
            publishTime = publishTime,
            playCount = playCount,
            // 抖音没有单独的简介，作品文案（desc）就是它
            description = title,
            images = images,
            musicUrl = musicUrl,
            musicTitle = musicTitle,
        )

    /**
     * 抖音对没有反爬 Cookie 的请求会返回**空壳分享页**：图文（note）分享页实测
     * `_ROUTER_DATA` 里只有页面元数据、压根没有作品数据，现象就是「解析不到」。
     * 先去字节的 ttwid 注册接口换一个 ttwid 回来。注册接口种的 Cookie 域名
     * 不一定匹配 iesdouyin.com，所以这里显式挂到 `.douyin.com`（见
     * [LinkCookieJar.put]），并留一份给 [shareHeaders] 显式带上。
     */
    private fun ensureTtwid(headers: Map<String, String>) {
        if (ttwidChecked) return
        ttwidChecked = true
        val jar = client.cookieJar as? LinkCookieJar ?: return
        if (jar.has("ttwid")) return
        runCatching {
            client.newCall(
                Request.Builder().url(ttwidUrl)
                    .post(TTWID_BODY.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build(),
            ).execute().use { resp ->
                resp.headers("Set-Cookie").forEach { line ->
                    val value = Regex("ttwid=([^;]+)").find(line)?.groupValues?.get(1) ?: return@forEach
                    ttwidValue = value
                    jar.put(".douyin.com", "ttwid", value)
                }
            }
            Log.w(TAG, "ttwid=" + jar.has("ttwid"))
        }
    }

    private companion object {
        const val TAG = "QzLink"

        const val KIND_VIDEO = "video"
        const val KIND_NOTE = "note"
        const val KIND_SLIDES = "slides"

        const val DEFAULT_TTWID_URL = "https://ttwid.bytedance.com/ttwid/union/register/"

        const val TTWID_BODY =
            "{\"region\":\"cn\",\"aid\":1768,\"needFid\":false,\"service\":\"www.ixigua.com\"," +
                "\"migrate_info\":{\"ticket\":\"\",\"source\":\"node\"},\"cbUrlProtocol\":\"https\",\"union\":true}"

        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        /** 分享页给不给播放地址跟 UA 有关，按实测顺序排。 */
        const val IPHONE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"

        const val WECHAT_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36 MicroMessenger/8.0.49.2600(0x2800313D) WeChat/arm64"

        /**
         * 分享页给不给播放地址跟 UA 有关，但**不是固定的**：实测同一个 UA 两次请求结果都能不一样
         * （一次 iPhone UA 命中，另一次 iPhone / 微信 全落空、安卓 Chrome 才命中）。
         * 所以这里只排个大概顺序挨个试，别押注某一个 —— 谁回 `videoInfoRes.item_list` 就用谁。
         */
        val USER_AGENTS = listOf(IPHONE_UA, WECHAT_UA, MOBILE_UA)
    }
}

internal data class DouyinItem(
    val title: String?,
    val playUrl: String?,
    val author: String? = null,
    val cover: String? = null,
    /** 发布时间（epoch 秒）。 */
    val publishTime: Long? = null,
    val playCount: Long? = null,
    /** 图集作品的原图直链（视频作品为空）。 */
    val images: List<String> = emptyList(),
    /** 作品带的背景音乐（图文作品的 BGM 就在这里面）。 */
    val musicUrl: String? = null,
    val musicTitle: String? = null,
    /**
     * 音乐 id（分享页 SSR 只给元数据不给播放地址，靠它去 [DouyinResolver] 的
     * music/detail 接口补 play_url）。
     */
    val musicId: String? = null,
)

/**
 * 整棵树里找作品：**图集（带 images）优先**，其次才是带播放地址的视频作品 ——
 * 图文作品里 images 和幻灯片 play_addr 会同时出现，图集才是用户要的东西。
 */
internal fun parseRouterData(html: String): DouyinItem? {
    val marker = html.indexOf("_ROUTER_DATA")
    if (marker < 0) return null
    val start = html.indexOf('{', marker)
    if (start < 0) return null
    val json = jsonObjectAt(html, start) ?: return null
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
    // 不写死 loaderData → videoInfoRes → item_list 这条路径：抖音换过好几次结构，
    // 而且图文、直播、合集用的键都不一样。整棵树里找，两轮扫描：先图集后视频。
    var videoFallback: DouyinItem? = null

    fun walk(node: Any?): DouyinItem? {
        when (node) {
            is JSONObject -> {
                val item = itemOf(node)
                if (item != null) {
                    if (item.images.isNotEmpty()) return item
                    if (item.playUrl != null && videoFallback == null) videoFallback = item
                }
                node.keys().asSequence().forEach { key -> walk(node.opt(key))?.let { return it } }
            }
            is JSONArray -> {
                for (index in 0 until node.length()) walk(node.opt(index))?.let { return it }
            }
        }
        return null
    }

    walk(root)?.let { return it }
    return videoFallback
}

/**
 * 分享页里为什么没有地址：把 item_list 条数和页面给的过滤 / 风控提示抠出来。
 * 只报一句「没有返回播放地址」等于什么都没说，用户和排查的人都得靠猜。
 */
internal fun routerDataSummary(html: String): String? {
    val marker = html.indexOf("_ROUTER_DATA")
    if (marker < 0) return null
    val start = html.indexOf('{', marker)
    if (start < 0) return null
    val json = jsonObjectAt(html, start) ?: return null
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
    val reasons = filterReasons(root)
    return buildString {
        append("item_list=").append(countItems(root))
        if (reasons.isNotEmpty()) append("，页面提示：").append(reasons.distinct().joinToString("；"))
    }
}

/** 排查用：把分享页里 loaderData 的键和各 videoInfoRes 的开头打出来（只看日志，不显示给用户）。 */
internal fun routerDataStructure(html: String): String? {
    val marker = html.indexOf("_ROUTER_DATA")
    if (marker < 0) return null
    val start = html.indexOf('{', marker)
    if (start < 0) return null
    val json = jsonObjectAt(html, start) ?: return null
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
    val loader = root.optJSONObject("loaderData") ?: return "没有 loaderData"
    val keys = loader.keys().asSequence().toList()
    return buildString {
        append("loaderData=[").append(keys.joinToString(",")).append(']')
        keys.forEach { key ->
            val info = loader.optJSONObject(key)?.optJSONObject("videoInfoRes") ?: return@forEach
            append(' ').append(key).append(".videoInfoRes=").append(info.toString().take(220))
        }
    }
}

private fun countItems(node: Any?): Int = when (node) {
    is JSONObject -> {
        var total = node.optJSONArray("item_list")?.length() ?: 0
        node.keys().asSequence().forEach { total += countItems(node.opt(it)) }
        total
    }
    is JSONArray -> (0 until node.length()).sumOf { countItems(node.opt(it)) }
    else -> 0
}

private fun filterReasons(node: Any?): List<String> = when (node) {
    is JSONObject -> buildList {
        node.optJSONArray("filter_list")?.let { list ->
            for (index in 0 until list.length()) {
                val entry = list.optJSONObject(index) ?: continue
                val text = entry.optString("detail_msg")
                    .ifBlank { entry.optString("filter_reason") }
                    .ifBlank { entry.optString("notice") }
                if (text.isNotBlank()) add(text)
            }
        }
        node.keys().asSequence().forEach { addAll(filterReasons(node.opt(it))) }
    }
    is JSONArray -> (0 until node.length()).flatMap { filterReasons(node.opt(it)) }
    else -> emptyList()
}

/** 老接口 `/web/api/v2/aweme/iteminfo/` 的返回。 */
internal fun parseItemInfo(json: String): DouyinItem? {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
    val list = root.optJSONArray("item_list") ?: return null
    val first = list.optJSONObject(0) ?: return null
    return itemOf(first)
}

/** `music/detail` 里抠出的一首音乐：播放地址 + 歌名 + 封面。 */
internal data class DouyinMusic(
    val url: String,
    val title: String?,
    val cover: String?,
)

/** `music/detail` 的返回：play_url / cover_medium 和 video.play_addr 同形状。 */
internal fun parseMusicDetail(json: String): DouyinMusic? {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
    if (root.optInt("status_code", -1) != 0) return null
    val info = root.optJSONObject("music_info") ?: return null
    val url = firstUrl(info.optJSONObject("play_url")) ?: return null
    return DouyinMusic(
        url = url,
        title = info.optString("title").ifBlank { null },
        cover = firstUrl(info.optJSONObject("cover_medium")) ?: firstUrl(info.optJSONObject("cover_large")),
    )
}

internal fun itemOfInfo(info: JSONObject): DouyinItem? {
    val list = info.optJSONArray("item_list") ?: return null
    val first = list.optJSONObject(0) ?: return null
    return itemOf(first)
}

/** 一条作品：标题取 desc，地址取 video.play_addr；图集作品取 images，其余是展示用的元数据。 */
internal fun itemOf(item: JSONObject): DouyinItem? {
    val title = item.optString("desc").ifBlank { null }
    val video = item.optJSONObject("video")
    val playAddr = video?.optJSONObject("play_addr")
    val raw = playAddr?.optJSONArray("url_list")?.let { list ->
        (0 until list.length()).map { list.optString(it) }.firstOrNull { it.isNotBlank() }
    } ?: playAddr?.optString("uri")?.takeIf { it.isNotBlank() }?.let {
        // 图文作品的幻灯片 play_addr 里 uri 本身就是一条完整 URL，直接用；
        // 只有真正的视频 id（数字/字母串）才需要包成 play 接口地址
        if (it.startsWith("http", true)) normalizePlayUrl(it)
        else "https://aweme.snssdk.com/aweme/v1/play/?video_id=$it&ratio=1080p&line=0"
    } ?: video?.optString("playApi")?.takeIf { it.isNotBlank() }?.let {
        if (it.startsWith("//")) "https:$it" else it
    }
    val url = raw?.let(::normalizePlayUrl)
    // 图集：images 数组里每个元素是一张图，形状是 {uri, url_list:[...]}
    // —— 直接把这张图的对象交给 firstUrl（url_list 是数组，不能再 optJSONObject 一层）
    val images = item.optJSONArray("images")?.let { array ->
        (0 until array.length()).mapNotNull { index ->
            firstUrl(array.optJSONObject(index))
        }
    }.orEmpty()
    if (title == null && url == null && images.isEmpty()) return null
    // 背景音乐：music.play_url 和 video.play_addr 同形状（{uri, url_list}）。
    // 图文作品没有视频，BGM 是它唯一可播的媒体；部分作品（纯原创声）没有 play_url，为空正常。
    val music = item.optJSONObject("music")
    return DouyinItem(
        title = title,
        playUrl = url,
        author = item.optJSONObject("author")?.optString("nickname")?.ifBlank { null },
        cover = video?.let { firstUrl(it.optJSONObject("cover")) ?: firstUrl(it.optJSONObject("origin_cover")) }
            ?: images.firstOrNull(),
        publishTime = item.optLong("create_time", 0L).takeIf { it > 0 },
        playCount = item.optJSONObject("statistics")?.optLong("play_count", 0L)?.takeIf { it > 0 },
        images = images,
        musicUrl = music?.optJSONObject("play_url")?.let(::firstUrl),
        musicTitle = music?.optString("title")?.ifBlank { null },
        // 分享页 SSR 的音乐对象只有 mid/歌名/封面，播放地址要走 music/detail 补
        musicId = music?.optString("mid")?.ifBlank { null },
    )
}

/** `{uri, url_list:[…]}` 形状的地址对象 → 第一条可用的 http(s) 地址。 */
private fun firstUrl(obj: JSONObject?): String? {
    if (obj == null) return null
    val fromList = obj.optJSONArray("url_list")?.let { list ->
        (0 until list.length()).map { list.optString(it) }.firstOrNull { it.isNotBlank() }
    }
    val raw = fromList ?: obj.optString("url").ifBlank { null } ?: obj.optString("uri").ifBlank { null }
    return raw?.takeIf { it.isNotBlank() }?.let(::absoluteUrl)
}

/** 抖音给的地址经常是协议相对的（`//p3-sign.douyinpic.com/…`），补上 https。 */
private fun absoluteUrl(raw: String): String =
    if (raw.startsWith("//")) "https:$raw" else raw

/** 从路径里认作品类型：/share/note/{id}/ 是图集，/share/slides/{id}/ 是幻灯片，其余按普通视频。 */
internal fun kindOf(url: String): String = when {
    pathOf(url).contains("/note/") -> "note"
    pathOf(url).contains("/slides/") -> "slides"
    else -> "video"
}

/** 直链可能是 http（Android 默认不允许明文），带水印那路也要换掉。 */
internal fun normalizePlayUrl(raw: String): String {
    val trimmed = raw.trim()
    val https = if (trimmed.startsWith("http://", true)) "https://" + trimmed.substring(7) else trimmed
    return https.replace("/playwm/", "/play/")
}

/** 按花括号配对切出从 [start] 开始的整个 JSON 对象；字符串里的括号不算数。 */
internal fun jsonObjectAt(text: String, start: Int): String? {
    if (start < 0 || start >= text.length || text[start] != '{') return null
    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until text.length) {
        val char = text[index]
        if (inString) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
            continue
        }
        when (char) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return text.substring(start, index + 1)
            }
        }
    }
    return null
}

/** 标题兜底：分享页的 og:title / <title> 里也有。 */
internal fun titleInHtml(html: String): String? =
    Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        .find(html)?.groupValues?.get(1)?.trim()?.ifBlank { null }
        ?: Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.trim()?.ifBlank { null }
