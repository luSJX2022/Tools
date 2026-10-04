package com.qzkt.timetable.jw.qz

import com.qzkt.timetable.jw.JwConfig
import com.qzkt.timetable.jw.JwException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.Charset

/**
 * jsxsd 登录。
 *
 * 这里模拟的是青岛农业大学海都学院那套行为 —— 实测发现它的
 * `Logon.do?method=logon&flag=sess` 会被前面一层网关拦下，返回
 * `{"flag1":2,"msgContent":"请先登录系统"}`，纯 HTTP 客户端拿不到校验串。
 * 所以适配器要能**认出这种拦截**并给出可操作的提示，
 * 而不是把它当成"密码错误"让用户白折腾。
 */
class QzJsxsdLoginFlowTest {

    private lateinit var server: HttpServer
    private lateinit var base: String

    private val requestedPaths = mutableListOf<String>()
    private val logonHeaders = mutableListOf<String?>()

    /** 模拟"网关拦截"（海都学院实测如此）。 */
    private var gated = true

    /** 网关放行时返回的校验串。 */
    private val sessString = "ABCDEFGHIJKLMNOPQRST#11111111111111111111"

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/jsxsd") { exchange -> handle(exchange) }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() = server.stop(0)

    @Test
    fun `被网关拦下时给出可操作提示而不是报密码错`() = runBlocking {
        gated = true
        try {
            QzJsxsdAdapter().connect(JwConfig(base, "2024001", "whatever"))
            throw AssertionError("应该抛 JwException")
        } catch (e: JwException) {
            assertTrue("应当标记为网关拦截，实际：${e.message}", e.gateBlocked)
            assertTrue("提示里应当说明原因，实际：${e.message}", e.message!!.contains("反自动化校验"))
            assertTrue("提示里应当带上学校返回的内容", e.message!!.contains("请先登录系统"))
            assertTrue(
                "提示里应当给出下一步（改用应用内登录），实际：${e.message}",
                e.message!!.contains("在应用内登录"),
            )
            // 这种情况换另一个适配器也没用，必须直接上报，不能被 app.do 的错误盖掉
            assertTrue("网关拦截不该再换平台重试", !e.worthRetryingElsewhere)
        }
    }

    @Test
    fun `取校验串时会带上 X-Requested-With`() = runBlocking {
        gated = false
        runCatching { QzJsxsdAdapter().connect(JwConfig(base, "2024001", "pw")) }

        val logonIndex = requestedPaths.indexOfFirst { it.contains("Logon.do") }
        assertTrue("应当请求过 Logon.do", logonIndex >= 0)
        assertTrue(
            "Logon.do 必须带 X-Requested-With: XMLHttpRequest（登录页里是 jQuery 调的），实际：${logonHeaders.getOrNull(0)}",
            logonHeaders.any { it.equals("XMLHttpRequest", ignoreCase = true) },
        )
    }

    @Test
    fun `网关放行后能走到真正的登录请求`() = runBlocking {
        gated = false
        runCatching { QzJsxsdAdapter().connect(JwConfig(base, "2024001", "pw")) }

        assertTrue(
            "应当继续请求 LoginToXk，实际请求过：$requestedPaths",
            requestedPaths.any { it.contains("LoginToXk") },
        )
    }

    @Test
    fun `base 地址会补齐 jsxsd`() {
        assertTrue(QzJsxsdAdapter.resolveBase("jw.hdxy.edu.cn").endsWith("/jsxsd"))
        assertTrue(
            QzJsxsdAdapter.resolveBase("https://jw.hdxy.edu.cn/jsxsd/framework/xsMainV.htmlx")
                .endsWith("/jsxsd"),
        )
        assertTrue(QzJsxsdAdapter.looksLikeJsxsd("http://jw.hdxy.edu.cn/jsxsd/"))
        assertTrue(!QzJsxsdAdapter.looksLikeJsxsd("http://jwgl.sdust.edu.cn"))
    }

    // ------------------------------------------------------------------ 假服务端

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        requestedPaths += path

        val body = when {
            path.contains("Logon.do") -> {
                logonHeaders += exchange.requestHeaders.getFirst("X-Requested-With")
                if (gated) {
                    // 这就是实测到的那段响应
                    """{"flag1":2,"msgContent":"请先登录系统"}"""
                } else {
                    sessString
                }
            }

            // 走到这里说明校验串拿到了；再往下就必须登录成功，这里直接打回登录页，
            // 让测试只关心"有没有走到这一步"
            else -> loginPage
        }
        respond(exchange, 200, body)
    }

    private val loginPage = """
    <html><body><form id="loginForm"><input name="userAccount"></form></body></html>
    """

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charset.forName("UTF-8"))
        exchange.responseHeaders.add("Content-Type", "text/plain;charset=UTF-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
