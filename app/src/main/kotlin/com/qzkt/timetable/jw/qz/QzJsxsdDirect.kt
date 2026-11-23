package com.qzkt.timetable.jw.qz

import com.qzkt.timetable.jw.ExamInfo
import com.qzkt.timetable.jw.JwException
import com.qzkt.timetable.jw.GradeInfo
import com.qzkt.timetable.data.PluginLog
import com.qzkt.timetable.jw.extractExamTermOptions
import com.qzkt.timetable.jw.parseExamsHtml
import com.qzkt.timetable.jw.parseGradesHtml
import com.qzkt.timetable.jw.qz.QzJsxsdAdapter.Companion.TIMETABLE_PATHS
import com.qzkt.timetable.jw.qz.QzJsxsdAdapter.Companion.resolveBase
import com.qzkt.timetable.model.CourseSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.time.LocalDate

/**
 * 拿着**已有的会话 cookie** 直接去拉课表页，跳过登录。
 *
 * 存在的原因：手机 WebView 里那个强智主界面是桌面布局（菜单又小又难找），
 * 让用户"先点进学生课表查询再抓取"不现实。既然他已经登录了，
 * 会话就在 `CookieManager` 里，我们直接用它请求课表页即可。
 *
 * 顺带还有个好处：这条路走的正是 [QzJsxsdAdapter] 用的那几个地址，
 * 所以它一旦成功，就等于验证了自动同步那条路在这个学校是通的。
 */
internal class QzJsxsdDirect(
    private val clientFactory: () -> OkHttpClient = QzHttp::defaultClient,
) {

    data class Outcome(
        /** 解析出来的课程；为空表示这条路没走通。 */
        val sessions: List<CourseSession>,
        /** 真正解析出课表的那个地址；失败时为 null。 */
        val usedPath: String?,
        /** 最后一次拿到（但没解析出课表）的页面内容，给诊断用。 */
        val html: String,
        val triedPaths: List<String>,
        /** 主界面上读到的"现在是第几周"；用来倒推开学日期，让课表能显示具体日期。 */
        val currentWeek: Int? = null,
        /** 主界面上读到的学期总周数。 */
        val weekCount: Int? = null,
        /**
         * 是否已经是登录状态。
         *
         * 三个候选地址全被打回登录页，就说明**还没登录** —— 这跟"登录了但课表排版不认识"
         * 是两回事，提示语必须分开，否则用户会一直点同一个按钮。
         */
        val loggedIn: Boolean = true,
    ) {
        val ok: Boolean get() = sessions.isNotEmpty()
    }

    data class GradesOutcome(
        /** 解析出来的成绩；为空表示这条路没走通。 */
        val grades: List<GradeInfo>,
        /**
         * 是否已经是登录状态。
         *
         * 成绩页被打回登录页（会话没登进去 / 已过期）时为 false，
         * 调用方应当引导重新在应用内登录，而不是报「查询失败」。
         */
        val loggedIn: Boolean,
    )

    data class ExamsOutcome(
        /** 解析出来的考试安排；为空表示这学期没有，或者页面没认出来。 */
        val exams: List<ExamInfo>,
        /** 同上：false = 会话过期，要去应用内登录。 */
        val loggedIn: Boolean,
        /** 真正解析出考试的那个地址；没找到页面时为 null。 */
        val usedPath: String? = null,
        /** 页面内容摘要（前 400 字），页面没认出来时给诊断用。 */
        val pageSnippet: String = "",
    )

    /**
     * 拿着**已有的会话 cookie** 直接拉成绩页。
     *
     * 有的学校登录页有反自动化校验，账号密码这条路走不通（成绩页以前因此直接废掉）；
     * 但用户在应用内登录一次之后会话 cookie 就在手上，和课表一样能直接查。
     *
     * 地址候选：
     * - `/xxwcqk/xxwcqkOnkcxz.do`：新版教务一体化的「学习完成情况」页，
     *   海都学院浏览器实测就这个地址出成绩（未登录时开它停在登录页）；
     * - `/kscj/cjcx_query`：老版成绩查询，别的学校兜底；
     * - 最后给 cjcx_query 补一次 POST 空查询（查询条件全空 = 全部学期）。
     *
     * 每个地址 GET 都试两遍：学校服务器偶尔抽风返回空页，第二遍往往就好了。
     */
    suspend fun fetchGrades(base: String, cookie: String?): GradesOutcome = withContext(Dispatchers.IO) {
        val client = clientFactory()
        val normalizedBase = runCatching { resolveBase(base) }.getOrElse { throw JwException("地址不对：$base") }
        var sawLoginPage = false

        fun handle(html: String): List<GradeInfo>? {
            if (html.isLoginPage()) {
                sawLoginPage = true
                return null
            }
            val grades = parseGradesHtml(html)
            return grades.ifEmpty { null }
        }

        try {
            for (path in listOf("/xxwcqk/xxwcqkOnkcxz.do", "/kscj/cjcx_query")) {
                val url = normalizedBase + path
                repeat(2) {
                    val html = runCatching { get(client, url, cookie) }.getOrNull() ?: return@repeat
                    handle(html)?.let { return@withContext GradesOutcome(it, loggedIn = true) }
                    // 被打回登录页说明会话没登进去 / 已过期，重试也没用，直接换下一个地址
                    if (html.isLoginPage()) return@repeat
                }
            }

            val queried = runCatching {
                post(client, "$normalizedBase/kscj/cjcx_query", cookie, form = mapOf("kksj" to "", "kcxz" to "", "kcmc" to "", "xsfs" to "1"))
            }.getOrNull()
            if (queried != null) {
                handle(queried)?.let { return@withContext GradesOutcome(it, loggedIn = true) }
            }

            GradesOutcome(emptyList(), loggedIn = !sawLoginPage)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    /**
     * 拿着**已有的会话 cookie** 拉「考试安排」页。
     *
     * 和成绩一样，考试安排页也是登录态才能看，所以走同一条会话路：
     *
     * - 地址先从教务主界面的菜单里找（各校把「考试安排」挂在不同路径下），
     *   找不到再用常见地址兜底；
     * - 先直接 GET（多数学校默认就查当前学期）；
     * - 页面上没有考试，再按学年学期 POST 一次 —— 强智的查询条件就是 `xn` / `xq`。
     */
    suspend fun fetchExams(base: String, cookie: String?): ExamsOutcome = withContext(Dispatchers.IO) {
        val client = clientFactory()
        val normalizedBase = runCatching { resolveBase(base) }.getOrElse { throw JwException("地址不对：$base") }
        var sawLoginPage = false
        var usedPath: String? = null
        var snippet = ""

        // 页面上出现的学年学期（强智列表页不带 xnxq01id 时不出表格）
        val terms = LinkedHashSet<String>()

        fun tryParse(html: String, path: String): List<ExamInfo>? {
            if (html.isLoginPage()) {
                sawLoginPage = true
                return null
            }
            usedPath = path
            snippet = html.replace(Regex("\\s+"), " ").take(400)
            extractExamTermOptions(html).forEach { terms += it }
            return parseExamsHtml(html).ifEmpty { null }
        }

        /** 页面本体解析不出来时，把同源的 iframe / frame 也抓一遍（有的强智把列表放在框架里）。 */
        fun tryParseWithFrames(html: String, url: String): List<ExamInfo>? {
            tryParse(html, url)?.let { return it }
            val doc = runCatching { Jsoup.parse(html, url) }.getOrNull() ?: return null
            for (frame in doc.select("iframe[src], frame[src]").take(3)) {
                val frameUrl = url.toHttpUrlOrNull()?.resolve(frame.attr("src"))?.toString() ?: continue
                val frameHtml = runCatching { get(client, frameUrl, cookie) }.getOrNull() ?: continue
                tryParse(frameHtml, frameUrl)?.let { return it }
            }
            return null
        }

        try {
            val discovered = runCatching { discoverExamPaths(client, normalizedBase, cookie) }.getOrNull().orEmpty()
            val candidates = (discovered + EXAM_PATHS.map { normalizedBase + it }).distinct()
            val reachable = LinkedHashSet<String>()

            // 第一轮：直接 GET（有些学校默认就查当前学期）
            for (url in candidates) {
                val html = runCatching { get(client, url, cookie) }.getOrNull() ?: continue
                tryParseWithFrames(html, url)?.let {
                    return@withContext ExamsOutcome(it, loggedIn = true, usedPath = url, pageSnippet = snippet)
                }
                if (html.isLoginPage()) continue
                reachable += url
            }

            // 学期候选：页面上抓到的在前（selected 的排第一），再按当前日期推算
            examTermCandidates().forEach { (xn, xq) -> terms += "$xn-$xq" }
            val termList = terms.take(4)

            fun forms(term: String): List<Map<String, String>> {
                val parts = term.split("-")
                val xn = parts.getOrNull(0).orEmpty() + "-" + parts.getOrNull(1).orEmpty()
                val xq = parts.getOrNull(2).orEmpty()
                return listOf(
                    mapOf("xnxq01id" to term, "xqlbmc" to "", "ksmcdm" to ""),
                    mapOf("xnxqid" to term, "xqlbmc" to ""),
                    mapOf("xn" to xn, "xq" to xq),
                )
            }

            fun hit(exams: List<ExamInfo>, url: String): ExamsOutcome {
                PluginLog.i("教务", "考试查询成功：" + url + " 共 " + exams.size + " 条")
                return ExamsOutcome(exams, loggedIn = true, usedPath = url, pageSnippet = snippet)
            }

            // 优先用「列表页」这个标准地址，没探到就用第一个能打开的候选
            val primary = reachable.firstOrNull { it.contains("xsksap_list") }
                ?: reachable.firstOrNull()
                ?: (normalizedBase + EXAM_PATHS.first())

            // 第二轮：强智标准 POST（xnxq01id），每个学期试一次，命中即停
            for (term in termList) {
                val html = runCatching { post(client, primary, cookie, forms(term).first()) }.getOrNull() ?: continue
                tryParse(html, primary)?.let { return@withContext hit(it, primary) }
                if (html.isLoginPage()) break
            }

            // 第三轮：换参数写法（xnxqid / xn+xq）与 query，参数只试前两个学期
            for (term in termList.take(2)) {
                for (form in forms(term).drop(1)) {
                    val html = runCatching { post(client, primary, cookie, form) }.getOrNull() ?: continue
                    tryParse(html, primary)?.let { return@withContext hit(it, primary) }
                    if (html.isLoginPage()) break
                }
                val separator = if (primary.contains('?')) "&" else "?"
                val viaQuery = runCatching { get(client, primary + separator + "xnxq01id=" + term, cookie) }
                    .getOrNull() ?: continue
                tryParse(viaQuery, primary)?.let { return@withContext hit(it, primary) }
            }

            // 第四轮：其它候选地址兜底（只用第一个学期试一次）
            val firstTerm = termList.firstOrNull()
            if (firstTerm != null) {
                for (url in (reachable + EXAM_PATHS.map { normalizedBase + it }).distinct()
                    .filter { it != primary }.take(2)
                ) {
                    val html = runCatching { post(client, url, cookie, forms(firstTerm).first()) }.getOrNull() ?: continue
                    tryParse(html, url)?.let { return@withContext hit(it, url) }
                }
            }

            PluginLog.w(
                "教务",
                "考试查询没出结果：主地址 " + primary + "、学期 " + termList + "、登录页=" + sawLoginPage +
                    "、最后页面=" + snippet.take(120),
            )
            ExamsOutcome(emptyList(), loggedIn = !sawLoginPage, usedPath = usedPath, pageSnippet = snippet)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    /**
     * 从教务主界面的菜单里找「考试安排」的地址。
     *
     * 新版一体化的菜单是 JS 动态生成的，`<a>` 常常扫不到，所以页面原文里的
     * `xsks` / `ksap` 地址也一起挖；菜单放在 iframe 里时把 iframe 也抓一遍。
     */
    private fun discoverExamPaths(client: OkHttpClient, base: String, cookie: String?): List<String> {
        val found = mutableListOf<String>()
        for (path in QzJsxsdAdapter.HOME_PATHS) {
            val pageUrl = base + path
            val pageHtml = runCatching { get(client, pageUrl, cookie) }.getOrNull() ?: continue
            if (pageHtml.isBlank() || pageHtml.isLoginPage()) continue

            val doc = runCatching { Jsoup.parse(pageHtml, pageUrl) }.getOrNull() ?: continue
            collectExamLinks(doc, pageUrl, found)

            for (frame in doc.select("iframe[src], frame[src]")) {
                val frameUrl = pageUrl.toHttpUrlOrNull()?.resolve(frame.attr("src"))?.toString() ?: continue
                val frameHtml = runCatching { get(client, frameUrl, cookie) }.getOrNull() ?: continue
                if (frameHtml.isBlank() || frameHtml.isLoginPage()) continue
                collectExamLinks(Jsoup.parse(frameHtml, frameUrl), frameUrl, found)
            }
        }
        return found.distinct()
    }

    private fun collectExamLinks(doc: org.jsoup.nodes.Document, pageUrl: String, into: MutableList<String>) {
        fun add(href: String) {
            if (href.isBlank() || href.startsWith("javascript", ignoreCase = true)) return
            pageUrl.toHttpUrlOrNull()?.resolve(href)?.toString()?.let { into += it }
        }

        doc.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href").trim()
            if (anchor.text().contains("考试") || EXAM_URL_PATTERN.containsMatchIn(href)) add(href)
        }
        EXAM_URL_PATTERN.findAll(doc.body().html()).map { it.groupValues[1] }.forEach(::add)
    }

    /** 当前学期的 `xn` / `xq`，再补一个相邻学期：学校放假期间两边都可能查得到。 */
    private fun examTermCandidates(today: LocalDate = LocalDate.now()): List<Pair<String, String>> {
        val month = today.monthValue
        val startYear = if (month >= 9) today.year else today.year - 1
        val term = if (month >= 9 || month == 1) "1" else "2"
        val other = if (term == "1") "2" else "1"
        return listOf(
            "$startYear-${startYear + 1}" to term,
            "$startYear-${startYear + 1}" to other,
        )
    }

    suspend fun fetchTimetable(base: String, cookie: String?): Outcome = withContext(Dispatchers.IO) {
        val client = clientFactory()
        val normalizedBase = runCatching { resolveBase(base) }.getOrElse { throw JwException("地址不对：$base") }

        val tried = mutableListOf<String>()
        var lastHtml = ""
        var sawLoginPage = false

        try {
            for (path in TIMETABLE_PATHS) {
                tried += path
                val html = runCatching { get(client, normalizedBase + path, cookie) }.getOrNull() ?: continue
                if (html.isBlank()) continue
                // 被打回登录页：没登录，或者会话过期了
                if (html.contains("userAccount") || html.contains("loginForm")) {
                    lastHtml = html
                    sawLoginPage = true
                    continue
                }
                lastHtml = html

                val sessions = QzWebParser.parseDocuments(listOf(html)).sessions
                if (sessions.isNotEmpty()) {
                    // 顺带读一下主界面上的周次：网页版不给开学日期，
                    // 但知道"现在是第几周"就能倒推出第一周周一，课表上才会有日期
                    val term = runCatching { fetchTermInfo(client, normalizedBase, cookie) }.getOrNull()
                    return@withContext Outcome(
                        sessions = sessions,
                        usedPath = path,
                        html = html,
                        triedPaths = tried,
                        currentWeek = term?.first,
                        weekCount = term?.second,
                        loggedIn = true,
                    )
                }
            }
            Outcome(
                sessions = emptyList(),
                usedPath = null,
                html = lastHtml,
                triedPaths = tried,
                loggedIn = !sawLoginPage,
            )
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    /** 从主界面抠出 (当前周次, 总周数)，读不到就返回 null。 */
    private fun fetchTermInfo(client: OkHttpClient, base: String, cookie: String?): Pair<Int, Int>? {
        for (path in QzJsxsdAdapter.HOME_PATHS) {
            val html = runCatching { get(client, base + path, cookie) }.getOrNull() ?: continue
            if (html.isBlank() || html.contains("userAccount")) continue

            val doc = runCatching { org.jsoup.Jsoup.parse(html) }.getOrNull() ?: continue
            val text = doc.text()
            val currentWeek = Regex("第\\s*(\\d{1,2})\\s*周").find(text)?.groupValues?.get(1)?.toIntOrNull()
            val weekCount = doc.select("div#li_showWeek").firstOrNull()?.text()
                ?.let { Regex("/(\\d{1,2})\\s*周").find(it)?.groupValues?.get(1)?.toIntOrNull() }

            if (currentWeek != null) return currentWeek to (weekCount ?: 0)
        }
        return null
    }

    private fun get(client: OkHttpClient, url: String, cookie: String?): String {
        val httpUrl = url.toHttpUrlOrNull() ?: throw JwException("地址格式不对：$url")
        val builder = Request.Builder()
            .url(httpUrl)
            .get()
            .header("User-Agent", QzHttp.USER_AGENT)
            .header("Referer", "${httpUrl.scheme}://${httpUrl.host}/jsxsd/")
        if (!cookie.isNullOrBlank()) builder.header("Cookie", cookie)

        client.newCall(builder.build()).execute().use { response ->
            val text = QzHttp.decodeBody(response.peekBody(Long.MAX_VALUE).bytes(), response.header("Content-Type"))
            if (!response.isSuccessful) throw JwException("HTTP ${response.code}")
            return text
        }
    }

    private fun post(client: OkHttpClient, url: String, cookie: String?, form: Map<String, String>): String {
        val httpUrl = url.toHttpUrlOrNull() ?: throw JwException("地址格式不对：$url")
        val formBody = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val builder = Request.Builder()
            .url(httpUrl)
            .post(formBody)
            .header("User-Agent", QzHttp.USER_AGENT)
            .header("Referer", url)
        if (!cookie.isNullOrBlank()) builder.header("Cookie", cookie)

        client.newCall(builder.build()).execute().use { response ->
            val text = QzHttp.decodeBody(response.peekBody(Long.MAX_VALUE).bytes(), response.header("Content-Type"))
            if (!response.isSuccessful) throw JwException("HTTP ${response.code}")
            return text
        }
    }

    /** 页面是不是登录页（会话没登进去 / 已过期时，学校会把任何地址重定向回它）。 */
    private fun String.isLoginPage(): Boolean = contains("userAccount") || contains("loginForm")
}

/**
 * 强智「考试安排」页的常见地址（主界面菜单里没挖到时兜底）。
 *
 * 新版一体化普遍是 `/xsks/xsksap_list`（查询条件 `xn` / `xq`），
 * 也有学校是 `xsksap_query` / `xsks_list`，都试一遍。
 */
internal val EXAM_PATHS = listOf(
    "/xsks/xsksap_list",
    "/xsks/xsksap_query",
    "/xsks/xsks_list",
)

/** 页面里像「考试安排」地址的串：带 `xsks` / `ksap` / `ksxx` 的路径。 */
private val EXAM_URL_PATTERN =
    Regex("[\"'(]([^\"'()\\s<>]*(?:xsks|ksap|ksxx)[^\"'()\\s<>]*)[\"')]", RegexOption.IGNORE_CASE)
