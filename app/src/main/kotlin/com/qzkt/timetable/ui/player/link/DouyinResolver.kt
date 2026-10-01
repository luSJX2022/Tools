package com.qzkt.timetable.ui.player.link

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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
) {

    /** ttwid 只取一次。 */
    private var ttwidChecked = false

    suspend fun resolve(link: MediaLink.Douyin, userHeaders: Map<String, String> = emptyMap()): ResolvedMedia =
        withContext(Dispatchers.IO) { resolveBlocking(link, userHeaders) }

    private fun resolveBlocking(link: MediaLink.Douyin, userHeaders: Map<String, String>): ResolvedMedia {
        ensureTtwid(userHeaders)

        var awemeId = link.awemeId
        var firstPage: String? = null
        if (awemeId == null) {
            val jumped = client.fetch(link.url, shareHeaders(userHeaders, MOBILE_UA))
            firstPage = jumped.body.takeIf { it.isNotBlank() }
            awemeId = parseDouyinUrl(jumped.finalUrl).awemeId
                ?: firstPage?.let { awemeIdInHtml(it) }
                ?: throw LinkResolveException("这条抖音链接里没有作品号（HTTP " + jumped.code + "）")
        }

        // 分享页对 UA 挑得很：同一个作品，有的 UA 回的是带 videoInfoRes 的移动分享页，
        // 有的（真机实测：安卓 Chrome）回的是压根没有播放信息的 web 布局页
        // —— loaderData 里只有 video_layout / video_(id)/page，item_list 是空的。
        // 所以挨个 UA 试，谁有地址用谁。
        val shareUrl = "$webBase/share/video/$awemeId/"
        var lastHtml = firstPage
        USER_AGENTS.forEachIndexed { index, ua ->
            val html = (if (index == 0) firstPage else null)
                ?: client.fetch(shareUrl, shareHeaders(userHeaders, ua)).body
            lastHtml = html
            val item = parseRouterData(html)
            if (item?.playUrl != null) {
                Log.i(TAG, "抖音分享页命中 UA#" + (index + 1))
                return item.asResolved(html, playHeaders(userHeaders, ua))
            }
            Log.w(TAG, "抖音 UA#" + (index + 1) + " 没有播放地址：" + (routerDataStructure(html) ?: "没有 _ROUTER_DATA"))
        }

        // 老接口兜底（分享页改版时偶尔还能用）
        val fallback = parseItemInfo(
            client.fetch("$apiBase/web/api/v2/aweme/iteminfo/?item_ids=$awemeId", shareHeaders(userHeaders, MOBILE_UA)).body,
        )
        fallback?.playUrl?.let { return it.let { _ -> fallback.asResolved(lastHtml.orEmpty(), playHeaders(userHeaders, MOBILE_UA)) } }

        // 说清楚卡在哪一步，别让用户对着一句「失败」猜
        val html = lastHtml.orEmpty()
        val hint = when {
            html.isBlank() -> "分享页是空的"
            !html.contains("_ROUTER_DATA") -> "分享页里没有 _ROUTER_DATA（页面可能改版或被要求验证）"
            else -> "分享页里没有播放地址" + (routerDataSummary(html)?.let { "（$it）" } ?: "")
        }
        throw LinkResolveException("抖音没有返回播放地址（" + hint + "）：链接可能已失效，或这个作品需要登录才能看")
    }

    private fun shareHeaders(userHeaders: Map<String, String>, ua: String): Map<String, String> =
        userHeaders + mapOf("User-Agent" to ua, "Referer" to "https://www.douyin.com/")

    /** 播放/下载那一侧不走 CookieJar，Cookie 得显式带（ttwid 之类）。 */
    private fun playHeaders(userHeaders: Map<String, String>, ua: String): Map<String, String> =
        userHeaders + mapOf("User-Agent" to ua) +
            ((client.cookieJar as? LinkCookieJar)?.header()?.takeIf { it.isNotBlank() }
                ?.let { mapOf("Cookie" to it) } ?: emptyMap())

    private fun DouyinItem.asResolved(page: String, headers: Map<String, String>): ResolvedMedia =
        ResolvedMedia(
            url = playUrl.orEmpty(),
            title = title ?: titleInHtml(page),
            headers = headers,
            platform = MediaPlatform.DOUYIN,
        )

    /**
     * 抖音对没有反爬 Cookie 的请求会返回**空壳分享页**（`item_list` 是空的）：
     * 现象就是「解析不到播放地址」。先去字节的 ttwid 注册接口换一个 ttwid 回来，
     * 再带着它请求分享页（真机实测：没有 ttwid 时 item_list=0）。
     */
    private fun ensureTtwid(headers: Map<String, String>) {
        if (ttwidChecked) return
        ttwidChecked = true
        val jar = client.cookieJar as? LinkCookieJar ?: return
        if (jar.has("ttwid")) return
        runCatching { client.postJson(ttwidUrl, TTWID_BODY, headers) }
        Log.w(TAG, "ttwid=" + jar.has("ttwid"))
    }

    private companion object {
        const val TAG = "QzLink"

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

internal data class DouyinItem(val title: String?, val playUrl: String?)

/**
 * 从分享页 HTML 里找 `window._ROUTER_DATA = {...}`，把 JSON 抠出来再取第一条作品。
 *
 * 不能直接正则截到行尾：这块 JSON 很长，且里面带转义引号，只能按花括号配对切出来。
 */
internal fun parseRouterData(html: String): DouyinItem? {
    val marker = html.indexOf("_ROUTER_DATA")
    if (marker < 0) return null
    val start = html.indexOf('{', marker)
    if (start < 0) return null
    val json = jsonObjectAt(html, start) ?: return null
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
    // 不写死 loaderData → videoInfoRes → item_list 这条路径：抖音换过好几次结构，
    // 而且图文、直播、合集用的键都不一样。整棵树里找第一个真带播放地址的 item 更省心。
    return findPlayableItem(root)
}

private fun findPlayableItem(node: Any?): DouyinItem? = when (node) {
    is JSONObject -> {
        itemOf(node)?.takeIf { it.playUrl != null }?.let { return it }
        node.keys().asSequence().forEach { key -> findPlayableItem(node.opt(key))?.let { return it } }
        null
    }
    is JSONArray -> {
        for (index in 0 until node.length()) findPlayableItem(node.opt(index))?.let { return it }
        null
    }
    else -> null
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

internal fun itemOfInfo(info: JSONObject): DouyinItem? {
    val list = info.optJSONArray("item_list") ?: return null
    val first = list.optJSONObject(0) ?: return null
    return itemOf(first)
}

/** 一条作品：标题取 desc，地址取 video.play_addr。 */
internal fun itemOf(item: JSONObject): DouyinItem? {
    val title = item.optString("desc").ifBlank { null }
    val video = item.optJSONObject("video")
    val playAddr = video?.optJSONObject("play_addr")
    val raw = playAddr?.optJSONArray("url_list")?.let { list ->
        (0 until list.length()).map { list.optString(it) }.firstOrNull { it.isNotBlank() }
    } ?: playAddr?.optString("uri")?.takeIf { it.isNotBlank() }?.let {
        "https://aweme.snssdk.com/aweme/v1/play/?video_id=$it&ratio=1080p&line=0"
    } ?: video?.optString("playApi")?.takeIf { it.isNotBlank() }?.let {
        if (it.startsWith("//")) "https:$it" else it
    }
    val url = raw?.let(::normalizePlayUrl)
    if (title == null && url == null) return null
    return DouyinItem(title, url)
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
