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
 * 用 JDK 自带的 HTTP 服务器假一个 B站：走真实的 OkHttp 请求、真实的跳转和 JSON 解析。
 */
class BilibiliResolverTest {

    private lateinit var server: HttpServer
    private lateinit var base: String
    private val seenHeaders = CopyOnWriteArrayList<String>()
    private val seenPaths = CopyOnWriteArrayList<String>()

    /** 让 playurl 回多条 durl（长视频被切段）或者只回 dash。 */
    private var playUrlMode = "single"

    /** 让 view 接口回一页 HTML —— 风控 / 需要验证时真机上就是这个表现。 */
    private var htmlInsteadOfJson = false

    /** 首页会不会种 buvid3（B站风控认这个 Cookie，真机上就是缺它才回 HTML）。 */
    private var homepageSetsBuvid = true

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        base = "http://127.0.0.1:" + server.address.port
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    @After
    fun tearDown() = server.stop(0)

    private fun resolver() = BilibiliResolver(apiBase = base, webBase = base)

    @Test
    fun `短链跟跳转后拿到BV号和分P`() = runBlocking {
        val media = resolver().resolve(MediaLink.Bilibili(url = "$base/b23"))

        assertEquals("$base/media/video.mp4", media.url)
        assertEquals("测试视频 · P2 结尾 · 720P", media.title)
        assertNull(media.warning)
        // 播放地址必须带 Referer 和 UA，否则 B站 CDN 直接 403
        assertEquals("$base/", media.headers["Referer"])
        assertTrue(media.headers["User-Agent"]!!.contains("Mozilla"))
        assertTrue(seenPaths.contains("/x/web-interface/view"))
        assertTrue(seenPaths.contains("/x/player/playurl"))
        assertTrue(seenHeaders.any { it == "Referer: $base/" })
        // 短链要真的跟了跳转：请求过 /b23，也用上了跳转后的 p=2
        assertTrue(seenPaths.contains("/b23"))
    }

    @Test
    fun `直接给BV号时不再请求短链`() = runBlocking {
        val media = resolver().resolve(MediaLink.Bilibili(url = "$base/video/BV1GJ411x7h7", bvid = "BV1GJ411x7h7"))
        assertEquals("$base/media/video.mp4", media.url)
        assertTrue(!seenPaths.contains("/b23"))
    }

    @Test
    fun `番剧走pgc接口`() = runBlocking {
        val media = resolver().resolve(MediaLink.Bilibili(url = "$base/bangumi/play/ep555", epId = 555L))
        assertEquals("$base/media/video.mp4", media.url)
        assertEquals("《测试番剧》第2话 · 720P", media.title)
        assertTrue(seenPaths.contains("/pgc/view/web/season"))
    }

    @Test
    fun `视频不存在时把接口的话带出来`() = runBlocking {
        val error = runCatching {
            resolver().resolve(MediaLink.Bilibili(url = "$base/video/BV0000000000", bvid = "BV0000000000"))
        }.exceptionOrNull() as LinkResolveException
        assertTrue(error.message!!.contains("视频不存在"))
    }

    @Test
    fun `先拿到 buvid3 再请求接口`() = runBlocking {
        resolver().resolve(MediaLink.Bilibili(url = "$base/video/BV1GJ411x7h7", bvid = "BV1GJ411x7h7"))
        assertTrue(seenPaths.contains("/"))
        assertTrue(seenHeaders.any { it.startsWith("Cookie", ignoreCase = true) && it.contains("buvid3=HOMEPAGE3") })
    }

    @Test
    fun `首页没种cookie时退回指纹接口`() = runBlocking {
        homepageSetsBuvid = false
        resolver().resolve(MediaLink.Bilibili(url = "$base/video/BV1GJ411x7h7", bvid = "BV1GJ411x7h7"))
        assertTrue(seenPaths.contains("/x/frontend/finger/spi"))
        assertTrue(seenHeaders.any { it.startsWith("Cookie", ignoreCase = true) && it.contains("buvid3=SPI3") })
    }

    @Test
    fun `候选地址优先常规镜像`() {
        val play = BiliPlayUrl(
            url = "https://809al93l.edge.mountaintoys.cn:4483/x.mp4",
            backups = listOf("https://upos-sz-mirrorali.bilivideo.com/x.mp4"),
            qualityLabel = null,
            warning = null,
        )
        assertEquals("https://upos-sz-mirrorali.bilivideo.com/x.mp4", play.candidates().first())
    }

    @Test
    fun `接口回网页时说人话并带上返回内容`() = runBlocking {
        htmlInsteadOfJson = true
        val error = runCatching {
            resolver().resolve(MediaLink.Bilibili(url = "$base/video/BV1GJ411x7h7", bvid = "BV1GJ411x7h7"))
        }.exceptionOrNull() as LinkResolveException
        assertTrue(error.message!!.contains("网页"))
        assertTrue(error.message!!.contains("DOCTYPE"))
    }

    @Test
    fun `多段视频给出提醒`() = runBlocking {
        playUrlMode = "segments"
        val media = resolver().resolve(MediaLink.Bilibili(url = "$base/video/BV1GJ411x7h7", bvid = "BV1GJ411x7h7"))
        assertEquals("$base/media/video-1.mp4", media.url)
        assertTrue(media.warning!!.contains("2 段"))
    }

    @Test
    fun `只有dash时明确报错而不是给一条没声音的流`() = runBlocking {
        playUrlMode = "dash"
        val error = runCatching {
            resolver().resolve(MediaLink.Bilibili(url = "$base/video/BV1GJ411x7h7", bvid = "BV1GJ411x7h7"))
        }.exceptionOrNull() as LinkResolveException
        assertTrue(error.message!!.contains("DASH"))
    }

    // ------------------------------------------------------------------ 假服务端

    private fun handle(exchange: HttpExchange) {
        seenPaths += exchange.requestURI.path
        exchange.requestHeaders.forEach { (name, values) -> values.forEach { seenHeaders += name + ": " + it } }
        when (exchange.requestURI.path) {
            // 首页：B站在这里种 buvid3
            "/" -> if (homepageSetsBuvid) {
                exchange.responseHeaders.add("Set-Cookie", "buvid3=HOMEPAGE3; Path=/")
                json(exchange, """{"code":0}""")
            } else {
                json(exchange, """{"code":0}""")
            }
            "/x/frontend/finger/spi" -> json(exchange, """{"code":0,"data":{"b_3":"SPI3","b_4":"SPI4"}}""")
            "/b23" -> redirect(exchange, "$base/video/BV1GJ411x7h7?p=2")
            "/x/web-interface/view" -> {
                val bvid = exchange.requestURI.query.orEmpty().substringAfter("bvid=", "")
                if (htmlInsteadOfJson) {
                    html(exchange, "<!DOCTYPE html><html><body>安全验证</body></html>")
                } else if (bvid.startsWith("BV0000")) {
                    json(exchange, """{"code":-404,"message":"啥都木有","data":null}""")
                } else {
                    json(
                        exchange,
                        """{"code":0,"message":"0","data":{"bvid":"BV1GJ411x7h7","title":"测试视频","pages":[""" +
                            """{"cid":111,"page":1,"part":"P1 开头"},{"cid":222,"page":2,"part":"P2 结尾"}]}}""",
                    )
                }
            }
            "/x/player/playurl" -> json(exchange, playUrlBody())
            "/pgc/view/web/season" -> json(
                exchange,
                """{"code":0,"message":"success","result":{"title":"测试番剧","episodes":[""" +
                    """{"id":444,"cid":333,"bvid":"BV1GJ411x7h7","title":"1","long_title":"第1话"}, """ +
                    """{"id":555,"cid":334,"bvid":"BV1GJ411x7h7","title":"2","share_copy":"《测试番剧》第2话"}]}}""",
            )
            else -> json(exchange, """{"code":-404,"message":"no such path"}""")
        }
    }

    private fun playUrlBody(): String = when (playUrlMode) {
        "segments" -> """{"code":0,"message":"0","data":{"quality":64,"durl":[""" +
            """{"order":1,"url":"$base/media/video-1.mp4"},{"order":2,"url":"$base/media/video-2.mp4"}]}}"""
        "dash" -> """{"code":0,"message":"0","data":{"quality":80,"dash":{"video":[{"id":80}]}}}"""
        else -> """{"code":0,"message":"0","data":{"quality":64,"durl":[{"order":1,"url":"$base/media/video.mp4"}]}}"""
    }

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
