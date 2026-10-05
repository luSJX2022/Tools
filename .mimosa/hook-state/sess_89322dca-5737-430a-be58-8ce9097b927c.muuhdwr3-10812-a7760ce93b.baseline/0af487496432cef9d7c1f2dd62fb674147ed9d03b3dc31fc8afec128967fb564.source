package com.qzkt.timetable.data.book

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 国家图书馆馆藏检索（opac.nlc.cn，Aleph 系统）。
 *
 * 这是 Aleph 的**会话制**服务，跟浏览器一样得先把会话走通：
 *
 * 1. 先访问一次 `/F` 建立会话（Cookie 要留住，见 [NlcCookies]）；
 * 2. `/F?func=find-b&find_code=WRD&request={关键词}` 返回结果页（每页 10 条，
 *    含题名 / 作者 / 出版社 / 年份 / ISBN / 系统号）；
 * 3. 刚开的新会话，Aleph 有时先回一页「会话页」，真正的结果在它 meta refresh / `var tmp`
 *    指到的地址上 —— 浏览器会自动跳过去，OkHttp 不会，所以这里要手动跟一次
 *    （见 [extractSessionUrl]）。不跟就会解析出 0 条，表现成「检索不出内容」。
 * 4. 翻页同样是会话制的：从结果页里抠出会话前缀，再拼 `?func=short-jump&jump={第几条}`。
 *
 * 注意：国图 OPAC 只提供**馆藏书目信息**；电子全文阅读 / 借阅需要国图读者账号
 * 登录（网页或 App），这里提供信息与跳转，不代登录。
 */
object NlcCatalog {

    private const val BASE = "http://opac.nlc.cn"

    /** 会话只建一次；失败也不拦着后面的检索（说不定服务器不需要）。 */
    private var sessionStarted = false

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(NlcCookies)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 关键词检索（题名 / 作者 / 主题词，WRD 通配）。 */
    suspend fun search(keyword: String): NlcSearchPage = withContext(Dispatchers.IO) {
        ensureSession()
        val encoded = URLEncoder.encode(keyword.trim(), "UTF-8")
        fetch("$BASE/F?func=find-b&find_code=WRD&request=$encoded", page = 1)
    }

    /** 像浏览器那样先打开一次 OPAC 首页：Aleph 的会话（Cookie / 会话前缀）从这一步开始。 */
    private fun ensureSession() {
        if (sessionStarted) return
        sessionStarted = true
        runCatching { get("$BASE/F").length }
    }

    /** 会话内翻页（用结果页里抠出的会话地址）。 */
    suspend fun gotoPage(sessionUrl: String, jump: Int, page: Int): NlcSearchPage = withContext(Dispatchers.IO) {
        fetch("$sessionUrl?func=short-jump&jump=$jump", page = page)
    }

    private fun fetch(url: String, page: Int): NlcSearchPage {
        var body = get(url)
        var result = parseNlcSearchPage(body, BASE, page)

        // 拿到的不是结果页（新会话的「会话页」/ 需要验证的页面）→ 跟着它指的地址再走一步
        if (!isResultPage(body)) {
            val next = extractSessionUrl(body)
            if (next != null && next != url) {
                body = get(next)
                result = parseNlcSearchPage(body, BASE, page)
            }
        }

        if (!isResultPage(body)) {
            // 既没有结果块、也没有总数：说明拿到的根本不是检索结果页，
            // 别让用户对着一片空白猜（报错带上页面标题，一眼能看出是登录页还是会话页）
            error("国图没有返回检索结果页（标题：" + pageTitle(body) + "）")
        }
        return result
    }

    private fun get(url: String): String {
        var lastError: IOException? = null
        repeat(2) { attempt ->
            try {
                return client.newCall(
                    Request.Builder().url(url)
                        // 国图对非浏览器的 UA 有时会拒绝，照浏览器写
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
                        )
                        .header("Accept-Language", "zh-CN,zh;q=0.9")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .build(),
                ).execute().use { resp ->
                    check(resp.isSuccessful) { "HTTP " + resp.code }
                    resp.body.string()
                }
            } catch (e: IOException) {
                // 国图偶发抽风（连接超时 / 连接被重置），重试一次再放弃
                lastError = e
                if (attempt == 0) Thread.sleep(800)
            }
        }
        throw lastError ?: IOException("请求国图失败")
    }
}

/**
 * Aleph 会话制的内存 Cookie：会话丢了翻页必然失败，所以要在进程里留住。
 */
private object NlcCookies : CookieJar {
    private val store = mutableMapOf<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(store) {
            val list = store.getOrPut(url.host) { mutableListOf() }
            cookies.forEach { cookie ->
                list.removeAll { it.name == cookie.name }
                list += cookie
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        synchronized(store) { store[url.host].orEmpty().toList() }
}

/** 页面里有没有检索结果块 / 总数（都没有就说明拿到的不是结果页）。 */
internal fun isResultPage(html: String): Boolean =
    html.contains("resultb", ignoreCase = true) || Regex("""class\s*=\s*["']?items""").containsMatchIn(html)

private val REFRESH_URL = Regex(
    """(?is)<meta[^>]*http-equiv\s*=\s*["']?refresh["']?[^>]*content\s*=\s*["']?[^"'>]*url\s*=\s*([^"'>\s]+)""",
)
private val TMP_URL = Regex("""var\s+tmp\s*=\s*"([^"]+)"""")
// 连查询串一起匹配：只匹配到会话号的话，`?func=logout` 就被截掉了，注销链接会漏过过滤
private val SESSION_URL = Regex("""https?://[^"'\s<>]+/F/[A-Za-z0-9\-]+(?:\?[^"'\s<>]*)?""")

/**
 * 从「会话页」里抠出真正该去的地址。
 *
 * 优先 meta refresh / `var tmp` 里带 `func=` 的那个（那才是带着本次检索参数的地址），
 * 其次是页面上任意会话地址；注销链接（func=logout）直接排除 —— 跟过去就掉会话了。
 */
internal fun extractSessionUrl(html: String): String? {
    val candidates = buildList {
        REFRESH_URL.find(html)?.groupValues?.getOrNull(1)?.let(::add)
        TMP_URL.find(html)?.groupValues?.getOrNull(1)?.let(::add)
        addAll(SESSION_URL.findAll(html).map { it.value }.toList())
    }.map { it.replace("&amp;", "&") }
        .filter { it.startsWith("http") && !it.contains("func=logout") }

    return candidates.firstOrNull { it.contains("func=") } ?: candidates.firstOrNull()
}

/** 只在报错信息里用：让用户看出「到底拿回来一个什么页面」。 */
private fun pageTitle(html: String): String =
    Regex("(?is)<title>(.*?)</title>").find(html)?.groupValues?.get(1)?.trim()?.take(60)
        ?.takeIf { it.isNotEmpty() } ?: "无标题"

/** 国图馆藏里的一条书目。 */
data class NlcRecord(
    /** 系统号，形如 NLC01014182394。 */
    val docNumber: String,
    val title: String,
    val author: String? = null,
    val publisher: String? = null,
    val year: String? = null,
    val isbn: String? = null,
    /** 文献格式（BK 图书 等），已翻成中文。 */
    val format: String? = null,
    /** 详情页地址（会话链接，浏览器里能打开看馆藏）。 */
    val detailUrl: String? = null,
)

data class NlcSearchPage(
    val total: Int,
    /** 1 起的页码。 */
    val page: Int,
    val records: List<NlcRecord>,
    /** 会话地址前缀（翻页要带上）。 */
    val sessionUrl: String? = null,
) {
    val pageCount: Int get() = if (total <= 0) 1 else (total + PAGE_SIZE - 1) / PAGE_SIZE
    val hasPrev: Boolean get() = page > 1
    val hasNext: Boolean get() = page < pageCount

    companion object {
        /** 国图结果页每页 10 条（实测）。 */
        const val PAGE_SIZE = 10
    }
}

/**
 * 解析国图 OPAC 检索结果页。用真实抓取的页面做单元测试（test/resources/nlc_search.html）。
 */
internal fun parseNlcSearchPage(html: String, baseUrl: String, page: Int): NlcSearchPage {
    val doc = Jsoup.parse(html, baseUrl)

    // 总记录数：var resultb="     9151";
    val total = Regex("""var\s+resultb\s*=\s*"\s*(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull()
        ?: Regex("""parseInt\(\s*"\s*(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull()
        ?: 0

    // 会话前缀：翻页链接 func=short-jump 所在的 F/{session} 地址
    val sessionUrl = Regex("""(https?://[^"'\s]+/F/[A-Za-z0-9\-]+)\?func=short-jump""")
        .find(html)?.groupValues?.get(1)

    val records = doc.select("table.items").mapNotNull { row -> parseNlcRecord(row) }

    return NlcSearchPage(total = total, page = page, records = records, sessionUrl = sessionUrl)
}

private fun parseNlcRecord(row: Element): NlcRecord? {
    val title = row.selectFirst(".itemtitle a")?.text()?.let(::clean)?.takeIf { it.isNotEmpty() } ?: return null
    val rowHtml = row.html()
    val docNumber = Regex("""doclist\["([A-Za-z0-9]+)"\]""").find(rowHtml)?.groupValues?.get(1)
        ?: Regex("""id="([A-Za-z0-9]{8,})"""").find(rowHtml)?.groupValues?.get(1)
        ?: return null

    val isbn = Regex("""fmt_issn\("([^"]+)"\)""").find(rowHtml)?.groupValues?.get(1)?.let(::clean)?.takeIf { it.isNotEmpty() }
    val formatCode = Regex("""dispfmt\('([A-Za-z]+)'\)""").find(rowHtml)?.groupValues?.get(1)

    // 字段行是「<td class=label1>作者： <td class=content>值」这种成对结构
    val fields = mutableMapOf<String, String>()
    row.select("td.label1, td.label").forEach { label ->
        val key = clean(label.text()).trimEnd('：', ':')
        val value = label.nextElementSibling()?.text()?.let(::clean).orEmpty()
        if (key.isNotEmpty() && value.isNotEmpty()) fields[key] = value
    }

    return NlcRecord(
        docNumber = docNumber,
        title = title,
        author = fields["作者"],
        publisher = fields["出版社"],
        year = fields["年份"],
        isbn = isbn,
        format = formatCode?.let { if (it == "BK") "图书" else it },
        detailUrl = row.selectFirst(".itemtitle a")?.absUrl("href")?.takeIf { it.isNotBlank() },
    )
}

/** 页面里的 `&nbsp;` 会被解析成不换行空格，统一成普通空格并压掉连续空白。 */
private fun clean(raw: String): String =
    raw.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
