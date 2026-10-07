package com.qzkt.timetable.data.xsxk

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Test
    fun `真实选课页样本：轮次未开放时表头可识别且轮次为空`() {
        // 从 jw.hdxy.edu.cn/jsxsd/xsxk/xklc_list 实际抓回来的页面结构
        val html = javaClass.getResourceAsStream("/xsxk_rounds.html")!!
            .readBytes().decodeToString()

        val rounds = parseRounds(html, "$base/xsxk/xklc_list")

        // parseRounds 能认出 attend_class 轮次表（表头含选课名称），只是没有数据行
        assertTrue(rounds.isEmpty())
        // fetchRounds 的 pageKnown 依据：页面包含「选课名称」表头
        assertTrue(html.contains("选课名称"))
    }

    @Test
    fun `轮次开放时解析出轮次和进入链接`() {
        val html = """
            <html><body>
            <table id="attend_class">
              <tr><th>学年学期</th><th>选课名称</th><th>选课时间</th><th>操作</th></tr>
              <tr>
                <td>2026-2027-1</td>
                <td>2026-2027-1第一轮选课</td>
                <td>2026-09-01 ~ 2026-09-05</td>
                <td><a href="/jsxsd/xsxk/xsxkIndex?xklcid=123">进入选课</a></td>
              </tr>
            </table>
            </body></html>
        """.trimIndent()

        val rounds = parseRounds(html, "$base/xsxk/xklc_list")

        assertEquals(1, rounds.size)
        assertEquals("2026-2027-1第一轮选课", rounds.first().name)
        assertEquals("$base/xsxk/xsxkIndex?xklcid=123", rounds.first().entryUrl)
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

        val urls = discoverCourseSelectUrls(base, "JSESSIONID=ABC")

        assertTrue("应包含菜单里的选课地址，实际：$urls", urls.contains("$base/xsxk/xsxkIndex.html?xnxqdm=2026-2027-1"))
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

        val urls = discoverCourseSelectUrls(base, "JSESSIONID=ABC")

        // 内嵌页在 /jsxsd/menu/ 下，相对链接按它所在目录解析
        assertTrue("应包含 iframe 里的选课地址，实际：$urls", urls.contains("$base/menu/xsxk/xsxkIndex.html"))
    }

    @Test
    fun `被打回登录页或没有入口时只剩常见地址兜底`() {
        menuHtml = """
            <html><body><form id="loginForm"><input name="userAccount"></form></body></html>
        """.trimIndent()

        val expired = discoverCourseSelectUrls(base, "JSESSIONID=EXPIRED")
        assertTrue("登录页扫不出菜单地址，实际：$expired", expired.none { it.contains("framework") })

        menuHtml = """
            <html><body><a href="/jsxsd/framework/xsMainV.htmlx">首页</a></body></html>
        """.trimIndent()

        val urls = discoverCourseSelectUrls(base, "JSESSIONID=ABC")
        assertTrue("兜底候选应该还在，实际：$urls", urls.contains("$base/xsxk/xsxk_index.html"))
    }

    @Test
    fun `xsxk 命名的脚本资源不算入口`() {
        menuHtml = """
            <html><body>
              <a href="/jsxsd/xsxk/js/xsxkMenu.js">选课脚本</a>
              <a href="/jsxsd/framework/xsMainV.htmlx">首页</a>
            </body></html>
        """.trimIndent()

        val urls = discoverCourseSelectUrls(base, "JSESSIONID=ABC")

        assertTrue("脚本资源不该进候选，实际：$urls", urls.none { it.endsWith(".js") })
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
