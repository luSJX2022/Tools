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

    /** 动态详情接口回哪种内容：draw（图文）/ article（转发专栏）/ empty（纯文字）。 */
    private var opusMode = "draw"

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

    @Test
    fun `图文动态返回图片列表`() = runBlocking {
        val media = resolver().resolve(
            MediaLink.Bilibili(url = "$base/opus/755822555336540166", opusId = 755822555336540166L),
        )
        assertEquals("", media.url)   // 图集不放播放地址，界面按图集展示
        assertEquals(
            listOf("https://i0.hdslb.com/bfs/new_dyn/a.png", "https://i0.hdslb.com/bfs/new_dyn/b.jpg"),
            media.images,
        )
        assertEquals("新返图", media.title)
        assertEquals("葱油饼er", media.author)
        assertEquals(1722173701L, media.publishTime)
        assertTrue(seenPaths.contains("/x/polymer/web-dynamic/v1/detail"))
    }

    /** 「转发专栏」型动态本身没有图片，图片在链接的专栏里 —— 要再拉一次专栏接口。 */
    @Test
    fun `转发专栏型动态落到专栏里拿图`() = runBlocking {
        opusMode = "article"
        val media = resolver().resolve(
            MediaLink.Bilibili(url = "$base/opus/1132111305778397224", opusId = 1132111305778397224L),
        )
        assertEquals(listOf("https://i0.hdslb.com/bfs/article/a.jpg"), media.images)
        assertEquals("白金攻略", media.title)
        assertEquals("李卡农", media.author)
        assertTrue(seenPaths.contains("/x/article/view"))
    }

    @Test
    fun `文字动态没有图时明确报错`() = runBlocking {
        opusMode = "empty"
        val error = runCatching {
            resolver().resolve(MediaLink.Bilibili(url = "$base/opus/1", opusId = 1L))
        }.exceptionOrNull() as LinkResolveException
        assertTrue(error.message!!.contains("没有图片"))
    }

    /** 实测有的动态给 http 地址（i0.hdslb.com），统一升级成 https。 */
    @Test
    fun `动态图片的http地址会升级成https`() {
        val post = parseDynamicPost(
            """{"code":0,"data":{"item":{"modules":{"module_dynamic":{"major":{"draw":{"items":[""" +
                """{"src":"http://i0.hdslb.com/a.png"},{"src":"//i0.hdslb.com/b.jpg"}]}}}}}}}""",
        )
        assertEquals(listOf("https://i0.hdslb.com/a.png", "https://i0.hdslb.com/b.jpg"), post.images)
    }

    /** 新版 opus 样式的动态图片在 major.opus.pics[].url。 */
    @Test
    fun `opus样式动态从pics取图`() {
        val post = parseDynamicPost(
            """{"code":0,"data":{"item":{"modules":{"module_dynamic":{"major":{"opus":""" +
                """{"title":"标题","pics":[{"url":"https://i0.hdslb.com/p.png"}]}}}}}}}""",
        )
        assertEquals(listOf("https://i0.hdslb.com/p.png"), post.images)
        assertEquals("标题", post.title)
    }

    @Test
    fun `专栏解析图片列表`() {
        val post = parseArticle(
            """{"code":0,"data":{"title":"攻略","publish_time":1722173701,"author":{"name":"作者"},""" +
                """"image_urls":["https://i0.hdslb.com/bfs/article/a.jpg"],"summary":"摘要"}}""",
        )
        assertEquals(listOf("https://i0.hdslb.com/bfs/article/a.jpg"), post.images)
        assertEquals("攻略", post.title)
        assertEquals("作者", post.author)
        assertEquals(1722173701L, post.publishTime)
        assertEquals("摘要", post.description)
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
            "/x/polymer/web-dynamic/v1/detail" -> json(exchange, dynamicBody())
            "/x/article/view" -> json(
                exchange,
                """{"code":0,"data":{"title":"白金攻略","publish_time":1722173701,""" +
                    """"author":{"name":"李卡农"},"image_urls":["https://i0.hdslb.com/bfs/article/a.jpg"],""" +
                    """"summary":"感谢使用"}}""",
            )
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

    private fun dynamicBody(): String = when (opusMode) {
        "article" -> """{"code":0,"data":{"item":{"type":"DYNAMIC_TYPE_ARTICLE","modules":""" +
            """{"module_dynamic":{"major":{"article":{"id":43606912,"title":"攻略补充资料"}}}}}}}"""
        "empty" -> """{"code":0,"data":{"item":{"type":"DYNAMIC_TYPE_WORD","modules":{"module_dynamic":{}}}}}"""
        else -> """{"code":0,"data":{"item":{"type":"DYNAMIC_TYPE_DRAW","modules":""" +
            """{"module_author":{"name":"葱油饼er","pub_ts":1722173701},""" +
            """"module_dynamic":{"desc":{"text":"新返图"},"major":{"draw":{"items":[""" +
            """{"src":"https://i0.hdslb.com/bfs/new_dyn/a.png"},""" +
            """{"src":"https://i0.hdslb.com/bfs/new_dyn/b.jpg"}]}}}}}}}"""
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
