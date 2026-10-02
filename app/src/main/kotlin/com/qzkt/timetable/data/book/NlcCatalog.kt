package com.qzkt.timetable.data.book

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 国家图书馆馆藏检索（opac.nlc.cn，Aleph 系统）。
 *
 * 单次 GET 就能检索：`/F?func=find-b&find_code=WRD&request={关键词}` 直接返回
 * 结果页（每页 10 条，含题名 / 作者 / 出版社 / 年份 / ISBN / 系统号）。
 * 翻页是会话制的：从结果页里抠出会话前缀，再拼 `?func=short-jump&jump={第几条}`。
 *
 * 注意：国图 OPAC 只提供**馆藏书目信息**；电子全文阅读 / 借阅需要国图读者账号
 * 登录（网页或 App），这里提供信息与跳转，不代登录。
 */
object NlcCatalog {

    private const val BASE = "http://opac.nlc.cn"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 关键词检索（题名 / 作者 / 主题词，WRD 通配）。 */
    suspend fun search(keyword: String): NlcSearchPage = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(keyword.trim(), "UTF-8")
        fetch("$BASE/F?func=find-b&find_code=WRD&request=$encoded", page = 1)
    }

    /** 会话内翻页（用结果页里抠出的会话地址）。 */
    suspend fun gotoPage(sessionUrl: String, jump: Int, page: Int): NlcSearchPage = withContext(Dispatchers.IO) {
        fetch("$sessionUrl?func=short-jump&jump=$jump", page = page)
    }

    private fun fetch(url: String, page: Int): NlcSearchPage {
        val body = client.newCall(
            Request.Builder().url(url)
                // 国图对非浏览器的 UA 有时会拒绝，照浏览器写
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
                )
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build(),
        ).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            resp.body?.string() ?: error("空响应")
        }
        return parseNlcSearchPage(body, BASE, page)
    }
}

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
