package com.qzkt.timetable.jw.qz

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Base64

/**
 * 账号密码自动登录（新一代强智登录页的纯 HTTP 复刻）。
 *
 * 仿学校登录流程：GET 登录页建会话 → POST LoginToXk 校验
 * `encoded == base64(账号)%%%base64(密码)` → 对了发 302 进主界面并发新会话 cookie。
 */
class QzJsxsdAutoLoginTest {

    private lateinit var server: HttpServer
    private lateinit var base: String

    /** 最近一次登录表单的字段，断言提交内容用。 */
    private val lastForm = mutableMapOf<String, String>()

    private val loginPage = """
    <html><body>
      <form id="loginForm" action="/jsxsd/xk/LoginToXk">
        <input name="userAccount"><input name="encoded" type="hidden">
      </form>
    </body></html>
    """

    private val mainPage = "<html><body><div id='xsMainV'>欢迎回来</div></body></html>"

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
    fun `账号密码正确时拿到新会话 cookie`() = runBlocking {
        val cookie = QzJsxsdAutoLogin.login(QzHttp.defaultClient(), base, "2025001", "secret")

        assertNotNull("正确凭据应当登录成功", cookie)
        assertTrue("应带上登录后的新会话", cookie.orEmpty().contains("JSESSIONID=NEWSESSION"))
        // 表单按学校登录页的 submitForm1() 组装：账号在 userAccount，密码全在 encoded 里
        assertEquals("2025001", lastForm["userAccount"])
        assertEquals("", lastForm["userPassword"])
        assertEquals(
            Base64.getEncoder().encodeToString("2025001".toByteArray()) + "%%%" +
                Base64.getEncoder().encodeToString("secret".toByteArray()),
            lastForm["encoded"],
        )
    }

    @Test
    fun `密码错误时返回 null 而不是误报成功`() = runBlocking {
        val cookie = QzJsxsdAutoLogin.login(QzHttp.defaultClient(), base, "2025001", "wrong")

        assertNull("错误凭据应当失败", cookie)
    }

    // ------------------------------------------------------------------ 假服务端

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val raw = exchange.requestBody.readBytes().decodeToString()
        val form = raw.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                pair.substring(0, idx) to URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            } else {
                null
            }
        }.toMap()

        when {
            path.endsWith("xsdrmLogin.jsp") -> {
                exchange.responseHeaders.add("Set-Cookie", "JSESSIONID=OLD; Path=/jsxsd")
                respond(exchange, 200, loginPage)
            }
            path.endsWith("LoginToXk") -> {
                lastForm.clear()
                lastForm.putAll(form)
                val expected = Base64.getEncoder().encodeToString("2025001".toByteArray()) + "%%%" +
                    Base64.getEncoder().encodeToString("secret".toByteArray())
                if (form["encoded"] == expected) {
                    exchange.responseHeaders.add("Set-Cookie", "JSESSIONID=NEWSESSION; Path=/jsxsd")
                    exchange.responseHeaders.add("Location", "/jsxsd/framework/xsMainV.htmlx")
                    respond(exchange, 302, "")
                } else {
                    respond(exchange, 200, loginPage)
                }
            }
            path.endsWith("xsMainV.htmlx") -> respond(exchange, 200, mainPage)
            else -> respond(exchange, 200, loginPage)
        }
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/html;charset=UTF-8")
        if (code == 302) {
            exchange.sendResponseHeaders(code, -1)
        } else {
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
