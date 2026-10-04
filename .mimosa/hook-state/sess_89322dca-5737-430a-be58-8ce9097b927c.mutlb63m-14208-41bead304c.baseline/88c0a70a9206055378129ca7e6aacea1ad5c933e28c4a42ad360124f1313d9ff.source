package com.qzkt.timetable.jw.qz

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.Charset

/**
 * 「用当前登录状态直接抓课表」这条路。
 *
 * 用 JDK 自带的 HTTP 服务器起一个假的强智，验证：
 * 会话 cookie 有没有带上、几个候选地址是不是按顺序试、以及拿到的课表 HTML 能不能解析出来。
 */
class QzJsxsdDirectTest {

    private lateinit var server: HttpServer
    private lateinit var base: String

    private val requestedPaths = mutableListOf<String>()
    private val cookiesSeen = mutableListOf<String?>()

    /** 让第一个候选地址失效，用来验证会不会继续往后试。 */
    private var firstPathBroken = false

    private val validCookie = "bzb_jsxsd=SESSION123"

    private val loginPage = """
    <html><body>
      <form id="loginForm" action="/jsxsd/xk/LoginToXk"><input name="userAccount"></form>
    </body></html>
    """

    /** 仿强智 jsxsd 课表：列是星期、行是节次，格子用 <br> 分行。 */
    private val timetablePage = """
    <html><body>
    <table id="kbtable">
      <tr>
        <th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th>
        <th>星期四</th><th>星期五</th><th>星期六</th><th>星期日</th>
      </tr>
      <tr>
        <td>第1节</td>
        <td><div class="kbcontent">高等数学<br>张三<br>1-16(周)[0102]<br>教1-101</div></td>
        <td></td><td></td><td></td><td></td><td></td><td></td>
      </tr>
      <tr>
        <td>第5节</td>
        <td></td><td></td>
        <td><div class="kbcontent">大学物理<br>李四<br>3-14周[0506](单)<br>实验楼305</div></td>
        <td></td><td></td><td></td><td></td>
      </tr>
    </table>
    </body></html>
    """

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/jsxsd") { exchange -> handle(exchange) }
        server.start()
        base = "http://127.0.0.1:${server.address.port}/jsxsd"
    }

    @After
    fun tearDown() = server.stop(0)

    @Test
    fun `带着会话 cookie 能直接解析出课表`() = runBlocking {
        val outcome = QzJsxsdDirect().fetchTimetable(base, validCookie)

        assertTrue("应当解析出课程，实际用了 ${outcome.usedPath}", outcome.ok)
        assertEquals("/xskb/xskb_list.do", outcome.usedPath)
        assertEquals(2, outcome.sessions.size)

        val math = outcome.sessions.first { it.name == "高等数学" }
        assertEquals(1, math.dayOfWeek)
        assertEquals(1, math.startPeriod)
        assertEquals("教1-101", math.room)
        assertEquals("张三", math.teacher)
        assertEquals((1..16).toList(), math.weeks) // 方括号里的 [0102] 不该被当成周次

        val physics = outcome.sessions.first { it.name == "大学物理" }
        assertEquals(3, physics.dayOfWeek)
        assertEquals(5, physics.startPeriod)
        assertEquals(listOf(3, 5, 7, 9, 11, 13), physics.weeks) // (单)

        // cookie 确实发出去了
        assertTrue(cookiesSeen.first().orEmpty().contains("SESSION123"))
    }

    @Test
    fun `会话失效时不会误报成功，且能识别出尚未登录`() = runBlocking {
        val outcome = QzJsxsdDirect().fetchTimetable(base, "bzb_jsxsd=EXPIRED")

        assertFalse(outcome.ok)
        assertEquals(null, outcome.usedPath)
        // 三个候选地址都试过
        assertEquals(3, outcome.triedPaths.size)
        // 全被打回登录页 —— 这一点要和"登录了但排版不认识"区分开，
        // 否则用户会对着同一个按钮一直点
        assertFalse("应当判定为未登录", outcome.loggedIn)
    }

    @Test
    fun `没有 cookie 时也不会崩，并判定为未登录`() = runBlocking {
        val outcome = QzJsxsdDirect().fetchTimetable(base, null)
        assertFalse(outcome.ok)
        assertFalse(outcome.loggedIn)
    }

    @Test
    fun `第一个地址不行时会继续试下一个`() = runBlocking {
        firstPathBroken = true
        val outcome = QzJsxsdDirect().fetchTimetable(base, validCookie)

        assertTrue(outcome.ok)
        assertEquals("/xskbcx/xskbcx_cxXsKb.html", outcome.usedPath)
        assertTrue("拿到了课表就说明登录状态有效", outcome.loggedIn)
        assertTrue(requestedPaths.any { it.endsWith("xskb_list.do") })
        assertTrue(requestedPaths.any { it.endsWith("xskbcx_cxXsKb.html") })
    }

    @Test
    fun `base 地址会被规整到 jsxsd 这一层`() {
        assertEquals("http://jw.hdxy.edu.cn/jsxsd", QzJsxsdAdapter.resolveBase("http://jw.hdxy.edu.cn"))
        assertEquals(
            "https://jw.hdxy.edu.cn/jsxsd",
            QzJsxsdAdapter.resolveBase("https://jw.hdxy.edu.cn/jsxsd/framework/xsMainV.htmlx"),
        )
        assertEquals("https://jw.hdxy.edu.cn/jsxsd", QzJsxsdAdapter.resolveBase("jw.hdxy.edu.cn/jsxsd/"))
    }

    // ------------------------------------------------------------------ 假服务端

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        requestedPaths += path
        val cookie = exchange.requestHeaders.getFirst("Cookie")
        cookiesSeen += cookie

        val authenticated = cookie.orEmpty().contains("SESSION123")
        val brokenFirst = firstPathBroken && path.endsWith("xskb_list.do")

        val body = when {
            !authenticated -> loginPage
            brokenFirst -> loginPage
            path.endsWith("xskb_list.do") -> timetablePage
            path.endsWith("xskbcx_cxXsKb.html") -> timetablePage
            else -> loginPage
        }
        respond(exchange, 200, body)
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charset.forName("UTF-8"))
        exchange.responseHeaders.add("Content-Type", "text/html;charset=UTF-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
