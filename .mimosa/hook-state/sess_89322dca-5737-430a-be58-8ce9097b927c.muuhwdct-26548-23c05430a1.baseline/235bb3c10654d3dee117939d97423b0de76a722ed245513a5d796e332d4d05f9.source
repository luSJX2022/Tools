package com.qzkt.timetable.jw.qz

import com.qzkt.timetable.jw.JwException
import com.qzkt.timetable.jw.qz.QzJsxsdAdapter.Companion.TIMETABLE_PATHS
import com.qzkt.timetable.jw.qz.QzJsxsdAdapter.Companion.resolveBase
import com.qzkt.timetable.model.CourseSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
}
