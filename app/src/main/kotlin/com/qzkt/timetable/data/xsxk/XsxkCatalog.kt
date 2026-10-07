package com.qzkt.timetable.data.xsxk

import com.qzkt.timetable.jw.qz.QzHttp
import com.qzkt.timetable.jw.qz.QzJsxsdAdapter
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/** 选课中心里的一轮选课（学年学期 / 选课名称 / 选课时间 / 操作列的进入链接）。 */
data class XsxkRound(
    val term: String,
    val name: String,
    val time: String,
    /** 「进入选课」那颗按钮的地址；纯文字时为 null。 */
    val entryUrl: String? = null,
)

/** 拉一轮选课列表的结果：轮次 + 登录态 + 页面认不认得。 */
data class RoundsOutcome(
    val rounds: List<XsxkRound>,
    /** false = 被打回登录页（会话过期），调用方走自动登录。 */
    val loggedIn: Boolean,
    /** false = 页面不是选课轮次表（入口地址不对 / 结构变了）。 */
    val pageKnown: Boolean,
)

/**
 * 拉取并解析「学生选课中心」的选课轮次表。
 *
 * 页面表头：学年学期 / 选课名称 / 选课时间 / 操作；没有开放轮次时是
 * 「未查询到数据」的空表（[RoundsOutcome.rounds] 为空、pageKnown 为 true）。
 * 阻塞式网络调用，调用方需自行放在 IO 线程。
 */
fun fetchRounds(client: OkHttpClient, entryUrl: String, cookie: String): RoundsOutcome {
    val body = runCatching {
        client.newCall(
            Request.Builder().url(entryUrl)
                .header("User-Agent", QzHttp.USER_AGENT)
                .header("Cookie", cookie)
                .get()
                .build(),
        ).execute().use { resp ->
            if (!resp.isSuccessful) {
                null
            } else {
                QzHttp.decodeBody(resp.peekBody(Long.MAX_VALUE).bytes(), resp.header("Content-Type"))
            }
        }
    }.getOrNull()

    return when {
        body == null -> RoundsOutcome(emptyList(), loggedIn = true, pageKnown = false)
        body.isLoginPage() -> RoundsOutcome(emptyList(), loggedIn = false, pageKnown = false)
        else -> {
            val rounds = parseRounds(body, entryUrl)
            val known = rounds.isNotEmpty() ||
                body.contains("选课名称") || body.contains("未查询到数据")
            RoundsOutcome(rounds, loggedIn = true, pageKnown = known)
        }
    }
}

private fun String.isLoginPage(): Boolean = contains("userAccount") || contains("loginForm")

/**
 * 解析选课轮次表。表头文字各校可能微调，按表头映射列；操作列里抓「进入选课」的链接。
 * 页面没有轮次表（结构不认识）返回空列表。
 */
internal fun parseRounds(html: String, pageUrl: String): List<XsxkRound> {
    val doc = runCatching { Jsoup.parse(html, pageUrl) }.getOrNull() ?: return emptyList()
    val table = doc.select("table").firstOrNull { t ->
        t.select("th,td").any { it.text().replace(Regex("\\s"), "").contains("选课名称") }
    } ?: return emptyList()

    val headerCells = table.select("tr")
        .firstOrNull { row -> row.select("th,td").any { it.text().contains("选课名称") } }
        ?.select("th,td")
        ?.map { it.text().replace(Regex("\\s"), "") }
        ?: return emptyList()

    fun col(vararg aliases: String): Int? = aliases.firstNotNullOfOrNull { alias ->
        headerCells.indexOfFirst { it.contains(alias) }.takeIf { it >= 0 }
    }
    val termCol = col("学年学期")
    val nameCol = col("选课名称")
    val timeCol = col("选课时间")
    val opCol = col("操作")

    return table.select("tr").mapNotNull { row ->
        val cells = row.select("td")
        if (cells.isEmpty()) return@mapNotNull null
        fun cell(index: Int?): String? = index?.let { cells.getOrNull(it)?.text()?.trim() }
        val name = cell(nameCol) ?: return@mapNotNull null
        if (name.isBlank() || name.contains("选课名称")) return@mapNotNull null
        val entry = opCol?.let { cells.getOrNull(it)?.selectFirst("a")?.attr("href") }
        XsxkRound(
            term = cell(termCol).orEmpty(),
            name = name,
            time = cell(timeCol).orEmpty(),
            entryUrl = entry
                ?.takeIf { it.isNotBlank() && !it.startsWith("javascript", ignoreCase = true) }
                ?.let { pageUrl.toHttpUrlOrNull()?.resolve(it)?.toString() },
        )
    }
}

/**
 * 从教务主界面的菜单里找「学生选课中心」入口，按可靠性递进三步：
 *
 * 1. 主界面 `<a>` 扫描：href 带 `xsxk`（强智选课路径的通用特征）或文字带「选课」；
 * 2. 整页原文挖 `xsxk` 地址 —— 新一代主界面的菜单是 JS 动态生成的，
 *    `<a>` 扫不到，但选课地址往往就写在页面脚本配置里；
 * 3. 菜单可能放在 iframe 里：iframe 的 src 也抓来扫一遍。
 *
 * 相对地址按浏览器语义解析（基于页面 URL）；全找不到返回 null。
 * xsxk 命名的脚本/样式资源（.js/.css）不算入口。
 */
internal fun discoverCourseSelectUrl(baseUrl: String, cookie: String): String? {
    val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    try {
        for (path in QzJsxsdAdapter.HOME_PATHS) {
            val pageUrl = baseUrl + path
            val body = fetchPage(client, pageUrl, cookie) ?: continue
            if (body.isBlank() || body.contains("userAccount")) continue // 被打回登录页

            val doc = Jsoup.parse(body, pageUrl)
            scanForCourseSelectUrl(doc, pageUrl)?.let { return it }

            for (frame in doc.select("iframe[src], frame[src]")) {
                val frameUrl = pageUrl.toHttpUrlOrNull()?.resolve(frame.attr("src"))?.toString() ?: continue
                val frameBody = fetchPage(client, frameUrl, cookie) ?: continue
                if (frameBody.isBlank() || frameBody.contains("userAccount")) continue
                scanForCourseSelectUrl(Jsoup.parse(frameBody, frameUrl), frameUrl)?.let { return it }
            }
        }
    } finally {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
    return null
}

private fun fetchPage(client: OkHttpClient, url: String, cookie: String): String? =
    runCatching {
        client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", QzHttp.USER_AGENT)
                .header("Cookie", cookie)
                .get()
                .build(),
        ).execute().use { resp ->
            if (!resp.isSuccessful) {
                null
            } else {
                QzHttp.decodeBody(resp.peekBody(Long.MAX_VALUE).bytes(), resp.header("Content-Type"))
            }
        }
    }.getOrNull()

/** 单个页面里的选课入口扫描：`<a>` 优先，扫不到再从整页原文挖带 xsxk 的页面地址。 */
private fun scanForCourseSelectUrl(doc: org.jsoup.nodes.Document, pageUrl: String): String? {
    val anchor = doc.select("a[href]").firstOrNull { a ->
        a.attr("href").contains("xsxk", ignoreCase = true) ||
            a.text().replace(" ", "").contains("选课")
    }
    val fromAnchor = anchor?.attr("href")?.trim().takeUnless {
        it.isNullOrEmpty() || it.startsWith("javascript", ignoreCase = true)
    }
    val found = fromAnchor
        ?: JS_URL_REGEX.findAll(doc.body().html()).map { it.groupValues[1] }
            .firstOrNull { href -> JS_URL_VALID(href) }
    // 菜单模板里可能挂着 xsxk 命名的脚本/样式资源，那不是页面入口
    if (found != null && isCourseSelectPage(found)) {
        // 按浏览器语义解析：相对链接基于页面 URL（含目录），不是基于 /jsxsd 根
        return pageUrl.toHttpUrlOrNull()?.resolve(found)?.toString()
    }
    return null
}

/** 页面脚本里挖地址：引号包起来的、带 xsxk 的字符串（JS 动态菜单的配置一般长这样）。 */
private val JS_URL_REGEX = Regex("[\"']([^\"']*xsxk[^\"']*)[\"']", RegexOption.IGNORE_CASE)

private fun JS_URL_VALID(href: String): Boolean =
    !href.startsWith("javascript", true) &&
        (href.contains(".html", true) || href.contains(".do", true) || href.contains(".jsp", true))

/** 像选课页（而不是脚本/样式资源）的地址：带 xsxk 且是页面类型。 */
internal fun isCourseSelectPage(url: String): Boolean =
    url.contains("xsxk", ignoreCase = true) &&
        !url.substringBefore('?').endsWith(".js", ignoreCase = true) &&
        !url.substringBefore('?').endsWith(".css", ignoreCase = true)
