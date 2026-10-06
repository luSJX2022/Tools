package com.qzkt.timetable.ui.academic

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
 * 「选课」入口的菜单发现：从教务主界面里找 href 带 xsxk 的或文字带「选课」的链接，
 * 相对地址要解析成绝对地址；找不到要返回 null（调用方退回教务主界面）。
 */
class CourseSelectDiscoveryTest {

    private lateinit var server: HttpServer
    private lateinit var base: String

    /** 主界面（框架页）返回的菜单内容，测试里按用例替换。 */
    private var menuHtml: String = ""

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
        base = "http://127.0.0.1:${server.address.port}/jsxsd"
    }

    @After
    fun tearDown() = server.stop(0)

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
    fun `文字带选课的相对链接也能被发现`() {
        menuHtml = """
            <html><body>
              <a href="xsxk/select.jsp">我的选课入口</a>
            </body></html>
        """.trimIndent()

        val url = discoverCourseSelectUrl(base, "JSESSIONID=ABC")

        // 相对链接按浏览器语义基于页面目录解析（菜单页在 /jsxsd/framework/ 下）
        assertEquals("$base/framework/xsxk/select.jsp", url)
    }

    @Test
    fun `菜单里没有选课入口时返回 null`() {
        menuHtml = """
            <html><body><a href="/jsxsd/xskbcx/xskbcx_cxXsKb.html">课表</a></body></html>
        """.trimIndent()

        assertNull(discoverCourseSelectUrl(base, "JSESSIONID=ABC"))
    }

    @Test
    fun `被打回登录页时不算发现入口`() {
        menuHtml = """
            <html><body><form id="loginForm"><input name="userAccount"></form></body></html>
        """.trimIndent()

        assertNull(discoverCourseSelectUrl(base, "JSESSIONID=EXPIRED"))
    }

    @Test
    fun `javascript 链接不会被当成入口`() {
        menuHtml = """
            <html><body>
              <a href="javascript:void(0)" onclick="openXsxk()">选课</a>
            </body></html>
        """.trimIndent()

        val url = discoverCourseSelectUrl(base, "JSESSIONID=ABC")

        assertTrue(url == null || !url.startsWith("javascript"))
    }

    @Test
    fun `动态菜单写在页面脚本里也能挖出选课地址`() {
        // 新一代主界面的菜单是 JS 生成的，<a> 扫不到，但地址就写在页面脚本配置里
        menuHtml = """
            <html><body><script>
              var menu = [{name: "选课中心", url: "/jsxsd/xsxk/xsxkIndex.html?xnxqdm=2026-2027-1"}];
            </script></body></html>
        """.trimIndent()

        val url = discoverCourseSelectUrl(base, "JSESSIONID=ABC")

        assertEquals("$base/xsxk/xsxkIndex.html?xnxqdm=2026-2027-1", url)
    }

    @Test
    fun `菜单找不到时试探常见选课地址`() {
        menuHtml = """
            <html><body><a href="/jsxsd/framework/xsMainV.htmlx">首页</a></body></html>
        """.trimIndent()
        candidateHtml = "<html><body><div>选课中心 欢迎使用</div></body></html>"

        val url = discoverCourseSelectUrl(base, "JSESSIONID=ABC")

        assertEquals("$base/xsxk/xsxk_index.html", url)
    }

    // ------------------------------------------------------------------ 假服务端

    /** 对 xsxk 目录试探请求的响应内容（默认给个「不像选课」的页面，命中用例再替换）。 */
    private var candidateHtml: String = "<html><body>404 not found</body></html>"

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val body = when {
            path.contains("/xsxk/") -> candidateHtml
            path.contains("framework") -> menuHtml
            else -> ""
        }
        val bytes = body.toByteArray(Charset.forName("UTF-8"))
        exchange.responseHeaders.add("Content-Type", "text/html;charset=UTF-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
