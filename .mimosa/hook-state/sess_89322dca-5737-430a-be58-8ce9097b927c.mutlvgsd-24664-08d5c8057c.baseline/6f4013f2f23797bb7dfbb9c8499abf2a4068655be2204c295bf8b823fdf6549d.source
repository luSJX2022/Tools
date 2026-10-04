package com.qzkt.timetable.jw.qz

import com.qzkt.timetable.jw.JwConfig
import com.qzkt.timetable.jw.JwException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.Charset
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 用 JDK 自带的 HTTP 服务器起一个假的强智教务系统，跑通
 * 「登录 → 取学期 → 取课表」整条链路。
 *
 * 走的是真实的 OkHttp 请求、真实的 TCP 连接、真实的 JSON 解析与落库，
 * 只是服务端换成了本机 —— 这样能在没有学校账号的情况下验证客户端链路本身。
 */
class QzAppDoAdapterTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    private val token = "tok-9f3a1c"
    private val requestedMethods = mutableListOf<String>()

    /** 让 getKbcxAzc 用 GBK 返回，验证解码回退。 */
    private var gbkForTimetable = false

    /** 让 getCurrentTime 带上开学日期。 */
    private var includeStartDate = true

    private val week3Json = """
    [
      {"jsxm":"张三","kcmc":"高等数学","jsmc":"教1-101","xqjmc":"星期一","zcd":"1-16","jcs":"0102","xqj":"1","jxbmc":"计算机2401"},
      {"jsxm":"李四","kcmc":"大学物理","jsmc":"实验楼305","xqjmc":"星期三","zcd":"3-14","jcs":"0506","xqj":"3","jxbmc":"计算机2401"}
    ]
    """

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/app.do") { exchange -> handle(exchange) }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    // ------------------------------------------------------------------ 用例

    @Test
    fun `登录到取课表整条链路`() = runBlocking {
        val session = QzAppDoAdapter().connect(JwConfig(baseUrl, "2024001", "correct"))

        assertEquals("张三", session.studentName)

        val term = session.loadTerm()
        assertEquals("2026-2027-1", term.xnxqh)
        assertEquals(3, term.currentWeek)
        assertEquals(LocalDate.parse("2026-09-07"), term.firstMonday)

        val week3 = session.loadWeek(3)
        assertEquals(2, week3.size)
        val math = week3.first { it.name == "高等数学" }
        assertEquals("教1-101", math.room)
        assertEquals(1, math.dayOfWeek)
        assertEquals(listOf(3), math.weeks) // 适配器把结果归一到"这一周"

        assertTrue(session.loadWeek(4).isEmpty())

        // 每个请求都留了痕迹，调试页才有东西可看
        assertTrue(session.rawLog.isNotEmpty())
        assertTrue(session.rawLog.first().url.contains("getKbcxAzc"))

        // 断言服务端确实按顺序收到了三次调用
        assertEquals(listOf("authUser", "getCurrentTime", "getKbcxAzc", "getKbcxAzc"), requestedMethods)

        session.close()
    }

    @Test
    fun `token 会随请求发出`() = runBlocking {
        val session = QzAppDoAdapter().connect(JwConfig(baseUrl, "2024001", "correct"))
        session.loadTerm()
        assertTrue(session.rawLog.first().ok)
        session.close()
    }

    @Test
    fun `密码错误时抛出带学校提示的异常`() = runBlocking {
        try {
            QzAppDoAdapter().connect(JwConfig(baseUrl, "2024001", "wrong"))
            throw AssertionError("应该抛 JwException")
        } catch (e: JwException) {
            assertTrue(e.message!!.contains("用户名或密码错误"))
        }
    }

    @Test
    fun `GBK 编码的课表能正确解码`() = runBlocking {
        gbkForTimetable = true
        val session = QzAppDoAdapter().connect(JwConfig(baseUrl, "2024001", "correct"))
        val week3 = session.loadWeek(3)
        assertEquals(2, week3.size)
        assertTrue(week3.any { it.name == "高等数学" && it.teacher == "张三" })
        session.close()
    }

    @Test
    fun `接口没给开学日期时按当前周次倒推`() = runBlocking {
        includeStartDate = false
        val session = QzAppDoAdapter().connect(JwConfig(baseUrl, "2024001", "correct"))
        val term = session.loadTerm()
        assertNotNull(term.firstMonday)
        assertEquals(DayOfWeek.MONDAY, term.firstMonday!!.dayOfWeek)
        session.close()
    }

    @Test
    fun `服务端返回非 JSON 时给出可读的报错`() = runBlocking {
        val adapter = QzAppDoAdapter()
        try {
            // 地址带了 scheme，所以不会走 https→http 回退，直接命中 404
            adapter.connect(JwConfig("${baseUrl}/nope", "2024001", "correct"))
            throw AssertionError("应该抛 JwException")
        } catch (e: JwException) {
            assertFalse(e.message!!.isBlank())
        }
    }

    @Test
    fun `地址规整`() {
        // 只填域名时默认按 https 起手，连不上会自动退回 http（见下一个用例）
        assertEquals("https://jwgl.x.edu.cn/app.do", QzAppDoAdapter.resolveEndpoint("jwgl.x.edu.cn"))
        assertEquals("http://jwgl.x.edu.cn/app.do", QzAppDoAdapter.resolveEndpoint("http://jwgl.x.edu.cn"))
        assertEquals("http://jwgl.x.edu.cn/app.do", QzAppDoAdapter.resolveEndpoint("  http://jwgl.x.edu.cn/  "))
        assertEquals("http://jwgl.x.edu.cn/app.do", QzAppDoAdapter.resolveEndpoint("http://jwgl.x.edu.cn/app.do"))
        assertEquals(
            "http://jwgl.x.edu.cn:8080/app.do",
            QzAppDoAdapter.resolveEndpoint("http://jwgl.x.edu.cn:8080/app.do?method=authUser&xh=1&pwd=2"),
        )
        assertEquals("https://jwgl.x.edu.cn/jwgl/app.do", QzAppDoAdapter.resolveEndpoint("https://jwgl.x.edu.cn/jwgl"))
    }

    @Test
    fun `只填域名时 https 连不上会自动退回 http`() = runBlocking {
        // 假服务端是明文 HTTP，https 一定会握手失败，正好验证这条回退路径
        val session = QzAppDoAdapter().connect(
            JwConfig("127.0.0.1:${server.address.port}", "2024001", "correct"),
        )
        assertEquals("张三", session.studentName)
        assertTrue(session.loadWeek(3).isNotEmpty())
        session.close()
    }

    // ------------------------------------------------------------------ 假服务端

    private fun handle(exchange: HttpExchange) {
        val query = parseQuery(exchange.requestURI.query.orEmpty())
        val method = query["method"].orEmpty()
        requestedMethods += method

        val sentToken = exchange.requestHeaders.getFirst("token")
        if (method != "authUser" && sentToken != token) {
            respond(exchange, 401, """{"flag":"0","msg":"token 无效"}""", Charsets.UTF_8, "application/json")
            return
        }

        when (method) {
            "authUser" -> {
                val body = if (query["pwd"] == "correct") {
                    """{"flag":"1","msg":"登录成功","token":"$token","user":"张三"}"""
                } else {
                    """{"flag":"0","msg":"用户名或密码错误"}"""
                }
                respond(exchange, 200, body, Charsets.UTF_8, "application/json;charset=UTF-8")
            }

            "getCurrentTime" -> {
                val start = if (includeStartDate) ""","s_time":"2026-09-07"""" else ""
                respond(
                    exchange,
                    200,
                    """{"zc":"3","xnxqh":"2026-2027-1"$start}""",
                    Charsets.UTF_8,
                    "application/json;charset=UTF-8",
                )
            }

            "getKbcxAzc" -> {
                val body = if (query["zc"] == "3") week3Json else "[]"
                if (gbkForTimetable) {
                    respond(exchange, 200, body, Charset.forName("GBK"), "application/json;charset=GBK")
                } else {
                    respond(exchange, 200, body, Charsets.UTF_8, "application/json;charset=UTF-8")
                }
            }

            else -> respond(exchange, 200, "{}", Charsets.UTF_8, "application/json")
        }
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String, charset: Charset, contentType: String) {
        val bytes = body.toByteArray(charset)
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun parseQuery(raw: String): Map<String, String> =
        raw.split('&')
            .filter { it.isNotEmpty() }
            .mapNotNull { pair ->
                val index = pair.indexOf('=')
                if (index < 0) {
                    null
                } else {
                    val key = URLDecoder.decode(pair.substring(0, index), "UTF-8")
                    val value = URLDecoder.decode(pair.substring(index + 1), "UTF-8")
                    key to value
                }
            }
            .toMap()
}
