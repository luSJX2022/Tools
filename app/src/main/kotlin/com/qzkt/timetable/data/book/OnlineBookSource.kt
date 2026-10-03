package com.qzkt.timetable.data.book

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 在线书源：搜书 / 目录 / 正文。 */
interface BookSource {
    val key: String
    val name: String
    suspend fun search(keyword: String): List<OnlineBook>
    suspend fun catalog(bookUrl: String): List<OnlineChapter>
    suspend fun content(chapter: OnlineChapter): String
}

/** 搜到的一本书。 */
data class OnlineBook(
    val sourceKey: String,
    /** 书籍落地页路径（如 /n/{id}/）。 */
    val bookUrl: String,
    val name: String,
    val author: String? = null,
    val cover: String? = null,
    val description: String? = null,
)

/** 一个章节。 */
data class OnlineChapter(val title: String, val url: String)

/** 分类浏览的一页结果。 */
data class OnlineBookPage(
    val books: List<OnlineBook>,
    /** 分类页翻页没有总页数信息，靠「下一页」链接是否存在判断。 */
    val hasNext: Boolean,
)

/** 支持分类浏览的书源（书城页签的分类栏）。 */
interface CategorizedBookSource : BookSource {
    /** 分类显示名 to 分类 id。 */
    val categories: List<Pair<String, String>>
    suspend fun categoryBooks(categoryId: String, page: Int): OnlineBookPage
}

/**
 * 全本小说网（quanben5.com）：经典 HTML 书站，明文正文。
 *
 * 搜索是 JSONP + 自定义字符加密：`/?c=book&a=search.json&callback=search&…&b={bEncode(encodeURI(kw))}`，
 * b 的算法是「字母表内每字符右移 3 位，前后各夹一个随机字符」（见 [bEncode]）；
 * 返回 `search({"content":"<html>"})`，结果块是 `div.pic_txt_list`。
 * 章节列表在 `/n/{id}/xiaoshuo.html`，正文在章节页的 `#content` 里
 * （正文里『』包着的字直接去掉括号即可还原）。
 *
 * 已知限制：站内搜索对**完整书名**反而常常搜不到（按作者 / 题材 / 部分关键词更容易命中）。
 */
class Quanben5Source(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val baseUrl: String = "https://www.quanben5.com",
) : BookSource, CategorizedBookSource {

    override val key = "quanben5"
    override val name = "全本小说网"

    /** 分类 id 按站点导航实测（2026-10），16 / 17 不存在。 */
    override val categories: List<Pair<String, String>> = listOf(
        "玄幻" to "1", "都市" to "2", "仙侠" to "3", "武侠" to "4",
        "言情" to "5", "穿越" to "6", "网游" to "7", "奇幻" to "8",
        "科幻" to "9", "悬疑" to "10", "青春" to "11", "校园" to "12",
        "军事" to "13", "历史" to "14", "同人" to "15", "其它" to "18",
    )

    /**
     * 分类浏览：第 1 页 `/category/{id}.html`，第 N 页 `/category/{id}_{N}.html`。
     * 书块与搜索结果同构（div.pic_txt_list），翻页靠「下一页」链接是否存在。
     */
    override suspend fun categoryBooks(categoryId: String, page: Int): OnlineBookPage =
        withContext(Dispatchers.IO) {
            val path = if (page <= 1) "/category/$categoryId.html" else "/category/${categoryId}_${page}.html"
            val doc = fetchDoc(path)
            val books = doc.select("div.pic_txt_list").mapNotNull { block ->
                val name = block.selectFirst("h3 .name")?.text().orEmpty().trim()
                val href = block.selectFirst("h3 a")?.attr("href").orEmpty().trim()
                if (name.isEmpty() || href.isEmpty()) return@mapNotNull null
                OnlineBook(
                    sourceKey = key,
                    bookUrl = href,
                    name = name,
                    author = block.selectFirst(".author b")?.text()?.trim(),
                    cover = block.selectFirst(".pic img")?.attr("src")?.takeIf { it.isNotBlank() },
                    description = block.selectFirst(".description")?.text()?.trim(),
                )
            }
            val hasNext = doc.selectFirst("a[href*=\"_${page + 1}.html\"]") != null
            OnlineBookPage(books = books, hasNext = hasNext)
        }

    override suspend fun search(keyword: String): List<OnlineBook> = withContext(Dispatchers.IO) {
        val kw = keyword.trim()
        val encoded = Uri.encode(kw)
        val body = jsonpGet(
            "$baseUrl/?c=book&a=search.json&callback=search&t=${System.currentTimeMillis()}" +
                "&keywords=$encoded&b=${bEncode(encoded)}",
        )
        val doc = Jsoup.parse(body)
        doc.select("div.pic_txt_list").mapNotNull { block ->
            val name = block.selectFirst("h3 .name")?.text().orEmpty().trim()
            val href = block.selectFirst("h3 a")?.attr("href").orEmpty().trim()
            if (name.isEmpty() || href.isEmpty()) return@mapNotNull null
            OnlineBook(
                sourceKey = key,
                bookUrl = href,
                name = name,
                author = block.selectFirst(".author b")?.text()?.trim(),
                cover = block.selectFirst(".pic img")?.attr("src")?.takeIf { it.isNotBlank() },
                description = block.selectFirst(".description")?.text()?.trim(),
            )
        }
    }

    override suspend fun catalog(bookUrl: String): List<OnlineChapter> = withContext(Dispatchers.IO) {
        val landing = fetchDoc(bookUrl)
        // 书籍落地页里带章节列表页的链接（…/xiaoshuo.html）
        val catalogUrl = landing.selectFirst("a[href*=xiaoshuo.html]")?.attr("href") ?: bookUrl
        val doc = fetchDoc(catalogUrl)
        val chapters = doc.select("a").mapNotNull { a ->
            val href = a.attr("href").trim()
            val title = a.text().trim()
            if (href.matches(Regex("/n/[^/]+/\\d+\\.html")) && title.isNotBlank()) {
                OnlineChapter(title, href)
            } else {
                null
            }
        }
        chapters.ifEmpty { error("没有返回章节列表（页面可能改版）") }
    }

    override suspend fun content(chapter: OnlineChapter): String = withContext(Dispatchers.IO) {
        val doc = fetchDoc(chapter.url)
        val element = doc.selectFirst("#content") ?: error("章节内容缺失（页面可能改版）")
        val withBreaks = element.html().replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        val plain = withBreaks
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .trim()
        // 站点把部分常用字用『』包起来防爬，里层才是真字，去掉括号即可还原
        plain.replace("『", "").replace("』", "")
    }

    private suspend fun fetchDoc(path: String) = withContext(Dispatchers.IO) {
        val url = if (path.startsWith("http")) path else baseUrl + path
        val body = client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36")
                .header("Referer", "$baseUrl/")
                .build(),
        ).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            resp.body?.string() ?: error("空响应")
        }
        Jsoup.parse(body, baseUrl)
    }

    private fun jsonpGet(url: String): String {
        val body = client.newCall(
            Request.Builder().url(url)
                .header("Referer", "$baseUrl/search.html")
                .build(),
        ).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            resp.body?.string() ?: error("空响应")
        }
        val start = body.indexOf('(')
        val end = body.lastIndexOf(')')
        check(start > 0 && end > start) { "搜索接口返回异常" }
        return JSONObject(body.substring(start + 1, end)).optString("content")
    }

    /**
     * 站点搜索用的自定义编码：输入是 encodeURI 后的字符串，每个字符在字母表里
     * 右移 3 位（不在表里的字符原样保留），前后各夹一个随机字符。
     */
    private fun bEncode(encoded: String): String {
        val alphabet = "PXhw7UT1B0a9kQDKZsjIASmOezxYG4CHo5Jyfg2b8FLpEvRr3WtVnlqMidu6cN"
        val out = StringBuilder()
        for (ch in encoded) {
            val index = alphabet.indexOf(ch)
            val code = if (index == -1) ch else alphabet[(index + 3) % 62]
            out.append(alphabet.random()).append(code).append(alphabet.random())
        }
        return out.toString()
    }
}

/** 内置在线书源。 */
object BookSources {

    val all: List<BookSource> = listOf(Quanben5Source(), Kunnu8Source())

    fun byKey(key: String): BookSource = all.firstOrNull { it.key == key } ?: all.first()

    /** Uri.encode 的包装：data 层直接用安卓的编码器（与浏览器 encodeURI 行为一致）。 */
    fun encodeComponent(value: String): String = Uri.encode(value)
}

/**
 * 鲲弩小说（kunnu8.com，Cloudflare，国内手机可达）。
 *
 * 搜索：`/search/{关键词}`；命中页里 `.search-list-cat a` 是书籍落地页（`/{slug}/`）；
 * 书页的 `div.book-list a` 是全部章节（绝对地址）；
 * 正文在章节页的 `#nr_body`，正文里混着「鲲`弩`小`说 w w w …」广告行，逐行剔除。
 *
 * 已知限制：站点没有分类页（分类只是导航），这个源不支持分类浏览，只支持搜索。
 */
class Kunnu8Source(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val baseUrl: String = "https://www.kunnu8.com",
) : BookSource {

    override val key = "kunnu8"
    override val name = "鲲弩小说"

    override suspend fun search(keyword: String): List<OnlineBook> = withContext(Dispatchers.IO) {
        val kw = keyword.trim()
        val doc = fetchDoc("/search/${Uri.encode(kw)}")
        // 落地页链接（/fanren/ 这类无 .htm 的目录链接）是搜索结果的书籍本体
        doc.select(".search-list-cat a").mapNotNull { a ->
            val href = a.attr("href").trim()
            val name = a.text().trim()
            if (name.isEmpty() || href.isEmpty()) return@mapNotNull null
            OnlineBook(sourceKey = key, bookUrl = href, name = name)
        }.ifEmpty {
            // 没有 cat 区时退回 mb 区的链接（同为书籍落地页）
            doc.select(".search-list.mb a").mapNotNull { a ->
                val href = a.attr("href").trim()
                val name = a.text().trim()
                if (name.isEmpty() || href.isEmpty()) return@mapNotNull null
                OnlineBook(sourceKey = key, bookUrl = href, name = name)
            }
        }
    }

    override suspend fun catalog(bookUrl: String): List<OnlineChapter> = withContext(Dispatchers.IO) {
        val doc = fetchDoc(bookUrl)
        val chapters = doc.select("div.book-list a").mapNotNull { a ->
            val href = a.attr("href").trim()
            val title = a.attr("title").ifBlank { a.text().trim() }
            if (href.isBlank() || title.isBlank()) null else OnlineChapter(title, href)
        }
        chapters.ifEmpty { error("没有返回章节列表（页面可能改版）") }
    }

    override suspend fun content(chapter: OnlineChapter): String = withContext(Dispatchers.IO) {
        val body = client.newCall(
            Request.Builder().url(chapter.url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36")
                .header("Referer", "$baseUrl/")
                .build(),
        ).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            resp.body?.string() ?: error("空响应")
        }
        val doc = Jsoup.parse(body, baseUrl)
        val element = doc.selectFirst("#nr_body") ?: error("章节内容缺失（页面可能改版）")
        val withBreaks = element.html().replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        val plain = withBreaks
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
        // 逐行剔除广告（鲲`弩`小`说 w w w … 之类的推广行）
        plain.split("\n")
            .map { it.replace("『", "").replace("』", "").trim() }
            .filter { line ->
                val folded = line.replace(" ", "").lowercase()
                line.isNotBlank() &&
                    !folded.contains("kunnu") && !folded.contains("鲲弩") &&
                    !folded.contains("www") && !folded.contains("章节内容缺少")
            }
            .joinToString("\n")
            .trim()
    }

    private suspend fun fetchDoc(path: String) = withContext(Dispatchers.IO) {
        val url = if (path.startsWith("http")) path else baseUrl + path
        val body = client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36")
                .header("Referer", "$baseUrl/")
                .build(),
        ).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            resp.body?.string() ?: error("空响应")
        }
        Jsoup.parse(body, baseUrl)
    }
}
