package com.qzkt.timetable.ui.player.link

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * B站：先用 view（番剧用 pgc）接口换到 cid，再用 playurl 接口换播放地址。
 *
 * 要的是 MP4 直链（durl），不是 DASH：播放器这头只能通过 MediaController
 * 塞一个 MediaItem，DASH 那种音视频分开的流没法合并，单独播一条 video 流会没声音。
 * 拿 MP4 用 `platform=html5&high_quality=1`（见 [playUrl]）。
 *
 * 画质受登录状态限制：不登录一般只给到 720P；想上 1080P 就在「请求头」里填
 * `Cookie: SESSDATA=…`，这个 Cookie 会一起带给接口和 CDN。
 *
 * 播放地址在 B站自己的 CDN 上，必须带 `Referer: https://www.bilibili.com/`，
 * 否则 403 —— 返回的请求头里已经带上了。
 */
class BilibiliResolver(
    private val client: OkHttpClient = defaultLinkClient(),
    private val apiBase: String = "https://api.bilibili.com",
    private val webBase: String = "https://www.bilibili.com",
) {

    /** buvid 只取一次：解析器活着的这段时间里 Cookie 一直有效。 */
    private var buvidChecked = false

    /** 探测用的短超时客户端（只是探个字节，不该让人等 10 秒）。 */
    private val probeClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    suspend fun resolve(link: MediaLink.Bilibili, userHeaders: Map<String, String> = emptyMap()): ResolvedMedia =
        withContext(Dispatchers.IO) { resolveBlocking(link, userHeaders) }

    private fun resolveBlocking(link: MediaLink.Bilibili, userHeaders: Map<String, String>): ResolvedMedia {
        // 平台要求的头盖过用户填的同名头（上一路流留下的旧 Referer 不该带进来），
        // 其余头照旧，尤其是 SESSDATA 这种 Cookie —— 画质全靠它。
        val headers = userHeaders.cookiesIntoJar() + mapOf(
            "User-Agent" to BROWSER_UA,
            "Referer" to "$webBase/",
            // 少这两个头，接口也更容易被当成脚本（真机实测：只缺 Cookie 时就已经回 HTML 了）
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "zh-CN,zh;q=0.9",
        )
        ensureBuvid(headers)
        val target = if (link.hasId()) link else followShortLink(link, headers)
        return if (target.epId != null || target.ssId != null) resolveBangumi(target, headers)
        else resolveVideo(target, headers)
    }

    /**
     * B站风控认 `buvid3`：没有这个 Cookie 时 `x/web-interface/view` 会回一页 HTML，
     * 用户看到的就是「解析失败」，跟链接对不对没有任何关系（真机踩过）。
     *
     * 先像浏览器一样访问一次首页收 Set-Cookie；首页没给就问 finger/spi 接口直接要。
     * 两种都失败也不拦着 —— 后面的请求会给出真正的错因。
     */
    private fun ensureBuvid(headers: Map<String, String>) {
        if (buvidChecked) return
        buvidChecked = true
        val jar = client.cookieJar as? LinkCookieJar ?: return
        runCatching { client.fetch("$webBase/", headers) }
        if (jar.has("buvid3")) return
        runCatching {
            val data = jsonOf(client.fetch("$apiBase/x/frontend/finger/spi", headers).body, "B站指纹接口")
                .optJSONObject("data")
            // 按接口域名存，子域才能带上（测试里 apiBase 是 127.0.0.1，也一样成立）
            val host = hostOf(apiBase) ?: "bilibili.com"
            jar.put(host, "buvid3", data?.optString("b_3").orEmpty())
            jar.put(host, "buvid4", data?.optString("b_4").orEmpty())
        }
    }

    /**
     * 用户填的 `Cookie: SESSDATA=…` 不能当普通请求头用：
     * OkHttp 见到显式的 Cookie 头就**不再用 CookieJar 里的**，buvid3 会一起丢掉，风控立刻回来。
     * 所以把它拆进 jar，由 jar 统一发。
     */
    private fun Map<String, String>.cookiesIntoJar(): Map<String, String> {
        val raw = this["Cookie"] ?: this["cookie"] ?: return this
        val jar = client.cookieJar as? LinkCookieJar ?: return this
        val host = hostOf(apiBase) ?: "bilibili.com"
        raw.split(';').forEach { pair ->
            val eq = pair.indexOf('=')
            if (eq > 0) jar.put(host, pair.substring(0, eq).trim(), pair.substring(eq + 1).trim())
        }
        return this - "Cookie" - "cookie"
    }

    /** 给播放/下载那侧显式带上 Cookie（CDN 认 buvid3 / SESSDATA）。 */
    private fun Map<String, String>.withCookies(): Map<String, String> {
        val value = (client.cookieJar as? LinkCookieJar)?.header().orEmpty()
        return if (value.isBlank()) this else this + ("Cookie" to value)
    }

    /** b23.tv 这种短链跟一次跳转，跳到的地址里才有 BV / av / ep。 */
    private fun followShortLink(link: MediaLink.Bilibili, headers: Map<String, String>): MediaLink.Bilibili {
        val response = client.fetch(link.url, headers)
        val jumped = parseBilibiliUrl(response.finalUrl)
        if (jumped.hasId()) return jumped
        throw LinkResolveException("这个 B站链接里没有视频（BV/av）或番剧（ep/ss）信息（HTTP " + response.code + "）")
    }

    private fun resolveVideo(link: MediaLink.Bilibili, headers: Map<String, String>): ResolvedMedia {
        val query = buildString {
            link.bvid?.let { append("bvid=").append(it) }
            link.aid?.let {
                if (isNotEmpty()) append('&')
                append("aid=").append(it)
            }
        }
        val view = parseVideoInfo(client.fetch("$apiBase/x/web-interface/view?$query", headers).body)
        val page = view.pages.getOrNull((link.page - 1).coerceAtLeast(0))
            ?: throw LinkResolveException("这个视频只有 " + view.pages.size + " 个分P，没有第 " + link.page + " 个")
        val bvid = view.bvid.ifBlank { link.bvid.orEmpty() }
        val play = playUrl(bvid, page.cid, headers)
        val usable = pickPlayable(play, headers, bvid)
        return ResolvedMedia(
            url = usable.first,
            title = listOfNotNull(view.title.ifBlank { null }, page.label(view.pages.size), play.qualityLabel)
                .joinToString(" · ")
                .ifBlank { null },
            headers = usable.second.withCookies(),
            platform = MediaPlatform.BILIBILI,
            warning = play.warning,
            playCount = view.playCount,
            description = view.description,
            publishTime = view.publishTime,
            author = view.author,
            cover = view.cover,
        )
    }

    /** 番剧 / 影视：pgc 接口按 ep 或 ss 拿剧集，再用同一套 playurl 拿地址。 */
    private fun resolveBangumi(link: MediaLink.Bilibili, headers: Map<String, String>): ResolvedMedia {
        val query = link.epId?.let { "ep_id=$it" } ?: ("season_id=" + link.ssId)
        val season = parseBangumiSeason(client.fetch("$apiBase/pgc/view/web/season?$query", headers).body)
        val episode = season.episodes.firstOrNull { it.epId == link.epId } ?: season.episodes.first()
        val play = playUrl(episode.bvid, episode.cid, headers)
        val usable = pickPlayable(play, headers, episode.bvid)
        return ResolvedMedia(
            url = usable.first,
            title = listOfNotNull(episode.title.ifBlank { null }, play.qualityLabel)
                .joinToString(" · ")
                .ifBlank { null },
            headers = usable.second.withCookies(),
            platform = MediaPlatform.BILIBILI,
            warning = play.warning,
            playCount = null,   // 番剧接口不公开播放量
            description = season.description,
            publishTime = season.publishTime,
            author = season.author,
            cover = season.cover,
        )
    }

    /**
     * B站 CDN 对请求头挑得很：Referer / UA / Accept 的组合稍微不对就是 403，
     * 而且**同一台手机上 ExoPlayer 和 OkHttp 都会 403**（真机实测），所以不能等播的时候才发现。
     *
     * 这里用 `Range: bytes=0-1` 探一次，主地址 + 备用镜像 × 几组请求头，谁先通就用谁；
     * 全都不通就按原样返回，并把探测到的状态码写进日志，便于定位是头的问题还是地址本身的问题。
     */
    private fun pickPlayable(play: BiliPlayUrl, headers: Map<String, String>, bvid: String): Pair<String, Map<String, String>> {
        // 真机实测的顺序：
        //  ① 桌面 UA + 不带 Accept —— B站 CDN 不认安卓 UA，这组才回 206；
        //  ② 原样（个别镜像反过来只认安卓 UA）。
        //
        // 先用第一组把所有地址挨个试完，都不行再换第二组 —— 以前是「同一个地址
        // 把两组头试完」，碰上连不上的 P2P 域名，8 秒超时要白吃两次。
        val known = (headers - "Accept" - "Accept-Language") + ("User-Agent" to DESKTOP_UA)
        val variants = listOf(known, headers).distinct()

        variants.forEach { variant ->
            play.candidates().forEach { url ->
                val code = probe(url, variant)
                Log.w(TAG, "播放地址探测 HTTP " + code + "（" + hostOf(url) + "）")
                if (code in 200..299) return url to variant
                // -1 是这个域名根本没连上（边缘节点在手机上不可达），换下一个候选地址，
                // 别在它身上把 7 组请求头挨个超时一遍 —— 那样解析要卡一分钟。
                if (code == -1) return@forEach
            }
        }
        Log.e(TAG, "所有镜像都没探通，按第一个候选 + 实测可用的请求头继续")
        return play.candidates().first() to known
    }

    /**
     * `Range: bytes=0-1` 只取一个字节，代价很小；超时单独收短，免得探一个不可达的
     * 边缘节点就卡 10 秒。
     */
    private fun probe(url: String, headers: Map<String, String>): Int = runCatching {
        val builder = Request.Builder().url(url).header("Range", "bytes=0-1")
        headers.forEach { (name, value) -> if (name.isNotBlank() && value.isNotBlank()) builder.header(name, value) }
        probeClient.newCall(builder.build()).execute().use { it.code }
    }.getOrDefault(-1)

    private fun playUrl(bvid: String, cid: Long, headers: Map<String, String>): BiliPlayUrl {
        // platform=html5&high_quality=1 要 MP4 直链（durl）。
        // 以前用 fnval=1，但它给的 durl 主地址经常是 edge/P2P 域名（实测
        // `*.edge.mountaintoys.cn:4483` 手机上 connect 超时），得靠备用镜像救；
        // html5 直接给 `upos-*.bilivideo.com` 的干净地址，探测一次就过。
        // 画质由登录状态决定：不登录 720P，带 SESSDATA 可到 1080P。
        val url = "$apiBase/x/player/playurl?bvid=$bvid&cid=$cid&qn=80&platform=html5&high_quality=1"
        return parsePlayUrl(client.fetch(url, headers).body)
    }

    private fun MediaLink.Bilibili.hasId(): Boolean =
        bvid != null || aid != null || epId != null || ssId != null

    private companion object {
        /**
         * 用安卓 Chrome 的 UA，而不是桌面 Chrome：真机上验证过，同一张网里
         * 手机浏览器（安卓 UA）能正常拿到 JSON，所以照着它写最不容易被风控挑出来。
         */
        const val TAG = "QzLink"

        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}

internal data class BiliPage(val cid: Long, val page: Int, val part: String) {
    /**
     * 多分P时才值得在标题里写出是第几个。
     *
     * B站的分P名经常本身就带 `P2` 前缀（默认名就是 `P1`、`P2`），
     * 直接拼会把标题写成「P2 P2 结尾」，所以先看看是不是已经带了。
     */
    fun label(total: Int): String? {
        if (total <= 1) return null
        val name = part.trim()
        val prefix = "P" + page
        return when {
            name.isEmpty() -> prefix
            name.startsWith(prefix, ignoreCase = true) -> name
            else -> prefix + " " + name
        }
    }
}

internal data class BiliVideo(
    val bvid: String,
    val title: String,
    val pages: List<BiliPage>,
    val cover: String? = null,
    val description: String? = null,
    val author: String? = null,
    /** 发布时间（epoch 秒）。 */
    val publishTime: Long? = null,
    val playCount: Long? = null,
)

internal data class BiliEpisode(val bvid: String, val cid: Long, val epId: Long, val title: String)

internal data class BiliSeason(
    val episodes: List<BiliEpisode>,
    val cover: String? = null,
    val description: String? = null,
    val author: String? = null,
    val publishTime: Long? = null,
)

internal data class BiliPlayUrl(
    val url: String,
    /** 同一路流的备用镜像；主地址被 CDN 拒了可以换一个再试。 */
    val backups: List<String>,
    val qualityLabel: String?,
    val warning: String?,
) {
    /**
     * 候选地址：常规镜像排前面。
     *
     * B站有时会把主地址给到一个边缘 / P2P 节点（例如 `xxxx.edge.mountaintoys.cn`），
     * 那个域名在手机上压根连不上（真机实测：connect 超时），而 backup_url 里的
     * `upos-*.bilivideo.com` 是通的 —— 所以先把常规镜像试掉。
     */
    fun candidates(): List<String> = (listOf(url) + backups).distinct()
        .sortedBy { if (isRegularCdn(it)) 0 else 1 }
}

private fun isRegularCdn(url: String): Boolean {
    val host = hostOf(url) ?: return false
    return host.endsWith("bilivideo.com") || host.endsWith("bilivideo.cn") ||
        host.endsWith("bilivideo.net") || host.endsWith("akamaized.net")
}

/** 解析 view 接口。code 不是 0 时把服务端的话原样带出来，别让用户猜。 */
internal fun parseVideoInfo(json: String): BiliVideo {
    val root = jsonOf(json, "B站视频信息接口")
    checkBiliCode(root)
    val data = root.optJSONObject("data") ?: throw LinkResolveException("B站没有返回视频信息")
    val pages = data.optJSONArray("pages")?.let { array ->
        (0 until array.length()).mapNotNull { index ->
            val page = array.optJSONObject(index) ?: return@mapNotNull null
            val cid = page.optLong("cid", 0L)
            if (cid == 0L) null else BiliPage(cid, page.optInt("page", index + 1), page.optString("part"))
        }
    }.orEmpty()
    if (pages.isEmpty()) throw LinkResolveException("B站没有返回可播放的分P")
    return BiliVideo(
        bvid = data.optString("bvid"),
        title = data.optString("title"),
        pages = pages,
        cover = data.optString("pic").ifBlank { null },
        description = data.optString("desc").ifBlank { null },
        author = data.optJSONObject("owner")?.optString("name")?.ifBlank { null },
        publishTime = data.optLong("pubdate", 0L).takeIf { it > 0 },
        playCount = data.optJSONObject("stat")?.optLong("view", 0L)?.takeIf { it > 0 },
    )
}

/** 解析 pgc 番剧接口：剧集列表 + 作品信息（简介 / 封面 / 出品方 / 发布日期）。 */
internal fun parseBangumiSeason(json: String): BiliSeason {
    val root = jsonOf(json, "B站番剧接口")
    checkBiliCode(root)
    val result = root.optJSONObject("result") ?: root.optJSONObject("data")
        ?: throw LinkResolveException("B站没有返回番剧信息")
    val array = result.optJSONArray("episodes") ?: throw LinkResolveException("B站没有返回番剧剧集")
    val episodes = (0 until array.length()).mapNotNull { index ->
        val ep = array.optJSONObject(index) ?: return@mapNotNull null
        val cid = ep.optLong("cid", 0L)
        val bvid = ep.optString("bvid")
        if (cid == 0L || bvid.isBlank()) return@mapNotNull null
        val label = ep.optString("share_copy")
            .ifBlank { ep.optString("long_title") }
            .ifBlank { ep.optString("title") }
        BiliEpisode(bvid, cid, ep.optLong("id", 0L), label)
    }
    if (episodes.isEmpty()) throw LinkResolveException("B站没有返回可播放的剧集")
    // 发布时间：publish.pub_time 是 "2023-01-01" 这类日期串，转成 epoch 秒；解析不出来就空着
    val publishTime = result.optJSONObject("publish")?.optString("pub_time")
        ?.takeIf { it.isNotBlank() }
        ?.let {
            runCatching {
                java.time.LocalDate.parse(it).atStartOfDay().toEpochSecond(java.time.ZoneOffset.UTC)
            }.getOrNull()
        }
    return BiliSeason(
        episodes = episodes,
        cover = result.optString("cover").ifBlank { null },
        description = result.optString("evaluate").ifBlank { null },
        author = result.optJSONObject("up_info")?.optString("uname")?.ifBlank { null },
        publishTime = publishTime,
    )
}

/**
 * 解析 playurl 接口。
 *
 * 只有 durl（MP4 直链）能直接播；接口在某些情况下只回 dash，这时明确报错，
 * 而不是丢一条没声音的 video 流给播放器。
 */
internal fun parsePlayUrl(json: String): BiliPlayUrl {
    val root = jsonOf(json, "B站播放地址接口")
    checkBiliCode(root)
    val data = root.optJSONObject("data") ?: throw LinkResolveException("B站没有返回播放地址")
    val durl = data.optJSONArray("durl")
    if (durl == null || durl.length() == 0) {
        throw LinkResolveException(
            if (data.optJSONObject("dash") != null) "B站只返回了音视频分离的 DASH 流，当前播放器放不了"
            else "B站没有返回播放地址",
        )
    }
    val first = durl.optJSONObject(0) ?: throw LinkResolveException("B站返回的播放地址格式不对")
    val url = first.optString("url")
    if (url.isBlank()) throw LinkResolveException("B站返回的播放地址是空的")
    val backups = first.optJSONArray("backup_url")?.let { array ->
        (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() && it != url }
    }.orEmpty()
    val warning = if (durl.length() > 1) "视频被切成 " + durl.length() + " 段，这里只能播第 1 段" else null
    return BiliPlayUrl(url, backups, qualityLabel(data.optInt("quality", 0)), warning)
}

private fun checkBiliCode(root: JSONObject) {
    val code = root.optInt("code", 0)
    if (code == 0) return
    val message = root.optString("message").ifBlank { "code=" + code }
    throw LinkResolveException(
        when (code) {
            -404 -> "B站：视频不存在或已失效（$message）"
            -403 -> "B站：接口拒绝访问，可能需要登录后再试（$message）"
            -400 -> "B站：请求不对（$message）"
            -412 -> "B站：请求被风控拦了，稍后再试（$message）"
            else -> "B站接口返回错误：$message"
        },
    )
}

private fun qualityLabel(quality: Int): String? = when (quality) {
    6 -> "240P"
    16 -> "360P"
    32 -> "480P"
    64 -> "720P"
    74 -> "720P60"
    80 -> "1080P"
    112 -> "1080P+"
    116 -> "1080P60"
    120 -> "4K"
    125 -> "HDR"
    126 -> "杜比视界"
    127 -> "8K"
    else -> null
}
