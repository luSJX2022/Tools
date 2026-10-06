package com.qzkt.timetable.jw.qz

import com.qzkt.timetable.jw.JwException
import com.qzkt.timetable.jw.GradeInfo
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
