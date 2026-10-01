package com.qzkt.timetable.ui.player.link

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 假一个抖音分享页：验证短链跳转、`_ROUTER_DATA` 解析、去水印改写和兜底接口。
 */
class DouyinResolverTest {

    private lateinit var server: HttpServer
    private lateinit var base: String
    private val seenPaths = CopyOnWriteArrayList<String>()
    private val seenHeaders = CopyOnWriteArrayList<String>()

    /** 分享页里有没有 `_ROUTER_DATA`（模拟改版）。 */
    private var routerDataPresent = true

    private val awemeId = "7123456789012345678"

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        base = "http://127.0.0.1:" + server.address.port
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    @After
    fun tearDown() = server.stop(0)

    private fun resolver() = DouyinResolver(
        webBase = base,
        apiBase = base,
        // 单测里把换 ttwid 的接口也指到假服务器，别去打真实网络
        ttwidUrl = "$base/ttwid/union/register/",
    )

    @Test
    fun `先换到 ttwid 再抓分享页`() = runBlocking {
        resolver().resolve(MediaLink.Douyin(url = "$base/s/xyz"))
        assertTrue(seenPaths.contains("/ttwid/union/register/"))
        // 没有 ttwid 时抖音会回空壳页（item_list=0），所以这个 Cookie 必须带上
        assertTrue(seenHeaders.any { it.startsWith("Cookie", ignoreCase = true) && it.contains("ttwid=TESTTTWID") })
    }

    @Test
    fun `短链跳转后从分享页拿到无水印地址`() = runBlocking {
        val media = resolver().resolve(MediaLink.Douyin(url = "$base/s/xyz"))

        assertEquals("https://cdn.example.com/aweme/v1/play/?video_id=v123&ratio=720p", media.url)
        assertEquals("测试抖音作品", media.title)
        // 分享页按 UA 给不同布局，哪一组命中就用哪一组去播放；
        // 抖音的 CDN 只认 UA，带网页 Referer 反而容易被 403
        assertTrue(media.headers["User-Agent"]!!.startsWith("Mozilla"))
        assertNull(media.headers["Referer"])
        assertTrue(seenPaths.contains("/s/xyz"))
        assertTrue(seenPaths.contains("/share/video/$awemeId/"))
    }

    @Test
    fun `直接给作品号时不再抓一次短链`() = runBlocking {
        val media = resolver().resolve(MediaLink.Douyin(url = "$base/video/$awemeId", awemeId = awemeId))
        assertEquals("https://cdn.example.com/aweme/v1/play/?video_id=v123&ratio=720p", media.url)
        assertTrue(!seenPaths.contains("/s/xyz"))
    }

    @Test
    fun `分享页改版时退回老接口`() = runBlocking {
        routerDataPresent = false
        val media = resolver().resolve(MediaLink.Douyin(url = "$base/video/$awemeId", awemeId = awemeId))
        assertEquals("https://cdn.example.com/x/play/?v=1", media.url)
        assertEquals("兜底作品", media.title)
        assertTrue(seenPaths.contains("/web/api/v2/aweme/iteminfo/"))
    }

    @Test
    fun `两边都拿不到地址时报人话`() = runBlocking {
        routerDataPresent = false
        emptyItemInfo = true
        val error = runCatching {
            resolver().resolve(MediaLink.Douyin(url = "$base/video/$awemeId", awemeId = awemeId))
        }.exceptionOrNull() as LinkResolveException
        assertTrue(error.message!!.contains("抖音没有返回播放地址"))
    }

    /** 结构变过好几版：只要树里有带播放地址的 item，就得认得出来。 */
    @Test
    fun `结构换了位置也能找到播放地址`() {
        val html = "<script>window._ROUTER_DATA = " +
            "{\"loaderData\":{\"note_(id)/page\":{\"awemeDetail\":{\"aweme_detail\":{\"desc\":\"新结构作品\"," +
            "\"video\":{\"play_addr\":{\"url_list\":[\"https://cdn.example.com/aweme/v1/playwm/?video_id=n9\"]}}}}}}};</script>"
        val item = parseRouterData(html)
        assertEquals("新结构作品", item?.title)
        assertEquals("https://cdn.example.com/aweme/v1/play/?video_id=n9", item?.playUrl)
    }

    /** 页面被风控 / 作品被过滤时，错误信息里要带上页面自己给的原因。 */
    @Test
    fun `被过滤时把页面提示带出来`() {
        val html = "<script>window._ROUTER_DATA = " +
            "{\"loaderData\":{\"video_(id)/page\":{\"videoInfoRes\":{\"item_list\":[]," +
            "\"filter_list\":[{\"detail_msg\":\"作品不存在或已删除\"}]}}}};</script>"
        assertNull(parseRouterData(html))
        val summary = routerDataSummary(html)
        assertTrue(summary!!.contains("item_list=0"))
        assertTrue(summary.contains("作品不存在或已删除"))
    }

    /** 分享页里那串 HTML 实体转义不影响 jsonObjectAt 的括号配对。 */
    @Test
    fun `字符串里的花括号不算配对`() {
        val html = "window._ROUTER_DATA = {\"a\":\"}{ 不是结构 \",\"b\":1}</script>"
        val start = html.indexOf('{')
        assertEquals("{\"a\":\"}{ 不是结构 \",\"b\":1}", jsonObjectAt(html, start))
    }

    // ------------------------------------------------------------------ 假服务端

    private var emptyItemInfo = false

    private fun handle(exchange: HttpExchange) {
        seenPaths += exchange.requestURI.path
        exchange.requestHeaders.forEach { (name, values) -> values.forEach { seenHeaders += name + ": " + it } }
        when {
            exchange.requestURI.path == "/ttwid/union/register/" -> {
                exchange.responseHeaders.add("Set-Cookie", "ttwid=TESTTTWID; Path=/")
                json(exchange, """{"status_code":0}""")
            }
            exchange.requestURI.path == "/s/xyz" -> redirect(exchange, "$base/share/video/$awemeId/")
            exchange.requestURI.path.startsWith("/share/video/") -> html(exchange, sharePage())
            exchange.requestURI.path.startsWith("/web/api/v2/aweme/iteminfo") ->
                json(exchange, if (emptyItemInfo) """{"item_list":[]}""" else itemInfoBody())
            else -> json(exchange, """{"status_code":-1}""")
        }
    }

    private fun sharePage(): String {
        if (!routerDataPresent) return "<html><head><title>抖音</title></head><body>改版了</body></html>"
        return "<html><head><title>抖音分享</title></head><body><script>window._ROUTER_DATA = " +
            "{\"loaderData\":{\"video_(id)/page\":{\"videoInfoRes\":{\"item_list\":[{\"desc\":\"测试抖音作品\"," +
            "\"video\":{\"play_addr\":{\"uri\":\"v123\",\"url_list\":[" +
            "\"https://cdn.example.com/aweme/v1/playwm/?video_id=v123&ratio=720p\"]}}}]}}},\"errors\":{}};</script></body></html>"
    }

    private fun itemInfoBody(): String =
        """{"item_list":[{"desc":"兜底作品","video":{"play_addr":{"url_list":["http://cdn.example.com/x/playwm/?v=1"]}}}]}"""

    private fun redirect(exchange: HttpExchange, location: String) {
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(302, -1)
        exchange.close()
    }

    private fun html(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun json(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
