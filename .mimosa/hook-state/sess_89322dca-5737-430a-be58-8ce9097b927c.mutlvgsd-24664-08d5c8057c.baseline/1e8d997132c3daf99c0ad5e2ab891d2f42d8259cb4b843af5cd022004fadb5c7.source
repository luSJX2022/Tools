package com.qzkt.timetable.data.anime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 分页结果。[totalPages] 用来判断有没有下一页。 */
data class AnimePage(
    val page: Int,
    val totalPages: Int,
    val items: List<AnimeItem>,
)

/** 影视源：把「分类列表 / 搜索 / 详情」翻译成统一的模型，UI 只认这个接口。 */
interface AnimeSource {
    val key: String
    /** 分类下的分页列表。 */
    suspend fun list(categoryId: String, page: Int): AnimePage
    suspend fun search(keyword: String, page: Int): AnimePage
    suspend fun detail(id: String): AnimeDetail
}

/**
 * 苹果CMS10 资源站。
 *
 * 只有三种 GET：`?ac=videolist&t=<分类>&pg=<页>`、`?ac=videolist&wd=<关键词>&pg=<页>`、
 * `?ac=videolist&ids=<id>`。响应的 Content-Type 会被标成 text/html，必须按 body 解析。
 * 分集在 `vod_play_url` 里：多个播放源用 `$$$` 分隔（挑名字带 m3u8 的那个）、
 * 集与集用 `#` 分隔、集名和地址用 `$` 分隔。
 * m3u8 直连可播，实测不需要 Referer / UA 防盗链头。
 *
 * 分类 `t` 支持逗号分隔的多个 type_id（实测），所以「电影」这类聚合页签
 * 直接把子分类 id 拼在一起查；注意查父分类 id 是不会带上子分类内容的。
 */
class MacCmsSource(
    override val key: String,
    /** 展示名（设置页选源用）。 */
    val name: String,
    val baseUrl: String,
    /** 页签显示名 to 接口的 type_id（可逗号分隔多个）。 */
    val categories: List<Pair<String, String>>,
) : AnimeSource {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun list(categoryId: String, page: Int): AnimePage =
        fetchPage("$baseUrl/api.php/provide/vod/?ac=videolist&t=$categoryId&pg=${page.coerceAtLeast(1)}")

    override suspend fun search(keyword: String, page: Int): AnimePage =
        fetchPage("$baseUrl/api.php/provide/vod/?ac=videolist&wd=${urlEncode(keyword)}&pg=${page.coerceAtLeast(1)}")

    override suspend fun detail(id: String): AnimeDetail = withContext(Dispatchers.IO) {
        val json = requestJson("$baseUrl/api.php/provide/vod/?ac=videolist&ids=$id")
        val item = json.optJSONArray("list")?.optJSONObject(0)
            ?: error("没有这部影片（id=$id）")
        AnimeDetail(
            source = key,
            id = item.optLong("vod_id", -1).let { if (it > 0) it.toString() else id },
            name = item.optString("vod_name").trim(),
            pic = item.optString("vod_pic").trim(),
            remark = item.optString("vod_remark").trim(),
            year = item.optString("vod_year").trim(),
            area = item.optString("vod_area").trim(),
            genre = item.optString("vod_class").trim(),
            content = stripHtml(item.optString("vod_content")),
            episodes = parseEpisodes(
                item.optString("vod_play_from"),
                item.optString("vod_play_url"),
            ),
        )
    }

    private suspend fun fetchPage(url: String): AnimePage = withContext(Dispatchers.IO) {
        val json = requestJson(url)
        val items = json.optJSONArray("list")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val it = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = it.optString("vod_name").trim()
                val id = it.optLong("vod_id", -1)
                if (name.isEmpty() || id <= 0) return@mapNotNull null
                AnimeItem(
                    source = key,
                    id = id.toString(),
                    name = name,
                    pic = it.optString("vod_pic").trim(),
                    remark = it.optString("vod_remark").trim(),
                )
            }
        } ?: emptyList()
        AnimePage(
            page = json.optInt("page", 1),
            totalPages = json.optInt("pagecount", 1),
            items = items,
        )
    }

    private suspend fun requestJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        val body = client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            resp.body?.string() ?: error("空响应")
        }
        // 有些站会在 JSON 前后夹广告字符，取第一个 { 到最后一个 } 之间
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        check(start >= 0 && end > start) { "响应不是 JSON：${body.take(80)}" }
        JSONObject(body.substring(start, end + 1))
    }

    /** 分集地址：选播放源（名字带 m3u8 的），再按 `#` / `$` 拆。 */
    private fun parseEpisodes(playFrom: String, playUrl: String): List<Episode> {
        if (playUrl.isBlank()) return emptyList()
        val froms = playFrom.split("$$$")
        val urls = playUrl.split("$$$")
        val index = froms.indexOfFirst { it.contains("m3u8", ignoreCase = true) }
            .let { if (it >= 0) it else urls.lastIndex }
        if (index < 0 || index >= urls.size) return emptyList()

        return urls[index].split("#").mapNotNull { segment ->
            val cut = segment.indexOf('$')
            if (cut <= 0) return@mapNotNull null
            val label = segment.substring(0, cut).trim()
            val url = segment.substring(cut + 1).trim()
            if (label.isEmpty() || !url.startsWith("http")) null else Episode(label, url)
        }
    }

    private fun stripHtml(text: String): String =
        text.replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ").trim()

    private fun urlEncode(text: String): String =
        java.net.URLEncoder.encode(text, "UTF-8")
}

/**
 * 内置影视源。type_id 按 2026-10 实测的各源 class 数组写死；
 * 「电影」「剧集」等聚合页签用逗号拼接叶子分类（已剔除伦理/理论这类）。
 * 某个源失效时可在设置页换源，也可以把它的条目从 [all] 里删掉。
 */
object VideoSources {

    val all: List<MacCmsSource> = listOf(
        MacCmsSource(
            key = "bfzy",
            name = "暴风资源",
            baseUrl = "https://bfzyapi.com",
            categories = listOf(
                "电影" to "21,22,23,24,25,26,27,28,50",
                "剧集" to "31,32,33,34,35,36,37,38",
                "动漫" to "40,41,42,43,44",
                "综艺" to "46,47,48,49",
                "短剧" to "58,65,66,67,68,69,70,71,72,74",
            ),
        ),
        MacCmsSource(
            key = "lziapi",
            name = "量子资源",
            baseUrl = "https://cj.lziapi.com",
            categories = listOf(
                "电影" to "6,7,8,9,10,11,12,20",
                "剧集" to "13,14,15,16,21,22,23,24",
                "动漫" to "29,30,31,32,33",
                "综艺" to "25,26,27,28",
                "短剧" to "46",
            ),
        ),
        MacCmsSource(
            key = "s360",
            name = "360资源",
            baseUrl = "https://360zy.com",
            categories = listOf(
                "电影" to "6,7,8,9,10,11,12,20,21,22,23,24,25,26,27,28,29",
                "剧集" to "13,14,15,16,30,31,32,33",
                "动漫" to "38,39,40",
                "综艺" to "34,35,36,37",
                "短剧" to "46,47,48,49,50,51,52,53",
            ),
        ),
        MacCmsSource(
            key = "ffzy",
            name = "非凡资源",
            baseUrl = "http://ffzy5.tv",
            categories = listOf(
                "电影" to "6,7,8,9,10,11,12,20",
                "剧集" to "13,14,15,16,21,22,23,24",
                "动漫" to "29,30,31,32,33",
                "综艺" to "25,26,27,28",
                "短剧" to "36",
            ),
        ),
    )

    fun byKey(key: String): MacCmsSource = all.firstOrNull { it.key == key } ?: all.first()
}
