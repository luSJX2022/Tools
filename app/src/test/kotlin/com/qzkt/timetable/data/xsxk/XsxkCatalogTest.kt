package com.qzkt.timetable.data.xsxk

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.Charset

/**
 * 选课中心的原生解析：轮次表（学年学期/选课名称/选课时间/操作）按表头映射列，
 * 没有开放轮次时是「未查询到数据」的空表；入口发现走菜单锚点 / 脚本配置 / iframe。
 */
class XsxkCatalogTest {

    private lateinit var server: HttpServer
    private lateinit var base: String

    /** 主界面（框架页）返回的菜单内容，测试里按用例替换。 */
    private var menuHtml: String = ""

    /** 菜单 iframe（src 带 menu）返回的内容。 */
    private var iframeHtml: String = "<html><body>空菜单</body></html>"

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
        base = "http://127.0.0.1:${server.address.port}/jsxsd"
    }

    @After
    fun tearDown() = server.stop(0)

    // ------------------------------------------------------------------ 轮次表解析

    @Test
    fun `轮次表按表头映射列并抓出进入链接`() {
        val html = """
            <html><body>
            <table>
              <tr><th>学年学期</th><th>选课名称</th><th>选课时间</th><th>操作</th></tr>
              <tr>
                <td>2026-2027-1</td>
                <td>第一轮选课</td>
                <td>2026-09-01 08:00 ~ 2026-09-05 23:59</td>
                <td><a href="/jsxsd/xsxk/xsxkIndex.html?xnxqdm=2026-2027-1">进入选课</a></td>
              </tr>
            </table>
            </body></html>
        """.trimIndent()

        val rounds = parseRounds(html, "$base/xsxk/xsxkIndex.html")

        assertEquals(1, rounds.size)
        val round = rounds.first()
        assertEquals("2026-2027-1", round.term)
        assertEquals("第一轮选课", round.name)
        assertTrue(round.time.contains("2026-09-01"))
        assertEquals("$base/xsxk/xsxkIndex.html?xnxqdm=2026-2027-1", round.entryUrl)
    }

    @Test
    fun `没有开放轮次的空表解析成空列表`() {
        // 学校页面当前的样子：表头在、数据行没有，只有一句「未查询到数据」
        val html = """
            <html><body>
            <table>
              <tr><th>学年学期</th><th>选课名称</th><th>选课时间</th><th>操作</th></tr>
              <tr><td colspan="4">未查询到数据</td></tr>
            </table>
            </body></html>
        """.trimIndent()

        val rounds = parseRounds(html, "$base/xsxk/xsxkIndex.html")

        assertTrue(rounds.isEmpty())
    }

    @Test
    fun `页面里没有轮次表时解析为空`() {
        val html = "<html><body><p>随便什么别的页面</p></body></html>"

        assertTrue(parseRounds(html, "$base/xsxk/xsxkIndex.html").isEmpty())
    }

    // ------------------------------------------------------------------ 入口发现

    @Test
    fun `href 带 xsxk 的链接被发现并解析成绝对地址`() {
        menuHtml = """
            <html><body>
              <a href="/jsxsd/framework/xsMainV.htmlx">首页</a>
              <a href="/jsxsd/xsxk/xsxkIndex.html?xnxqdm=2026-2027-1">学生选课</a>
            </body></html>
        """.trimIndent()

        val url = discoverCourseSelectUrl(base, "JSESSIONID=ABC")

        assertEquals("$base/xsxk/xsxkIndex.html?xnxqdm=2026-2027-1", url)
    }

    @Test
    fun `菜单放在 iframe 里时抓内嵌页来扫`() {
        menuHtml = """
            <html><body>
              <iframe src="/jsxsd/menu/left.html"></iframe>
            </body></html>
        """.trimIndent()
        iframeHtml = """
            <html><body><a href="xsxk/xsxkIndex.html">学生选课中心</a></body></html>
        """.trimIndent()

        val url = discoverCourseSelectUrl(base, "JSESSIONID=ABC")

        // 内嵌页在 /jsxsd/menu/ 下，相对链接按它所在目录解析
        assertEquals("$base/menu/xsxk/xsxkIndex.html", url)
    }

    @Test
    fun `被打回登录页或没有入口时返回 null`() {
        menuHtml = """
            <html><body><form id="loginForm"><input name="userAccount"></form></body></html>
        """.trimIndent()

        assertNull(discoverCourseSelectUrl(base, "JSESSIONID=EXPIRED"))

        menuHtml = """
            <html><body><a href="/jsxsd/framework/xsMainV.htmlx">首页</a></body></html>
        """.trimIndent()

        assertNull(discoverCourseSelectUrl(base, "JSESSIONID=ABC"))
    }

    @Test
    fun `xsxk 命名的脚本资源不算入口`() {
        menuHtml = """
            <html><body>
              <a href="/jsxsd/xsxk/js/xsxkMenu.js">选课脚本</a>
              <a href="/jsxsd/framework/xsMainV.htmlx">首页</a>
            </body></html>
        """.trimIndent()

        assertNull(discoverCourseSelectUrl(base, "JSESSIONID=ABC"))
    }

    // ------------------------------------------------------------------ 假服务端

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val body = when {
            path.contains("menu") -> iframeHtml
            path.contains("framework") -> menuHtml
            else -> ""
        }
        val bytes = body.toByteArray(Charset.forName("UTF-8"))
        exchange.responseHeaders.add("Content-Type", "text/html;charset=UTF-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
