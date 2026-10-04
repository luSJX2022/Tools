package com.qzkt.timetable.jw.qz

import com.qzkt.timetable.jw.JwAdapter
import com.qzkt.timetable.jw.JwConfig
import com.qzkt.timetable.jw.JwException
import com.qzkt.timetable.jw.JwSession
import com.qzkt.timetable.jw.JwTermInfo
import com.qzkt.timetable.jw.RawExchange
import com.qzkt.timetable.model.CourseSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * 强智 **jsxsd 网页版**适配器（`/jsxsd/...` 这一代，含 `xsMainV.htmlx` 新版界面）。
 *
 * 移动端 `app.do` 接口不是每个学校都开 —— 比如邯郸学院就没有，
 * 但网页版是每个学校都有的，所以这条路更通用。
 *
 * 流程：
 * ```
 * ① POST {base}/Logon.do?method=logon&flag=sess   → "<scode>#<sxh>"
 * ② 按 scode/sxh 把「学号%%%密码」搅成 encoded      （见 QzJsxsdLogin）
 * ③ POST {base}/xk/LoginToXk                      → 拿到会话 cookie
 * ④ GET  {base}/xskb/xskb_list.do                 → 整学期课表 HTML
 * ⑤ GET  {base}/framework/xsMain_new.jsp?t1=1     → 当前周次 / 总周数
 * ```
 *
 * 课表 HTML 不单独写解析器，直接交给已经在用的 [QzWebParser]：
 * 它对"列是星期、行是节次"的表格排版本来就是容错的，少一份要维护的解析逻辑。
 */
class QzJsxsdAdapter(
    private val clientFactory: () -> OkHttpClient = QzHttp::defaultClient,
) : JwAdapter {

    override val id: String = ID
    override val displayName: String = "强智（jsxsd 网页版接口）"

    override suspend fun connect(config: JwConfig): JwSession = withContext(Dispatchers.IO) {
        val base = resolveBase(config.baseUrl)
        val session = QzJsxsdSession(base, config.username, clientFactory())
        session.login(config.password)
        session
    }

    companion object {
        const val ID = "qz-jsxsd"

        /** 课表页候选。各校版本不一，按顺序试，能解析出课表就停。 */
        internal val TIMETABLE_PATHS = listOf(
            "/xskb/xskb_list.do",
            "/xskbcx/xskbcx_cxXsKb.html",
            "/kbcx/xskbcx_index.jsp",
        )

        /** 主界面候选，用于读当前周次。 */
        internal val HOME_PATHS = listOf(
            "/framework/xsMain_new.jsp?t1=1",
            "/framework/xsMain.jsp",
            "/framework/xsMainV.htmlx",
        )

        /**
         * 把用户填的地址整成 `.../jsxsd`。
         *
         * 用户可能填了站点根、`/jsxsd/`、甚至具体某个页面（`/jsxsd/framework/xsMainV.htmlx`），
         * 统一截到 `/jsxsd` 这一层。
         */
        fun resolveBase(rawInput: String): String {
            var text = rawInput.trim()
            if (text.isEmpty()) throw JwException("请先填写学校强智教务系统地址")

            if (!text.startsWith("http://", true) && !text.startsWith("https://", true)) {
                text = "https://$text"
            }
            text = text.substringBefore('?').substringBefore('#').trimEnd('/')

            val marker = text.indexOf("/jsxsd", ignoreCase = true)
            if (marker >= 0) return text.substring(0, marker + "/jsxsd".length)

            return "$text/jsxsd"
        }

        /** 这个地址看起来是不是 jsxsd 平台。 */
        fun looksLikeJsxsd(rawInput: String): Boolean =
            rawInput.contains("jsxsd", ignoreCase = true)
    }

    private class QzJsxsdSession(
        private val base: String,
        private val username: String,
        private val client: OkHttpClient,
    ) : JwSession {

        private val log = ArrayDeque<RawExchange>()

        override var studentName: String? = null
            private set

        override val rawLog: List<RawExchange> get() = log.toList()

        fun login(password: String) {
            val sessionKey = fetchSessionKey()
            val encoded = QzJsxsdLogin.encode(username, password, sessionKey)
                ?: throw JwException("登录参数计算失败：学校返回的校验串格式不对（${sessionKey.take(40)}）")

            val body = post(
                label = "③ 登录 LoginToXk",
                url = "$base/xk/LoginToXk",
                form = mapOf(
                    "loginMethod" to "LoginToXk",
                    "userAccount" to username,
                    "userPassword" to "",
                    "encoded" to encoded,
                ),
                referer = "$base/",
            )

            // 登录成功就不会再看到登录表单了
            if (body.contains("userAccount") || body.contains("loginForm")) {
                throw JwException(
                    "登录失败：${extractLoginMessage(body)}" +
                        "（学校这次没有读到程序提交的表单内容，可能它的登录页有反自动化校验；" +
                        "如果是这样，请改用「在应用内登录课表」）",
                )
            }

            studentName = Jsoup.parse(body).select("span,div,a")
                .firstOrNull { it.text().contains("同学") || it.text().contains("欢迎") }
                ?.text()
                ?.take(40)
        }

        /** ① 拿 scode/sxh。 */
        private fun fetchSessionKey(): String {
            val body = post(
                label = "① 取校验串 Logon.do",
                url = "$base/Logon.do?method=logon&flag=sess",
                form = emptyMap(),
                referer = "$base/",
                // 这个接口是页面里用 jQuery $.ajax 调的，必须带上这个头，
                // 否则服务器不认、会把登录页整页返回回来
                extraHeaders = mapOf("X-Requested-With" to "XMLHttpRequest"),
            ).trim()

            // 学校前面挡了一层网关（要浏览器执行 JS 才能过），纯 HTTP 客户端会被它拦下
            if (body.startsWith("{") && (body.contains("msgContent") || body.contains("flag1"))) {
                val message = Regex("\"msgContent\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)
                throw JwException(
                    "这所学校的登录页有反自动化校验，程序用账号密码直接登不进去" +
                        if (message.isNullOrBlank()) "。" else "（学校返回：$message）。" +
                        "请改用在应用内登录：点下面的「在应用内登录课表」，在页面里登录一次，" +
                        "之后后台会自动同步，不需要再填密码。",
                ).also { it.gateBlocked = true }
            }

            if (body.isEmpty() || body == "no" || !body.contains('#')) {
                throw JwException("取登录校验串失败，学校返回：${body.take(80).ifEmpty { "(空)" }}")
            }
            return body
        }

        override suspend fun loadTerm(): JwTermInfo = withContext(Dispatchers.IO) {
            for (path in HOME_PATHS) {
                val body = runCatching { get(label = "⑤ 主界面 $path", url = base + path) }.getOrNull() ?: continue
                if (body.contains("userAccount")) continue // 又看到登录页了，换下一个

                val doc = runCatching { Jsoup.parse(body) }.getOrNull() ?: continue
                val currentWeek = Regex("第\\s*(\\d{1,2})\\s*周").find(doc.text())?.groupValues?.get(1)?.toIntOrNull()
                val weekCount = doc.select("div#li_showWeek").firstOrNull()?.text()
                    ?.let { Regex("/(\\d{1,2})\\s*周").find(it)?.groupValues?.get(1)?.toIntOrNull() }

                if (currentWeek != null || weekCount != null) {
                    return@withContext JwTermInfo(
                        xnxqh = "",
                        currentWeek = currentWeek,
                        // 网页版不给开学日期，交给上层用 currentWeek 倒推
                        firstMonday = null,
                        weekCount = weekCount,
                        raw = mapOf(
                            "currentWeek" to "${currentWeek ?: ""}",
                            "weekCount" to "${weekCount ?: ""}",
                        ),
                    )
                }
            }
            JwTermInfo(xnxqh = "", currentWeek = null, firstMonday = null)
        }

        /**
         * 网页版一次就给整学期的课（每门课自带周次），所以走这条路而不是逐周拉。
         */
        override suspend fun loadWholeTerm(): List<CourseSession>? = withContext(Dispatchers.IO) {
            for (path in TIMETABLE_PATHS) {
                val body = runCatching { get(label = "④ 课表 $path", url = base + path) }.getOrNull() ?: continue
                if (body.isBlank() || body.contains("userAccount")) continue

                val result = QzWebParser.parseDocuments(listOf(body))
                if (result.sessions.isNotEmpty()) {
                    return@withContext result.sessions
                }
            }
            throw JwException(
                "登录成功了，但没能从课表页解析出课程。可能这个学校的课表页排版是另一种，" +
                    "请在设置页「查看接口原始返回」里把内容发我。",
            )
        }

        override suspend fun loadWeek(week: Int): List<CourseSession> =
            throw JwException("jsxsd 网页版没有按周查询的接口，请整学期导入")

        override fun close() {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }

        // ------------------------------------------------------------ HTTP

        private fun get(label: String, url: String): String =
            execute(label, Request.Builder().url(checkUrl(url)).get())

        private fun post(
            label: String,
            url: String,
            form: Map<String, String>,
            referer: String,
            extraHeaders: Map<String, String> = emptyMap(),
        ): String {
            val bodyBuilder = FormBody.Builder()
            form.forEach { (k, v) -> bodyBuilder.add(k, v) }
            var builder = Request.Builder()
                .url(checkUrl(url))
                .post(bodyBuilder.build())
                .header("Referer", referer)
            extraHeaders.forEach { (name, value) -> builder = builder.header(name, value) }
            return execute(label, builder)
        }

        private fun checkUrl(url: String) =
            url.toHttpUrlOrNull() ?: throw JwException("地址格式不对：$url")

        private fun execute(label: String, builder: Request.Builder): String {
            val request = builder.header("User-Agent", QzHttp.USER_AGENT).build()
            val shownUrl = QzHttp.redact(request.url.toString())

            try {
                client.newCall(request).execute().use { response ->
                    val text = QzHttp.decodeBody(response.peekBody(Long.MAX_VALUE).bytes(), response.header("Content-Type"))
                    val ok = response.isSuccessful
                    log.addFirst(
                        RawExchange(label = label, url = shownUrl, responseSnippet = text.take(4000), ok = ok),
                    )
                    if (!ok) throw JwException("$label 失败：HTTP ${response.code}")
                    return text
                }
            } catch (e: JwException) {
                throw e
            } catch (e: Exception) {
                log.addFirst(
                    RawExchange(
                        label = label,
                        url = shownUrl,
                        responseSnippet = e.message ?: e.toString(),
                        ok = false,
                    ),
                )
                throw JwException(
                    message = "$label 请求失败：${e.message ?: e.javaClass.simpleName}",
                    cause = e,
                    connectivity = e is java.io.IOException,
                )
            }
        }

        /** 从登录失败页面里抠出学校给的提示（"用户名或密码错误"之类）。 */
        private fun extractLoginMessage(html: String): String {
            val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return "账号或密码不正确"
            val message = doc.select("#showMsg, .login-cr, p")
                .map { it.text().trim() }
                .firstOrNull { it.isNotEmpty() && !it.contains("浏览器") && !it.contains("分辨率") && it.length < 60 }
            return message ?: "账号或密码不正确"
        }
    }
}
