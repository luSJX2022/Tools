package com.qzkt.timetable.data.book

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
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
        // 站点把部分常用字用『』包起来防爬，里层才是真字，去掉括号即可还原
        element.textWithBreaks()
            .replace("『", "").replace("』", "")
            .stripSiteJunk()
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

/**
 * Project Gutenberg via Gutendex: Chinese-language works marked public domain
 * in the United States. The Gutenberg HTML edition is used for reading.
 */
class GutenbergChineseSource(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val apiUrl: String = "https://gutendex.com",
) : BookSource, CategorizedBookSource {

    override val key = "gutenberg-zh"
    override val name = "古腾堡中文公版（美国）"
    override val categories = listOf("中文公版" to "zh")

    override suspend fun search(keyword: String): List<OnlineBook> = withContext(Dispatchers.IO) {
        val query = "$apiUrl/books/?languages=zh&copyright=false&search=${Uri.encode(keyword.trim())}"
        parseGutenbergBookPage(getText(query)).books
    }

    override suspend fun categoryBooks(categoryId: String, page: Int): OnlineBookPage =
        withContext(Dispatchers.IO) {
            require(categoryId == "zh") { "未知分类" }
            val pageQuery = if (page <= 1) "" else "&page=$page"
            val pageResult = parseGutenbergBookPage(
                getText("$apiUrl/books/?languages=zh&copyright=false$pageQuery"),
            )
            OnlineBookPage(pageResult.books, pageResult.hasNext)
        }

    override suspend fun catalog(bookUrl: String): List<OnlineChapter> = withContext(Dispatchers.IO) {
        val uri = Uri.parse(bookUrl)
        check(uri.scheme == "https" && uri.host == "www.gutenberg.org") { "书籍链接无效" }
        listOf(OnlineChapter("全文", bookUrl))
    }

    override suspend fun content(chapter: OnlineChapter): String = withContext(Dispatchers.IO) {
        val html = getText(chapter.url)
        extractGutenbergText(html).ifBlank { error("书籍正文为空") }
    }

    private fun getText(url: String): String =
        client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", "Tools Android app (public-domain reader)")
                .build(),
        ).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            response.body.string()
        }
}

internal data class GutenbergBookPage(val books: List<OnlineBook>, val hasNext: Boolean)

internal fun parseGutenbergBookPage(body: String): GutenbergBookPage {
    val response = JSONObject(body)
    val results = response.optJSONArray("results") ?: JSONArray()
    val books = (0 until results.length()).mapNotNull { index ->
        val item = results.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optLong("id")
        val title = item.optString("title").trim()
        if (id <= 0 || title.isEmpty()) return@mapNotNull null
        val formats = item.optJSONObject("formats") ?: return@mapNotNull null
        val htmlUrl = formats.keys().asSequence()
            .filter { it.equals("text/html", ignoreCase = true) || it.startsWith("text/html;", ignoreCase = true) }
            .mapNotNull { formats.optString(it).takeIf(String::isNotBlank) }
            .firstOrNull() ?: return@mapNotNull null
        val authors = item.optJSONArray("authors")
        val author = authors?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() }
        val cover = formats.optString("image/jpeg").takeIf { it.isNotBlank() }
        OnlineBook(
            sourceKey = "gutenberg-zh",
            bookUrl = htmlUrl,
            name = title,
            author = author,
            cover = cover,
        )
    }
    return GutenbergBookPage(books, !response.isNull("next") && response.optString("next").isNotBlank())
}

internal fun extractGutenbergText(html: String): String {
    val doc = Jsoup.parse(html)
    doc.select("#pg-header, #pg-footer, .pg-boilerplate, nav, script, style").remove()
    val content = doc.selectFirst("#pg-main-content") ?: doc.body()
    return content.textWithBreaks().trim()
}

/** 内置在线书源。 */
object BookSources {

    val all: List<BookSource> = listOf(Quanben5Source(), Kunnu8Source(), GutenbergChineseSource())

    fun byKey(key: String): BookSource = all.firstOrNull { it.key == key } ?: all.first()

    /** Uri.encode 的包装：data 层直接用安卓的编码器（与浏览器 encodeURI 行为一致）。 */
    fun encodeComponent(value: String): String = Uri.encode(value)
}

/**
 * 正文容器 → 纯文本。
 *
 * 书站页面把广告脚本直接塞进正文容器：整段 `<script>` 留在里面的话，
 * 只删标签会把脚本代码当正文显示出来。这里先把 script/style 连同内容一起删掉，
 * `<br>` 换成换行，再用 Jsoup 取文本 —— 实体（`&gt;` 之类）随之正确反转义，
 * 不会像正则剥标签那样留下半转义的残字。
 */
internal fun org.jsoup.nodes.Element.textWithBreaks(): String {
    select("script, style").remove()
    val html = html().replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
    // wholeText 会把 &nbsp; 转成不换行空格，统一回普通空格
    return Jsoup.parse(html).wholeText().replace(160.toChar(), ' ') // 160 = U+00A0 不换行空格
}

/**
 * 鲲弩/落霞模板的正文容器。
 *
 * 这套模板把 `id="nr_body"` 挂在 `<body>` 标签上（kunnu8.com 和 luoxiadushu.com
 * 都一样），真正的正文在 `#nr1` 里。直接抓 `#nr_body` 等于抓整页 ——
 * 页头、「Ctrl+D 收藏本站」、「共 N 条评论」、页脚全进了阅读页。
 * 有的站若真把 nr_body 用作正文 div，先认 `div#nr_body`，最后才退回 `#nr_body`。
 */
internal fun org.jsoup.nodes.Document.kunnuContentElement(): org.jsoup.nodes.Element =
    selectFirst("#nr1")
        ?: selectFirst("div#nr_body")
        ?: selectFirst("#nr_body")
        ?: error("章节内容缺失（页面可能改版）")

/**
 * 剔除书站模板混进正文的杂行和行内水印。
 *
 * 实测（kunnu8 模板「落霞读书」）正文里会带上：面包屑（首页&gt; 书&gt; 卷&gt; 章）、
 * 重复的章节标题、「关灯 护眼 小 中 大 繁 直达底部」字号按钮、「上一章/下一章」导航、
 * adsbygoogle 广告和脚本残留。还有一种粘在段落末尾的水印
 * 「……缘分。”他说落*霞*读*书* 🐱 =- l u o x i a d u s h u . c o m -=」——
 * 字符间插了星号/表情/空格，整行过滤抓不到，得先按模板把水印连同后面的
 * 装饰和域名尾巴从行内抠掉，再逐行滤掉独立杂行；相邻的完全相同行
 * （站点把章节标题连贴两遍）也只留一份。
 */
internal fun String.stripSiteJunk(): String {
    // 「落霞读书」「鲲弩小说」：字符间可能插着 * 、空格、反引号、· 等装饰符，
    // 水印后面往往还跟着一段非汉字的装饰尾巴（表情 + 隔开的域名）
    val inlineWatermarks = listOf("落霞读书", "鲲弩小说").map { brand ->
        Regex(
            brand.toCharArray().joinToString("[\\s*＊·、`｜|]") { Regex.escape(it.toString()) } +
                "[\\s*＊·、`｜|]*(?:[^\\u4e00-\\u9fa5\\u201c\\u201d\\u300c\\u300d]{0,60})?",
        )
    }

    fun String.isSiteJunk(): Boolean {
        val folded = replace(Regex("\\s"), "").lowercase()
        // 品牌名和站点域名不受行长限制：正文不可能出现这些词
        val squashed = folded.replace(Regex("[*＊·、`｜|=\\-]"), "")
        if (squashed.contains("落霞读书") || squashed.contains("鲲弩小说") ||
            folded.contains("luoxia") || folded.contains("kunnu") ||
            folded.contains("www.")
        ) {
            return true
        }
        if (folded.length >= 60) return false
        return folded.startsWith("首页>") ||
            folded.startsWith("上一章") ||
            folded.startsWith("下一章") ||
            folded.startsWith("上一頁") ||
            folded.startsWith("下一頁") ||
            folded.contains("直达底部") ||
            folded.contains("adsbygoogle") ||
            contains("document.") ||
            contains("window.") ||
            contains("appendChild") ||
            contains("function(") ||
            contains("readyState")
    }

    val kept = mutableListOf<String>()
    for (line in split("\n").map { it.trim() }) {
        var clean = line
        inlineWatermarks.forEach { clean = clean.replace(it, "") }
        clean = clean.trim()
        if (clean.isBlank() || clean.isSiteJunk()) continue
        if (kept.isNotEmpty() && kept.last() == clean) continue // 相邻重复行（站点重复贴的标题）
        kept += clean
    }
    return kept.joinToString("\n").trim()
}

/**
 * 鲲弩小说（kunnu8.com，Cloudflare，国内手机可达）。
 *
 * 搜索：`/search/{关键词}`；命中页里 `.search-list-cat a` 是书籍落地页（`/{slug}/`）；
 * 书页的 `div.book-list a` 是全部章节（绝对地址）；
 * 正文在章节页的 `#nr1`（模板把 id="nr_body" 挂在 <body> 标签上，别抓错），
 * 正文里混着「鲲`弩`小`说 w w w …」广告行，逐行剔除。
 *
 * 分类：世界名著 / 影视原著 / 悬疑推理 / 畅销文学 / 言情穿越 / 编辑精选。
 */
class Kunnu8Source(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val baseUrl: String = "https://www.kunnu8.com",
) : BookSource, CategorizedBookSource {

    override val key = "kunnu8"
    override val name = "鲲弩小说"

    override val categories: List<Pair<String, String>> = listOf(
        "世界名著" to "mingzhu",
        "影视原著" to "yuanzhu",
        "悬疑推理" to "xuanyi",
        "畅销文学" to "hot",
        "言情穿越" to "yanqing",
        "编辑精选" to "hao",
    )

    /** 分类页 `div.pop-books2` 里是带封面 / 书名 / 简介的书卡，没有翻页。 */
    override suspend fun categoryBooks(categoryId: String, page: Int): OnlineBookPage =
        withContext(Dispatchers.IO) {
            if (page > 1) return@withContext OnlineBookPage(emptyList(), hasNext = false)
            val doc = fetchDoc("/$categoryId/")
            val books = doc.select(".pop-books2 .pop-book2").mapNotNull { block ->
                val href = block.selectFirst("a[href]")?.attr("href")?.trim() ?: return@mapNotNull null
                val name = block.selectFirst(".pop-tit")?.text()?.trim() ?: return@mapNotNull null
                if (name.isBlank() || href.isBlank()) return@mapNotNull null
                OnlineBook(
                    sourceKey = key,
                    bookUrl = href,
                    name = name,
                    cover = block.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() },
                    description = block.selectFirst(".pop-intro")?.attr("title")?.ifBlank { null },
                )
            }
            OnlineBookPage(books = books, hasNext = false)
        }

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
        val element = doc.kunnuContentElement()
        // 先剥掉正文里的 script/style 并反转义实体，再逐行剔除广告
        // （鲲`弩`小`说 w w w … 之类的推广行），最后剔除书站模板杂行
        element.textWithBreaks()
            .split("\n")
            .map { it.replace("『", "").replace("』", "").trim() }
            .filter { line ->
                val folded = line.replace(" ", "").lowercase()
                line.isNotBlank() &&
                    !folded.contains("kunnu") && !folded.contains("鲲弩") &&
                    !folded.contains("www") && !folded.contains("章节内容缺少")
            }
            .joinToString("\n")
            .stripSiteJunk()
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
